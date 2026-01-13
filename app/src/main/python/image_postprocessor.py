"""
Image Post-processor Module
Xử lý hậu kỳ (Post-processing) để nâng cao chất lượng vùng được inpaint.
Bao gồm: khôi phục chi tiết, upscaling, khử nhiễu, sharpening, và seamless blending.

Tương tự chức năng của các app Remini, Snap Edit, Photoshop's Content-Aware Fill.
"""

import numpy as np
import logging
from PIL import Image, ImageFilter, ImageOps, ImageEnhance
import cv2

# Try to import advanced libraries
try:
    import cv2
    HAS_CV2 = True
    logging.info("OpenCV imported successfully for post-processing")
except Exception:
    HAS_CV2 = False
    logging.warning("OpenCV not available for post-processing")


def edge_enhance(img_array, strength=1.5, kernel_size=3):
    """
    Tăng cường cạnh và chi tiết hình ảnh bằng unsharp masking.
    Giúp khôi phục chi tiết bị mất do inpainting.
    """
    if not HAS_CV2:
        return img_array
    
    # Convert to float32
    img_float = img_array.astype(np.float32) / 255.0
    
    # Gaussian blur để tạo base layer
    blurred = cv2.GaussianBlur(img_float, (kernel_size, kernel_size), 0)
    
    # Unsharp mask: Original + (Original - Blurred) * strength
    enhanced = img_float + (img_float - blurred) * (strength - 1.0)
    
    # Clip to valid range
    enhanced = np.clip(enhanced, 0, 1)
    
    return (enhanced * 255).astype(np.uint8)


def bilateral_denoise(img_array, d=9, sigma_color=75, sigma_space=75):
    """
    Khử nhiễu sử dụng bilateral filter.
    Giữ lại cạnh sắc nét trong khi làm mịn vùng smooth.
    """
    if not HAS_CV2:
        return img_array
    
    # Convert to BGR for OpenCV bilateral filter if it expects it, 
    # but bilateralFilter works on RGB too. We'll use RGB for simplicity.
    denoised = cv2.bilateralFilter(img_array.astype(np.uint8), d, sigma_color, sigma_space)
    
    return denoised


def morphological_enhance(img_array, kernel_size=3):
    """
    Tăng cường chi tiết dùng morphological operations.
    """
    if not HAS_CV2:
        return img_array
    
    gray = cv2.cvtColor(img_array, cv2.COLOR_RGB2GRAY)
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (kernel_size, kernel_size))
    
    # Morphological gradient (dilation - erosion) highlight edges
    gradient = cv2.morphologyEx(gray, cv2.MORPH_GRADIENT, kernel)
    
    # Convert gradient to 3-channel
    gradient_3ch = np.stack([gradient] * 3, axis=-1)
    
    # Blend with original
    result = cv2.addWeighted(img_array, 1.0, gradient_3ch, 0.2, 0)
    
    return np.clip(result, 0, 255).astype(np.uint8)


def adaptive_histogram_equalization(img_array, clip_limit=2.0, tile_size=8):
    """
    CLAHE (Contrast Limited Adaptive Histogram Equalization).
    """
    if not HAS_CV2:
        return img_array
    
    # Convert to Lab color space to enhance L channel
    img_lab = cv2.cvtColor(img_array, cv2.COLOR_RGB2LAB)
    
    clahe = cv2.createCLAHE(clipLimit=clip_limit, tileGridSize=(tile_size, tile_size))
    img_lab[:, :, 0] = clahe.apply(img_lab[:, :, 0])
    
    # Convert back to RGB
    return cv2.cvtColor(img_lab, cv2.COLOR_LAB2RGB)


