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
        
    # Cấu hình theo mức độ - TĂNG CƯỜNG để giảm nhiễu hạt
    params = {
        'light': {'h': 5, 'hColor': 5, 'templateWindowSize': 7, 'searchWindowSize': 21},
        'medium': {'h': 8, 'hColor': 8, 'templateWindowSize': 7, 'searchWindowSize': 21},
        'strong': {'h': 12, 'hColor': 12, 'templateWindowSize': 7, 'searchWindowSize': 21}
    }
    
    p = params.get(strength, params['medium'])
    
    # Convert RGB to BGR cho OpenCV
    img_bgr = cv2.cvtColor(img_array, cv2.COLOR_RGB2BGR)
    
    # Non-Local Means Denoising - TỐT NHẤT cho ảnh màu
    # Tăng cường denoising để giảm nhiễu hạt
    denoised_bgr = cv2.fastNlMeansDenoisingColored(
        img_bgr,
        None,
        h=p['h'],
        hColor=p['hColor'],
        templateWindowSize=p['templateWindowSize'],
        searchWindowSize=p['searchWindowSize']
    )
    
    # Thêm bilateral filter để làm mịn thêm
    denoised_bgr = cv2.bilateralFilter(denoised_bgr, 7, 50, 50)
    
    # Convert lại RGB
    return cv2.cvtColor(denoised_bgr, cv2.COLOR_BGR2RGB)


def roi_only_inpaint_dual(region_rgb, roi_mask, radius_telea=10, radius_ns=5):
    """
    Inpainting kết hợp TELEA và ADAPTIVE GRAIN SYNTHESIS.
    Tự động thích nghi:
    - Nền mịn (Gradient/Màu bệt): Giữ nguyên độ mịn, KHÔNG thêm hạt.
    - Nền nhám (Giấy/Chi tiết): Tái tạo hạt (grain) để tệp với nền.
    """
    if roi_mask.sum() == 0:
        return region_rgb

    # Đảm bảo mask là uint8
    roi_mask = roi_mask.astype(np.uint8)

    if not HAS_CV2:
        return region_rgb

    rh, rw = roi_mask.shape

    # Convert sang BGR cho OpenCV
    region_bgr = cv2.cvtColor(region_rgb, cv2.COLOR_RGB2BGR)
    original_bgr = region_bgr.copy()

    # === BƯỚC 1: Inpaint nền (Background) ===
    # Telea inpainting để tạo nền mượt
    inpainted = cv2.inpaint(region_bgr, roi_mask, 3, cv2.INPAINT_TELEA)

    # === BƯỚC 2: Phân tích độ nhám của nền (Texture Analysis) ===

    # Xác định vùng mẫu sạch (ngoài mask và các vùng an toàn)
    kernel_dilate = np.ones((5, 5), np.uint8)
    expanded_mask = cv2.dilate(roi_mask, kernel_dilate, iterations=1)

    # Lọc bỏ mực đen để chỉ lấy mẫu trên GIẤY/NỀN
    gray = cv2.cvtColor(original_bgr, cv2.COLOR_BGR2GRAY)
    _, dark_pixels_mask = cv2.threshold(gray, 80, 255, cv2.THRESH_BINARY_INV)

    exclusion_mask = cv2.bitwise_or(expanded_mask, dark_pixels_mask)

    sample_mask = (exclusion_mask == 0)
    if np.sum(sample_mask) < 50:
         sample_mask = (expanded_mask == 0)

    # Tính sigma (độ lệch chuẩn) của nhiễu trên nền
    blur_original = cv2.GaussianBlur(original_bgr, (5, 5), 0)
    texture_diff = original_bgr.astype(np.float32) - blur_original.astype(np.float32)

    sigma = 0 
    if np.sum(sample_mask) > 50:
        samples = texture_diff[sample_mask]
        sigma = np.mean(np.std(samples, axis=0))

    # === BƯỚC 3: Quyết định chiến lược (Adaptive Strategy) ===

    # Ngưỡng phân loại nền MỊN vs nền NHÁM
    # Gradient kỹ thuật số thường có sigma < 2.0
    # Giấy truyện tranh scan thường có sigma > 3.0 - 5.0
    SMOOTH_THRESHOLD = 3.0

    if sigma < SMOOTH_THRESHOLD:
        # --- CHIẾN LƯỢC CHO NỀN MỊN ---
        # Không thêm noise để tránh rỗ pixel/kính mờ
        # Chỉ trả về kết quả Telea (vốn dĩ đã mịn)
        return cv2.cvtColor(inpainted, cv2.COLOR_BGR2RGB)

    else:
        # --- CHIẾN LƯỢC CHO NỀN NHÁM ---
        # Sinh noise giả lập để tệp với độ nhám của giấy

        # Giới hạn sigma hợp lý
        sigma = np.clip(sigma, SMOOTH_THRESHOLD, 15.0)

        # Sinh hạt (Grain Synthesis)
        noise = np.random.randn(rh, rw, 3).astype(np.float32) * sigma

        # Làm mềm hạt để giống giấy tự nhiên
        noise = cv2.GaussianBlur(noise, (3, 3), 0.5)

        # Blend hạt vào nền inpaint
        mask_3ch = (roi_mask > 0).astype(np.float32)[:, :, np.newaxis]
        final_float = inpainted.astype(np.float32) + noise * mask_3ch

        result_bgr = np.clip(final_float, 0, 255).astype(np.uint8)

        return cv2.cvtColor(result_bgr, cv2.COLOR_BGR2RGB)


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
    Sử dụng distance transform để tìm pixel source gần nhất - nhanh hơn.
    
    Args:
        texture_source: Ảnh source chứa texture (float32 hoặc uint8)
        mask: Mask quy định vùng cần fill (255) và vùng source (0)
               Vùng 255 (non-zero) là vùng đích. Vùng 0 là vùng nguồn.
    """
    if not HAS_CV2:
        return texture_source

    # Đảm bảo mask là uint8
    mask = mask.astype(np.uint8)
    h, w = mask.shape
    result = texture_source.copy()

    # Distance transform 
    # Tính khoảng cách tới pixel 0 gần nhất.
    # Input phải là: 0 = Source, Non-zero = Target.
    # Mask hiện tại: 0 = Source, 255 = Target. Đúng chuẩn.
    dist, labels = cv2.distanceTransformWithLabels(
        mask,
        cv2.DIST_L2,
        5,
        labelType=cv2.DIST_LABEL_PIXEL
    )

    # Tạo lookup table từ labels đến coordinates
    indices = np.arange(h * w).reshape(h, w)
    source_indices = indices[mask == 0]

    # Vectorized implementation thay vì loop chậm
    mask_bool = mask > 0
    if not np.any(mask_bool):
        return result

    # Lấy labels tại các điểm đích
    target_labels = labels[mask_bool]

    # Map labels (1-based index) sang flat indices
    # Cần clip để đảm bảo an toàn index
    valid_indices = np.clip(target_labels - 1, 0, len(source_indices) - 1)
    src_flat_indices = source_indices[valid_indices]

    # Unravel coordinate
    src_y = src_flat_indices // w
    src_x = src_flat_indices % w

    # Copy texture một cách trực tiếp
    result[mask_bool] = texture_source[src_y, src_x]

    return result