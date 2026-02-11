

package com.example.ocrmanga.ui.screens.view

import com.example.ocrmanga.utils.AppLogger as Log

// import android.content.Context (removed duplicate)
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.nativeCanvas
import com.example.ocrmanga.data.models.TextBlockInfo
import com.example.ocrmanga.data.models.TextAlignMode
import java.io.IOException
import android.provider.MediaStore
import kotlin.math.max
import kotlin.math.min
import android.content.Context
import android.graphics.Typeface
import java.util.concurrent.ConcurrentHashMap

// Image and text region utilities extracted from ViewerScreen.kt

// Resolve font file name from a logical font family name used in the app
private fun resolveFontFile(fontFamilyName: String?): String {
    return when (fontFamilyName) {
        // MTO fonts used in drawText
        "mto_astro_city" -> "mto_astro_city.ttf"
        "mto_augie" -> "mto_augie.ttf"
        "mighty_zero" -> "mighty_zero.ttf"
        "mto_chancery" -> "mto_chancery.ttf"
        "mto_comic_1" -> "mto_comic_1.ttf"
        "mto_comic_2" -> "mto_comic_2.ttf"
        "mto_dom" -> "mto_dom.ttf"
        "mto_mikes" -> "mto_mikes.ttf"
        "mto_sans" -> "mto_sans.ttf"
        "mto_shadow" -> "mto_shadow.ttf"
        "semhesta" -> "semhesta.otf"
        "brush_king" -> "brush_king.otf"
        "downward_fall" -> "downward_fall.ttf"
        "dry_brush" -> "dry _brush.otf"
        "edosz" -> "edosz.ttf"
        "kirens_demo" -> "kirens_demo.ttf"
        "kingston" -> "kingston.ttf"
        "novitha_script" -> "novitha_script.ttf"
        "bougher" -> "bougher.otf"
        "adeline" -> "adeline.ttf"
        "blow_brush" -> "blow_brush.ttf"
        "boutique_script" -> "boutique_script.ttf"
        "break_brush" -> "break_brush.otf"
        "calligraphy" -> "calligraphy.otf"
        "cent_comics" -> "cent_comics.ttf"
        "chinacat" -> "chinacat.ttf"
        "chit_chat" -> "chit_chat.ttf"
        "comic_sans" -> "comic_sans.ttf"
        "dexsar_brush" -> "dexsar_brush.otf"
        "entrails" -> "entrails.ttf"
        "felt" -> "felt.ttf"
        "fresh_script" -> "fresh_script.otf"
        "handelson_two" -> "handelson_two.otf"
        "harry_brush" -> "harry_brush.otf"
        "hiro_misake" -> "hiro_misake.otf"
        "iciel_pony" -> "iciel_pony.ttf"
        "imaginary_friend" -> "imaginary_friend.ttf"
        "kashima_brush" -> "kashima_brush.otf"
        "lnth" -> "lnth.ttf"
        "mto_chranko" -> "mto_chranko.ttf"
        "okami" -> "okami.otf"
        "redtowns" -> "redtowns.otf"
        "story_brush" -> "story_brush.ttf"
        "wrong_hunt" -> "wrong_hunt.otf"
        "you_murdere" -> "you_murdere.otf"
        // SF Toontime family names used elsewhere
        "SF Toontime B" -> "SF Toontime B.ttf"
        "SF Toontime B Italic" -> "SF Toontime B Italic.ttf"
        "SF Toontime Blotch Bold" -> "SF Toontime Blotch Bold.ttf"
        "SF Toontime Blotch Bold Italic" -> "SF Toontime Blotch Bold Italic.ttf"
        "SF Toontime Extended" -> "SF Toontime Extended.ttf"
        "SF Toontime Extended Italic" -> "SF Toontime Extended Italic.ttf"
        "SF Toontime Extended Bold" -> "SF Toontime Extended Bold.ttf"
        "SF Toontime Extended Bold Italic" -> "SF Toontime Extended Bold Italic.ttf"
        else -> "mto_astro_city.ttf"
    }
}

// Typeface cache to avoid repeated asset loads during drawing/layout
private val typefaceCache: MutableMap<String, Typeface?> = ConcurrentHashMap()

private fun getCachedTypeface(context: Context, fontFamilyName: String?): Typeface? {
    val key = fontFamilyName ?: "default"
    return typefaceCache.getOrPut(key) {
        try {
            val file = resolveFontFile(fontFamilyName)
            Typeface.createFromAsset(context.assets, "tessdata/font/$file")
        } catch (e: Exception) {
            null
        }
    }
}

// Public helper for export function to load typeface
fun getCachedTypefaceForExport(context: Context, fontFamilyName: String?): Typeface? {
    return getCachedTypeface(context, fontFamilyName)
}


fun getImageDimensions(context: Context, uri: Uri): Pair<Int, Int> {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, options)
        }
        if (options.outWidth > 0 && options.outHeight > 0) {
            return options.outWidth to options.outHeight
        }
        throw IOException("Kích thước ảnh không hợp lệ")
    } catch (e: SecurityException) {
        // Permission denied when trying to open the stream.
        // Try a safer MediaStore query fallback for media URIs before giving up.
        Log.w("ViewerScreen", "SecurityException opening $uri, attempting MediaStore query", e)
        try {
            val projection = arrayOf(MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val wIdx = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                    val hIdx = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                    val w = if (wIdx >= 0) cursor.getInt(wIdx) else -1
                    val h = if (hIdx >= 0) cursor.getInt(hIdx) else -1
                    if (w > 0 && h > 0) return w to h
                }
            }
        } catch (qe: Exception) {
            Log.w("ViewerScreen", "MediaStore query fallback failed for $uri", qe)
        }
        Log.e("ViewerScreen", "Permission denied: reading $uri requires READ_EXTERNAL_STORAGE or grantUriPermission()")
        return 1280 to 1808
    } catch (e: Exception) {
        Log.e("ViewerScreen", "Error getting image dimensions for $uri", e)
        return 1280 to 1808
    }
}

