from PIL import Image, ImageDraw, ImageFilter
import numpy as np
import json
import math
import sys
import logging
import concurrent.futures
from concurrent.futures import ThreadPoolExecutor, ProcessPoolExecutor
from functools import partial
import multiprocessing

# Try to import cv2 for advanced inpainting
try:
    import cv2
    HAS_CV2 = True
except Exception:
    HAS_CV2 = False

# Import internal post-processor
try:
    import image_postprocessor
    HAS_POSTPROCESSOR = True
except Exception:
    HAS_POSTPROCESSOR = False

# Check for xphoto module (advanced inpainting)
HAS_XPHOTO = False
try:
    if HAS_CV2:
        import cv2.xphoto
        HAS_XPHOTO = True
except Exception:
    HAS_XPHOTO = False

# Optimal number of workers for parallel processing
MAX_WORKERS = min(multiprocessing.cpu_count(), 8)

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
    
    # Dilate nhẹ để bắt toàn bộ viền chữ (bao gồm cả stroke trắng rộng)
    # Tăng cường dilation để đảm bảo xóa sạch bóng chữ
    kernel_dilate = np.ones((5, 5), np.uint8)
    text_mask = cv2.dilate(text_mask, kernel_dilate, iterations=1)
    
    # Expand mask to include halo/stroke - stroke trắng trong manga thường khá rộng
    try:
        # Tăng cường expansion để bao phủ vùng viền trắng của chữ
        expand_px = max(2, min(15, int(min(rh, rw) / 30)))
        if expand_px > 1:
            kernel_expand = np.ones((expand_px, expand_px), np.uint8)
            text_mask = cv2.dilate(text_mask, kernel_expand, iterations=1)
    except Exception:
        pass
    
    # Tạo full mask
    full_mask = np.zeros((h, w), dtype=np.uint8)
    # Ensure text_mask is ROI-sized (rh,rw)
    try:
        if text_mask.shape[:2] == (rh, rw):
            full_mask[y1:y2, x1:x2] = text_mask
        else:
            # fallback: resize to ROI and assign
            if HAS_CV2:
                resized = cv2.resize(text_mask, (rw, rh), interpolation=cv2.INTER_NEAREST)
                full_mask[y1:y2, x1:x2] = resized
            else:
                from PIL import Image as _PILImage
                pil = _PILImage.fromarray(text_mask)
                resized = pil.resize((rw, rh), resample=_PILImage.NEAREST)
                full_mask[y1:y2, x1:x2] = np.array(resized)
    except Exception:
        # In extreme cases, fill the box
        full_mask[y1:y2, x1:x2] = 255
    
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


def estimate_stroke_mask(img_array, x1, y1, x2, y2):
    """
    Try to estimate a conservative text/stroke mask inside the box.
    Returns a single-channel uint8 mask with 255 where strokes likely are.
    """
    h, w = img_array.shape[:2]
    x1 = max(0, min(x1, w))
    y1 = max(0, min(y1, h))
    x2 = max(0, min(x2, w))
    y2 = max(0, min(y2, h))

    if x2 <= x1 or y2 <= y1:
        return np.zeros((0, 0), dtype=np.uint8)

    region = img_array[y1:y2, x1:x2].copy()
    rh, rw = region.shape[:2]

    if not HAS_CV2:
        # Fallback: return an empty mask to avoid filling the whole block
        return np.zeros((rh, rw), dtype=np.uint8)

    gray = cv2.cvtColor(region, cv2.COLOR_RGB2GRAY)

    # Small blur to reduce texture noise
    blur = cv2.medianBlur(gray, 3)

    # Edge-based stroke detection
    edges = cv2.Canny(blur, 50, 150)
    # Expand edges a bit to encompass anti-aliased strokes
    try:
        exp_k = max(1, min(8, int(min(rh, rw) / 60)))
        if exp_k > 1:
            edges = cv2.dilate(edges, np.ones((exp_k, exp_k), np.uint8), iterations=1)
    except Exception:
        pass
    kernel = np.ones((2, 2), np.uint8)
    mask = cv2.dilate(edges, kernel, iterations=1)

    # If edges are too sparse, try adaptive thresholding (for bright/dark strokes)
    if cv2.countNonZero(mask) < (0.01 * rh * rw):
        th = cv2.adaptiveThreshold(blur, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
                                   cv2.THRESH_BINARY_INV, 11, 2)
        th = cv2.morphologyEx(th, cv2.MORPH_OPEN, kernel)
        if cv2.countNonZero(th) > cv2.countNonZero(mask):
            mask = th

    # Clean up small noise and close small gaps
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((3, 3), np.uint8))
    # Slight dilation to capture faint anti-aliased borders
    try:
        dial_k = max(1, min(6, int(min(rh, rw) / 80)))
        if dial_k > 1:
            mask = cv2.dilate(mask, np.ones((dial_k, dial_k), np.uint8), iterations=1)
    except Exception:
        pass

    # Final check: if still empty, return empty mask (we'll use conservative center fallback later)
    if cv2.countNonZero(mask) == 0:
        return np.zeros((rh, rw), dtype=np.uint8)

    # Ensure binary 0/255
    mask = (mask > 0).astype('uint8') * 255
    return mask


def is_overlay_region(img_array, x1, y1, x2, y2, var_thresh=400.0, area_thresh=0.12):
    """
    Heuristic: determine if region is an overlay (uniform colored box) so we avoid
    erasing non-text content inside it. Returns True if likely overlay.
    """
    h, w = img_array.shape[:2]
    x1 = max(0, min(x1, w))
    y1 = max(0, min(y1, h))
    x2 = max(0, min(x2, w))
    y2 = max(0, min(y2, h))

    if x2 <= x1 or y2 <= y1:
        return False

    region = img_array[y1:y2, x1:x2]
    if region.size == 0:
        return False

    # Low color variance -> likely an overlay background
    variances = np.var(region.reshape(-1, 3).astype(np.float32), axis=0)
    mean_var = float(np.mean(variances))

    region_area = (x2 - x1) * (y2 - y1)
    img_area = h * w
    frac = region_area / max(1, img_area)

    if mean_var < var_thresh and frac > area_thresh:
        return True

    return False


