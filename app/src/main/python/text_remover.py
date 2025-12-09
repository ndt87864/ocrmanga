from PIL import Image, ImageDraw, ImageFilter
import numpy as np
import json

# Try to import cv2 for advanced inpainting
try:
    import cv2
    HAS_CV2 = True
except ImportError:
    HAS_CV2 = False


def create_text_stroke_mask(img_array, x1, y1, x2, y2):
    """
    Tạo mask CHỈ cho nét chữ thực sự trong vùng bounding box.
    Sử dụng edge detection + color analysis để tìm chính xác pixel text.
    """
    if not HAS_CV2:
        # Fallback: mask toàn bộ vùng
        mask = np.zeros((img_array.shape[0], img_array.shape[1]), dtype=np.uint8)
        mask[y1:y2, x1:x2] = 255
        return mask
    
    h, w = img_array.shape[:2]
    
    # Validate bounds
    x1 = max(0, min(x1, w))
    y1 = max(0, min(y1, h))
    x2 = max(0, min(x2, w))
    y2 = max(0, min(y2, h))
    
    if x2 <= x1 or y2 <= y1:
        return np.zeros((h, w), dtype=np.uint8)
    
    # Crop vùng cần xử lý
    region = img_array[y1:y2, x1:x2].copy()
    rh, rw = region.shape[:2]
    
    if rh < 2 or rw < 2:
        return np.zeros((h, w), dtype=np.uint8)
    
    # Convert to grayscale
    gray = cv2.cvtColor(region, cv2.COLOR_RGB2GRAY)
    
    # === Phương pháp 1: Detect text đen/tối ===
    # Text thường tối hơn nền
    mean_val = np.mean(gray)
    
    if mean_val > 127:
        # Nền sáng, text tối
        threshold_val = min(mean_val - 40, 150)
        _, dark_text = cv2.threshold(gray, threshold_val, 255, cv2.THRESH_BINARY_INV)
    else:
        # Nền tối, text có thể sáng
        threshold_val = max(mean_val + 40, 100)
        _, dark_text = cv2.threshold(gray, threshold_val, 255, cv2.THRESH_BINARY)
    
    # === Phương pháp 2: Detect text màu (saturation cao) ===
    hsv = cv2.cvtColor(region, cv2.COLOR_RGB2HSV)
    saturation = hsv[:, :, 1]
    _, colored_text = cv2.threshold(saturation, 100, 255, cv2.THRESH_BINARY)
    
    # === Kết hợp ===
    text_mask = cv2.bitwise_or(dark_text, colored_text)
    
    # === Làm sạch mask ===
    # Loại bỏ noise nhỏ
    kernel_open = np.ones((2, 2), np.uint8)
    text_mask = cv2.morphologyEx(text_mask, cv2.MORPH_OPEN, kernel_open)
    
    # Dilate nhẹ để bắt viền chữ
    kernel_dilate = np.ones((3, 3), np.uint8)
    text_mask = cv2.dilate(text_mask, kernel_dilate, iterations=1)
    
    # Tạo full mask
    full_mask = np.zeros((h, w), dtype=np.uint8)
    full_mask[y1:y2, x1:x2] = text_mask
    
    return full_mask


def create_simple_mask(img_array, x1, y1, x2, y2, padding=2):
    """
    Tạo mask đơn giản cho toàn bộ bounding box với padding.
    Đây là fallback đáng tin cậy khi smart detection không hoạt động.
    """
    h, w = img_array.shape[:2]
    
    # Apply padding
    x1 = max(0, x1 - padding)
    y1 = max(0, y1 - padding)
    x2 = min(w, x2 + padding)
    y2 = min(h, y2 + padding)
    
    mask = np.zeros((h, w), dtype=np.uint8)
    mask[y1:y2, x1:x2] = 255
    
    return mask


