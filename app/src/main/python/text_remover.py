from PIL import Image, ImageDraw, ImageFilter
import numpy as np
import json
import math
import sys
import logging
import concurrent.futures

# Try to import cv2 for advanced inpainting
try:
    import cv2
    HAS_CV2 = True
except Exception:
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
    # Expand mask to include anti-aliased (white/soft) borders around text
    try:
        # Scale expansion by small fraction of region size to capture halo without overfilling
        expand_px = max(1, min(12, int(min(rh, rw) / 40)))
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
        mask = _ensure_mask_roi(mask, h, w, x1, y1, x2, y2)
        # Ensure mask is properly ROI-sized before placing it into full_mask
        mask = _ensure_mask_roi(mask, h, w, x1, y1, x2, y2)
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

        result_array = img_array.copy()

        # Process each block independently (do not combine masks across blocks)
        def _process_block(block_idx, block):
            # This worker computes an ROI-sized processed image for the given block, or None
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

                local_text_mask = create_text_stroke_mask(img_array, x1, y1, x2, y2)
                try:
                    local_text_mask = _ensure_mask_roi(local_text_mask, height, width, x1, y1, x2, y2)
                except Exception:
                    local_text_mask = np.zeros((y2 - y1, x2 - x1), dtype=np.uint8)

                if np.sum(local_text_mask) == 0:
                    return None

                # Allowed margin
                max_dim = max(bw, bh)

                # overlay check
                try:
                    overlay = is_overlay_region(img_array, x1, y1, x2, y2)
                except Exception:
                    overlay = False

                proc = None
                if overlay:
                    if not HAS_CV2:
                        full_mask = np.zeros((height, width), dtype=np.uint8)
                        full_mask[y1:y2, x1:x2] = local_text_mask
                        tmp = pil_inpaint_fallback(img_array, full_mask)
                        if tmp is not None:
                            proc = _extract_or_resize_to_roi(tmp, x1, y1, x2, y2)
                    else:
                        full = _fill_mask_iteratively(img_array.copy(), local_text_mask.copy(), x1, y1, max_dim)
                        if full is not None:
                            proc = full[y1:y2, x1:x2]
                else:
                    try:
                        tmp = color_aware_inpaint(img_array.copy(), local_text_mask, x1, y1, x2, y2)
                    except Exception:
                        tmp = None
                    if tmp is not None:
                        proc = _extract_or_resize_to_roi(tmp, x1, y1, x2, y2)
                    else:
                        if not HAS_CV2:
                            full_mask = np.zeros((height, width), dtype=np.uint8)
                            full_mask[y1:y2, x1:x2] = local_text_mask
                            tmp2 = pil_inpaint_fallback(img_array, full_mask)
                            if tmp2 is not None:
                                proc = _extract_or_resize_to_roi(tmp2, x1, y1, x2, y2)
                        else:
                            full = _fill_mask_iteratively(img_array.copy(), local_text_mask.copy(), x1, y1, max_dim)
                            if full is not None:
                                proc = full[y1:y2, x1:x2]

                # Final verify
                if proc is None:
                    return None
                if proc.shape[:2] != (y2 - y1, x2 - x1):
                    proc = _extract_or_resize_to_roi(proc, x1, y1, x2, y2)

                # Apply smoothing step (edge-aware small-radius inpainting) on a local copy
                try:
                    local_result = img_array.copy()
                    local_result[y1:y2, x1:x2] = proc

                    # Compute edge mask (small kernel) from local_text_mask
                    if HAS_CV2:
                        kernel_small = np.ones((3, 3), np.uint8)
                        edge_mask = cv2.dilate(local_text_mask, kernel_small, iterations=1) - cv2.erode(local_text_mask, kernel_small, iterations=1)
                        # Compute safety band
                        inv = (local_text_mask == 0).astype('uint8') * 255
                        dist = cv2.distanceTransform(inv, cv2.DIST_L2, 5)
                        allowed_margin = max(1, min(int(max_dim * 2), max(width, height)))
                        safety_band = (dist <= float(allowed_margin)).astype('uint8') * 255
                        limited_edge = cv2.bitwise_and(edge_mask, safety_band)
                        full_edge_mask = np.zeros((height, width), dtype=np.uint8)
                        limited_edge_roi = _ensure_mask_roi(limited_edge, height, width, x1, y1, x2, y2)
                        full_edge_mask[y1:y2, x1:x2] = limited_edge_roi
                        try:
                            tmp_s = smart_inpaint(local_result, full_edge_mask, radius=2)
                            if tmp_s is not None:
                                local_result = tmp_s
                        except Exception:
                            pass

                    # Forced fallback: if ROI hasn't changed vs original, do aggressive inpaint
                    roi_before = img_array[y1:y2, x1:x2]
                    roi_after = local_result[y1:y2, x1:x2]
                    if roi_before.shape == roi_after.shape and np.array_equal(roi_before, roi_after):
                        if HAS_CV2:
                            full_box_mask = np.zeros((height, width), dtype=np.uint8)
                            full_box_mask[y1:y2, x1:x2] = 255
                            tmpf = smart_inpaint(local_result, full_box_mask, radius=6)
                            if tmpf is not None:
                                local_result = tmpf
                        else:
                            full_box_mask = np.zeros((height, width), dtype=np.uint8)
                            full_box_mask[y1:y2, x1:x2] = 255
                            tmpf = pil_inpaint_fallback(local_result, full_box_mask)
                            if tmpf is not None:
                                procf = _extract_or_resize_to_roi(tmpf, x1, y1, x2, y2)
                                if procf is not None and procf.shape[:2] == (y2 - y1, x2 - x1):
                                    local_result[y1:y2, x1:x2] = procf

                    # Extract final ROI
                    final_proc = local_result[y1:y2, x1:x2]
                    proc = final_proc
                except Exception:
                    # if any smoothing fails, keep proc as-is
                    pass

                return (x1, y1, x2, y2, proc, block_idx)
            except Exception:
                return None

        # Run blocks in parallel (threaded) and then apply results sequentially
        results = []
        if len(blocks) > 1:
            max_workers = min(4, len(blocks))
            with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as executor:
                futures = [executor.submit(_process_block, idx, block) for idx, block in enumerate(blocks)]
                for fut in concurrent.futures.as_completed(futures):
                    res = fut.result()
                    if res is not None:
                        results.append(res)
        else:
            for idx, block in enumerate(blocks):
                r = _process_block(idx, block)
                if r is not None:
                    results.append(r)

        # Apply results sequentially to avoid race conditions / overlapping mishandling
        for (x1, y1, x2, y2, proc, block_idx) in results:
            try:
                if proc is not None and proc.shape[:2] == (y2 - y1, x2 - x1):
                    result_array[y1:y2, x1:x2] = proc
            except Exception:
                pass
            # All smoothing and fallback are performed within the worker; nothing to do here.
        
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