def simple_upscale(img_array, scale=2, method='cubic'):
    """
    Upscale ảnh đơn giản sử dụng interpolation.
    """
    if not HAS_CV2:
        h, w = img_array.shape[:2]
        img_pil = Image.fromarray(img_array)
        new_size = (w * scale, h * scale)
        upscaled_pil = img_pil.resize(new_size, Image.LANCZOS)
        return np.array(upscaled_pil)
    
    h, w = img_array.shape[:2]
    new_h, new_w = h * scale, w * scale
    
    methods = {
        'cubic': cv2.INTER_CUBIC,
        'linear': cv2.INTER_LINEAR,
        'lanczos': cv2.INTER_LANCZOS4,
        'nearest': cv2.INTER_NEAREST
    }
    
    interp = methods.get(method, cv2.INTER_CUBIC)
    return cv2.resize(img_array, (new_w, new_h), interpolation=interp)


def super_resolution_upscale(img_array, scale=2):
    """
    Super-resolution upscaling giả lập (Upscale + Enhance).
    """
    upscaled = simple_upscale(img_array, scale=scale, method='lanczos')
    
    # Tăng cường sau khi upscale
    enhanced = edge_enhance(upscaled, strength=1.4)
    denoised = bilateral_denoise(enhanced, d=5, sigma_color=50, sigma_space=50)
    
    return denoised


def sharpen_with_unsharp_mask(img_array, amount=1.0):
    """
    Sharpening sử dụng Unsharp Mask qua PIL (kiểm soát tốt hơn).
    """
    pil_img = Image.fromarray(img_array)
    enhancer = ImageEnhance.Sharpness(pil_img)
    sharpened = enhancer.enhance(1.0 + amount)
    
    return np.array(sharpened)


def remove_blur_simple(img_array, strength=1.5):
    """
    Xóa mờ đơn giản sử dụng Laplacian filter.
    """
    if not HAS_CV2:
        return img_array
    
    kernel = np.array([
        [0, -1, 0],
        [-1, 5, -1],
        [0, -1, 0]
    ], dtype=np.float32)
    
    sharpened = cv2.filter2D(img_array, -1, kernel)
    
    if strength != 1.0:
        result = cv2.addWeighted(img_array, 1.0 - strength, sharpened, strength, 0)
        return np.clip(result, 0, 255).astype(np.uint8)
    
    return sharpened


def poisson_seamless_blend(original_rgb, inpainted_rgb, mask):
    """
    Poisson Seamless Clone - PHƯƠNG PHÁP QUAN TRỌNG NHẤT để hòa nhập seamless.
    Sử dụng cv2.seamlessClone để tự động điều chỉnh gradient cho khớp với nền.
    
    Args:
        original_rgb: Ảnh gốc (background) RGB
        inpainted_rgb: Ảnh đã inpaint (source) RGB  
        mask: Mask uint8 (255 = vùng inpaint)
    
    Returns:
        RGB array đã blend seamless
    """
    if not HAS_CV2:
        mask_3ch = np.stack([mask > 128] * 3, axis=-1)
        return np.where(mask_3ch, inpainted_rgb, original_rgb)
    
    # Đảm bảo mask là uint8 binary
    mask_binary = (mask > 0).astype(np.uint8) * 255
    
    # Tìm tâm của vùng mask
    moments = cv2.moments(mask_binary)
    if moments['m00'] == 0:
        return inpainted_rgb
    
    cx = int(moments['m10'] / moments['m00'])
    cy = int(moments['m01'] / moments['m00'])
    
    h, w = original_rgb.shape[:2]
    cx = np.clip(cx, 0, w - 1)
    cy = np.clip(cy, 0, h - 1)
    
    # Convert to BGR cho OpenCV
    original_bgr = cv2.cvtColor(original_rgb, cv2.COLOR_RGB2BGR)
    inpainted_bgr = cv2.cvtColor(inpainted_rgb, cv2.COLOR_RGB2BGR)
    
    try:
        # MIXED_CLONE: Giữ texture của cả source và destination
        # Tốt hơn NORMAL_CLONE cho manga/comic vì bảo toàn chi tiết
        result_bgr = cv2.seamlessClone(
            inpainted_bgr,  # source
            original_bgr,   # destination  
            mask_binary,    # mask
            (cx, cy),       # center
            cv2.MIXED_CLONE # MIXED_CLONE giữ texture tốt hơn
        )
        return cv2.cvtColor(result_bgr, cv2.COLOR_BGR2RGB)
    except Exception:
        # Fallback nếu seamlessClone thất bại (mask quá nhỏ/lớn)
        return adaptive_blend(original_rgb, inpainted_rgb, mask, blend_width=10)


