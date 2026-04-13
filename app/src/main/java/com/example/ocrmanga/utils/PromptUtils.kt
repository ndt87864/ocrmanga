package com.example.ocrmanga.utils

import android.content.Context
import java.io.IOException

object PromptUtils {
    fun loadPromptFromAssets(context: Context, fileName: String): String {
        return try {
            context.assets.open("prompts/$fileName").bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            AppLogger.e("PromptUtils", "Error loading prompt from assets: $fileName", e)
            ""
        }
    }
}
