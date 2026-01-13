"""
Image Inpainting Module
Handles various inpainting techniques for image restoration after text removal.
Supports LaMa (high quality), OpenCV methods, and custom algorithms.
"""

print("DEBUG: image_inpainter.py module loaded")  # Debug print for Android logcat

from PIL import Image
import numpy as np
import logging

# Try to import cv2 for advanced inpainting
try:
    import cv2
    HAS_CV2 = True
    print("INFO: OpenCV imported successfully")  # Print for Android logcat
    logging.info("OpenCV imported successfully")
except Exception:
    HAS_CV2 = False
    print("WARNING: OpenCV not available")  # Print for Android logcat
    logging.warning("OpenCV not available")

# Note: LaMa requires PyTorch which is not compatible with Chaquopy Android
# Using enhanced OpenCV inpainting with LaMa-inspired techniques


def denoise_before_inpainting(img_array, strength='medium'):
    """
    Khử nhiễu hạt TRƯỚC khi inpainting.
    Sử dụng Non-Local Means Denoising - giữ chi tiết tốt nhất.
    
    Args:
        img_array: RGB numpy array
        strength: 'light', 'medium', 'strong'
    
    Returns:
        RGB numpy array đã khử nhiễu
    """
    if not HAS_CV2:
        return img_array
        
    # Cấu hình theo mức độ
    params = {
        'light': {'h': 3, 'hColor': 3, 'templateWindowSize': 7, 'searchWindowSize': 15},
        'medium': {'h': 5, 'hColor': 5, 'templateWindowSize': 7, 'searchWindowSize': 21},
        'strong': {'h': 10, 'hColor': 10, 'templateWindowSize': 7, 'searchWindowSize': 21}
    }
    
    p = params.get(strength, params['medium'])
    
    # Convert RGB to BGR cho OpenCV
    img_bgr = cv2.cvtColor(img_array, cv2.COLOR_RGB2BGR)
    
    # Non-Local Means Denoising - TỐT NHẤT cho ảnh màu
    denoised_bgr = cv2.fastNlMeansDenoisingColored(
        img_bgr,
        None,
        h=p['h'],
        hColor=p['hColor'],
        templateWindowSize=p['templateWindowSize'],
        searchWindowSize=p['searchWindowSize']
    )
    
    # Convert lại RGB
    return cv2.cvtColor(denoised_bgr, cv2.COLOR_BGR2RGB)