def _interpolate_fill_local(img_array, local_mask, x1, y1, max_search_radius):
    """
    Fill masked pixels (local_mask: 255 for pixels to fill) within the ROI of img_array
    by interpolating nearby non-masked pixels. The function only writes into the
    ROI area and returns a modified copy of img_array.
    This performs a per-pixel weighted average of nearby non-masked neighbors
    (inverse-distance weighting) limited by max_search_radius.
    """
    result = img_array.copy()
    h, w = img_array.shape[:2]
    rh, rw = local_mask.shape

    # Coordinates of pixels to fill (local coords)
    to_fill = np.argwhere(local_mask > 0)

    # Precompute neighbor kernel offsets up to a small window; we'll expand if needed
    for (ly, lx) in to_fill:
        gy = y1 + ly
        gx = x1 + lx

        # search radius (cap to image bounds and max_search_radius)
        r = int(min(max_search_radius, max(h, w)))
        # reduce hard cap for performance; a very large radius is too slow
        r = max(1, min(r, 60))  # cap to 60 px for performance

        # Window bounds
        wy1 = max(0, gy - r)
        wy2 = min(h, gy + r + 1)
        wx1 = max(0, gx - r)
        wx2 = min(w, gx + r + 1)

        window = result[wy1:wy2, wx1:wx2]
        window_mask = local_mask[(wy1 - y1):(wy2 - y1), (wx1 - x1):(wx2 - x1)] if (wy1 - y1) >= 0 and (wx1 - x1) >= 0 and (wy2 - y1) <= rh and (wx2 - x1) <= rw else None

        # Collect candidate pixels from the window BORDER only (faster)
        candidates = []
        cand_weights = []
        # top row
        for xx in range(wx1, wx2):
            yy = wy1
            lm_y = yy - y1
            lm_x = xx - x1
            if 0 <= lm_y < rh and 0 <= lm_x < rw and local_mask[lm_y, lm_x] == 0:
                py, px = yy, xx
                dist = math.hypot(py - gy, px - gx)
                weight = 1.0 if dist == 0 else 1.0 / (dist + 1e-6)
                candidates.append(result[py, px].astype(np.float64))
                cand_weights.append(weight)
        # bottom row
        for xx in range(wx1, wx2):
            yy = wy2 - 1
            lm_y = yy - y1
            lm_x = xx - x1
            if 0 <= lm_y < rh and 0 <= lm_x < rw and local_mask[lm_y, lm_x] == 0:
                py, px = yy, xx
                dist = math.hypot(py - gy, px - gx)
                weight = 1.0 if dist == 0 else 1.0 / (dist + 1e-6)
                candidates.append(result[py, px].astype(np.float64))
                cand_weights.append(weight)
        # left col
        for yy in range(wy1 + 1, wy2 - 1):
            xx = wx1
            lm_y = yy - y1
            lm_x = xx - x1
            if 0 <= lm_y < rh and 0 <= lm_x < rw and local_mask[lm_y, lm_x] == 0:
                py, px = yy, xx
                dist = math.hypot(py - gy, px - gx)
                weight = 1.0 if dist == 0 else 1.0 / (dist + 1e-6)
                candidates.append(result[py, px].astype(np.float64))
                cand_weights.append(weight)
        # right col
        for yy in range(wy1 + 1, wy2 - 1):
            xx = wx2 - 1
            lm_y = yy - y1
            lm_x = xx - x1
            if 0 <= lm_y < rh and 0 <= lm_x < rw and local_mask[lm_y, lm_x] == 0:
                py, px = yy, xx
                dist = math.hypot(py - gy, px - gx)
                weight = 1.0 if dist == 0 else 1.0 / (dist + 1e-6)
                candidates.append(result[py, px].astype(np.float64))
                cand_weights.append(weight)

        if len(candidates) == 0:
            # no neighbors found in window; skip filling this pixel this pass
            continue

        weights = np.array(cand_weights, dtype=np.float64)
        weights = weights / (np.sum(weights) + 1e-12)
        colors = np.vstack(candidates)
        fill_color = np.sum(colors * weights[:, None], axis=0)

        result[gy, gx] = np.clip(fill_color, 0, 255).astype(np.uint8)

    return result


def _ensure_mask_roi(mask, h, w, x1, y1, x2, y2):
    """
    Ensure a provided mask matches ROI shape (y2-y1, x2-x1).
    Accepts masks that are either full-image sized (h, w), already ROI-sized,
    or other sizes (resizes or center-crops them to ROI).
    """
    roi_h = y2 - y1
    roi_w = x2 - x1
    try:
        if mask is None:
            return np.zeros((roi_h, roi_w), dtype=np.uint8)
        mh, mw = mask.shape[:2]
        # If mask is full-image sized
        if (mh, mw) == (h, w):
            return mask[y1:y2, x1:x2]
        # If mask already ROI sized
        if (mh, mw) == (roi_h, roi_w):
            return mask
        # Otherwise, try to resize via cv2 if available, else PIL
        if HAS_CV2:
            resized = cv2.resize(mask, (roi_w, roi_h), interpolation=cv2.INTER_NEAREST)
            # Ensure 0/255 semantics
            resized = (resized > 0).astype('uint8') * 255
            return resized.astype(np.uint8)
        else:
            pil = Image.fromarray(mask)
            pil_resized = pil.resize((roi_w, roi_h), resample=Image.NEAREST)
            arr = np.array(pil_resized).astype(np.uint8)
            arr = (arr > 0).astype('uint8') * 255
            return arr
    except Exception:
        return np.zeros((roi_h, roi_w), dtype=np.uint8)