fun mergeOverlappingRegions(
    regions: List<Triple<TextBlockInfo, Rect, Float>>,
    imageWidth: Float
): List<Triple<TextBlockInfo, Rect, Float>> {
    if (regions.isEmpty()) return emptyList()
    
    // Kiểm tra xem có block nào cần apply merge không
    val needMerge = regions.any { it.first.applyMerge }
    if (!needMerge) {
        // Không có block nào cần merge, trả về nguyên bản
        return regions
    }

    val sortedRegions = regions.sortedWith { (blockA, rectA, _), (blockB, rectB, _) ->
        if (blockA.isVertical == blockB.isVertical && blockA.isVertical) {
            compareValuesBy(rectB, rectA, { it.right }, { it.top })
        } else {
            compareValuesBy(rectA, rectB, { it.top }, { it.left })
        }
    }.toMutableList()

    val resultRegions = mutableListOf<Triple<TextBlockInfo, Rect, Float>>()
    val processed = BooleanArray(sortedRegions.size) { false }

    fun isIntersect(a: Rect, b: Rect): Boolean {
        return a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
    }

    fun isIntersectOrSameLine(a: Rect, b: Rect, isVertical: Boolean): Boolean {
        if (isVertical) {
            return a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
        } else {
            val verticalOverlap = a.top < b.bottom && a.bottom > b.top
            val topDiff = kotlin.math.abs(a.top - b.top)
            val avgHeight = ((a.height + b.height) / 2f).coerceAtLeast(1f)
            val sameLine = topDiff < avgHeight * 0.2f
            return (a.left < b.right && a.right > b.left && verticalOverlap) || sameLine
        }
    }

    for (i in sortedRegions.indices) {
        if (processed[i]) continue
        var (currentBlock, currentRect, currentFontSize) = sortedRegions[i]
        processed[i] = true
        var merged = false

        for (j in sortedRegions.indices) {
            if (processed[j] || i == j) continue
            val (otherBlock, otherRect, otherFontSize) = sortedRegions[j]
            if (currentBlock.isVertical != otherBlock.isVertical) continue

            val isOverlap = currentRect.left < otherRect.right && currentRect.right > otherRect.left && currentRect.top < otherRect.bottom && currentRect.bottom > otherRect.top
            val isSameLine = if (!currentBlock.isVertical) {
                val topDiff = kotlin.math.abs(currentRect.top - otherRect.top)
                val avgHeight = ((currentRect.height + otherRect.height) / 2f).coerceAtLeast(1f)
                topDiff < avgHeight * 0.2f
            } else {
                false
            }

            if (isOverlap && !isSameLine) {
                val offset = if (!currentBlock.isVertical) currentRect.width * 0.1f + 2f else currentRect.height * 0.1f + 2f
                if (!currentBlock.isVertical) {
                    val newRect = Rect(
                        otherRect.left + offset,
                        otherRect.top,
                        otherRect.right + offset,
                        otherRect.bottom
                    )
                    sortedRegions[j] = Triple(
                        otherBlock.copy(
                            bounds = android.graphics.Rect(
                                (otherBlock.bounds.left + offset).toInt(),
                                otherBlock.bounds.top,
                                (otherBlock.bounds.right + offset).toInt(),
                                otherBlock.bounds.bottom
                            )
                        ),
                        newRect,
                        otherFontSize
                    )
                } else {
                    val newRect = Rect(
                        otherRect.left,
                        otherRect.top + offset,
                        otherRect.right,
                        otherRect.bottom + offset
                    )
                    sortedRegions[j] = Triple(
                        otherBlock.copy(
                            bounds = android.graphics.Rect(
                                otherBlock.bounds.left,
                                (otherBlock.bounds.top + offset).toInt(),
                                otherBlock.bounds.right,
                                (otherBlock.bounds.bottom + offset).toInt()
                            )
                        ),
                        newRect,
                        otherFontSize
                    )
                }
                continue
            }

            if (isIntersectOrSameLine(currentRect, otherRect, currentBlock.isVertical)) {
                if (regions.isNotEmpty() && !regions.first().first.isVertical) {
                    return regions
                }
                val mergedRect = Rect(
                    min(currentRect.left, otherRect.left),
                    min(currentRect.top, otherRect.top),
                    max(currentRect.right, otherRect.right),
                    max(currentRect.bottom, otherRect.bottom)
                )
                val mergedWidth = mergedRect.width
                val mergedHeight = mergedRect.height
                val minFontSize = min(currentFontSize, otherFontSize)

                val mergedText: String
                val primaryBlock: TextBlockInfo

                if (currentBlock.isVertical) {
                    val isCurrentRighter = currentRect.right > otherRect.right
                    val rightBlock = if (isCurrentRighter) currentBlock else otherBlock
                    val leftBlock = if (isCurrentRighter) otherBlock else currentBlock
                    mergedText = "${rightBlock.text} ${leftBlock.text}"
                    primaryBlock = rightBlock
                } else {
                    val isCurrentUpper = currentRect.top < otherRect.top
                    val upperBlock = if (isCurrentUpper) currentBlock else otherBlock
                    val lowerBlock = if (isCurrentUpper) otherBlock else currentBlock
                    mergedText = "${upperBlock.text}\n${lowerBlock.text}"
                    primaryBlock = upperBlock
                }

                val optimalFontSize = calculateOptimalFontSize(
                    text = mergedText,
                    width = mergedRect.width,
                    height = mergedRect.height,
                    minFontSize = minFontSize,
                    shapeType = currentBlock.shapeType,
                    extraSizeAllowance = 4f,
                    lineSpacing = primaryBlock.lineSpacing
                )



                currentBlock = primaryBlock.copy(
                    text = mergedText,
                    bounds = android.graphics.Rect(
                        mergedRect.left.toInt(),
                        mergedRect.top.toInt(),
                        mergedRect.right.toInt(),
                        mergedRect.bottom.toInt()
                    ),
                    fontSize = optimalFontSize
                )
                currentRect = mergedRect
                currentFontSize = optimalFontSize
                processed[j] = true
                merged = true
            }
        }

        resultRegions.add(Triple(currentBlock, currentRect, currentFontSize))
        if (merged) {
            processed[i] = false
            sortedRegions[i] = Triple(currentBlock, currentRect, currentFontSize)
        }
    }

    val finalRegions = resultRegions.toMutableList()
    var changed: Boolean
    var loopCount = 0
    var dynamicOffset = 16f
    do {
        changed = false
        for (i in finalRegions.indices) {
            val (blockA, rectA, fontSizeA) = finalRegions[i]
            val (wrappedTextA, fontSizeFixedA) = adjustWhiteoutBounds(blockA.text, rectA.width, rectA.height, fontSizeA, blockA.isVertical)
            val linesA = wrapText(wrappedTextA, rectA.width, fontSizeFixedA)
            val lineHeightA = fontSizeFixedA * 1.2f
            val textHeightA = linesA.size * lineHeightA
            val safeRectA = Rect(
                rectA.left - 12f,
                rectA.top - 12f,
                rectA.right + 12f,
                rectA.top + textHeightA + 12f
            )
            for (j in finalRegions.indices) {
                if (i == j) continue
                val (blockB, rectB, fontSizeB) = finalRegions[j]
                if (blockA.isVertical != blockB.isVertical) continue
                val (wrappedTextB, fontSizeFixedB) = adjustWhiteoutBounds(blockB.text, rectB.width, rectB.height, fontSizeB, blockB.isVertical)
                val linesB = wrapText(wrappedTextB, rectB.width, fontSizeFixedB)
                val lineHeightB = fontSizeFixedB * 1.2f
                val textHeightB = linesB.size * lineHeightB
                val safeRectB = Rect(
                    rectB.left - 12f,
                    rectB.top - 12f,
                    rectB.right + 12f,
                    rectB.top + textHeightB + 12f
                )
                val isOverlap = safeRectA.left < safeRectB.right && safeRectA.right > safeRectB.left && safeRectA.top < safeRectB.bottom && safeRectA.bottom > safeRectB.top
                if (isOverlap) {
                    val offset = dynamicOffset + max(safeRectA.height, safeRectB.height) * 0.2f
                    val tryDownRect = Rect(
                        rectB.left,
                        rectB.top + offset,
                        rectB.right,
                        rectB.bottom + offset
                    )
                    val tryDownSafe = Rect(
                        tryDownRect.left - 12f,
                        tryDownRect.top - 12f,
                        tryDownRect.right + 12f,
                        tryDownRect.top + textHeightB + 12f
                    )
                    val stillOverlap = safeRectA.left < tryDownSafe.right && safeRectA.right > tryDownSafe.left && safeRectA.top < tryDownSafe.bottom && safeRectA.bottom > tryDownSafe.top
                    val newRect = if (!stillOverlap) {
                        tryDownRect
                    } else {
                        Rect(
                            rectB.left + offset,
                            rectB.top,
                            rectB.right + offset,
                            rectB.bottom
                        )
                    }
                    finalRegions[j] = Triple(
                        blockB.copy(
                            bounds = android.graphics.Rect(
                                newRect.left.toInt(),
                                newRect.top.toInt(),
                                newRect.right.toInt(),
                                newRect.bottom.toInt(),
                            ),
                            fontSize = fontSizeFixedB
                        ),
                        newRect,
                        fontSizeFixedB
                    )
                    changed = true
                }
            }
        }
        loopCount++
        if (loopCount > 10 && changed) dynamicOffset *= 1.5f
    } while (changed && loopCount < 30)
    return finalRegions
}
fun calculateOptimalFontSize(
    text: String,
    width: Float,
    height: Float,
    minFontSize: Float = 12f,
    maxFontSize: Float = 1000f,
    shapeType: Int = 0, // 0 = rectangle, 1 = oval
    context: Context? = null,
    fontFamilyName: String? = null,
    // Allow returning a slightly larger font so UI can display one extra "step" when
    // increasing size. Set to 1f by default to add one pixel/point of allowance.
    extraSizeAllowance: Float = 1f,
    // Optional padding in pixels to keep text away from overlay edges. These are
    // subtracted from the available width/height before sizing. Defaults keep
    // current behavior.
    horizontalPadding: Float = 0f,
    verticalPadding: Float = 0f,
    lineSpacing: Float = 1.0f // Khoảng cách dòng multiplier
): Float {
    if (text.isBlank() || width <= 0 || height <= 0) return minFontSize
    
    // Ensure valid range: if maxFontSize < minFontSize, use minFontSize for both
    val safeMinFontSize = minFontSize
    val safeMaxFontSize = maxFontSize.coerceAtLeast(minFontSize)

    val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
        this.textAlign = android.graphics.Paint.Align.LEFT
        // Use cached Typeface to avoid repeated asset loads
        context?.let { ctx ->
            getCachedTypeface(ctx, fontFamilyName)?.let { tf -> this.typeface = tf }
        }
    }

    var low = safeMinFontSize
    var high = safeMaxFontSize
    var optimalFontSize = safeMinFontSize

    // Điều chỉnh hệ số scale cho hình oval để text vừa vặn
    // Tăng vùng text trong oval lên tối đa: 99% chiều dọc, 93% chiều ngang
    val widthScale = if (shapeType == 1) 0.93f else 0.999f
    val heightScale = if (shapeType == 1) 0.99f else 0.999f

    // Compute available drawing area after applying explicit paddings.
    val safeWidth = (width - (horizontalPadding * 2f)).coerceAtLeast(1f)
    val safeHeight = (height - (verticalPadding * 2f)).coerceAtLeast(1f)

    // Nhị phân để tìm fontSize lớn nhất mà text vẫn vừa vùng bôi trắng
    repeat(12) {
        val mid = (low + high) / 2
        paint.textSize = mid
            val wrappedLines = wrapText(text, safeWidth * widthScale, mid, context, fontFamilyName)
        val fontMetrics = paint.fontMetrics
        // Áp dụng lineSpacing vào tính toán lineHeight
        val lineHeight = (fontMetrics.descent - fontMetrics.ascent) * lineSpacing
        val textHeight = wrappedLines.size * lineHeight
        val maxLineWidth = wrappedLines.maxOfOrNull { line ->
            val bounds = android.graphics.Rect()
            paint.getTextBounds(line, 0, line.length, bounds)
            bounds.width().toFloat()
        } ?: 0f

        // Prefer height fit: if the text block height fits the safeHeight, allow
        // increasing font size even when lines reach left/right edges. This
        // supports translated text that is shorter than the original and can be
        // rendered larger until top/bottom are touched.
        val heightFits = textHeight <= safeHeight * heightScale
        val widthFits = maxLineWidth <= safeWidth * widthScale

// Cho phép text tràn nhiều hơn trong oval để tận dụng tối đa không gian (99% dọc, 93% ngang)
// Mục tiêu: giảm vùng trống, tăng kích thước text
        if (heightFits && widthFits) {
            optimalFontSize = mid
            low = mid + 0.2f
        } else if (heightFits && !widthFits && maxLineWidth <= safeWidth * 1.25f) {
            // Cho phép tràn 25% chiều ngang cho oval (thay vì 10%)
            optimalFontSize = mid
            low = mid + 0.2f
        } else if (!heightFits && textHeight <= safeHeight * 1.15f && widthFits) {
            // Cho phép tràn 15% chiều cao cho oval (thay vì 5%)
            optimalFontSize = mid
            low = mid + 0.2f
        } else {
            high = mid - 0.2f
        }

    }

    // Apply a small allowance so UI can present one or two more incremental steps to the user.
    val allowed = (optimalFontSize + extraSizeAllowance).coerceIn(safeMinFontSize, safeMaxFontSize)
    return allowed
}