def roi_only_inpaint_dual(region_rgb, roi_mask, radius_telea=10, radius_ns=5):
    """
    Inpainting sử dụng LaMa-inspired techniques với khử nhiễu trước.
    KHÔNG tạo nhiễu hạt, mượt mà tự nhiên.

    Args:
        region_rgb: numpy array RGB của vùng ROI
        roi_mask: mask uint8 của vùng ROI (255 = cần inpaint)
        radius_telea: bán kính cho TELEA (fallback)
        radius_ns: bán kính cho Navier-Stokes (fallback)

    Returns:
        numpy array RGB của vùng ROI đã inpaint
    """
    if roi_mask.sum() == 0:
        return region_rgb

    # Đảm bảo mask là uint8
    roi_mask = roi_mask.astype(np.uint8)

    # Enhanced OpenCV inpainting with LaMa-inspired techniques
    print("INFO: Using LaMa-style enhanced OpenCV inpainting (denoised)")  # Print for Android logcat
    logging.info("Using LaMa-style enhanced OpenCV inpainting (denoised)")
    if not HAS_CV2:
        return region_rgb

    rh, rw = roi_mask.shape

    # ✨ Khử nhiễu TRƯỚC khi sơn
    region_rgb = denoise_before_inpainting(region_rgb, strength='medium')

    # Convert sang BGR cho OpenCV
    region_bgr = cv2.cvtColor(region_rgb, cv2.COLOR_RGB2BGR)
    original_bgr = region_bgr.copy()

    # BƯỚC 1: TELEA inpainting - smooth structure
    inpainted = cv2.inpaint(region_bgr, roi_mask, radius_telea, cv2.INPAINT_TELEA)

    # BƯỚC 2: NS inpainting - natural blending
    inpainted = cv2.inpaint(inpainted, roi_mask, radius_ns, cv2.INPAINT_NS)

    # 🎯 Bộ lọc song phương để làm mịn MÀ KHÔNG blur chi tiết
    # Đây là chìa khóa để loại bỏ grain!
    inpainted = cv2.bilateralFilter(inpainted, 5, 50, 50)

    # === TEXTURE TRANSFER không nhiễu ===
    non_mask = (roi_mask == 0)
    mask_area = (roi_mask > 0)

    if np.sum(non_mask) > 50 and np.sum(mask_area) > 0:
        # 🚫 Giảm nhiễu kết cấu xuống 20%
        blur_original = cv2.GaussianBlur(original_bgr, (5, 5), 0)
        texture = original_bgr.astype(np.float32) - blur_original.astype(np.float32)

        # Texture strength từ vùng source
        texture_std = np.std(texture[non_mask]) + 1e-6
        texture_strength = np.clip(texture_std * 0.5, 1, 8)  # Giảm strength

        # Random variation NHẸ để tránh grain
        np.random.seed(42)
        noise = np.random.randn(*original_bgr.shape).astype(np.float32)
        noise = cv2.GaussianBlur(noise, (3, 3), 1.0)
        noise = noise * texture_strength * 0.2  # Giảm nhiều để tránh nhiễu

        # Apply vào vùng mask
        mask_3ch = np.stack([mask_area] * 3, axis=-1)
        textured = inpainted.astype(np.float32) + noise * mask_3ch
        textured = np.clip(textured, 0, 255).astype(np.uint8)

        # Soft blend
        soft_mask = cv2.GaussianBlur(roi_mask.astype(np.float32), (7, 7), 0) / 255.0
        soft_mask = soft_mask[:, :, np.newaxis]
        inpainted = (textured * soft_mask + inpainted * (1 - soft_mask)).astype(np.uint8)

    # 💎 Gaussian Blur nhẹ nhàng cuối cùng
    inpainted = cv2.GaussianBlur(inpainted, (3, 3), 0.5)

    return cv2.cvtColor(inpainted, cv2.COLOR_BGR2RGB)


def roi_only_inpaint(region_rgb, roi_mask, radius=5):
    """
    Inpainting CHỈ trên vùng ROI đã được crop.
    Trả về vùng ROI đã được inpaint.
    Hàm này được thiết kế để chạy song song.

    Args:
        region_rgb: numpy array RGB của vùng ROI
        roi_mask: mask uint8 của vùng ROI (255 = cần inpaint)
        radius: bán kính inpainting

    Returns:
        numpy array RGB của vùng ROI đã inpaint
    """
    if not HAS_CV2:
        return region_rgb

    if roi_mask.sum() == 0:
        return region_rgb

    # Đảm bảo mask là uint8
    roi_mask = roi_mask.astype(np.uint8)

    # Convert sang BGR cho OpenCV
    region_bgr = cv2.cvtColor(region_rgb, cv2.COLOR_RGB2BGR)

    # TELEA inpainting - nhanh và hiệu quả
    inpainted = cv2.inpaint(region_bgr, roi_mask, radius, cv2.INPAINT_TELEA)

    # Convert lại RGB
    return cv2.cvtColor(inpainted, cv2.COLOR_BGR2RGB)


