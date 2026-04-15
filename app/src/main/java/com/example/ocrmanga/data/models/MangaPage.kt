package com.example.ocrmanga.data.models

import android.net.Uri

/**
 * Đại diện cho một trang trong bộ truyện (Manga Page).
 */
data class MangaPage(
    val uri: Uri,
    val displayOrder: Int,
    val originalText: String,
    val textBlocks: List<TextBlockInfo>,
    val cloudUrl: String? = null
)