def get_border_color(img_array, x1, y1, x2, y2, border_width=5):
    """
    Lấy màu trung bình từ viền xung quanh vùng text.
    Điều này giúp fill màu phù hợp với nền.
    """
    h, w = img_array.shape[:2]
    
    # Mở rộng vùng để lấy border
    bx1 = max(0, x1 - border_width)
    by1 = max(0, y1 - border_width)
    bx2 = min(w, x2 + border_width)
    by2 = min(h, y2 + border_width)
    
    # Collect border pixels
    border_pixels = []
    
    # Top border
    if by1 < y1:
        border_pixels.extend(img_array[by1:y1, bx1:bx2].reshape(-1, 3).tolist())
    
    # Bottom border
    if y2 < by2:
        border_pixels.extend(img_array[y2:by2, bx1:bx2].reshape(-1, 3).tolist())
    
    # Left border
    if bx1 < x1:
        border_pixels.extend(img_array[y1:y2, bx1:x1].reshape(-1, 3).tolist())
    
    # Right border
    if x2 < bx2:
        border_pixels.extend(img_array[y1:y2, x2:bx2].reshape(-1, 3).tolist())
    
    if border_pixels:
        return np.mean(border_pixels, axis=0).astype(np.uint8)
    else:
        return None


def color_aware_inpaint(img_array, mask, x1, y1, x2, y2):
    """
    Inpainting có nhận thức màu sắc - blend màu từ viền xung quanh.
    Tốt hơn cho manga với nền gradient/màu phức tạp.
    mask: local mask cho vùng [y1:y2, x1:x2], shape = (y2-y1, x2-x1)
    """
    if not HAS_CV2:
        return None
    
    result = img_array.copy()
    h, w = img_array.shape[:2]
    
    # Lấy màu viền
    border_color = get_border_color(img_array, x1, y1, x2, y2, border_width=8)
    
    if border_color is None:
        # Fallback to standard inpainting
        full_mask = np.zeros((h, w), dtype=np.uint8)
        full_mask[y1:y2, x1:x2] = mask
        return smart_inpaint(img_array, full_mask, radius=3)
    
    # Tạo gradient fill từ các cạnh
    region_h = y2 - y1
    region_w = x2 - x1
    
    if region_h <= 0 or region_w <= 0:
        return img_array
    
    # Lấy màu từ 4 cạnh
    top_colors = img_array[max(0, y1-3):y1, x1:x2] if y1 > 0 else None
    bottom_colors = img_array[y2:min(h, y2+3), x1:x2] if y2 < h else None
    left_colors = img_array[y1:y2, max(0, x1-3):x1] if x1 > 0 else None
    right_colors = img_array[y1:y2, x2:min(w, x2+3)] if x2 < w else None
    
    # Tính màu trung bình cho mỗi cạnh
    top_avg = np.mean(top_colors, axis=(0, 1)) if top_colors is not None and top_colors.size > 0 else border_color
    bottom_avg = np.mean(bottom_colors, axis=(0, 1)) if bottom_colors is not None and bottom_colors.size > 0 else border_color
    left_avg = np.mean(left_colors, axis=(0, 1)) if left_colors is not None and left_colors.size > 0 else border_color
    right_avg = np.mean(right_colors, axis=(0, 1)) if right_colors is not None and right_colors.size > 0 else border_color
    
    # Tạo gradient blend cho vùng text
    for ry in range(region_h):
        for rx in range(region_w):
            py = y1 + ry  # Global y coordinate
            px = x1 + rx  # Global x coordinate
            
            if py >= h or px >= w:
                continue
            
            # Chỉ fill pixel trong mask (sử dụng local coordinates cho mask)
            if ry >= mask.shape[0] or rx >= mask.shape[1]:
                continue
            if mask[ry, rx] == 0:
                continue
            
            # Tính weight dựa trên khoảng cách đến các cạnh
            # Bilinear interpolation
            ty = ry / max(1, region_h - 1)  # 0 = top, 1 = bottom
            tx = rx / max(1, region_w - 1)  # 0 = left, 1 = right
            
            # Interpolate vertically
            top_blend = (1 - tx) * left_avg + tx * right_avg
            bottom_blend = (1 - tx) * left_avg + tx * right_avg
            
            # More weight to nearest edges
            top_weight = 1 - ty
            bottom_weight = ty
            left_weight = 1 - tx
            right_weight = tx
            
            # Weighted average of 4 edges
            total_weight = top_weight + bottom_weight + left_weight + right_weight
            color = (
                top_weight * top_avg + 
                bottom_weight * bottom_avg + 
                left_weight * left_avg + 
                right_weight * right_avg
            ) / total_weight
            
            result[py, px] = color.astype(np.uint8)
    
    return result


