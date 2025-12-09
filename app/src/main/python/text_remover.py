from PIL import Image, ImageDraw, ImageFilter
import numpy as np
import json

def remove_text(image_path, blocks_json, output_path):
    """
    Xóa text từ ảnh sử dụng simple inpainting với PIL
    
    Args:
        image_path: Đường dẫn đến ảnh gốc
        blocks_json: JSON string chứa danh sách blocks cần xóa
                     Format: [{"x": int, "y": int, "width": int, "height": int}, ...]
        output_path: Đường dẫn lưu ảnh đã xóa text
    
    Returns:
        str: Đường dẫn đến ảnh đã xóa text hoặc error message
    """
    try:
        # Đọc ảnh
        img = Image.open(image_path)
        if img is None:
            return f"Error: Cannot read image from {image_path}"
        
        # Convert to RGB if needed
        if img.mode != 'RGB':
            img = img.convert('RGB')
        
        # Parse blocks JSON
        blocks = json.loads(blocks_json)
        if not blocks or len(blocks) == 0:
            return "Error: No blocks provided"
        
        # Convert to numpy array for easier manipulation
        img_array = np.array(img)
        
        # Process each block
        for block in blocks:
            x = int(block.get("x", 0))
            y = int(block.get("y", 0))
            width = int(block.get("width", 0))
            height = int(block.get("height", 0))
            
            # Mở rộng vùng một chút để xóa sạch hơn
            padding = 3
            x1 = max(0, x - padding)
            y1 = max(0, y - padding)
            x2 = min(img_array.shape[1], x + width + padding)
            y2 = min(img_array.shape[0], y + height + padding)
            
            # Simple inpainting: lấy màu trung bình từ viền xung quanh
            # Lấy border pixels (top, bottom, left, right)
            border_pixels = []
            
            # Top and bottom borders
            if y1 > 0:
                border_pixels.extend(img_array[y1-1, x1:x2].tolist())
            if y2 < img_array.shape[0]:
                border_pixels.extend(img_array[y2, x1:x2].tolist())
            
            # Left and right borders
            if x1 > 0:
                border_pixels.extend(img_array[y1:y2, x1-1].tolist())
            if x2 < img_array.shape[1]:
                border_pixels.extend(img_array[y1:y2, x2].tolist())
            
            # Calculate average color
            if border_pixels:
                avg_color = np.mean(border_pixels, axis=0).astype(int)
            else:
                # Fallback to white
                avg_color = np.array([255, 255, 255])
            
            # Fill the region with average color
            img_array[y1:y2, x1:x2] = avg_color
        
        # Convert back to PIL Image
        result_img = Image.fromarray(img_array.astype('uint8'))
        
        # Apply slight blur to make it more natural
        result_img = result_img.filter(ImageFilter.GaussianBlur(radius=1))
        
        # Save result
        result_img.save(output_path, quality=95)
        
        return output_path
            
    except json.JSONDecodeError as e:
        return f"Error: Invalid JSON format - {str(e)}"
    except Exception as e:
        return f"Error: {str(e)}"


def remove_text_with_mask(image_path, mask_path, output_path):
    """
    Xóa text từ ảnh sử dụng mask ảnh riêng (alternative method)
    
    Args:
        image_path: Đường dẫn đến ảnh gốc
        mask_path: Đường dẫn đến ảnh mask (trắng = xóa, đen = giữ nguyên)
        output_path: Đường dẫn lưu ảnh đã xóa text
    
    Returns:
        str: Đường dẫn đến ảnh đã xóa text hoặc error message
    """
    try:
        img = Image.open(image_path).convert('RGB')
        mask = Image.open(mask_path).convert('L')
        
        if img is None:
            return f"Error: Cannot read image from {image_path}"
        if mask is None:
            return f"Error: Cannot read mask from {mask_path}"
        
        # Convert to numpy arrays
        img_array = np.array(img)
        mask_array = np.array(mask)
        
        # Find regions to inpaint (where mask is white/high value)
        inpaint_regions = mask_array > 128
        
        # Simple inpainting using surrounding pixels
        result_array = img_array.copy()
        
        # For each channel
        for c in range(3):
            channel = img_array[:, :, c]
            # Use blur to fill masked regions
            blurred = Image.fromarray(channel).filter(ImageFilter.GaussianBlur(radius=3))
            blurred_array = np.array(blurred)
            result_array[:, :, c] = np.where(inpaint_regions, blurred_array, channel)
        
        result_img = Image.fromarray(result_array.astype('uint8'))
        result_img.save(output_path, quality=95)
        
        return output_path
            
    except Exception as e:
        return f"Error: {str(e)}"
