

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

// Cache cho calculateWindowedOverlayBounds - tránh tính toán lại liên tục
internal val windowedBoundsCache = object : LinkedHashMap<String, WindowedOverlayResult>(100, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WindowedOverlayResult>?): Boolean {
        return size > 200 // Giới hạn cache
    }
}

// Lock cho thread safety
internal val boundsCacheLock = Any()

internal fun generateCacheKey(
    bounds: androidx.compose.ui.geometry.Rect,
    text: String,
    baseFontSize: Float,
    isVertical: Boolean,
    fontFamilyName: String?,
    lineSpacing: Float,
    shapeType: Int,
    overlayInsetH: Float,
    overlayInsetV: Float,
    originalFontSize: Float?,
    isSolidBubble: Boolean
): String {
    return "${bounds.left.toInt()}_${bounds.top.toInt()}_${bounds.width.toInt()}_${bounds.height.toInt()}_${text.hashCode()}_${baseFontSize.toInt()}_${isVertical}_${fontFamilyName ?: ""}_${(lineSpacing * 100).toInt()}_${shapeType}_${overlayInsetH.toInt()}_${overlayInsetV.toInt()}_${originalFontSize?.toInt()}_$isSolidBubble"
}

internal fun getCachedTypeface(context: Context, fontFamilyName: String?): Typeface? {
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
    minFontSize: Float = 6f,
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
    lineSpacing: Float = 1.0f, // Khoảng cách dòng multiplier
    boldness: Float = 1.0f // Độ đậm của chữ
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
        
        // Cần tính cả độ đậm khi đo kích thước
        if (boldness > 1.0f) {
            this.style = android.graphics.Paint.Style.FILL_AND_STROKE
            this.strokeWidth = (boldness - 1.0f) * 2.0f
        }
    }

    var low = safeMinFontSize
    var high = safeMaxFontSize
    var optimalFontSize = safeMinFontSize

    // Điều chỉnh hệ số scale cho hình oval để text vừa vặn
    // Đối với Oval, ta cho phép wrap rộng hơn một chút nhưng kiểm soát chặt chẽ bằng phương trình Ellipse ở bước kiểm tra fit
    val widthScale = if (shapeType == 1) 0.82f else 0.95f
    val heightScale = if (shapeType == 1) 0.82f else 0.98f

    // Compute available drawing area after applying explicit paddings.
    val safeWidth = (width - (horizontalPadding * 2f)).coerceAtLeast(1f)
    val safeHeight = (height - (verticalPadding * 2f)).coerceAtLeast(1f)

    // Nhị phân để tìm fontSize lớn nhất mà text vẫn vừa vùng bôi trắng
    repeat(12) {
        val mid = (low + high) / 2
        paint.textSize = mid
        val wrappedLines = wrapText(text, safeWidth * widthScale, mid, context, fontFamilyName, boldness)
        val fontMetrics = paint.fontMetrics
        // Áp dụng lineSpacing vào tính toán lineHeight
        val lineHeight = (fontMetrics.descent - fontMetrics.ascent) * lineSpacing
        val textHeight = wrappedLines.size * lineHeight
        val maxLineWidth = wrappedLines.maxOfOrNull { line ->
            paint.measureText(line)
        } ?: 0f

        // MỤC TIÊU: Đảm bảo text KHÔNG bao giờ tràn ra ngoài vùng chứa
        val fits = if (shapeType == 1) {
            // Kiểm tra 4 góc của text block (hình chữ nhật) có nằm trong Ellipse an toàn không
            // Phương trình Ellipse: (wRatio^2 + hRatio^2) <= 1.0
            val wRatio = maxLineWidth / safeWidth
            val hRatio = textHeight / safeHeight
            // Sử dụng ngưỡng 0.96 (thay vì 1.0) để tạo lề an toàn nhỏ, tránh chạm sát viền
            (wRatio * wRatio + hRatio * hRatio) <= 0.96f
        } else {
            val heightFits = textHeight <= safeHeight * heightScale
            val widthFits = maxLineWidth <= safeWidth * widthScale
            heightFits && widthFits
        }

        if (fits) {
            optimalFontSize = mid
            low = mid + 0.1f
        } else {
            high = mid - 0.1f
        }
    }

    // Không cộng thêm sai số để đảm bảo text luôn nằm trong vùng chứa
    val allowed = optimalFontSize.coerceIn(safeMinFontSize, safeMaxFontSize)
    return allowed
}

/**
 * Compute the effective font size to use in edit mode.
 * Uses the explicitly edited font size when provided, otherwise falls back to the block's fontSize.
 * For vertical text we scale down to avoid extremely large visual rendering.
 */