def _extract_or_resize_to_roi(tmp, x1, y1, x2, y2):
    """
    Ensure `tmp` can be assigned into ROI [y1:y2, x1:x2].
    If `tmp` is full-image sized, return its crop for the ROI.
    If `tmp` matches ROI size, return it.
    Otherwise, resize `tmp` to ROI size using OpenCV if available, or PIL as fallback.
    Returns None on failure.
    """
    if tmp is None:
        return None

    roi_h = y2 - y1
    roi_w = x2 - x1

    # If tmp is numpy array
    try:
        th, tw = tmp.shape[:2]
    except Exception:
        return None

    # If already ROI sized
    if (th, tw) == (roi_h, roi_w):
        return tmp

    # If tmp is full image (> roi), try to crop
    # We can't know original image size here, assume caller will pass full-image tmp
    if th >= roi_h and tw >= roi_w:
        # try cropping by coordinates if possible
        try:
            cropped = tmp[y1:y2, x1:x2]
            if cropped.shape[:2] == (roi_h, roi_w):
                return cropped
        except Exception:
            pass
        # If coordinate crop didn't work (tmp not aligned to global coords), try center-crop
        try:
            cy = max(0, (th - roi_h) // 2)
            cx = max(0, (tw - roi_w) // 2)
            cropped = tmp[cy:cy + roi_h, cx:cx + roi_w]
            if cropped.shape[:2] == (roi_h, roi_w):
                return cropped
        except Exception:
            pass

    # Resize tmp to roi size
    try:
        if HAS_CV2:
            resized = cv2.resize(tmp, (roi_w, roi_h), interpolation=cv2.INTER_LINEAR)
            return resized
        else:
            # PIL fallback
            pil_img = Image.fromarray(tmp)
            pil_resized = pil_img.resize((roi_w, roi_h), resample=Image.BILINEAR)
            return np.array(pil_resized)
    except Exception:
        return None


def _fill_mask_iteratively(img_array, local_mask, x1, y1, max_dim):
    """
    Iteratively fill masked pixels one-pixel-at-a-time (conceptually) using
    interpolation from surrounding pixels. The search radius is 3*max_dim but
    we cap it to reasonable size. The function stops early if no progress.
    """
    result = img_array.copy()
    rh, rw = local_mask.shape
    # radius heuristic from requirement: 3 * max(ch, cw)
    radius = int(min(max(1, 3 * max(rh, rw)), max(result.shape[:2])))
    # don't search absurdly far
    radius = min(radius, 200)

    prev_unfilled = np.sum(local_mask > 0)
    max_iters = 10
    for it in range(max_iters):
        result = _interpolate_fill_local(result, local_mask, x1, y1, radius)

        # recompute local mask: pixels considered filled if they differ little from neighbors
        # A pixel is considered filled when it no longer exactly equals original text color area.
        # For simplicity, we check whether masked pixels now have neighbors (non-mask) nearby
        # and mark them as filled when their value is close to neighborhood average.
        new_local_mask = local_mask.copy()
        coords = np.argwhere(local_mask > 0)
        for (ly, lx) in coords:
            gy = y1 + ly
            gx = x1 + lx
            # neighborhood
            wy1 = max(0, gy - 1)
            wy2 = min(result.shape[0], gy + 2)
            wx1 = max(0, gx - 1)
            wx2 = min(result.shape[1], gx + 2)
            neigh = result[wy1:wy2, wx1:wx2].reshape(-1, 3)
            if neigh.size == 0:
                continue
            avg = np.mean(neigh, axis=0)
            cur = result[gy, gx].astype(np.float32)
            if np.linalg.norm(cur - avg) < 20.0:
                new_local_mask[ly, lx] = 0

        local_mask = new_local_mask
        remaining = np.sum(local_mask > 0)
        if remaining == 0 or remaining == prev_unfilled:
            break
        prev_unfilled = remaining

    return result


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


def exemplar_based_inpaint(img_array, mask, patch_size=9):
    """
    Exemplar-based inpainting - thuật toán tương tự Content-Aware Fill của Photoshop.
    Tìm và copy patch tương tự từ vùng không bị mask để tái tạo texture.
    
    Thuật toán:
    1. Tìm pixel ưu tiên cao nhất ở biên mask (dựa trên gradient và confidence)
    2. Tìm patch tương tự nhất từ vùng source
    3. Copy patch đó vào vùng target
    4. Lặp lại cho đến khi fill hết
    """
    if not HAS_CV2:
        return img_array
    
    img = img_array.copy()
    h, w = img.shape[:2]
    
    # Đảm bảo mask là binary
    mask = (mask > 0).astype(np.uint8) * 255
    
    # Confidence map - ban đầu = 1 cho vùng không mask, 0 cho vùng mask
    confidence = (mask == 0).astype(np.float32)
    
    half_patch = patch_size // 2
    
    # Số iteration tối đa
    max_iterations = 10000
    iteration = 0
    
    while True:
        iteration += 1
        if iteration > max_iterations:
            break
            
        # Tìm biên của vùng mask (fill front)
        kernel = np.ones((3, 3), np.uint8)
        dilated = cv2.dilate(mask, kernel)
        fill_front = dilated - mask
        
        # Tìm các pixel trên fill front
        front_pixels = np.argwhere(fill_front > 0)
        if len(front_pixels) == 0:
            break  # Đã fill xong
        
        # Tính priority cho mỗi pixel trên front
        best_priority = -1
        best_pixel = None
        
        # Tính gradient của ảnh
        gray = cv2.cvtColor(img, cv2.COLOR_RGB2GRAY).astype(np.float32)
        grad_x = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
        grad_y = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
        
        # Tính normal của fill front (gradient của mask)
        mask_float = mask.astype(np.float32)
        normal_x = cv2.Sobel(mask_float, cv2.CV_32F, 1, 0, ksize=3)
        normal_y = cv2.Sobel(mask_float, cv2.CV_32F, 0, 1, ksize=3)
        
        # Sample một số pixel từ front để tăng tốc
        sample_size = min(100, len(front_pixels))
        sampled_indices = np.random.choice(len(front_pixels), sample_size, replace=False)
        
        for idx in sampled_indices:
            py, px = front_pixels[idx]
            
            if py < half_patch or py >= h - half_patch or px < half_patch or px >= w - half_patch:
                continue
            
            # Confidence term
            patch_confidence = confidence[py-half_patch:py+half_patch+1, px-half_patch:px+half_patch+1]
            C = np.mean(patch_confidence)
            
            # Data term (dot product của gradient và normal)
            gx, gy = grad_x[py, px], grad_y[py, px]
            nx, ny = normal_x[py, px], normal_y[py, px]
            
            # Isophote direction (perpendicular to gradient)
            iso_x, iso_y = -gy, gx
            
            D = abs(iso_x * nx + iso_y * ny) / 255.0 + 0.001
            
            priority = C * D
            
            if priority > best_priority:
                best_priority = priority
                best_pixel = (py, px)
        
        if best_pixel is None:
            # Fallback: chọn pixel đầu tiên
            for (py, px) in front_pixels:
                if py >= half_patch and py < h - half_patch and px >= half_patch and px < w - half_patch:
                    best_pixel = (py, px)
                    break
        
        if best_pixel is None:
            break
        
        py, px = best_pixel
        
        # Lấy patch tại vị trí này
        target_patch = img[py-half_patch:py+half_patch+1, px-half_patch:px+half_patch+1].copy()
        target_mask = mask[py-half_patch:py+half_patch+1, px-half_patch:px+half_patch+1].copy()
        
        # Tìm patch tương tự nhất từ vùng source (không bị mask)
        best_match = None
        best_ssd = float('inf')
        
        # Tìm kiếm trong vùng xung quanh trước (local search)
        search_radius = min(100, max(h, w) // 2)
        
        for sy in range(max(half_patch, py - search_radius), min(h - half_patch, py + search_radius), 3):
            for sx in range(max(half_patch, px - search_radius), min(w - half_patch, px + search_radius), 3):
                # Bỏ qua nếu patch này chứa vùng mask
                source_mask = mask[sy-half_patch:sy+half_patch+1, sx-half_patch:sx+half_patch+1]
                if np.any(source_mask > 0):
                    continue
                
                source_patch = img[sy-half_patch:sy+half_patch+1, sx-half_patch:sx+half_patch+1]
                
                # Tính SSD chỉ cho phần không bị mask của target
                valid_mask = (target_mask == 0)
                if not np.any(valid_mask):
                    continue
                
                diff = (source_patch.astype(np.float32) - target_patch.astype(np.float32)) ** 2
                ssd = np.sum(diff * valid_mask[:, :, np.newaxis])
                
                if ssd < best_ssd:
                    best_ssd = ssd
                    best_match = source_patch.copy()
        
        # Nếu không tìm được trong local, tìm global
        if best_match is None:
            for sy in range(half_patch, h - half_patch, 5):
                for sx in range(half_patch, w - half_patch, 5):
                    source_mask = mask[sy-half_patch:sy+half_patch+1, sx-half_patch:sx+half_patch+1]
                    if np.any(source_mask > 0):
                        continue
                    
                    source_patch = img[sy-half_patch:sy+half_patch+1, sx-half_patch:sx+half_patch+1]
                    
                    valid_mask = (target_mask == 0)
                    if not np.any(valid_mask):
                        continue
                    
                    diff = (source_patch.astype(np.float32) - target_patch.astype(np.float32)) ** 2
                    ssd = np.sum(diff * valid_mask[:, :, np.newaxis])
                    
                    if ssd < best_ssd:
                        best_ssd = ssd
                        best_match = source_patch.copy()
        
        if best_match is not None:
            # Copy phần mask của best_match vào target
            fill_mask = target_mask > 0
            target_patch[fill_mask] = best_match[fill_mask]
            
            # Ghi lại vào ảnh
            img[py-half_patch:py+half_patch+1, px-half_patch:px+half_patch+1] = target_patch
            
            # Cập nhật mask và confidence
            mask[py-half_patch:py+half_patch+1, px-half_patch:px+half_patch+1][fill_mask] = 0
            
            old_confidence = confidence[py-half_patch:py+half_patch+1, px-half_patch:px+half_patch+1]
            new_confidence = np.mean(old_confidence[~fill_mask]) if np.any(~fill_mask) else 0.5
            confidence[py-half_patch:py+half_patch+1, px-half_patch:px+half_patch+1][fill_mask] = new_confidence
    
    return img


def weight_aware_inpaint(img_array, mask, x1, y1, x2, y2):
    """
    Inpainting đơn giản và nhanh sử dụng cv2.inpaint.
    Kết hợp TELEA và NS để có kết quả tốt nhất.
    CHỈ xử lý vùng ROI, giữ nguyên các vùng khác.
    """
    if not HAS_CV2:
        return None
        
    h, w = img_array.shape[:2]
    
    # Trích xuất vùng ROI với padding
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


def roi_only_inpaint_dual(region_rgb, roi_mask, radius_telea=10, radius_ns=5):
    """
    Inpainting kết hợp NS và SEAMLESS BLENDING.
    Tự động thích nghi:
    - Nền mịn (Gradient/Màu bệt): Giữ nguyên độ mịn, KHÔNG thêm hạt.
    - Nền nhám (Giấy/Chi tiết): Tái tạo hạt (grain) để tệp với nền.
    - Luôn áp dụng Poisson seamless blend để hòa nhập với background.
    """
    if not HAS_CV2:
        return region_rgb
    
    if roi_mask.sum() == 0:
        return region_rgb
    
    # Đảm bảo mask là uint8
    roi_mask = roi_mask.astype(np.uint8)
    h, w = roi_mask.shape
    
    # Convert sang BGR cho OpenCV
    region_bgr = cv2.cvtColor(region_rgb, cv2.COLOR_RGB2BGR)
    original_bgr = region_bgr.copy()
    original_rgb_copy = region_rgb.copy()
    
    # === BƯỚC 1: Inpaint nền (Background) ===
    # Kết hợp TELEA + NS để có chất lượng tốt nhất:
    # - TELEA: Fast Marching Method - propagate texture/color từ biên vào tốt
    # - NS: Navier-Stokes - làm mượt gradient, giữ structure đường thẳng
    
    # Bước 1a: TELEA trước - fill cấu trúc cơ bản và texture
    inpainted_telea = cv2.inpaint(region_bgr, roi_mask, 7, cv2.INPAINT_TELEA)
    
    # Bước 1b: NS sau - làm mượt và refine gradient
    # Dùng eroded mask để chỉ smooth phần giữa, giữ nguyên biên đã blend tốt
    eroded_mask = cv2.erode(roi_mask, np.ones((3, 3), np.uint8), iterations=1)
    inpainted = cv2.inpaint(inpainted_telea, eroded_mask, 5, cv2.INPAINT_NS)
    
    # Bước 1c: Kiểm tra mask dài/hẹp - cần xử lý thêm để tránh seam
    contours, _ = cv2.findContours(roi_mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if contours:
        x, y, cw, ch = cv2.boundingRect(contours[0])
        aspect_ratio = max(cw, ch) / (min(cw, ch) + 1e-6)
        if aspect_ratio > 3.0:  # Mask dài gấp 3 lần rộng
            # Pass thêm với TELEA radius lớn để xóa seam ở giữa
            center_mask = cv2.erode(roi_mask, np.ones((5, 5), np.uint8), iterations=2)
            if cv2.countNonZero(center_mask) > 0:
                inpainted = cv2.inpaint(inpainted, center_mask, 10, cv2.INPAINT_TELEA)
    
    # === BƯỚC 2: Phân tích độ nhám của nền (Texture Analysis) ===
    
    # Xác định vùng mẫu sạch (ngoài mask và các vùng an toàn)
    kernel_dilate = np.ones((7, 7), np.uint8)
    expanded_mask = cv2.dilate(roi_mask, kernel_dilate, iterations=1)
    
    # Lọc bỏ mực đen để chỉ lấy mẫu trên GIẤY/NỀN
    gray = cv2.cvtColor(original_bgr, cv2.COLOR_BGR2GRAY)
    _, dark_pixels_mask = cv2.threshold(gray, 100, 255, cv2.THRESH_BINARY_INV)
    
    exclusion_mask = cv2.bitwise_or(expanded_mask, dark_pixels_mask)
    
    sample_mask = (exclusion_mask == 0)
    if np.sum(sample_mask) < 30:
         sample_mask = (expanded_mask == 0)
    
    # Tính sigma (độ lệch chuẩn) của nhiễu trên nền
    blur_original = cv2.GaussianBlur(original_bgr, (5, 5), 0)
    texture_diff = original_bgr.astype(np.float32) - blur_original.astype(np.float32)
    
    sigma = 0 
    if np.sum(sample_mask) > 30:
        samples = texture_diff[sample_mask]
        sigma = np.mean(np.std(samples, axis=0))
    
    # === BƯỚC 3: Quyết định chiến lược (Adaptive Strategy) ===
    # TĂNG ngưỡng để phân biệt rõ hơn giữa mịn và sạn
    SMOOTH_THRESHOLD = 5.0
    
    if sigma < SMOOTH_THRESHOLD:
        # --- CHIẾN LƯỢC CHO NỀN MỊN ---
        # KHÔNG thêm grain - giữ nguyên kết quả inpaint mượt
        inpainted_rgb = cv2.cvtColor(inpainted, cv2.COLOR_BGR2RGB)
    else:
        # --- CHIẾN LƯỢC CHO NỀN NHÁM (SẠN) ---
        # Chỉ thêm grain khi vùng xung quanh thực sự có texture sạn
        grain_sigma = np.clip(sigma * 0.8, 3.0, 10.0)
        
        # Sinh hạt nhẹ hơn
        noise = np.random.randn(h, w, 3).astype(np.float32) * grain_sigma
        noise = cv2.GaussianBlur(noise, (5, 5), 0.8)
        
        # Blend hạt vào nền inpaint
        mask_3ch = (roi_mask > 0).astype(np.float32)[:, :, np.newaxis]
        final_float = inpainted.astype(np.float32) + noise * mask_3ch
        result_bgr = np.clip(final_float, 0, 255).astype(np.uint8)
        inpainted_rgb = cv2.cvtColor(result_bgr, cv2.COLOR_BGR2RGB)
    
    # === BƯỚC 4: POST-PROCESSING (Chỉ feather viền) ===
    # Chỉ làm mềm viền, KHÔNG blend với ảnh gốc để tránh giữ lại text
    if HAS_POSTPROCESSOR:
        try:
            inpainted_rgb = image_postprocessor.feather_edge_only(
                original_rgb_copy, 
                inpainted_rgb, 
                roi_mask,
                feather_width=5
            )
        except Exception:
            pass
    
    return inpainted_rgb


def _copy_nearest_texture_fast(texture_source, mask):
    """
    Copy texture từ vùng source gần nhất vào vùng mask.
    Sử dụng distance transform để tìm pixel source gần nhất - nhanh hơn.
    """
    if not HAS_CV2:
        return texture_source
    
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
    
    return result


def process_single_block_parallel(args):
    """
    Xử lý một block text đơn lẻ - được thiết kế để chạy song song.
    Chỉ xử lý vùng ROI của block, không chạm đến các pixel khác.
    
    Args:
        args: tuple (block_idx, block, img_array_shape, img_array_bytes)
    
    Returns:
        tuple (x1, y1, x2, y2, processed_roi, block_idx) hoặc None nếu thất bại
    """
    block_idx, block, img_shape, img_bytes, has_cv2 = args
    
    try:
        # Reconstruct image array from bytes (for ProcessPoolExecutor)
        img_array = np.frombuffer(img_bytes, dtype=np.uint8).reshape(img_shape)
        height, width = img_array.shape[:2]
        
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
            return None

        # Tạo text stroke mask CHỈ cho vùng ROI
        local_text_mask = create_text_stroke_mask(img_array, x1, y1, x2, y2)
        try:
            local_text_mask = _ensure_mask_roi(local_text_mask, height, width, x1, y1, x2, y2)
        except Exception:
            local_text_mask = np.zeros((y2 - y1, x2 - x1), dtype=np.uint8)

        if np.sum(local_text_mask) == 0:
            return None

        # Trích xuất ROI từ ảnh gốc
        roi_original = img_array[y1:y2, x1:x2].copy()
        
        # Inpainting CHỈ trên ROI
        if has_cv2:
            roi_inpainted = roi_only_inpaint_dual(roi_original, local_text_mask)
        else:
            # Fallback cho trường hợp không có OpenCV
            roi_inpainted = roi_original.copy()
            # Simple average fill
            mask_bool = local_text_mask > 0
            if np.any(mask_bool):
                non_mask_pixels = roi_original[~mask_bool]
                if len(non_mask_pixels) > 0:
                    fill_color = np.mean(non_mask_pixels, axis=0).astype(np.uint8)
                    roi_inpainted[mask_bool] = fill_color
        
        return (x1, y1, x2, y2, roi_inpainted, block_idx)
        
    except Exception as e:
        logging.error(f"Error processing block {block_idx}: {e}")
        return None


def color_aware_inpaint(img_array, mask, x1, y1, x2, y2):
    # Wrapper để giữ tương thích, sử dụng thuật toán mới
    return weight_aware_inpaint(img_array, mask, x1, y1, x2, y2)


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
    Xóa text từ ảnh sử dụng inpainting với xử lý song song.
    CHỈ tái tạo các vùng bị xóa text, GIỮ NGUYÊN các vùng khác.
    
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

        # GIỮ NGUYÊN ảnh gốc, chỉ cập nhật các vùng ROI
        result_array = img_array.copy()

        def _process_block_roi_only(block_idx, block):
            """
            Xử lý một block text - CHỈ trả về vùng ROI đã được inpaint.
            Không chạm đến bất kỳ pixel nào ngoài vùng ROI.
            """
            try:
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
                    return None

                # Tạo text stroke mask CHỈ cho vùng ROI
                local_text_mask = create_text_stroke_mask(img_array, x1, y1, x2, y2)
                try:
                    local_text_mask = _ensure_mask_roi(local_text_mask, height, width, x1, y1, x2, y2)
                except Exception:
                    local_text_mask = np.zeros((y2 - y1, x2 - x1), dtype=np.uint8)

                if np.sum(local_text_mask) == 0:
                    return None

                # Trích xuất vùng ROI từ ảnh gốc
                roi_original = img_array[y1:y2, x1:x2].copy()
                
                # === INPAINTING CHỈ TRÊN VÙNG ROI ===
                roi_inpainted = None
                
                if HAS_CV2:
                    # Sử dụng ROI-only inpainting (nhanh và hiệu quả)
                    roi_inpainted = roi_only_inpaint_dual(roi_original, local_text_mask)
                    
                    # === POST-PROCESSING CAO CẤP ===
                    if HAS_POSTPROCESSOR:
                        try:
                            # Áp dụng bộ lọc hậu kỳ từ module image_postprocessor
                            # Sử dụng level 'strong' để tối ưu độ nét và bám sát texture gốc
                            # Tăng cường lọc chi tiết để ảnh sắc nét hơn (giống AI Upscaling)
                            roi_inpainted = image_postprocessor.post_process_inpainted_region(
                                roi_original, 
                                roi_inpainted, 
                                local_text_mask, 
                                level='strong' 
                            )
                        except Exception as e:
                            logging.error(f"Post-processing failed for block: {e}")
                else:
                    # Fallback: sử dụng PIL inpainting trên ROI
                    roi_inpainted = _pil_inpaint_roi(roi_original, local_text_mask)
                
                # Kiểm tra kết quả
                if roi_inpainted is None:
                    return None
                
                if roi_inpainted.shape[:2] != (y2 - y1, x2 - x1):
                    roi_inpainted = _extract_or_resize_to_roi(roi_inpainted, x1, y1, x2, y2)
                
                if roi_inpainted is None:
                    return None

                return (x1, y1, x2, y2, roi_inpainted, block_idx)
                
            except Exception as e:
                logging.error(f"Error processing block {block_idx}: {e}")
                return None

        # === XỬ LÝ SONG SONG ===
        results = []
        num_blocks = len(blocks)
        
        if num_blocks > 1:
            # Sử dụng ThreadPoolExecutor cho nhiều blocks
            # (ThreadPool tốt hơn ProcessPool vì share memory với img_array)
            max_workers = min(MAX_WORKERS, num_blocks)
            
            with ThreadPoolExecutor(max_workers=max_workers) as executor:
                # Submit tất cả các tasks cùng lúc
                future_to_block = {
                    executor.submit(_process_block_roi_only, idx, block): idx 
                    for idx, block in enumerate(blocks)
                }
                
                # Thu thập kết quả khi hoàn thành
                for future in concurrent.futures.as_completed(future_to_block):
                    try:
                        result = future.result()
                        if result is not None:
                            results.append(result)
                    except Exception as e:
                        logging.error(f"Block processing error: {e}")
        else:
            # Xử lý đơn lẻ nếu chỉ có 1 block
            result = _process_block_roi_only(0, blocks[0])
            if result is not None:
                results.append(result)

        # === ÁP DỤNG KẾT QUẢ VÀO ẢNH GỐC ===
        # Chỉ cập nhật các vùng ROI đã được xử lý
        for (x1, y1, x2, y2, roi_processed, block_idx) in results:
            try:
                if roi_processed is not None and roi_processed.shape[:2] == (y2 - y1, x2 - x1):
                    # CHỈ cập nhật vùng ROI này, không chạm các pixel khác
                    result_array[y1:y2, x1:x2] = roi_processed
            except Exception as e:
                logging.error(f"Error applying result for block {block_idx}: {e}")
        
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


def _pil_inpaint_roi(roi_rgb, roi_mask, iterations=8):
    """
    PIL-based inpainting CHỈ trên vùng ROI.
    Fallback khi không có OpenCV.
    
    Args:
        roi_rgb: numpy array RGB của vùng ROI
        roi_mask: mask uint8 của vùng ROI (255 = cần inpaint)
        iterations: số vòng lặp
    
    Returns:
        numpy array RGB của vùng ROI đã inpaint
    """
    result = roi_rgb.copy().astype(np.float32)
    rh, rw = roi_mask.shape
    mask_bool = roi_mask > 0
    
    for _ in range(iterations):
        # Tạo ảnh blur
        result_img = Image.fromarray(result.astype(np.uint8))
        blurred_img = result_img.filter(ImageFilter.GaussianBlur(radius=2))
        blurred = np.array(blurred_img).astype(np.float32)
        
        # Fill từng pixel trong mask
        for y in range(1, rh - 1):
            for x in range(1, rw - 1):
                if mask_bool[y, x]:
                    neighbors = []
                    for dy in [-1, 0, 1]:
                        for dx in [-1, 0, 1]:
                            if dy == 0 and dx == 0:
                                continue
                            ny, nx = y + dy, x + dx
                            if 0 <= ny < rh and 0 <= nx < rw:
                                if not mask_bool[ny, nx]:
                                    neighbors.append(result[ny, nx])
                                else:
                                    neighbors.append(blurred[ny, nx])
                    
                    if neighbors:
                        avg_neighbor = np.mean(neighbors, axis=0)
                        result[y, x] = 0.7 * avg_neighbor + 0.3 * blurred[y, x]
    
    return result.astype(np.uint8)


def remove_text_with_mask(image_path, mask_path, output_path):
    """
    Xóa text từ ảnh sử dụng mask bitmap (đen trắng).
    Mask: vùng trắng (255) = vùng cần xóa, vùng đen (0) = giữ nguyên.
    CHỈ tái tạo các vùng bị mask, GIỮ NGUYÊN các vùng khác.
    Sử dụng xử lý song song để tăng tốc độ.
    """
    try:
        # Load images
        img = Image.open(image_path)
        if img.mode != 'RGB':
            img = img.convert('RGB')
        img_array = np.array(img)
        h, w = img_array.shape[:2]
        
        mask_img = Image.open(mask_path).convert('L')
        if mask_img.size != img.size:
            mask_img = mask_img.resize(img.size, Image.NEAREST)
        mask_array = np.array(mask_img)
        
        # Binary mask - vùng trắng = cần xóa
        mask_binary = (mask_array > 128).astype(np.uint8) * 255
        
        # Đếm số pixel cần xóa
        mask_pixels = np.sum(mask_binary > 0)
        
        if mask_pixels == 0:
            img.save(output_path, quality=100)
            return output_path
        
        # GIỮ NGUYÊN ảnh gốc, chỉ cập nhật vùng mask
        result_array = img_array.copy()
        
        if HAS_CV2:
            # Tìm các connected components trong mask để xử lý song song
            num_labels, labels, stats, centroids = cv2.connectedComponentsWithStats(mask_binary)
            
            def _process_mask_region(region_info):
                """Xử lý một vùng mask đơn lẻ - CHỈ trên ROI"""
                try:
                    i, x, y, bw, bh, area = region_info
                    if area < 10:
                        return None
                    
                    # Thêm padding nhỏ
                    pad = max(5, min(20, int(min(bw, bh) * 0.2)))
                    x1 = max(0, x - pad)
                    y1 = max(0, y - pad)
                    x2 = min(w, x + bw + pad)
                    y2 = min(h, y + bh + pad)
                    
                    # Trích xuất vùng ROI
                    roi_img = img_array[y1:y2, x1:x2].copy()
                    roi_mask = mask_binary[y1:y2, x1:x2].copy()
                    
                    # Dilate mask nhẹ
                    kernel = np.ones((3, 3), np.uint8)
                    roi_mask_dilated = cv2.dilate(roi_mask, kernel, iterations=1)
                    
                    # Inpainting CHỈ trên ROI - hàm này đã bao gồm texture transfer
                    roi_inpainted = roi_only_inpaint_dual(roi_img, roi_mask_dilated, 
                                                          radius_telea=10, radius_ns=5)
                    
                    # === POST-PROCESSING CAO CẤP ===
                    if HAS_POSTPROCESSOR:
                        try:
                            roi_inpainted = image_postprocessor.post_process_inpainted_region(
                                roi_img,
                                roi_inpainted,
                                roi_mask_dilated,
                                level='medium'
                            )
                        except Exception as e:
                            logging.error(f"Post-processing failed for region: {e}")
                    
                    return (x1, y1, x2, y2, roi_inpainted, i)
                    
                except Exception as e:
                    logging.error(f"Error processing mask region {i}: {e}")
                    return None
            
            # Chuẩn bị danh sách các vùng cần xử lý
            regions = []
            for i in range(1, num_labels):
                x, y, bw, bh, area = stats[i]
                regions.append((i, x, y, bw, bh, area))
            
            # Xử lý song song
            results = []
            if len(regions) > 1:
                max_workers = min(MAX_WORKERS, len(regions))
                with ThreadPoolExecutor(max_workers=max_workers) as executor:
                    future_to_region = {
                        executor.submit(_process_mask_region, region): region[0]
                        for region in regions
                    }
                    for future in concurrent.futures.as_completed(future_to_region):
                        try:
                            result = future.result()
                            if result is not None:
                                results.append(result)
                        except Exception as e:
                            logging.error(f"Region processing error: {e}")
            else:
                for region in regions:
                    result = _process_mask_region(region)
                    if result is not None:
                        results.append(result)
            
            # Áp dụng kết quả - CHỈ cập nhật các vùng ROI
            for (x1, y1, x2, y2, roi_processed, region_idx) in results:
                try:
                    if roi_processed is not None and roi_processed.shape[:2] == (y2 - y1, x2 - x1):
                        # Chỉ cập nhật những pixel trong mask, giữ nguyên pixel khác
                        roi_mask = mask_binary[y1:y2, x1:x2]
                        mask_3ch = np.stack([roi_mask > 0] * 3, axis=-1)
                        result_array[y1:y2, x1:x2] = np.where(
                            mask_3ch,
                            roi_processed,
                            result_array[y1:y2, x1:x2]
                        )
                except Exception as e:
                    logging.error(f"Error applying mask region {region_idx}: {e}")
            
            final_img = Image.fromarray(result_array)
        else:
            # Fallback PIL - xử lý từng vùng mask
            final_img = Image.fromarray(pil_inpaint_fallback(img_array, mask_binary, iterations=10))
            
        final_img.save(output_path, quality=100)
        return output_path
        
    except Exception as e:
        import traceback
        error_msg = f"Error: {str(e)}\n{traceback.format_exc()}"
        return error_msg


def _texture_transfer_roi(original_rgb, inpainted_rgb, roi_mask):
    """
    Transfer texture CHỈ trên vùng ROI đã được inpaint.
    Tái tạo chi tiết ảnh từ vùng xung quanh.
    
    Args:
        original_rgb: vùng ROI gốc (RGB)
        inpainted_rgb: vùng ROI đã inpaint (RGB)
        roi_mask: mask của vùng ROI
    
    Returns:
        numpy array RGB với texture đã được transfer và chi tiết được tái tạo
    """
    if not HAS_CV2:
        return inpainted_rgb
    
    result = inpainted_rgb.copy()
    rh, rw = roi_mask.shape
    
    # Convert sang BGR
    result_bgr = cv2.cvtColor(result, cv2.COLOR_RGB2BGR)
    original_bgr = cv2.cvtColor(original_rgb, cv2.COLOR_RGB2BGR)
    
    non_mask = (roi_mask == 0)
    mask_area = (roi_mask > 0)
    
    if np.sum(non_mask) > 30 and np.sum(mask_area) > 0:
        # === Bước 1: Extract high-frequency texture từ vùng gốc ===
        blur_original = cv2.GaussianBlur(original_bgr, (5, 5), 0)
        texture_high_freq = original_bgr.astype(np.float32) - blur_original.astype(np.float32)
        
        # === Bước 2: Copy texture từ vùng source gần nhất ===
        texture_map = _copy_nearest_texture_fast(texture_high_freq, roi_mask)
        
        # === Bước 3: Tính texture statistics và tạo variation ===
        texture_std = np.std(texture_high_freq[non_mask], axis=0) + 1e-6
        # Reduce strength significantly - gentle mode
        texture_strength = np.clip(texture_std * 0.3, 0.5, 5)
        
        # Random variation để tái tạo chi tiết tự nhiên
        np.random.seed(int(np.sum(roi_mask) % 1000))
        random_texture = np.random.randn(rh, rw, 3).astype(np.float32)
        if HAS_CV2:
             random_texture = cv2.GaussianBlur(random_texture, (5, 5), 2.0)
        random_texture = random_texture * texture_strength * 0.1
        
        # Kết hợp texture map và random variation
        combined_texture = texture_map * 0.9 + random_texture * 0.1
        
        # === Bước 4: Áp dụng texture vào vùng mask ===
        mask_3ch = np.stack([mask_area] * 3, axis=-1).astype(np.float32)
        textured = result_bgr.astype(np.float32) + combined_texture * mask_3ch
        textured = np.clip(textured, 0, 255).astype(np.uint8)
        
        # === Bước 5: Soft blend ở biên để transition mượt ===
        soft_mask = cv2.GaussianBlur(roi_mask.astype(np.float32), (5, 5), 0) / 255.0
        soft_mask = soft_mask[:, :, np.newaxis]
        result_bgr = (textured * soft_mask + result_bgr * (1 - soft_mask)).astype(np.uint8)
        
        # === Bước 6: Sharpen để tăng độ nét chi tiết ===
        blurred = cv2.GaussianBlur(result_bgr, (0, 0), 2)
        sharpened = cv2.addWeighted(result_bgr, 1.4, blurred, -0.4, 0)
        
        # Chỉ sharpen vùng mask
        mask_3ch_bool = np.stack([mask_area] * 3, axis=-1)
        result_bgr = np.where(mask_3ch_bool, sharpened, result_bgr)
    
    return cv2.cvtColor(result_bgr, cv2.COLOR_BGR2RGB)


def texture_transfer_inpaint(original_bgr, inpainted_bgr, mask):
    """
    Transfer texture thực từ vùng xung quanh vào vùng đã inpaint.
    Sử dụng patch-based texture synthesis + sharpening để tái tạo chi tiết rõ nét.
    """
    if not HAS_CV2:
        return inpainted_bgr
    
    h, w = original_bgr.shape[:2]
    result = inpainted_bgr.copy()
    
    # Bước 1: Sharpen toàn bộ ảnh inpainted trước
    result = sharpen_image(result, strength=1.5)
    
    # Tìm các connected components trong mask
    num_labels, labels, stats, centroids = cv2.connectedComponentsWithStats(mask)
    
    for i in range(1, num_labels):
        x, y, bw, bh, area = stats[i]
        if area < 10:
            continue
        
        # Mở rộng vùng tìm kiếm texture
        pad = max(50, max(bw, bh) * 2)
        x1 = max(0, x - pad)
        y1 = max(0, y - pad)
        x2 = min(w, x + bw + pad)
        y2 = min(h, y + bh + pad)
        
        region_mask = mask[y1:y2, x1:x2]
        region_original = original_bgr[y1:y2, x1:x2]
        region_inpainted = result[y1:y2, x1:x2]
        
        # Tách texture từ vùng source (không bị mask)
        # Dùng kernel nhỏ hơn để giữ nhiều chi tiết hơn
        blur_original = cv2.GaussianBlur(region_original, (5, 5), 0)
        texture_high_freq = region_original.astype(np.float32) - blur_original.astype(np.float32)
        
        # Lấy texture từ vùng source (không bị mask)
        non_mask = (region_mask == 0)
        mask_area = (region_mask > 0)
        
        if np.sum(non_mask) > 100 and np.sum(mask_area) > 0:
            # Phương pháp 1: Copy texture thực từ vùng gần nhất
            # Sử dụng distance transform để tìm pixel gần nhất
            dist_transform = cv2.distanceTransform((region_mask == 0).astype(np.uint8), cv2.DIST_L2, 5)
            
            # Tạo texture map bằng cách copy từ vùng source gần nhất
            texture_map = copy_nearest_texture(texture_high_freq, region_mask, patch_size=7)
            
            # Phương pháp 2: Thêm high-frequency detail
            # Tính mean texture từ vùng source
            texture_mean = np.mean(texture_high_freq[non_mask], axis=0)
            texture_std = np.std(texture_high_freq[non_mask], axis=0) + 1e-6
            
            # Normalize và scale texture
            texture_strength = np.clip(texture_std * 1.5, 5, 30)  # Tăng cường độ texture
            
            # Kết hợp texture map và random variation
            np.random.seed(42)  # Reproducible
            random_variation = np.random.randn(*region_inpainted.shape).astype(np.float32)
            random_texture = random_variation * texture_strength * 0.3
            
            # Blend cả hai loại texture
            combined_texture = texture_map * 0.7 + random_texture * 0.3
            
            # Chỉ áp dụng vào vùng mask
            mask_3ch = np.stack([mask_area] * 3, axis=-1).astype(np.float32)
            
            # Áp dụng texture với cường độ cao hơn
            textured = region_inpainted.astype(np.float32) + combined_texture * mask_3ch
            textured = np.clip(textured, 0, 255).astype(np.uint8)
            
            # Soft blend ở biên với kernel nhỏ hơn để giữ nét
            soft_mask = cv2.GaussianBlur(region_mask.astype(np.float32), (3, 3), 0) / 255.0
            soft_mask = soft_mask[:, :, np.newaxis]
            
            blended = (textured * soft_mask + region_inpainted * (1 - soft_mask)).astype(np.uint8)
            result[y1:y2, x1:x2] = blended
    
    # Bước cuối: Sharpen lần nữa ở vùng mask
    result = sharpen_masked_region(result, mask, strength=1.2)
    
    return result


def sharpen_image(img_bgr, strength=1.5):
    """Sharpen ảnh sử dụng unsharp mask."""
    if not HAS_CV2:
        return img_bgr
    
    # Unsharp mask: sharpened = original + (original - blurred) * strength
    blurred = cv2.GaussianBlur(img_bgr, (0, 0), 3)
    sharpened = cv2.addWeighted(img_bgr, 1 + strength, blurred, -strength, 0)
    return sharpened


def sharpen_masked_region(img_bgr, mask, strength=1.2):
    """Sharpen chỉ vùng được mask."""
    if not HAS_CV2:
        return img_bgr
    
    # Dilate mask một chút để sharpen cả biên
    kernel = np.ones((3, 3), np.uint8)
    dilated = cv2.dilate(mask, kernel, iterations=1)
    
    # Sharpen toàn bộ
    sharpened = sharpen_image(img_bgr, strength)
    
    # Chỉ lấy vùng sharpened ở nơi có mask
    mask_3ch = np.stack([dilated > 0] * 3, axis=-1)
    result = np.where(mask_3ch, sharpened, img_bgr)
    
    return result


def copy_nearest_texture(texture_source, mask, patch_size=7):
    """
    Copy texture từ vùng source gần nhất vào vùng mask.
    Sử dụng patch-based approach để giữ coherent texture.
    """
    h, w = mask.shape
    result = texture_source.copy()
    
    # Vị trí các pixel cần điền (trong mask)
    mask_indices = np.where(mask > 0)
    if len(mask_indices[0]) == 0:
        return result
    
    # Vị trí các pixel source (ngoài mask)
    source_indices = np.where(mask == 0)
    if len(source_indices[0]) == 0:
        return result
    
    source_coords = np.column_stack((source_indices[0], source_indices[1]))
    
    # Với mỗi pixel trong mask, tìm pixel source gần nhất và copy texture
    half_patch = patch_size // 2
    
    for idx in range(len(mask_indices[0])):
        py, px = mask_indices[0][idx], mask_indices[1][idx]
        
        # Tìm pixel source gần nhất
        distances = np.sqrt((source_coords[:, 0] - py)**2 + (source_coords[:, 1] - px)**2)
        nearest_idx = np.argmin(distances)
        sy, sx = source_coords[nearest_idx]
        
        # Copy texture value (có thêm một chút random variation)
        if 0 <= sy < h and 0 <= sx < w:
            result[py, px] = texture_source[sy, sx] * (0.8 + 0.4 * np.random.random())
    
    return result