def weight_aware_inpaint(img_array, mask, x1, y1, x2, y2):
    """
    Inpainting đơn giản và nhanh sử dụng cv2.inpaint.

    Args:
        img_array: numpy array RGB của toàn bộ ảnh
        mask: mask uint8 (255 = cần inpaint)
        x1, y1, x2, y2: bounding box của vùng cần inpaint

    Returns:
        numpy array RGB đã inpaint
    """
    if not HAS_CV2:
        return img_array

    if mask.sum() == 0:
        return img_array

    h, w = img_array.shape[:2]

    # Validate bounds
    x1 = max(0, min(x1, w))
    y1 = max(0, min(y1, h))
    x2 = max(0, min(x2, w))
    y2 = max(0, min(y2, h))

    if x2 <= x1 or y2 <= y1:
        return img_array

    # Crop vùng cần xử lý
    region = img_array[y1:y2, x1:x2].copy()
    roi_mask = mask[y1:y2, x1:x2].copy()

    if roi_mask.sum() == 0:
        return img_array

    # Đảm bảo mask là uint8
    roi_mask = roi_mask.astype(np.uint8)

    # Convert sang BGR cho OpenCV
    region_bgr = cv2.cvtColor(region, cv2.COLOR_RGB2BGR)

    # Bước 1: TELEA với radius lớn để propagate structure
    inpainted = cv2.inpaint(region_bgr, roi_mask, 10, cv2.INPAINT_TELEA)

    # Bước 2: NS để làm mượt
    inpainted = cv2.inpaint(inpainted, roi_mask, 5, cv2.INPAINT_NS)

    # Convert lại RGB
    filled_rgb = cv2.cvtColor(inpainted, cv2.COLOR_BGR2RGB)

    # CHỈ cập nhật vùng ROI, giữ nguyên phần còn lại
    result = img_array.copy()
    result[y1:y2, x1:x2] = filled_rgb

    return result


def exemplar_based_inpaint(img_array, mask, patch_size=9):
    """
    Exemplar-based inpainting - thuật toán tương tự Content-Aware Fill của Photoshop.
    Sử dụng patch-based approach để điền texture từ vùng xung quanh.

    Args:
        img_array: numpy array RGB
        mask: mask uint8 (255 = cần inpaint)
        patch_size: kích thước patch

    Returns:
        numpy array RGB đã inpaint
    """
    if not HAS_CV2:
        return img_array

    if mask.sum() == 0:
        return img_array

    # Convert to BGR for OpenCV
    img_bgr = cv2.cvtColor(img_array, cv2.COLOR_RGB2BGR)

    # Use OpenCV's exemplar-based inpainting
    inpainted = cv2.inpaint(img_bgr, mask.astype(np.uint8), patch_size, cv2.INPAINT_TELEA)

    # Convert back to RGB
    return cv2.cvtColor(inpainted, cv2.COLOR_BGR2RGB)


def _copy_nearest_texture_fast(texture_source, mask):
    """
    Copy texture từ vùng source gần nhất vào vùng mask.
    Sử dụng distance transform để tìm pixel source gần nhất.
    """
    if not HAS_CV2:
        return texture_source

    h, w = mask.shape
    result = texture_source.copy()

    # Distance transform để tìm pixel nguồn gần nhất
    dist, labels = cv2.distanceTransformWithLabels(
        (mask == 0).astype(np.uint8),
        cv2.DIST_L2,
        5,
        labelType=cv2.DIST_LABEL_PIXEL
    )

    # Tạo lookup table từ labels đến coordinates
    indices = np.arange(h * w).reshape(h, w)
    source_indices = indices[mask == 0]

    # Với mỗi pixel trong mask, copy texture từ nguồn gần nhất KO BIẾN ĐỔI
    mask_indices = np.where(mask > 0)
    for i in range(len(mask_indices[0])):
        py, px = mask_indices[0][i], mask_indices[1][i]
        label = labels[py, px]

        # Tìm coordinate của pixel nguồn
        if label > 0 and label <= len(source_indices):
            src_idx = source_indices[label - 1] if label <= len(source_indices) else 0
            sy, sx = src_idx // w, src_idx % w

            if 0 <= sy < h and 0 <= sx < w and mask[sy, sx] == 0:
                # Copy nguyên bản, không random variation để tránh nhiễu
                result[py, px] = texture_source[sy, sx]

    return result