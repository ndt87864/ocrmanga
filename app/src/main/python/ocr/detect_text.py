import os
import json
import logging
from PIL import Image
import numpy as np

try:
    import pytesseract
    HAS_PYTESSERACT = True
except Exception:
    HAS_PYTESSERACT = False

try:
    import cv2
    HAS_CV2 = True
except Exception:
    HAS_CV2 = False


def _pytesseract_detect(img_pil, min_conf=50):
    data = pytesseract.image_to_data(img_pil, output_type=pytesseract.Output.DICT)
    boxes = []
    n = len(data['level'])
    for i in range(n):
        conf = int(float(data['conf'][i])) if data['conf'][i] != '-1' else -1
        text = data['text'][i].strip()
        if conf >= min_conf and text:
            x, y, w, h = data['left'][i], data['top'][i], data['width'][i], data['height'][i]
            boxes.append({'box_2d': [int(x), int(y), int(x + w), int(y + h)], 'text_content': text, 'conf': conf})
    return boxes


def _mser_detect(img_array):
    # Return coarse boxes detected by MSER (no OCR)
    boxes = []
    if not HAS_CV2:
        return boxes
    gray = cv2.cvtColor(img_array, cv2.COLOR_RGB2GRAY)
    # create MSER with defaults; keyword args vary between OpenCV versions
    mser = cv2.MSER_create()
    regions, _ = mser.detectRegions(gray)
    for p in regions:
        x, y, w, h = cv2.boundingRect(p.reshape(-1, 1, 2))
        boxes.append({'box_2d': [int(x), int(y), int(x + w), int(y + h)], 'text_content': ''})
    # Merge overlapping boxes mildly
    boxes = _merge_boxes(boxes)
    return boxes


def _merge_boxes(boxes, iou_thresh=0.3):
    if not boxes:
        return boxes
    arr = np.array([b['box_2d'] for b in boxes])
    picked = []
    idxs = list(range(len(arr)))
    while idxs:
        i = idxs.pop(0)
        x1, y1, x2, y2 = arr[i]
        keep = [i]
        remove = []
        for j in idxs:
            xx1, yy1, xx2, yy2 = arr[j]
            ix1 = max(x1, xx1); iy1 = max(y1, yy1)
            ix2 = min(x2, xx2); iy2 = min(y2, yy2)
            iw = max(0, ix2 - ix1); ih = max(0, iy2 - iy1)
            inter = iw * ih
            area1 = (x2 - x1) * (y2 - y1)
            area2 = (xx2 - xx1) * (yy2 - yy1)
            union = area1 + area2 - inter
            iou = inter / union if union > 0 else 0
            if iou > iou_thresh:
                # merge
                x1 = min(x1, xx1); y1 = min(y1, yy1); x2 = max(x2, xx2); y2 = max(y2, yy2)
                remove.append(j)
        # remove merged indexes
        idxs = [k for k in idxs if k not in remove]
        picked.append({'box_2d': [int(x1), int(y1), int(x2), int(y2)], 'text_content': ''})
    return picked


def detect_text_boxes_from_path(image_path, min_conf=50, save_json=None):
    """Detect text boxes and optionally OCR text. Returns list of boxes.

    Prefers pytesseract if available; otherwise uses MSER contour detection.
    Each box is {'box_2d':[x1,y1,x2,y2], 'text_content': str, 'conf': int (optional)}
    """
    if not os.path.exists(image_path):
        raise FileNotFoundError(image_path)
    img_pil = Image.open(image_path).convert('RGB')
    img_array = np.array(img_pil)

    boxes = []
    if HAS_PYTESSERACT:
        try:
            boxes = _pytesseract_detect(img_pil, min_conf=min_conf)
        except Exception as e:
            logging.warning(f"pytesseract failed: {e}")
            boxes = []

    if not boxes:
        # fallback to MSER
        boxes = _mser_detect(img_array)

    if save_json:
        try:
            with open(save_json, 'w', encoding='utf-8') as f:
                json.dump(boxes, f, ensure_ascii=False, indent=2)
        except Exception:
            pass

    return boxes


if __name__ == '__main__':
    import argparse
    p = argparse.ArgumentParser()
    p.add_argument('image')
    p.add_argument('--min_conf', type=int, default=50)
    p.add_argument('--out', default=None)
    args = p.parse_args()
    boxes = detect_text_boxes_from_path(args.image, min_conf=args.min_conf, save_json=args.out)
    print(json.dumps(boxes, ensure_ascii=False, indent=2))
