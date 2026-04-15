package com.example.ocrmanga.data.models

/**
 * Đại diện cho một bộ truyện/phòng làm việc (Manga Room).
 */
data class MangaRoom(
    val roomId: Long,
    val pages: List<MangaPage>
)
