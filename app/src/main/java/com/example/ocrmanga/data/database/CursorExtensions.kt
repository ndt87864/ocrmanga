package com.example.ocrmanga.data.database

import android.database.Cursor

/** Trả về Int hoặc null nếu cột không tồn tại hoặc giá trị là NULL. */
internal fun Cursor.getIntOrNull(index: Int): Int? = try {
    if (index < 0 || isNull(index)) null else getInt(index)
} catch (e: Exception) { null }

/** Trả về Int với giá trị mặc định nếu cột không tồn tại hoặc giá trị là NULL. */
internal fun Cursor.getIntOrDefault(index: Int, default: Int): Int = try {
    if (index < 0 || isNull(index)) default else getInt(index)
} catch (e: Exception) { default }

/** Trả về Float với giá trị mặc định nếu cột không tồn tại hoặc giá trị là NULL. */
internal fun Cursor.getFloatOrDefault(index: Int, default: Float): Float = try {
    if (index < 0 || isNull(index)) default else getFloat(index)
} catch (e: Exception) { default }

/** Trả về Float hoặc null nếu cột không tồn tại hoặc giá trị là NULL. */
internal fun Cursor.getFloatOrNull(index: Int): Float? = try {
    if (index < 0 || isNull(index)) null else getFloat(index)
} catch (e: Exception) { null }

/** Trả về String hoặc null nếu cột không tồn tại hoặc giá trị là NULL. */
internal fun Cursor.getStringOrNull(index: Int): String? = try {
    if (index < 0 || isNull(index)) null else getString(index)
} catch (e: Exception) { null }
