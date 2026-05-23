package com.example.ocrmanga.data.models

data class ApiKeyInfo(
    val id: Int,
    val value: String,
    val type: String,
    val isActive: Boolean,
    val allowedModels: String = ""
) {
    fun isModelAllowed(model: String): Boolean {
        if (allowedModels.isBlank()) return true
        val list = allowedModels.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) return true
        return list.contains(model)
    }
}