def multi_band_blend(original_rgb, inpainted_rgb, mask, levels=4):
    """
    Multi-band blending (Laplacian Pyramid) - blend mượt hơn Gaussian.
    Blend ở nhiều tần số khác nhau để tránh seams.
    
    Args:
        original_rgb: Ảnh gốc RGB
        inpainted_rgb: Ảnh đã inpaint RGB
        mask: Mask uint8
        levels: Số level pyramid
    
    Returns:
        RGB array đã blend
    """
    if not HAS_CV2:
        mask_3ch = np.stack([mask > 128] * 3, axis=-1)
        return np.where(mask_3ch, inpainted_rgb, original_rgb)
    
    # Ensure images are same size
    h, w = original_rgb.shape[:2]
    
    # Resize to power of 2 for pyramid
    new_h = 2 ** int(np.ceil(np.log2(h)))
    new_w = 2 ** int(np.ceil(np.log2(w)))
    
    orig_resized = cv2.resize(original_rgb, (new_w, new_h))
    inpaint_resized = cv2.resize(inpainted_rgb, (new_w, new_h))
    mask_resized = cv2.resize(mask, (new_w, new_h))
    
    # Build Gaussian pyramids
    gp_orig = [orig_resized.astype(np.float32)]
    gp_inpaint = [inpaint_resized.astype(np.float32)]
    gp_mask = [mask_resized.astype(np.float32) / 255.0]
    
    for i in range(levels):
        gp_orig.append(cv2.pyrDown(gp_orig[i]))
        gp_inpaint.append(cv2.pyrDown(gp_inpaint[i]))
        gp_mask.append(cv2.pyrDown(gp_mask[i]))
    
    # Build Laplacian pyramids
    lp_orig = [gp_orig[levels]]
    lp_inpaint = [gp_inpaint[levels]]
    
    for i in range(levels, 0, -1):
        size = (gp_orig[i-1].shape[1], gp_orig[i-1].shape[0])
        lap_orig = gp_orig[i-1] - cv2.pyrUp(gp_orig[i], dstsize=size)
        lap_inpaint = gp_inpaint[i-1] - cv2.pyrUp(gp_inpaint[i], dstsize=size)
        lp_orig.append(lap_orig)
        lp_inpaint.append(lap_inpaint)
    
    # Blend Laplacian pyramids
    lp_blend = []
    for i in range(levels + 1):
        mask_3ch = np.stack([gp_mask[levels - i]] * 3, axis=-1)
        blended = lp_orig[i] * (1 - mask_3ch) + lp_inpaint[i] * mask_3ch
        lp_blend.append(blended)
    
    # Reconstruct
    result = lp_blend[0]
    for i in range(1, levels + 1):
        size = (lp_blend[i].shape[1], lp_blend[i].shape[0])
        result = cv2.pyrUp(result, dstsize=size) + lp_blend[i]
    
    # Resize back to original
    result = cv2.resize(np.clip(result, 0, 255).astype(np.uint8), (w, h))
    
    return result