def smart_inpaint(img_array, mask, radius=3):
    """
    Inpainting với radius phù hợp.
    """
    if not HAS_CV2:
        return None
    
    img_bgr = cv2.cvtColor(img_array, cv2.COLOR_RGB2BGR)
    mask_uint8 = mask.astype(np.uint8)
    
    # Sử dụng TELEA cho kết quả mượt hơn
    result_bgr = cv2.inpaint(img_bgr, mask_uint8, radius, cv2.INPAINT_TELEA)
    
    return cv2.cvtColor(result_bgr, cv2.COLOR_BGR2RGB)


def telea_inpaint(img_array, mask, radius=5):
    """
    Sử dụng thuật toán Telea inpainting từ OpenCV để vẽ lại vùng bị che.
    Thuật toán này sử dụng Fast Marching Method để propagate pixel values
    từ vùng biên vào vùng cần inpaint dựa trên gradient và structure xung quanh.
    """
    if not HAS_CV2:
        return None
    
    # Convert RGB to BGR for OpenCV
    img_bgr = cv2.cvtColor(img_array, cv2.COLOR_RGB2BGR)
    
    # Ensure mask is uint8
    mask_uint8 = mask.astype(np.uint8)
    
    # Apply Telea inpainting algorithm (INPAINT_TELEA)
    # This algorithm uses Fast Marching Method based on the paper:
    # "An Image Inpainting Technique Based on the Fast Marching Method" by Alexandru Telea
    result_bgr = cv2.inpaint(img_bgr, mask_uint8, radius, cv2.INPAINT_TELEA)
    
    # Convert back to RGB
    result_rgb = cv2.cvtColor(result_bgr, cv2.COLOR_BGR2RGB)
    return result_rgb


def ns_inpaint(img_array, mask, radius=5):
    """
    Sử dụng thuật toán Navier-Stokes inpainting từ OpenCV.
    Thuật toán này dựa trên fluid dynamics để propagate isophote lines
    (đường đẳng cường độ sáng) vào vùng cần inpaint.
    """
    if not HAS_CV2:
        return None
    
    # Convert RGB to BGR for OpenCV
    img_bgr = cv2.cvtColor(img_array, cv2.COLOR_RGB2BGR)
    
    # Ensure mask is uint8
    mask_uint8 = mask.astype(np.uint8)
    
    # Apply Navier-Stokes inpainting algorithm (INPAINT_NS)
    result_bgr = cv2.inpaint(img_bgr, mask_uint8, radius, cv2.INPAINT_NS)
    
    # Convert back to RGB
    result_rgb = cv2.cvtColor(result_bgr, cv2.COLOR_BGR2RGB)
    return result_rgb


def pil_inpaint_fallback(img_array, mask, iterations=10):
    """
    Fallback inpainting khi không có OpenCV.
    Sử dụng iterative blur và blend để "vẽ lại" vùng bị che.
    Kết quả không tốt bằng OpenCV nhưng vẫn tốt hơn fill màu đơn.
    """
    result = img_array.copy().astype(np.float32)
    height, width = mask.shape
    
    # Dilate mask slightly for better blending
    mask_bool = mask > 0
    
    for iteration in range(iterations):
        # Create blurred version
        result_img = Image.fromarray(result.astype(np.uint8))
        blurred_img = result_img.filter(ImageFilter.GaussianBlur(radius=3))
        blurred = np.array(blurred_img).astype(np.float32)
        
        # For each masked pixel, blend with average of neighbors
        for y in range(1, height - 1):
            for x in range(1, width - 1):
                if mask_bool[y, x]:
                    # Get neighboring pixels (only non-masked ones)
                    neighbors = []
                    for dy in [-1, 0, 1]:
                        for dx in [-1, 0, 1]:
                            if dy == 0 and dx == 0:
                                continue
                            ny, nx = y + dy, x + dx
                            if 0 <= ny < height and 0 <= nx < width:
                                # Prefer non-masked neighbors
                                if not mask_bool[ny, nx]:
                                    neighbors.append(result[ny, nx])
                                else:
                                    neighbors.append(blurred[ny, nx])
                    
                    if neighbors:
                        # Average of neighbors with some blur contribution
                        avg_neighbor = np.mean(neighbors, axis=0)
                        result[y, x] = 0.7 * avg_neighbor + 0.3 * blurred[y, x]
    
    return result.astype(np.uint8)


