import json
import os
import sys

from text_remover import remove_text


def boxes_from_user(boxes_list):
    # convert [x1,y1,x2,y2] -> {x,y,width,height}
    out = []
    for b in boxes_list:
        coords = b.get("box_2d") or b.get("box") or None
        if not coords or len(coords) < 4:
            continue
        x1, y1, x2, y2 = coords[:4]
        w = max(0, int(x2) - int(x1))
        h = max(0, int(y2) - int(y1))
        out.append({"x": int(x1), "y": int(y1), "width": w, "height": h})
    return out


def main():
    base = os.path.dirname(__file__)
    image_path = os.path.join(base, "image_3.webp")
    output_path = os.path.join(base, "image_3_inpaint_result.png")

    user_boxes = [
        {"box_2d": [18, 220, 127, 331]},
        {"box_2d": [103, 125, 173, 187]},
        {"box_2d": [174, 15, 330, 131]},
        {"box_2d": [201, 467, 351, 564]},
        {"box_2d": [128, 590, 303, 730]},
        {"box_2d": [46, 785, 127, 959]},
        {"box_2d": [403, 807, 584, 984]},
        {"box_2d": [482, 630, 621, 755]},
        {"box_2d": [784, 159, 870, 260]},
        {"box_2d": [669, 781, 764, 956]},
    ]

    blocks = boxes_from_user(user_boxes)
    blocks_json = json.dumps(blocks)

    print(f"Running remove_text on: {image_path}\nblocks: {blocks}")
    res = remove_text(image_path, blocks_json, output_path)
    print("Result:", res)


if __name__ == '__main__':
    main()