/**
 * Compute the effective font size to use in edit mode.
 * Uses the explicitly edited font size when provided, otherwise falls back to the block's fontSize.
 * For vertical text we scale down to avoid extremely large visual rendering.
 */
fun computeEditModeFontSize(block: com.example.ocrmanga.data.models.TextBlockInfo, editedFontSize: Float?): Float {
    val baseSize = editedFontSize ?: block.fontSize
    return if (block.isVertical) (baseSize / 3f).coerceAtLeast(8f) else baseSize
}

fun drawTextOnCanvas(drawScope: DrawScope,
    text: String,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    color: Color,
    fontSize: Float,
    isVertical: Boolean,
    boldness: Float = 1.0f, // Độ đậm của text (0.5 - 2.0)
    context: Context,
    fontFamilyName: String? = null,
    borderColor: Color? = null, // Màu viền chữ
    borderThickness: Float = 0.0f, // Độ dày viền (0.0 - 5.0)
    borderAlpha: Float = 1.0f, // Độ trong suốt của viền (0.0 - 1.0),
    editMode: Boolean = false, // Nếu true, không giới hạn font size bởi overlay
    shapeType: Int = 0, // 0 = rectangle, 1 = oval
    lineSpacing: Float = 1.0f, // Khoảng cách dòng, multiplier (1.0 = bình thường)
    shadowColor: Color? = null, // Màu đổ bóng chữ
    shadowAlpha: Float = 1.0f, // Alpha multiplier for shadow (0.0 - 1.0)
    shadowRadius: Float = 0f, // Blur radius in px for shadow; 0 = use default proportional radius
    textAlign: com.example.ocrmanga.data.models.TextAlignMode = com.example.ocrmanga.data.models.TextAlignMode.CENTER
) {
    val whenAligned = textAlign

    // ...existing code...
    // Tạo paint cho viền text (nếu có yêu cầu viền)
    val borderPaint = if (borderColor != null && borderThickness > 0) {
        androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
            this.color = borderColor.copy(alpha = borderAlpha).toArgb()
            this.textSize = fontSize
            this.textAlign = android.graphics.Paint.Align.CENTER
            this.style = android.graphics.Paint.Style.STROKE
            this.strokeWidth = borderThickness
            
            // Use cached Typeface to avoid repeated asset loads
            try {
                getCachedTypeface(context, fontFamilyName)?.let { this.typeface = it }
            } catch (_: Exception) { }
        }
    } else null

    // Tạo paint cho text chính
    val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
        this.color = color.toArgb()
        this.textSize = fontSize
        this.textAlign = android.graphics.Paint.Align.CENTER // Đổi từ LEFT sang CENTER để căn giữa
        // Use cached Typeface to avoid repeated asset loads
        try {
            getCachedTypeface(context, fontFamilyName)?.let { this.typeface = it }
        } catch (_: Exception) { }
        // Điều chỉnh stroke width để tạo hiệu ứng đậm nhạt
        if (boldness > 1.0f) {
            this.style = android.graphics.Paint.Style.FILL_AND_STROKE
            this.strokeWidth = (boldness - 1.0f) * 2.0f
        } else if (boldness < 1.0f) {
            // Làm nhạt bằng cách giảm alpha
            val alpha = (255 * boldness).toInt().coerceIn(50, 255)
            this.alpha = alpha
        }
    }
    // Prepare a separate shadow paint so shadow is drawn behind border/text and remains outside border edges
    val shadowPaint = if (shadowColor != null) {
        androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
            val finalShadow = shadowColor.copy(alpha = shadowAlpha)
            // Use the shadow color for the paint so setShadowLayer produces a visible cast
            // The main text will be drawn afterwards, covering the glyph interior, leaving
            // the blurred shadow visible around the glyph edges.
            this.color = finalShadow.toArgb()
            this.textSize = fontSize
            this.textAlign = android.graphics.Paint.Align.CENTER
            this.style = android.graphics.Paint.Style.FILL
            // Use cached Typeface to match main text
            try { getCachedTypeface(context, fontFamilyName)?.let { this.typeface = it } } catch (_: Exception) { }
            try {
                // If user provided an explicit radius use it, otherwise fall back to proportional radius
                val radius = if (shadowRadius > 0f) shadowRadius else (fontSize * 0.14f).coerceAtLeast(1f)
                val dx = (fontSize * 0.04f)
                val dy = (fontSize * 0.04f)
                // set shadow color on the layer; paint color remains transparent so glyph fill is not colored
                this.setShadowLayer(radius, dx, dy, finalShadow.toArgb())
            } catch (_: Exception) { }
        }
    } else null

    val (wrappedText, optimalFontSize) = adjustWhiteoutBounds(
        text = text,
        initialWidth = width,
        initialHeight = height,
        fontSize = fontSize,
        isVertical = isVertical,
        context = context,
        fontFamilyName = fontFamilyName,
        shapeType = shapeType,
        lineSpacing = lineSpacing
    )
    // Use textAlign to affect drawing positions (default CENTER behavior)
    // textAlign will be applied below when drawing each line.
    paint.textSize = optimalFontSize
    borderPaint?.textSize = optimalFontSize
    // Ensure shadow paint scales when final font size is adjusted
    shadowPaint?.let { sp ->
        try {
            sp.textSize = optimalFontSize
            val radius = if (shadowRadius > 0f) shadowRadius else (optimalFontSize * 0.14f).coerceAtLeast(1f)
            val dx = (optimalFontSize * 0.04f)
            val dy = (optimalFontSize * 0.04f)
            val finalShadow = (shadowColor?.copy(alpha = shadowAlpha) ?: Color.Black.copy(alpha = shadowAlpha))
            sp.setShadowLayer(radius, dx, dy, finalShadow.toArgb())
        } catch (_: Exception) { }
    }
    val lines = wrappedText.split("\n")
    val fontMetrics = paint.fontMetrics
    val lineHeight = (fontMetrics.descent - fontMetrics.ascent) * lineSpacing

    drawScope.drawIntoCanvas { canvas ->
        if (isVertical) {
            var currentX = x + width - lineHeight
            for (line in lines) {
                if (line.isNotBlank() && currentX >= x) {
                    canvas.nativeCanvas.save()
                    canvas.nativeCanvas.translate(currentX, y)
                    canvas.nativeCanvas.rotate(90f)
                    val lineWidth = paint.measureText(line)
                    val centeredY = (height - lineWidth) / 2
                    // Draw shadow first so it appears outside the border and text
                    shadowPaint?.let {
                        canvas.nativeCanvas.drawText(line, centeredY, -fontMetrics.ascent, it)
                    }
                    borderPaint?.let {
                        canvas.nativeCanvas.drawText(line, centeredY, -fontMetrics.ascent, it)
                    }
                    canvas.nativeCanvas.drawText(line, centeredY, -fontMetrics.ascent, paint)
                    canvas.nativeCanvas.restore()
                    currentX -= lineHeight
                }
            }
        } else {
            // Center the block of lines vertically within the overlay with equal top/bottom margins
            val totalTextHeight = lines.size * lineHeight
            // Calculate margin to center text block vertically (equal spacing top and bottom)
            val verticalMargin = (height - totalTextHeight) / 2f
            // Start Y position: top of overlay + vertical margin - ascent to position baseline correctly
            val startY = y + verticalMargin - fontMetrics.ascent

                    var currentY = startY
            for ((index, line) in lines.withIndex()) {
                if (line.isNotBlank()) {
                    val centerX = x + width / 2
                    when (whenAligned) {
                        TextAlignMode.LEFT -> {
                            // left-align inside box with small padding
                            paint.textAlign = android.graphics.Paint.Align.LEFT
                            borderPaint?.textAlign = android.graphics.Paint.Align.LEFT
                            shadowPaint?.textAlign = android.graphics.Paint.Align.LEFT
                            val leftX = x + 4f
                            shadowPaint?.let { canvas.nativeCanvas.drawText(line, leftX, currentY, it) }
                            borderPaint?.let { canvas.nativeCanvas.drawText(line, leftX, currentY, it) }
                            canvas.nativeCanvas.drawText(line, leftX, currentY, paint)
                        }
                        TextAlignMode.CENTER -> {
                            paint.textAlign = android.graphics.Paint.Align.CENTER
                            borderPaint?.textAlign = android.graphics.Paint.Align.CENTER
                            shadowPaint?.textAlign = android.graphics.Paint.Align.CENTER
                            shadowPaint?.let { canvas.nativeCanvas.drawText(line, centerX, currentY, it) }
                            borderPaint?.let { canvas.nativeCanvas.drawText(line, centerX, currentY, it) }
                            canvas.nativeCanvas.drawText(line, centerX, currentY, paint)
                        }
                    }
                }
                currentY += lineHeight
            }

        }
    }
}