def adaptive_blend(original_rgb, inpainted_rgb, mask, blend_width=10):
    """
    Hòa nhập seamless giữa vùng inpaint và vùng gốc.
    Dùng Gaussian transition ở biên để tránh ragged edge.
    
    BẤT ĐẲNG HƯỚNG: Thêm noise vào blend mask để tránh hình tròn đối xứng.
    Làm cho ranh giới trông tự nhiên hơn, không dễ phát hiện.
    """
    if not HAS_CV2:
        mask_3ch = np.stack([mask > 128] * 3, axis=-1)
        return np.where(mask_3ch, inpainted_rgb, original_rgb)

    # Đảm bảo mask là binary uint8
    mask_binary = (mask > 0).astype(np.uint8) * 255
    h, w = mask.shape[:2]
    
    # Tạo feather mask bằng blur - TĂNG kernel size cho mượt hơn
    ksize = blend_width * 2 + 1
    if ksize % 2 == 0:
        ksize += 1
    feather_mask = cv2.GaussianBlur(mask_binary.astype(np.float32), (ksize, ksize), blend_width / 2.0)
    feather_mask = feather_mask / 255.0
    feather_mask = np.clip(feather_mask, 0, 1)
    
    # === BẤT ĐẲNG HƯỚNG: Thêm noise vào feather mask ===
    # Tạo random noise -> blur -> normalize
    random_noise = np.random.rand(h, w).astype(np.float32) * 0.35
    random_noise = cv2.GaussianBlur(random_noise, (blend_width // 2, blend_width // 2), blend_width / 4.0)
    random_noise = (random_noise - random_noise.min()) / (random_noise.max() - random_noise.min() + 1e-6)
    random_noise = random_noise * 0.35  # Cường độ noise
    
    # Cộng noise vào feather mask
    feather_mask_noisy = np.clip(feather_mask + random_noise - 0.15, 0, 1)
    
    # Blend images với feather mask BẤT ĐẲNG HƯỚNG
    blend_weight = feather_mask_noisy[:, :, np.newaxis]
    result = original_rgb.astype(np.float32) * (1.0 - blend_weight) + inpainted_rgb.astype(np.float32) * blend_weight
    
    return np.clip(result, 0, 255).astype(np.uint8)


def color_correction_blend(original_rgb, inpainted_rgb, mask, border_width=5):
    """
    Hiệu chỉnh màu vùng inpaint để khớp với vùng gốc.
    """
    if not HAS_CV2:
        return inpainted_rgb
    
    # Tìm viền của mask để lấy mẫu màu
    kernel = np.ones((border_width, border_width), np.uint8)
    dilated = cv2.dilate(mask.astype(np.uint8), kernel)
    border = dilated - mask.astype(np.uint8)
    
    # Điểm lấy mẫu (vùng gốc sát biên)
    sample_points = (border > 0) & (mask == 0)
    
    if np.sum(sample_points) < 10:
        return inpainted_rgb
    
    # Tính màu trung bình vùng gốc gần biên
    original_mean = np.mean(original_rgb[sample_points], axis=0)
    
    # Tính màu trung bình vùng inpaint
    inpaint_area = (mask > 0)
    if np.sum(inpaint_area) == 0:
        return inpainted_rgb
        
    inpaint_mean = np.mean(inpainted_rgb[inpaint_area], axis=0)
    
    # Offset màu
    color_offset = original_mean - inpaint_mean
    
    # Áp dụng offset cho vùng inpaint
    corrected = inpainted_rgb.astype(np.float32) + color_offset
    corrected = np.clip(corrected, 0, 255).astype(np.uint8)
    
    # Trả về kết quả (chỉ trong vùng mask)
    mask_3ch = np.stack([mask > 0] * 3, axis=-1)
    return np.where(mask_3ch, corrected, original_rgb)


def _copy_nearest_texture_fast(texture_source, mask):
    """
    Helper: Copy texture từ vùng source gần nhất vào vùng mask.
    Sử dụng distance transform.
    """
    if not HAS_CV2:
        return texture_source
        
    mask = mask.astype(np.uint8)
    h, w = mask.shape
    result = texture_source.copy()
    
    # Distance transform 
    # 0 = pixel cần lấy làm mẫu (source), Non-zero = pixel cần được fill (target)
    dist, labels = cv2.distanceTransformWithLabels(
        mask,
        cv2.DIST_L2,
        5,
        labelType=cv2.DIST_LABEL_PIXEL
    )
    
    # Map target pixels to source pixels
    indices = np.arange(h * w).reshape(h, w)
    source_indices = indices[mask == 0]
    
    mask_bool = mask > 0
    if not np.any(mask_bool) or len(source_indices) == 0:
        return result
        
    target_labels = labels[mask_bool]
    valid_indices = np.clip(target_labels - 1, 0, len(source_indices) - 1)
    src_flat_indices = source_indices[valid_indices]
    
    src_y = src_flat_indices // w
    src_x = src_flat_indices % w
    
    result[mask_bool] = texture_source[src_y, src_x]
    
    return result


def texture_synthesis_enhance(original_rgb, inpainted_rgb, mask, strength=0.3):
    """
    Tái tạo texture CHỈ KHI vùng xung quanh có texture sạn.
    Nếu vùng xung quanh MỊN -> KHÔNG thêm texture để giữ mượt.
    
    Logic:
    1. Phân tích std_tex của vùng biên
    2. Nếu std_tex < 4.0 (mịn) -> KHÔNG xử lý, trả về nguyên
    3. Nếu std_tex >= 4.0 (sạn) -> thêm texture phù hợp
    """
    if not HAS_CV2:
        return inpainted_rgb
    
    h, w = mask.shape[:2]
    mask_bool = mask > 0
    
    if not np.any(mask_bool):
        return inpainted_rgb
        
    # === BƯỚC 1: Phân tích texture từ vùng biên ===
    original_lab = cv2.cvtColor(original_rgb, cv2.COLOR_RGB2LAB).astype(np.float32)
    L_original = original_lab[:, :, 0]
    
    # High-pass filter để lấy texture
    blur_L = cv2.GaussianBlur(L_original, (15, 15), 0)
    texture_original = L_original - blur_L
    
    # Lấy mẫu từ vùng biên (vùng gốc sát mask)
    kernel = np.ones((10, 10), np.uint8)
    dilated_mask = cv2.dilate(mask.astype(np.uint8), kernel, iterations=2)
    border_region = (dilated_mask > 0) & (mask == 0)
    
    if np.sum(border_region) < 20:
        return inpainted_rgb
    
    border_texture = texture_original[border_region]
    std_tex = np.std(border_texture)
    
    # === BƯỚC 2: QUYẾT ĐỊNH - Nếu vùng biên MỊN -> KHÔNG thêm texture ===
    TEXTURE_THRESHOLD = 4.0  # Ngưỡng phân biệt mịn/sạn
    
    if std_tex < TEXTURE_THRESHOLD:
        # Vùng xung quanh MỊN -> giữ nguyên kết quả inpaint mượt
        return inpainted_rgb
    
    # === BƯỚC 3: Vùng xung quanh SẠN -> thêm texture nhẹ ===
    inpainted_lab = cv2.cvtColor(inpainted_rgb, cv2.COLOR_RGB2LAB).astype(np.float32)
    L_inpainted = inpainted_lab[:, :, 0]
    
    mean_tex = np.mean(border_texture)
    # Giảm cường độ texture để không quá sạn
    std_tex_scaled = np.clip(std_tex * 0.6, 2.0, 8.0)
    
    # Tính distance từ mỗi pixel đến biên mask
    dist = cv2.distanceTransform(mask.astype(np.uint8), cv2.DIST_L2, 5)
    max_dist = np.max(dist) + 1e-6
    
    # === BẤT ĐẲNG HƯỚNG: Thêm noise vào distance weight ===
    # Làm cho texture blend không hình tròn đối xứng
    random_noise_dist = np.random.rand(h, w).astype(np.float32) * 0.3
    random_noise_dist = cv2.GaussianBlur(random_noise_dist, (15, 15), 5)
    random_noise_dist = (random_noise_dist - random_noise_dist.min()) / (random_noise_dist.max() - random_noise_dist.min() + 1e-6)
    random_noise_dist = random_noise_dist * 0.25  # Cường độ noise
    
    # Cộng noise vào distance để tạo bất đẳng hướng
    dist_noisy = dist + random_noise_dist * max_dist * 0.2
    
    # Propagate texture từ biên vào trong (blur mạnh để mượt)
    texture_propagated = _copy_nearest_texture_fast(texture_original, mask)
    texture_propagated = cv2.GaussianBlur(texture_propagated, (25, 25), 0)
    
    # Sinh grain noise nhẹ hơn
    grain_noise = np.random.randn(h, w).astype(np.float32) * std_tex_scaled + mean_tex
    grain_noise = cv2.GaussianBlur(grain_noise, (5, 5), 1.0)  # Blur mạnh để grain mềm
    
    # Blend: ở biên dùng propagated nhiều hơn, ở giữa dùng noise
    # Sử dụng dist_noisy để tạo bất đẳng hướng
    dist_weight = np.clip(dist_noisy / (max_dist * 0.5), 0, 1)
    texture_final = texture_propagated * (1 - dist_weight) + grain_noise * dist_weight
    
    # Áp dụng với strength thấp hơn
    actual_strength = strength * 0.7  # Giảm strength để tránh quá sạn
    texture_contribution = texture_final * actual_strength
    texture_contribution = np.clip(texture_contribution, -10, 10)
    
    L_result = L_inpainted.copy()
    L_result[mask_bool] = L_inpainted[mask_bool] + texture_contribution[mask_bool]
    L_result = np.clip(L_result, 0, 255)
    
    # Reconstruct RGB
    result_lab = inpainted_lab.copy()
    result_lab[:, :, 0] = L_result
    result_rgb = cv2.cvtColor(result_lab.astype(np.uint8), cv2.COLOR_LAB2RGB)
    
    return result_rgb
    
    return result_rgb


def color_histogram_match(source_rgb, target_rgb, mask):
    """
    Match histogram màu của vùng source với vùng target (vùng biên).
    Sử dụng phương pháp Reinhard color transfer trong LAB space.
    
    Args:
        source_rgb: Ảnh cần điều chỉnh (inpainted region)
        target_rgb: Ảnh reference (vùng gốc xung quanh)
        mask: Mask uint8 (255 = vùng cần điều chỉnh)
    
    Returns:
        RGB array đã match histogram
    """
    if not HAS_CV2:
        return source_rgb
    
    mask_bool = mask > 0
    if not np.any(mask_bool):
        return source_rgb
    
    # Tìm vùng reference (biên mask)
    kernel = np.ones((10, 10), np.uint8)
    dilated = cv2.dilate(mask.astype(np.uint8), kernel, iterations=2)
    reference_region = (dilated > 0) & (mask == 0)
    
    if np.sum(reference_region) < 50:
        return source_rgb
    
    # Convert to LAB
    source_lab = cv2.cvtColor(source_rgb, cv2.COLOR_RGB2LAB).astype(np.float32)
    target_lab = cv2.cvtColor(target_rgb, cv2.COLOR_RGB2LAB).astype(np.float32)
    
    result_lab = source_lab.copy()
    
    # Match từng channel L, A, B
    for c in range(3):
        # Statistics của source (vùng mask)
        src_mean = np.mean(source_lab[:, :, c][mask_bool])
        src_std = np.std(source_lab[:, :, c][mask_bool]) + 1e-6
        
        # Statistics của target (vùng biên)
        tgt_mean = np.mean(target_lab[:, :, c][reference_region])
        tgt_std = np.std(target_lab[:, :, c][reference_region]) + 1e-6
        
        # Reinhard transfer: normalize → rescale → shift
        normalized = (source_lab[:, :, c] - src_mean) / src_std
        transferred = normalized * tgt_std + tgt_mean
        
        # Chỉ áp dụng cho vùng mask
        result_lab[:, :, c][mask_bool] = transferred[mask_bool]
    
    # Clip và convert back
    result_lab = np.clip(result_lab, 0, 255).astype(np.uint8)
    result_rgb = cv2.cvtColor(result_lab, cv2.COLOR_LAB2RGB)
    
    return result_rgb


def post_process_inpainted_region(original_rgb, inpainted_rgb, mask, level='medium'):
    """
    Xử lý hậu kỳ toàn diện cho vùng inpaint.
    
    QUAN TRỌNG: KHÔNG blend với ảnh gốc để tránh giữ lại text!
    Chỉ làm mềm biên và khớp màu/texture.
    
    Pipeline:
    1. Color histogram matching (đảm bảo màu khớp với viền)
    2. Texture synthesis (khôi phục grain/texture)
    3. Feather blend CHỈ Ở VIỀN (không blend toàn bộ với ảnh gốc)
    """
    # Params mapping
    params = {
        'light': {'tex_strength': 0.2, 'blend_width': 3},
        'medium': {'tex_strength': 0.3, 'blend_width': 5},
        'strong': {'tex_strength': 0.4, 'blend_width': 7}
    }
    p = params.get(level, params['medium'])
    
    result = inpainted_rgb.copy()
    
    # Ensure mask is proper
    if mask is None or mask.sum() == 0:
        return result
    
    mask = mask.astype(np.uint8)
    if len(mask.shape) > 2:
        mask = mask[:, :, 0]
    
    # === STEP 1: Color Histogram Matching ===
    # Đảm bảo màu sắc vùng inpaint khớp với vùng xung quanh
    try:
        result = color_histogram_match(result, original_rgb, mask)
    except Exception:
        pass
    
    # === STEP 2: Texture Synthesis ===
    # Khôi phục texture/grain để tránh vùng "nhẵn bóng"
    try:
        result = texture_synthesis_enhance(original_rgb, result, mask, strength=p['tex_strength'])
    except Exception:
        pass
    
    # === STEP 3: Feather blend CHỈ Ở VIỀN ===
    # Blend mềm ở vùng biên để tránh hard edge
    # KHÔNG blend toàn bộ mask để giữ kết quả inpaint
    try:
        result = feather_edge_only(original_rgb, result, mask, feather_width=p['blend_width'])
    except Exception:
        pass
    
    return result


def feather_edge_only(original_rgb, inpainted_rgb, mask, feather_width=5):
    """
    Feather blend CHỈ Ở VIỀN của mask, KHÔNG blend bên trong.
    Điều này giữ nguyên kết quả inpaint bên trong, chỉ làm mềm viền.
    
    BẤT ĐẲNG HƯỚNG: Thêm noise vào feather zone để tránh blend hình tròn đối xứng
    (dễ phát hiện là đã sửa). Làm cho ranh giới bất thường, tự nhiên hơn.
    
    Args:
        original_rgb: Ảnh gốc (chỉ dùng để blend ở viền)
        inpainted_rgb: Ảnh đã inpaint
        mask: Mask uint8 (255 = vùng inpaint)
        feather_width: Độ rộng vùng feather (pixel)
    
    Returns:
        RGB array với viền được feather (bất đẳng hướng)
    """
    if not HAS_CV2:
        return inpainted_rgb
    
    mask_binary = (mask > 0).astype(np.uint8) * 255
    h, w = mask.shape[:2]
    
    # Tạo inner mask (vùng bên trong không cần blend)
    kernel_erode = np.ones((feather_width * 2, feather_width * 2), np.uint8)
    inner_mask = cv2.erode(mask_binary, kernel_erode, iterations=1)
    
    # Tạo feather zone (vùng giữa inner và outer)
    feather_zone = mask_binary.astype(np.float32) - inner_mask.astype(np.float32)
    
    # Blur feather zone để tạo gradient mềm
    ksize = feather_width * 2 + 1
    if ksize % 2 == 0:
        ksize += 1
    feather_weight = cv2.GaussianBlur(feather_zone, (ksize, ksize), feather_width / 2.0)
    feather_weight = feather_weight / 255.0
    feather_weight = np.clip(feather_weight, 0, 1)
    
    # === BƯỚC BẤT ĐẲNG HƯỚNG: Thêm noise ngẫu nhiên vào feather weight ===
    # Tạo Perlin-like noise bằng cách: random noise -> blur -> normalize
    # Điều này làm cho ranh giới không còn hình tròn đối xứng
    random_noise = np.random.rand(h, w).astype(np.float32) * 0.3  # 0-0.3 amplitude
    random_noise = cv2.GaussianBlur(random_noise, (feather_width, feather_width), feather_width / 3.0)
    random_noise = (random_noise - random_noise.min()) / (random_noise.max() - random_noise.min() + 1e-6)
    random_noise = random_noise * 0.4  # Điều chỉnh cường độ noise (0.4 = 40%)
    
    # Cộng noise vào feather weight nhưng chỉ ở feather zone
    feather_weight_noisy = feather_weight.copy()
    feather_zone_bool = feather_zone > 0
    feather_weight_noisy[feather_zone_bool] = np.clip(
        feather_weight[feather_zone_bool] + random_noise[feather_zone_bool] - 0.2, 
        0, 1
    )
    
    # Blend: 
    # - Inner (feather_weight = 0): 100% inpainted
    # - Feather zone: gradient blend (BẤT ĐẲNG HƯỚNG nhờ noise)
    # - Outer: 100% original
    blend_weight = feather_weight_noisy[:, :, np.newaxis]
    
    # Tạo full mask cho vùng inpaint (inner + feather)
    full_mask = (mask_binary > 0).astype(np.float32)[:, :, np.newaxis]
    
    # Kết quả: giữ inpainted ở vùng inner, blend ở viền
    # inner_weight = 1 nếu trong inner_mask, 0 nếu không
    inner_weight = (inner_mask > 0).astype(np.float32)[:, :, np.newaxis]
    
    # Final blend: 
    # - Trong inner: 100% inpainted
    # - Trong feather zone: blend inpainted với original (BẤT ĐẲNG HƯỚNG)
    # - Ngoài mask: 100% original
    result = original_rgb.astype(np.float32).copy()
    
    # Áp dụng inpainted vào vùng inner (100%)
    result = result * (1 - inner_weight) + inpainted_rgb.astype(np.float32) * inner_weight
    
    # Áp dụng blend ở feather zone (BẤT ĐẲNG HƯỚNG)
    feather_only = feather_weight_noisy[:, :, np.newaxis] * (1 - inner_weight)
    result = result * (1 - feather_only) + inpainted_rgb.astype(np.float32) * feather_only
    
    return np.clip(result, 0, 255).astype(np.uint8)


def post_process_batch_regions(original_rgb, inpainted_rgb, mask_regions_list, level='medium'):
    """
    Xử lý hậu kỳ cho danh sách các vùng.
    mask_regions_list: list of tuples ((x1, y1, x2, y2), local_mask)
    """
    result = inpainted_rgb.copy()
    
    for (x1, y1, x2, y2), local_mask in mask_regions_list:
        roi_orig = original_rgb[y1:y2, x1:x2]
        roi_inpaint = result[y1:y2, x1:x2]  # Dùng result thay vì inpainted_rgb
        
        # Ensure mask matches ROI size
        if local_mask.shape != (y2-y1, x2-x1):
            local_mask = cv2.resize(local_mask, (x2-x1, y2-y1), interpolation=cv2.INTER_NEAREST)
            
        processed_roi = post_process_inpainted_region(roi_orig, roi_inpaint, local_mask, level=level)
        result[y1:y2, x1:x2] = processed_roi
        
    return result


def seamless_inpaint_complete(original_rgb, inpainted_rgb, mask, use_poisson=True):
    """
    One-shot function để inpaint và blend seamless.
    Đây là function chính để gọi từ bên ngoài.
    
    Args:
        original_rgb: Ảnh gốc RGB
        inpainted_rgb: Ảnh đã inpaint RGB (có thể dùng cv2.inpaint trước)
        mask: Mask uint8 (255 = vùng cần blend)
        use_poisson: Sử dụng Poisson blend (khuyến nghị True)
    
    Returns:
        RGB array đã blend seamless hoàn hảo
    """
    if mask is None or mask.sum() == 0:
        return inpainted_rgb
    
    # Full pipeline
    result = post_process_inpainted_region(original_rgb, inpainted_rgb, mask, level='medium')
    
    return result

# Export all requested functions explicitly
__all__ = [
    'edge_enhance', 'morphological_enhance', 'adaptive_histogram_equalization',
    'bilateral_denoise', 'remove_blur_simple', 'simple_upscale',
    'super_resolution_upscale', 'sharpen_with_unsharp_mask', 'texture_synthesis_enhance',
    'adaptive_blend', 'color_correction_blend', 'post_process_inpainted_region',
    'post_process_batch_regions', 'poisson_seamless_blend', 'multi_band_blend',
    'color_histogram_match', 'seamless_inpaint_complete', 'feather_edge_only'
]
