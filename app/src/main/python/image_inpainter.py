"""
Image Inpainting Module
Handles various inpainting techniques for image restoration after text removal.
Uses OpenCV and custom algorithms for image restoration.
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

# Import internal post-processor
try:
    import image_postprocessor
    HAS_POSTPROCESSOR = True
except Exception:
    HAS_POSTPROCESSOR = False


def denoise_before_inpainting(img_array, strength='medium'):
    """
    Khử nhiễu hạt TRƯỚC khi inpainting.
    Sử dụng Non-Local Means Denoising - giữ chi tiết tốt nhất.
    """
    if not HAS_CV2:
        return img_array
        
    params = {
        'light': {'h': 5, 'hColor': 5, 'templateWindowSize': 7, 'searchWindowSize': 21},
        'medium': {'h': 8, 'hColor': 8, 'templateWindowSize': 7, 'searchWindowSize': 21},
        'strong': {'h': 12, 'hColor': 12, 'templateWindowSize': 7, 'searchWindowSize': 21}
    }
    
    p = params.get(strength, params['medium'])
    img_bgr = cv2.cvtColor(img_array, cv2.COLOR_RGB2BGR)
    
    denoised_bgr = cv2.fastNlMeansDenoisingColored(
        img_bgr,
        None,
        h=p['h'],
        hColor=p['hColor'],
        templateWindowSize=p['templateWindowSize'],
        searchWindowSize=p['searchWindowSize']
    )
    
    denoised_bgr = cv2.bilateralFilter(denoised_bgr, 7, 50, 50)
    return cv2.cvtColor(denoised_bgr, cv2.COLOR_BGR2RGB)


def analyze_background_complexity(img_array, mask, edge_size=15, complexity_threshold=15.0):
    """
    Phân tích độ phức tạp của nền xung quanh vùng mask (Logic từ Android TextRemovalHelper).
    Returns: tuple (is_simple: bool, avg_color: tuple)
    """
    if img_array is None or mask is None:
        return False, (255, 255, 255)

    h, w = img_array.shape[:2]
    y_idxs, x_idxs = np.indices((h, w))
    
    is_at_edge = (x_idxs < edge_size) | (x_idxs >= w - edge_size) | \
                 (y_idxs < edge_size) | (y_idxs >= h - edge_size)
    
    valid_sample_mask = is_at_edge & (mask == 0)
    sample_pixels = img_array[valid_sample_mask]
    
    if len(sample_pixels) == 0:
        return True, (255, 255, 255)
    
    avg_color = np.mean(sample_pixels, axis=0).astype(int)
    avg_color_tuple = tuple(avg_color.tolist())
    std_devs = np.std(sample_pixels, axis=0)
    avg_std_dev = np.mean(std_devs)
    
    is_simple = avg_std_dev < complexity_threshold
    logging.info(f"Complexity Analysis: StdDev={avg_std_dev:.2f}, Threshold={complexity_threshold}, IsSimple={is_simple}")
    
    return is_simple, avg_color_tuple


def simple_inpaint(img_array, mask, color):
    """Đổ màu trung bình vào vùng mask."""
    result = img_array.copy()
    mask_bool = mask > 0
    result[mask_bool] = color
    return result


def roi_only_inpaint_dual(region_rgb, roi_mask, radius_telea=10, radius_ns=5):
    """
    Inpainting kết hợp TELEA + NS và texture grain.
    """
    if roi_mask.sum() == 0:
        return region_rgb

    if not HAS_CV2:
        return region_rgb

    roi_mask = roi_mask.astype(np.uint8)
    rh, rw = roi_mask.shape
    region_bgr = cv2.cvtColor(region_rgb, cv2.COLOR_RGB2BGR)
    original_bgr = region_bgr.copy()
    
    # Combined Inpaint
    inpainted = cv2.inpaint(region_bgr, roi_mask, radius_telea, cv2.INPAINT_TELEA)
    eroded_mask = cv2.erode(roi_mask, np.ones((3, 3), np.uint8), iterations=1)
    inpainted = cv2.inpaint(inpainted, eroded_mask, radius_ns, cv2.INPAINT_NS)
    
    # Texture Analysis & Grain Synthesis
    kernel_dilate = np.ones((7, 7), np.uint8)
    expanded_mask = cv2.dilate(roi_mask, kernel_dilate, iterations=1)
    sample_mask = (expanded_mask == 0)
    
    if np.sum(sample_mask) > 30:
        blur_raw = cv2.GaussianBlur(original_bgr, (5, 5), 0)
        texture_diff = original_bgr.astype(np.float32) - blur_raw.astype(np.float32)
        samples = texture_diff[sample_mask]
        sigma = np.mean(np.std(samples, axis=0))
        
        if sigma > 5.0:  # Nền nhám
            grain_sigma = np.clip(sigma * 0.8, 3.0, 10.0)
            noise = np.random.randn(rh, rw, 3).astype(np.float32) * grain_sigma
            noise = cv2.GaussianBlur(noise, (5, 5), 0.8)
            mask_3ch = (roi_mask > 0).astype(np.float32)[:, :, np.newaxis]
            inpainted = np.clip(inpainted.astype(np.float32) + noise * mask_3ch, 0, 255).astype(np.uint8)

    inpainted_rgb = cv2.cvtColor(inpainted, cv2.COLOR_BGR2RGB)
    
    if HAS_POSTPROCESSOR:
        try:
            inpainted_rgb = image_postprocessor.feather_edge_only(region_rgb, inpainted_rgb, roi_mask, feather_width=5)
        except Exception:
            pass
            
    return inpainted_rgb


def _copy_nearest_texture_fast(texture_source, mask):
    """Copy texture từ vùng source gần nhất dựa trên distance transform."""
    if not HAS_CV2:
        return texture_source
    mask = mask.astype(np.uint8)
    h, w = mask.shape
    dist, labels = cv2.distanceTransformWithLabels(mask, cv2.DIST_L2, 5, labelType=cv2.DIST_LABEL_PIXEL)
    indices = np.arange(h * w).reshape(h, w)
    source_indices = indices[mask == 0]
    mask_bool = mask > 0
    if not np.any(mask_bool) or len(source_indices) == 0:
        return texture_source
    target_labels = labels[mask_bool]
    valid_indices = np.clip(target_labels - 1, 0, len(source_indices) - 1)
    src_flat_indices = source_indices[valid_indices]
    result = texture_source.copy()
    result[mask_bool] = texture_source[src_flat_indices // w, src_flat_indices % w]
    return result


def exemplar_based_inpaint(img_array, mask, patch_size=9):
    """Patch-based inpainting (OpenCV fallback)."""
    if not HAS_CV2:
        return img_array
    img_bgr = cv2.cvtColor(img_array, cv2.COLOR_RGB2BGR)
    inpainted = cv2.inpaint(img_bgr, mask.astype(np.uint8), patch_size, cv2.INPAINT_TELEA)
    return cv2.cvtColor(inpainted, cv2.COLOR_BGR2RGB)