fun adjustWhiteoutBounds(
    text: String,
    initialWidth: Float,
    initialHeight: Float,
    fontSize: Float,
    isVertical: Boolean,
    context: Context? = null,
    fontFamilyName: String? = null,
    shapeType: Int = 0,
    lineSpacing: Float = 1.0f // Khoảng cách dòng multiplier
): Pair<String, Float> {
    val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
        this.textAlign = android.graphics.Paint.Align.LEFT
        this.textSize = fontSize
        context?.let { ctx ->
            getCachedTypeface(ctx, fontFamilyName)?.let { this.typeface = it }
        }
    }

    val effectiveWidth = if (isVertical) initialHeight else initialWidth
    val effectiveHeight = if (isVertical) initialWidth else initialHeight

    // Safe padding to keep text away from overlay edges. Slightly conservative to avoid clipping.
    val safePadding = 4f.coerceAtMost(fontSize * 0.5f)

    val availableWidth = (effectiveWidth - safePadding * 2f).coerceAtLeast(1f)
    val availableHeight = (effectiveHeight - safePadding * 2f).coerceAtLeast(1f)

    // Compute an optimal font size that fits into the available area. Do not allow it
    // to grow beyond the provided fontSize (we only want to shrink when overflowing).
    // Ensure maxFontSize is at least minFontSize to avoid invalid range.
    val minSize = 8f
    val maxSize = fontSize.coerceAtLeast(minSize)
    val optimal = calculateOptimalFontSize(
        text = text,
        width = availableWidth,
        height = availableHeight,
        minFontSize = minSize,
        maxFontSize = maxSize,
        shapeType = shapeType,
        context = context,
        fontFamilyName = fontFamilyName,
        extraSizeAllowance = 0f,
        horizontalPadding = safePadding,
        verticalPadding = safePadding,
        lineSpacing = lineSpacing
    )

    // Now wrap the text using the computed font size so measurements align with rendering.
    val wrappedLines = wrapText(text, availableWidth * 0.995f, optimal, context, fontFamilyName)
    return wrappedLines.joinToString("\n") to optimal
}

 fun wrapText(text: String, width: Float, fontSize: Float, context: Context? = null, fontFamilyName: String? = null): List<String> {
    val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
        this.textSize = fontSize
        this.textAlign = android.graphics.Paint.Align.LEFT
        // Use cached Typeface for measuring text width/bounds
        context?.let { ctx -> getCachedTypeface(ctx, fontFamilyName)?.let { this.typeface = it } }
    }

    val lines = mutableListOf<String>()
    val normalizedText = text.replace(Regex("\\s+"), " ").trim()
    val words = normalizedText.split(" ").filter { it.isNotBlank() }
    var currentLine = StringBuilder()

    for (word in words) {
        val testLine = if (currentLine.isEmpty()) word else "${currentLine} $word"
        val bounds = android.graphics.Rect()
        paint.getTextBounds(testLine, 0, testLine.length, bounds)
        if (bounds.width().toFloat() <= width || currentLine.isEmpty()) {
            currentLine = StringBuilder(testLine)
        } else {
            if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
            currentLine = StringBuilder(word)
        }
    }
    if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
    return lines
}

 fun DrawScope.estimateBackgroundColor(rect: Rect): Color {
    // Phân tích vùng xung quanh để ước lượng màu nền phù hợp
    // Đối với manga, thường là màu trắng hoặc các tone màu sáng

    // Tạo một mẫu màu dựa trên vị trí trong ảnh
    val centerX = rect.center.x / size.width
    val centerY = rect.center.y / size.height

    // Phần lớn manga có nền trắng, nhưng có thể có vùng tối
    // Ước lượng dựa trên vị trí và kích thước vùng văn bản
    return when {
        // Vùng có khả năng là nền trắng (phần lớn manga)
        centerY < 0.8f && rect.width < size.width * 0.7f -> Color.White

        // Vùng có thể có nền xám nhạt (bubble speech, thought bubbles)
        rect.width < size.width * 0.4f && rect.height < size.height * 0.15f -> Color(0xFFF8F8F8)

        // Vùng lớn có thể cần màu nền phức tạp hơn
        rect.width > size.width * 0.5f -> {
            // Sử dụng gradient từ trắng đến xám nhạt
            val gray = (0.95f - (centerY * 0.1f)).coerceIn(0.85f, 0.98f)
            Color(gray, gray, gray, 1f)
        }

        // Mặc định là trắng với độ trong suốt nhẹ để hòa quyện
        else -> Color(0xFFFAFAFA)
    }
}

