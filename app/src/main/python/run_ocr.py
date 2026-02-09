import os
import json
from ocr.detect_text import detect_text_boxes_from_path


def main():
    base = os.path.dirname(__file__)
    image_path = os.path.join(base, 'image_3.webp')
    out_json = os.path.join(base, 'image_3_ocr.json')
    boxes = detect_text_boxes_from_path(image_path, min_conf=40, save_json=out_json)
    print('Detected boxes:', len(boxes))
    print(out_json)


if __name__ == '__main__':
    main()
