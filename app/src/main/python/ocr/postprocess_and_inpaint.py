import os
import sys
import json
import math
# Ensure parent python folder is importable (so text_remover can be imported)
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
from text_remover import remove_text


def load_boxes(path):
    with open(path, 'r', encoding='utf-8') as f:
        return json.load(f)


def iou(a, b):
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    ix1 = max(ax1, bx1); iy1 = max(ay1, by1)
    ix2 = min(ax2, bx2); iy2 = min(ay2, by2)
    iw = max(0, ix2 - ix1); ih = max(0, iy2 - iy1)
    inter = iw * ih
    area_a = max(0, ax2 - ax1) * max(0, ay2 - ay1)
    area_b = max(0, bx2 - bx1) * max(0, by2 - by1)
    union = area_a + area_b - inter
    return inter / union if union > 0 else 0.0


def vertical_overlap_ratio(a, b):
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    ih = max(0, min(ay2, by2) - max(ay1, by1))
    min_h = min(max(0, ay2 - ay1), max(0, by2 - by1))
    return ih / min_h if min_h > 0 else 0.0


def merge_boxes_greedy(boxes, iou_thresh=0.35, v_overlap_thresh=0.7, h_gap_factor=0.3, max_box_area=100000, min_box_area=20):
    rects = [b['box_2d'] for b in boxes]
    rects = [[int(x) for x in r] for r in rects]
    # Filter by area first to avoid huge regions
    rects = [[x1,y1,x2,y2] for x1,y1,x2,y2 in rects if (x2-x1)*(y2-y1) >= min_box_area and (x2-x1)*(y2-y1) <= max_box_area]
    changed = True
    passes = 0
    while changed and passes < 10:
        changed = False
        passes += 1
        n = len(rects)
        used = [False] * n
        new_rects = []
        for i in range(n):
            if used[i]:
                continue
            ax1, ay1, ax2, ay2 = rects[i]
            aw = ax2 - ax1; ah = ay2 - ay1
            merged_any = True
            while merged_any:
                merged_any = False
                for j in range(i+1, n):
                    if used[j]:
                        continue
                    bx1, by1, bx2, by2 = rects[j]
                    bw = bx2 - bx1; bh = by2 - by1
                    if aw <=0 or ah <=0 or bw<=0 or bh<=0:
                        continue
                    if iou([ax1,ay1,ax2,ay2], [bx1,by1,bx2,by2]) > iou_thresh:
                        # merge
                        ax1 = min(ax1, bx1); ay1 = min(ay1, by1); ax2 = max(ax2, bx2); ay2 = max(ay2, by2)
                        aw = ax2 - ax1; ah = ay2 - ay1
                        used[j] = True
                        merged_any = True
                        changed = True
                    else:
                        vov = vertical_overlap_ratio([ax1,ay1,ax2,ay2], [bx1,by1,bx2,by2])
                        hgap = max(0, max(bx1 - ax2, ax1 - bx2))
                        if vov > v_overlap_thresh and hgap < max(aw, bw) * h_gap_factor:
                            ax1 = min(ax1, bx1); ay1 = min(ay1, by1); ax2 = max(ax2, bx2); ay2 = max(ay2, by2)
                            aw = ax2 - ax1; ah = ay2 - ay1
                            used[j] = True
                            merged_any = True
                            changed = True
            used[i] = True
            new_rects.append([ax1, ay1, ax2, ay2])
        rects = new_rects

    # filter tiny boxes and oversized regions
    filtered = []
    for r in rects:
        x1,y1,x2,y2 = r
        area = (x2 - x1) * (y2 - y1)
        if area < min_box_area or area > max_box_area:
            continue
        filtered.append([int(x1),int(y1),int(x2),int(y2)])

    return filtered


def to_blocks_format(rects):
    out = []
    for x1,y1,x2,y2 in rects:
        out.append({'x': int(x1), 'y': int(y1), 'width': int(max(0, x2 - x1)), 'height': int(max(0, y2 - y1))})
    return out


def main():
    base = os.path.dirname(os.path.dirname(__file__))
    ocr_json = os.path.join(base, 'image_3_ocr.json')
    image_path = os.path.join(base, 'image_3.webp')
    out_blocks = os.path.join(base, 'image_3_blocks.json')
    out_result = os.path.join(base, 'image_3_inpaint_from_ocr.png')

    boxes = load_boxes(ocr_json)
    rects = merge_boxes_greedy(boxes)
    blocks = to_blocks_format(rects)
    with open(out_blocks, 'w', encoding='utf-8') as f:
        json.dump(blocks, f, ensure_ascii=False, indent=2)

    print('Merged boxes:', len(rects))
    # Run inpainting using remove_text
    blocks_json = json.dumps(blocks)
    res = remove_text(image_path, blocks_json, out_result)
    print('Inpaint result:', res)


if __name__ == '__main__':
    main()