// Hàm thay thế để xóa văn bản gốc một cách thông minh hơn
 fun DrawScope.smartTextRemoval(rect: Rect, surroundingColor: Color? = null) {
    // Tạo hiệu ứng "content-aware fill" đơn giản
    val estimatedColor = surroundingColor ?: estimateBackgroundColor(rect)

    // Vẽ với gradient nhẹ để tự nhiên hơn
    val gradientColors = listOf(
        estimatedColor.copy(alpha = 0.95f),
        estimatedColor,
        estimatedColor.copy(alpha = 0.98f)
    )

    // Tạo hiệu ứng mờ dần ở viền để không có ranh giới rõ rệt
    val blurRadius = 2f
    val expandedRect = Rect(
        left = rect.left - blurRadius,
        top = rect.top - blurRadius,
        right = rect.right + blurRadius,
        bottom = rect.bottom + blurRadius
    )

    // Vẽ vùng xóa với hiệu ứng mềm mại
    drawRect(
        color = estimatedColor,
        topLeft = androidx.compose.ui.geometry.Offset(rect.left, rect.top),
        size = androidx.compose.ui.geometry.Size(rect.width, rect.height)
    )
}

// Hàm phân tích màu nền xung quanh vùng văn bản để tạo hiệu ứng xóa tự nhiên
fun DrawScope.advancedTextRemoval(rect: Rect, originalImageWidth: Float = 0f, originalImageHeight: Float = 0f) {
    // Phân tích bối cảnh của vùng văn bản để xác định kiểu xóa phù hợp
    val contextInfo = analyzeTextContext(rect, originalImageWidth)

    when (contextInfo.type) {
        TextContextType.SPEECH_BUBBLE -> {
            // Xóa trong speech bubble - thường có nền trắng với viền
            drawRoundedTextRemoval(rect, Color.White, cornerRadius = 4f)
        }
        TextContextType.THOUGHT_BUBBLE -> {
            // Xóa trong thought bubble - nền xám nhạt
            drawRoundedTextRemoval(rect, Color(0xFFF5F5F5), cornerRadius = 6f)
        }
        TextContextType.NARRATIVE_BOX -> {
            // Hộp tường thuật - nền có thể có viền
            drawBoxTextRemoval(rect, Color.White, hasFrame = true)
        }
        TextContextType.SOUND_EFFECT -> {
            // Hiệu ứng âm thanh - xóa sạch hoàn toàn
            drawCleanRemoval(rect, Color.White)
        }
        TextContextType.BACKGROUND_TEXT -> {
            // Văn bản trên nền - ước lượng màu nền phức tạp
            drawContextAwareRemoval(rect)
        }
    }
}

 enum class TextContextType {
    SPEECH_BUBBLE,
    THOUGHT_BUBBLE,
    NARRATIVE_BOX,
    SOUND_EFFECT,
    BACKGROUND_TEXT
}

 data class TextContext(
    val type: TextContextType,
    val confidence: Float,
    val estimatedBackgroundColor: Color
)

 fun DrawScope.analyzeTextContext(rect: Rect, originalImageWidth: Float): TextContext {
    val rectWidth = rect.width
    val rectHeight = rect.height
    val aspectRatio = rectWidth / rectHeight
    val sizeRatio = (rectWidth * rectHeight) / (size.width * size.height)

    // Phân tích dựa trên kích thước và tỷ lệ
    return when {
        // Speech bubble: hình chữ nhật nhỏ-trung bình, tỷ lệ cân đối
        sizeRatio < 0.15f && aspectRatio in 0.3f..3.0f && rectWidth < size.width * 0.6f -> {
            TextContext(TextContextType.SPEECH_BUBBLE, 0.8f, Color.White)
        }

        // Thought bubble: tương tự speech bubble nhưng có thể nhỏ hơn
        sizeRatio < 0.1f && aspectRatio in 0.5f..2.0f -> {
            TextContext(TextContextType.THOUGHT_BUBBLE, 0.7f, Color(0xFFF8F8F8))
        }

        // Narrative box: hình chữ nhật dài, thường ở trên/dưới
        aspectRatio > 2.5f && (rect.top < size.height * 0.2f || rect.bottom > size.height * 0.8f) -> {
            TextContext(TextContextType.NARRATIVE_BOX, 0.9f, Color.White)
        }

        // Sound effect: kích thước lớn, có thể có hình dạng bất kỳ
        sizeRatio > 0.2f || rectWidth > size.width * 0.7f -> {
            TextContext(TextContextType.SOUND_EFFECT, 0.6f, Color.White)
        }

        // Background text: mặc định
        else -> {
            TextContext(TextContextType.BACKGROUND_TEXT, 0.5f, estimateBackgroundColor(rect))
        }
    }
}

 fun DrawScope.drawRoundedTextRemoval(rect: Rect, color: Color, cornerRadius: Float, originalImageWidth: Float = 0f, originalImageHeight: Float = 0f) {
    // Scale lại nếu có thông tin kích thước gốc
    val (scaledLeft, scaledTop, scaledWidth, scaledHeight) = if (originalImageWidth > 0f && originalImageHeight > 0f) {
        val scaleX = size.width / originalImageWidth
        val scaleY = size.height / originalImageHeight
        val left = rect.left * scaleX
        val top = rect.top * scaleY
        val width = rect.width * scaleX
        val height = rect.height * scaleY
        listOf(left, top, width, height)
    } else {
        listOf(rect.left, rect.top, rect.width, rect.height)
    }
    val roundRect = RoundRect(
        scaledLeft,
        scaledTop,
        scaledLeft + scaledWidth,
        scaledTop + scaledHeight,
        cornerRadius,
        cornerRadius
    )
    val path = Path().apply { addRoundRect(roundRect) }
    drawPath(path, color)
}

 fun DrawScope.drawBoxTextRemoval(rect: Rect, color: Color, hasFrame: Boolean, originalImageWidth: Float = 0f, originalImageHeight: Float = 0f) {
    // Nếu có thông tin kích thước gốc, scale lại tọa độ cho đúng với canvas hiện tại
    val (scaledLeft, scaledTop, scaledWidth, scaledHeight) = if (originalImageWidth > 0f && originalImageHeight > 0f) {
        val scaleX = size.width / originalImageWidth
        val scaleY = size.height / originalImageHeight
        val left = rect.left * scaleX
        val top = rect.top * scaleY
        val width = rect.width * scaleX
        val height = rect.height * scaleY
        listOf(left, top, width, height)
    } else {
        listOf(rect.left, rect.top, rect.width, rect.height)
    }
    drawRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(scaledLeft, scaledTop),
        size = androidx.compose.ui.geometry.Size(scaledWidth, scaledHeight)
    )

    if (hasFrame) {
        // Vẽ viền nhẹ nếu cần
        drawRect(
            color = Color.Black.copy(alpha = 0.1f),
            topLeft = androidx.compose.ui.geometry.Offset(scaledLeft, scaledTop),
            size = androidx.compose.ui.geometry.Size(scaledWidth, scaledHeight),
            style = Stroke(width = 1f)
        )
    }
}

 fun DrawScope.drawCleanRemoval(rect: Rect, color: Color, originalImageWidth: Float = 0f, originalImageHeight: Float = 0f) {
    // Xóa hoàn toàn sạch sẽ, scale lại nếu có thông tin kích thước gốc
    val (scaledLeft, scaledTop, scaledWidth, scaledHeight) = if (originalImageWidth > 0f && originalImageHeight > 0f) {
        val scaleX = size.width / originalImageWidth
        val scaleY = size.height / originalImageHeight
        val left = rect.left * scaleX
        val top = rect.top * scaleY
        val width = rect.width * scaleX
        val height = rect.height * scaleY
        listOf(left, top, width, height)
    } else {
        listOf(rect.left, rect.top, rect.width, rect.height)
    }
    drawRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(scaledLeft, scaledTop),
        size = androidx.compose.ui.geometry.Size(scaledWidth, scaledHeight)
    )
}

 fun DrawScope.drawContextAwareRemoval(rect: Rect, originalImageWidth: Float = 0f, originalImageHeight: Float = 0f) {
    // Phân tích xung quanh để tạo màu nền phù hợp
    val estimatedColor = estimateBackgroundColor(rect)
    // Scale lại nếu có thông tin kích thước gốc
    val (scaledLeft, scaledTop, scaledWidth, scaledHeight) = if (originalImageWidth > 0f && originalImageHeight > 0f) {
        val scaleX = size.width / originalImageWidth
        val scaleY = size.height / originalImageHeight
        val left = rect.left * scaleX
        val top = rect.top * scaleY
        val width = rect.width * scaleX
        val height = rect.height * scaleY
        listOf(left, top, width, height)
    } else {
        listOf(rect.left, rect.top, rect.width, rect.height)
    }
    // Tạo gradient nhẹ để hòa quyện tự nhiên
    drawRect(
        color = estimatedColor,
        topLeft = androidx.compose.ui.geometry.Offset(scaledLeft, scaledTop),
        size = androidx.compose.ui.geometry.Size(scaledWidth, scaledHeight)
    )
}