def remove_text(image_path, blocks_json, output_path):
    """
    Xóa text từ ảnh sử dụng inpainting.
    Thử smart detection trước, nếu không detect được text thì dùng simple mask.
    
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
        
        # Convert to numpy array
        img_array = np.array(img)
        height, width = img_array.shape[:2]
        
        # Create combined mask for all text regions
        combined_mask = np.zeros((height, width), dtype=np.uint8)
        
        # Process each block
        for block in blocks:
            x = int(block.get("x", 0))
            y = int(block.get("y", 0))
            bw = int(block.get("width", 0))
            bh = int(block.get("height", 0))
            
            # Clamp to image bounds
            x1 = max(0, x)
            y1 = max(0, y)
            x2 = min(width, x + bw)
            y2 = min(height, y + bh)
            
            if x2 <= x1 or y2 <= y1:
                continue
            
            # Thử smart detection trước
            text_mask = create_text_stroke_mask(img_array, x1, y1, x2, y2)
            
            # Kiểm tra xem smart detection có tìm thấy text không
            mask_sum = np.sum(text_mask)
            region_area = (x2 - x1) * (y2 - y1)
            
            # Nếu mask quá ít (< 5% vùng) hoặc quá nhiều (> 80%), dùng simple mask
            if mask_sum < region_area * 0.05 * 255 or mask_sum > region_area * 0.8 * 255:
                # Smart detection thất bại, dùng simple mask với padding nhỏ
                text_mask = create_simple_mask(img_array, x1, y1, x2, y2, padding=1)
            
            # Combine with main mask
            if HAS_CV2:
                combined_mask = cv2.bitwise_or(combined_mask, text_mask)
            else:
                combined_mask = np.maximum(combined_mask, text_mask)
        
        # Check if mask has any pixels to inpaint
        if np.sum(combined_mask) == 0:
            # No text detected, save original
            img.save(output_path, quality=98)
            return output_path
        
        # Inpainting từng block với color-aware approach
        result_array = img_array.copy()
        
        for block in blocks:
            x = int(block.get("x", 0))
            y = int(block.get("y", 0))
            bw = int(block.get("width", 0))
            bh = int(block.get("height", 0))
            
            # Clamp to image bounds
            x1 = max(0, x)
            y1 = max(0, y)
            x2 = min(width, x + bw)
            y2 = min(height, y + bh)
            
            if x2 <= x1 or y2 <= y1:
                continue
            
            # Extract local mask for this block
            local_mask = combined_mask[y1:y2, x1:x2]
            
            if np.sum(local_mask) == 0:
                continue
            
            # Bước 1: Dùng color-aware inpaint để fill màu nền đúng
            result_array = color_aware_inpaint(result_array, local_mask, x1, y1, x2, y2)
            
            # Bước 2: Dùng smart_inpaint để blend edges mượt hơn (với radius nhỏ)
            if HAS_CV2:
                # Tạo edge mask để smooth biên
                kernel = np.ones((3, 3), np.uint8)
                edge_mask = cv2.dilate(local_mask, kernel, iterations=1) - cv2.erode(local_mask, kernel, iterations=1)
                
                # Apply to full image mask
                full_edge_mask = np.zeros((height, width), dtype=np.uint8)
                full_edge_mask[y1:y2, x1:x2] = edge_mask
                
                # Light inpaint for edge smoothing
                result_array = smart_inpaint(result_array, full_edge_mask, radius=2)
                if result_array is None:
                    result_array = img_array.copy()
        
        if result_array is None:
            return "Error: Inpainting failed"
        
        # Convert back to PIL Image
        result_img = Image.fromarray(result_array)
        
        # Save result with max quality
        result_img.save(output_path, quality=100)
        
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
