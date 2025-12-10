from text_remover import remove_text
from PIL import Image, ImageDraw, ImageFont
import json
import os

def create_test_image(path, w=600, h=400):
    img = Image.new('RGB', (w, h), color=(240, 240, 240))
    d = ImageDraw.Draw(img)
    # Try to use a default font if available
    try:
        font = ImageFont.load_default()
    except Exception:
        font = None
    d.text((50, 100), "TEST TEXT", fill=(10, 10, 10), font=font)
    img.save(path, quality=90)

def test_remove_text():
    tmp_dir = os.path.dirname(__file__)
    image_path = os.path.join(tmp_dir, 'test_input.jpg')
    output_path = os.path.join(tmp_dir, 'test_output.jpg')
    create_test_image(image_path, 600, 400)
    # block around the text
    block = {"x": 45, "y": 95, "width": 220, "height": 60}
    blocks_json = json.dumps([block])
    res = remove_text(image_path, blocks_json, output_path)
    print('remove_text result:', res)

if __name__ == '__main__':
    test_remove_text()