// Hàm tìm vùng nền đồng nhất quanh text (ví dụ: vùng trắng lớn nhất chứa text)
 fun findBackgroundRegion(rect: Rect, bitmap: Bitmap): Rect {
    // Lấy vùng lân cận quanh rect, kiểm tra màu nền đồng nhất (ví dụ: trắng)
    val margin = 8 // px
    val left = rect.left.toInt().coerceAtLeast(0)
    val top = rect.top.toInt().coerceAtLeast(0)
    val right = rect.right.toInt().coerceAtMost(bitmap.width - 1)
    val bottom = rect.bottom.toInt().coerceAtMost(bitmap.height - 1)
    val bgColor = bitmap.getPixel(left, top)
    var bgLeft = left
    var bgRight = right
    var bgTop = top
    var bgBottom = bottom
    // Mở rộng sang trái
    for (x in left downTo 0) {
        if (bitmap.getPixel(x, top) != bgColor) break
        bgLeft = x
    }
    // Mở rộng sang phải
    for (x in right until bitmap.width) {
        if (bitmap.getPixel(x, top) != bgColor) break
        bgRight = x
    }
    // Mở rộng lên trên
    for (y in top downTo 0) {
        if (bitmap.getPixel(left, y) != bgColor) break
        bgTop = y
    }
    // Mở rộng xuống dưới
    for (y in bottom until bitmap.height) {
        if (bitmap.getPixel(left, y) != bgColor) break
        bgBottom = y
    }
    return Rect(bgLeft.toFloat(), bgTop.toFloat(), bgRight.toFloat(), bgBottom.toFloat())
}

// Khi merge/shifting, giới hạn vùng whiteout và text trong vùng nền
 fun limitRectToBackground(rect: Rect, bgRect: Rect): Rect {
    return Rect(
        max(rect.left, bgRect.left),
        max(rect.top, bgRect.top),
        min(rect.right, bgRect.right),
        min(rect.bottom, bgRect.bottom)
    )
}
fun shrinkOverlappingBoxes(blocks: List<com.example.ocrmanga.data.models.TextBlockInfo>): List<com.example.ocrmanga.data.models.TextBlockInfo> {
    val result = blocks.map { it.copy() }.toMutableList()
    for (i in result.indices) {
        val boxA = result[i].bounds
        for (j in result.indices) {
            if (i == j) continue
            val boxB = result[j].bounds
            if (android.graphics.Rect.intersects(boxA, boxB)) {
                val intersect = android.graphics.Rect(
                    maxOf(boxA.left, boxB.left),
                    maxOf(boxA.top, boxB.top),
                    minOf(boxA.right, boxB.right),
                    minOf(boxA.bottom, boxB.bottom)
                )
                val areaA = (boxA.width() * boxA.height()).toFloat()
                val areaIntersect = (intersect.width() * intersect.height()).toFloat()
                if (areaA > 0 && areaIntersect / areaA > 0.15f) {
                    val shrinkLeft = if (intersect.left == boxA.left) intersect.width() / 2 else 0
                    val shrinkRight = if (intersect.right == boxA.right) intersect.width() / 2 else 0
                    val shrinkTop = if (intersect.top == boxA.top) intersect.height() / 2 else 0
                    val shrinkBottom = if (intersect.bottom == boxA.bottom) intersect.height() / 2 else 0
                    result[i] = result[i].copy(
                        bounds = android.graphics.Rect(
                            boxA.left + shrinkLeft,
                            boxA.top + shrinkTop,
                            boxA.right - shrinkRight,
                            boxA.bottom - shrinkBottom
                        )
                    )
                }
            }
        }
    }
    return result
}

fun splitNonOverlappingBoxes(blocks: List<TextBlockInfo>): List<TextBlockInfo> {
    val result = mutableListOf<TextBlockInfo>()
    val used = BooleanArray(blocks.size)
    for (i in blocks.indices) {
        var boxA = blocks[i].bounds
        var keep = true
        for (j in blocks.indices) {
            if (i == j) continue
            val boxB = blocks[j].bounds
            if (android.graphics.Rect.intersects(boxA, boxB)) {
                if (boxB.contains(boxA)) {
                    keep = false
                    break
                }
                val intersect = android.graphics.Rect(
                    maxOf(boxA.left, boxB.left),
                    maxOf(boxA.top, boxB.top),
                    minOf(boxA.right, boxB.right),
                    minOf(boxA.bottom, boxB.bottom)
                )
                if (intersect.width() > 0 && intersect.height() > 0) {
                    if (intersect.right == boxA.right) boxA.right = intersect.left
                    if (intersect.left == boxA.left) boxA.left = intersect.right
                    if (intersect.bottom == boxA.bottom) boxA.bottom = intersect.top
                    if (intersect.top == boxA.top) boxA.top = intersect.bottom
                }
            }
        }
        if (keep && boxA.width() > 0 && boxA.height() > 0) {
            result.add(blocks[i].copy(bounds = android.graphics.Rect(boxA)))
        }
    }
    return result
}
// Khi vẽ whiteout/text, chỉ vẽ trong vùng giao với vùng nền
// Sử dụng trong drawWithCache/onDrawBehind:
// val bgRect = findBackgroundRegion(rect, bitmap)
// val limitedRect = limitRectToBackground(rect, bgRect)
// drawRect(..., topLeft = Offset(limitedRect.left, limitedRect.top), size = Size(limitedRect.width, limitedRect.height))

// Phân tích màu nền và màu text của text block từ bitmap gốc
fun analyzeBackgroundAndTextColor(bitmap: Bitmap?, bounds: android.graphics.Rect): Triple<com.example.ocrmanga.data.models.BackgroundType, Int?, Int?> {
    if (bitmap == null) return Triple(com.example.ocrmanga.data.models.BackgroundType.WHITE, null, null)
    
    try {
        val width = bounds.width()
        val height = bounds.height()
        
        // Lấy mẫu từ nhiều điểm bên trong bounds
        val allSamplePoints = mutableListOf<Int>()
        val margin = 2
        
        // Lấy mẫu từ viền và trung tâm
        val samplePositions = listOf(
            // 4 góc bên trong
            Pair(bounds.left + margin, bounds.top + margin),
            Pair(bounds.right - margin, bounds.top + margin),
            Pair(bounds.left + margin, bounds.bottom - margin),
            Pair(bounds.right - margin, bounds.bottom - margin),
            // 4 cạnh bên trong
            Pair(bounds.centerX(), bounds.top + margin),
            Pair(bounds.centerX(), bounds.bottom - margin),
            Pair(bounds.left + margin, bounds.centerY()),
            Pair(bounds.right - margin, bounds.centerY()),
            // Điểm trung tâm
            Pair(bounds.centerX(), bounds.centerY()),
            // Thêm các điểm phụ để có nhiều mẫu hơn
            Pair(bounds.left + width / 4, bounds.top + margin),
            Pair(bounds.left + 3 * width / 4, bounds.top + margin),
            Pair(bounds.left + width / 4, bounds.bottom - margin),
            Pair(bounds.left + 3 * width / 4, bounds.bottom - margin)
        )
        
        samplePositions.forEach { (x, y) ->
            if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                allSamplePoints.add(bitmap.getPixel(x, y))
            }
        }
        
        if (allSamplePoints.isEmpty()) return Triple(com.example.ocrmanga.data.models.BackgroundType.WHITE, null, null)
        
        // QUAN TRỌNG: Lọc bỏ các pixel TỐI (có khả năng là text) khi tính background
        // Chỉ giữ lại các pixel SÁNG (brightness > 150) để tính màu nền
        val brightPixels = allSamplePoints.filter { color ->
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val pixelBrightness = (r + g + b) / 3
            pixelBrightness > 150 // Chỉ giữ pixel sáng
        }
        
        // Nếu có đủ pixel sáng (ít nhất 30%), dùng chúng để tính background
        val samplePoints = if (brightPixels.size >= allSamplePoints.size * 0.3) {
            brightPixels
        } else {
            // Nếu không đủ pixel sáng, có thể là nền tối thật -> dùng tất cả
            allSamplePoints
        }
        
        // Tính màu trung bình từ các pixel đã lọc
        var totalR = 0
        var totalG = 0
        var totalB = 0
        var totalA = 0
        
        samplePoints.forEach { color ->
            totalR += (color shr 16) and 0xFF
            totalG += (color shr 8) and 0xFF
            totalB += color and 0xFF
            totalA += (color shr 24) and 0xFF
        }
        
        val avgR = totalR / samplePoints.size
        val avgG = totalG / samplePoints.size
        val avgB = totalB / samplePoints.size
        val avgA = totalA / samplePoints.size
        
        val avgColor = (avgA shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
        
        // Xác định loại nền dựa trên brightness và color variance
        val brightness = (avgR + avgG + avgB) / 3
        
        // Tính độ biến thiên màu sắc để phát hiện nền có màu (chỉ từ pixel sáng)
        var colorVariance = 0
        var colorChannelVariance = 0
        samplePoints.forEach { color ->
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val pixelBrightness = (r + g + b) / 3
            colorVariance += kotlin.math.abs(pixelBrightness - brightness)
            
            val maxChannel = maxOf(r, g, b)
            val minChannel = minOf(r, g, b)
            colorChannelVariance += (maxChannel - minChannel)
        }
        colorVariance /= samplePoints.size
        colorChannelVariance /= samplePoints.size
        
        // Tính độ lệch chuẩn của RGB để phát hiện màu thật
        val rVariance = kotlin.math.abs(avgR - avgG)
        val gVariance = kotlin.math.abs(avgG - avgB)
        val bVariance = kotlin.math.abs(avgB - avgR)
        val rgbDeviation = (rVariance + gVariance + bVariance) / 3
        
        // Phân tích màu text bằng cách lấy mẫu lưới bên trong bounds,
        // chọn các pixel có độ sáng khác biệt so với nền (ứng viên text).
        var textColor: Int? = null
        try {
            val candidates = mutableListOf<Int>()
            val backgroundBrightness = brightness
            
            // Helper to check and add candidate
            fun checkAndAdd(pixelColor: Int) {
                val r = (pixelColor shr 16) and 0xFF
                val g = (pixelColor shr 8) and 0xFF
                val b = pixelColor and 0xFF
                val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                
                // Check contrast: Text must differ from background
                val diff = kotlin.math.abs(lum - backgroundBrightness)
                if (diff >= 30) { // Threshold 30 to avoid noise/artifacts
                    candidates.add(pixelColor)
                }
            }

            // 1. Grid Sampling
            val gridSize = 6 // 6x6 grid sampling
            val stepX = maxOf(1, width / gridSize)
            val stepY = maxOf(1, height / gridSize)

            for (dy in 0 until gridSize) {
                for (dx in 0 until gridSize) {
                    val x = bounds.left + dx * stepX + stepX / 2
                    val y = bounds.top + dy * stepY + stepY / 2
                    if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                        try { checkAndAdd(bitmap.getPixel(x, y)) } catch (_: Exception) {}
                    }
                }
            }

            // 2. Dense Scan on Center Lines (Fallback if grid missed)
            // If we found specific candidates in grid, use them. If not (text might be thin or sparse), 
            // scan the center lines where text is likely to be.
            if (candidates.size < 3) {
                val cy = bounds.centerY()
                // Scan horizontal center line
                for (x in bounds.left until bounds.right step 2) {
                     if (x in 0 until bitmap.width && cy in 0 until bitmap.height) {
                        try { checkAndAdd(bitmap.getPixel(x, cy)) } catch (_: Exception) {}
                     }
                }
                
                // Scan vertical center line
                val cx = bounds.centerX()
                for (y in bounds.top until bounds.bottom step 2) {
                     if (cx in 0 until bitmap.width && y in 0 until bitmap.height) {
                        try { checkAndAdd(bitmap.getPixel(cx, y)) } catch (_: Exception) {}
                     }
                }
            }

            if (candidates.isNotEmpty()) {
                // Quantize to 16-level buckets per channel to find dominant color
                val buckets = mutableMapOf<Int, MutableList<Int>>()
                for (c in candidates) {
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val keyR = (r / 16) and 0xF
                    val keyG = (g / 16) and 0xF
                    val keyB = (b / 16) and 0xF
                    val key = (keyR shl 8) or (keyG shl 4) or keyB
                    buckets.getOrPut(key) { mutableListOf() }.add(c)
                }
                val dominantBucket = buckets.maxByOrNull { it.value.size }?.value
                if (!dominantBucket.isNullOrEmpty()) {
                    var totR = 0; var totG = 0; var totB = 0; var totA = 0
                    dominantBucket.forEach { cc ->
                        totR += (cc shr 16) and 0xFF
                        totG += (cc shr 8) and 0xFF
                        totB += cc and 0xFF
                        totA += (cc shr 24) and 0xFF
                    }
                    val n = dominantBucket.size
                    val avgR = totR / n
                    val avgG = totG / n
                    val avgB = totB / n
                    val avgA = if (totA == 0) 0xFF else totA / n
                    textColor = (avgA shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
                }
            }

            if (textColor == null) {
                // Smart Default: Contrast with background
                textColor = if (backgroundBrightness > 128) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
            
            // --- STRICT FIX: Prevent White-on-White and Black-on-Black ---
            // Yêu cầu: Nền sáng -> không được chứa text trắng. Nền tối -> không được chứa text đen.
            textColor?.let { color ->
                val tr = (color shr 16) and 0xFF
                val tg = (color shr 8) and 0xFF
                val tb = color and 0xFF
                val textBrightness = (tr + tg + tb) / 3
                
                // Nếu nền sáng (> 170) mà text cũng sáng (> 170) -> Force Black
                if (backgroundBrightness > 170 && textBrightness > 170) {
                    textColor = 0xFF000000.toInt()
                }
                // Nếu nền tối (< 85) mà text cũng tối (< 85) -> Force White
                else if (backgroundBrightness < 85 && textBrightness < 85) {
                    textColor = 0xFFFFFFFF.toInt()
                }
            }
        } catch (e: Exception) {
            textColor = if (brightness > 128) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }

        val backgroundType = when {
            // Nền trong suốt: alpha thấp (kiểm tra trước)
            avgA < 200 -> com.example.ocrmanga.data.models.BackgroundType.TRANSPARENT
            
            // Nền trắng thuần: brightness rất cao + RGB đồng đều
            brightness >= 245 && rgbDeviation <= 5 && colorVariance <= 15 -> {
                com.example.ocrmanga.data.models.BackgroundType.WHITE
            }
            
            // Nền trắng/xám nhạt: brightness cao + RGB gần nhau
            brightness >= 230 && rgbDeviation <= 10 && colorChannelVariance <= 15 -> {
                // Kiểm tra kỹ: nếu TẤT CẢ RGB đều >= 230 thì là trắng
                if (avgR >= 230 && avgG >= 230 && avgB >= 230) {
                    com.example.ocrmanga.data.models.BackgroundType.WHITE
                } else {
                    // Có ít nhất 1 kênh < 230 -> có màu
                    com.example.ocrmanga.data.models.BackgroundType.COLORED
                }
            }
            
            // Nền gần trắng nhưng có chút sắc độ
            brightness >= 210 && rgbDeviation <= 20 && colorChannelVariance <= 25 -> {
                // Kiểm tra lại lần nữa với ngưỡng thấp hơn
                if (avgR >= 210 && avgG >= 210 && avgB >= 210 && rgbDeviation <= 12) {
                    com.example.ocrmanga.data.models.BackgroundType.WHITE
                } else {
                    com.example.ocrmanga.data.models.BackgroundType.COLORED
                }
            }
            
            // Nền có màu rõ ràng: có độ lệch lớn giữa các kênh hoặc brightness thấp
            colorChannelVariance > 25 || rgbDeviation > 20 || brightness < 210 -> {
                com.example.ocrmanga.data.models.BackgroundType.COLORED
            }
            
            // Default: dựa vào brightness
            else -> if (brightness >= 200) {
                com.example.ocrmanga.data.models.BackgroundType.WHITE
            } else {
                com.example.ocrmanga.data.models.BackgroundType.COLORED
            }
        }
        
        try {
            val tHex = textColor?.let { String.format("#%08X", it) } ?: "null"
            val bgHex = if (backgroundType != com.example.ocrmanga.data.models.BackgroundType.WHITE) avgColor?.let { String.format("#%08X", it) } ?: "null" else "WHITE"
            Log.i("ImageTextUtils", "[ANALYZE] bounds=${bounds.left},${bounds.top},${bounds.right},${bounds.bottom} background=$bgHex textColor=$tHex")
        } catch (_: Exception) { }
        return Triple(backgroundType, if (backgroundType != com.example.ocrmanga.data.models.BackgroundType.WHITE) avgColor else null, textColor)
        
    } catch (e: Exception) {
        // Nếu xảy ra exception, mặc định màu đen (0xFF000000)
        return Triple(com.example.ocrmanga.data.models.BackgroundType.WHITE, null, 0xFF000000.toInt())
    }
}

// Hàm wrapper để tương thích với code cũ
fun analyzeBackgroundColor(bitmap: Bitmap?, bounds: android.graphics.Rect): Pair<com.example.ocrmanga.data.models.BackgroundType, Int?> {
    val (backgroundType, avgColor, _) = analyzeBackgroundAndTextColor(bitmap, bounds)
    return Pair(backgroundType, avgColor)
}

// Vẽ overlay bán trong suốt cho text trên nền có màu với màu nền gốc
fun DrawScope.drawTranslucentOverlay(
    rect: Rect,
    backgroundType: com.example.ocrmanga.data.models.BackgroundType,
    averageBackgroundColor: Int?,
    originalTextColor: Int? = null,
    shapeType: Int = 0
) {
    when (backgroundType) {
        com.example.ocrmanga.data.models.BackgroundType.WHITE -> {
            // Nền trắng - sử dụng màu trắng bình thường
            val overlayColor = Color.White
            when (shapeType) {
                1 -> drawOval(
                    color = overlayColor,
                    topLeft = Offset(rect.left, rect.top),
                    size = androidx.compose.ui.geometry.Size(rect.width, rect.height)
                )
                else -> drawRect(
                    color = overlayColor,
                    topLeft = Offset(rect.left, rect.top),
                    size = androidx.compose.ui.geometry.Size(rect.width, rect.height)
                )
            }
        }
        com.example.ocrmanga.data.models.BackgroundType.COLORED -> {
            // Nền có màu - sử dụng màu nền gốc làm overlay
            if (averageBackgroundColor != null) {
                val overlayColor = Color(averageBackgroundColor)
                
                // Vẽ overlay với màu nền gốc, độ mờ vừa đủ để che text cũ
                when (shapeType) {
                    1 -> drawOval(
                        color = overlayColor.copy(alpha = 0.95f),
                        topLeft = Offset(rect.left, rect.top),
                        size = androidx.compose.ui.geometry.Size(rect.width, rect.height)
                    )
                    else -> drawRect(
                        color = overlayColor.copy(alpha = 0.95f),
                        topLeft = Offset(rect.left, rect.top),
                        size = androidx.compose.ui.geometry.Size(rect.width, rect.height)
                    )
                }
            } else {
                // Fallback - màu trắng với độ mờ cao
                val overlayColor = Color.White.copy(alpha = 0.9f)
                when (shapeType) {
                    1 -> drawOval(
                        color = overlayColor,
                        topLeft = Offset(rect.left, rect.top),
                        size = androidx.compose.ui.geometry.Size(rect.width, rect.height)
                    )
                    else -> drawRect(
                        color = overlayColor,
                        topLeft = Offset(rect.left, rect.top),
                        size = androidx.compose.ui.geometry.Size(rect.width, rect.height)
                    )
                }
            }
        }
        com.example.ocrmanga.data.models.BackgroundType.TRANSPARENT -> {
            // Nền trong suốt - sử dụng màu trắng với độ mờ cao
            val overlayColor = Color.White.copy(alpha = 0.95f)
            when (shapeType) {
                1 -> drawOval(
                    color = overlayColor,
                    topLeft = Offset(rect.left, rect.top),
                    size = androidx.compose.ui.geometry.Size(rect.width, rect.height)
                )
                else -> drawRect(
                    color = overlayColor,
                    topLeft = Offset(rect.left, rect.top),
                    size = androidx.compose.ui.geometry.Size(rect.width, rect.height)
                )
            }
        }
    }
}

/**
 * Compute default text color (black or white) based on overlay or average background color brightness.
 * If both are null, returns black.
 */
fun computeDefaultTextColor(overlayColor: Int?, averageBackgroundColor: Int? = null): Int {
    val base = overlayColor ?: averageBackgroundColor
    if (base == null) return 0xFF000000.toInt()
    val opaque = base or 0xFF000000.toInt()
    val lum = androidx.core.graphics.ColorUtils.calculateLuminance(opaque)
    return if (lum <= 0.5) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
}
