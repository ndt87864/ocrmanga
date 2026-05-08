package com.example.ocrmanga.ui.animation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset

object AnimationUtils {
    private const val DURATION = 300

    // Shared axis X: horizontal slide + fade
    fun sharedAxisXForward(): ContentTransform {
        return ContentTransform(
            targetContentEnter = slideInHorizontally(animationSpec = tween(DURATION)) + fadeIn(animationSpec = tween(DURATION)),
            initialContentExit = slideOutHorizontally(animationSpec = tween(DURATION)) + fadeOut(animationSpec = tween(DURATION))
        )
    }

    fun sharedAxisXBackward(): ContentTransform {
        return ContentTransform(
            targetContentEnter = slideInHorizontally(animationSpec = tween(DURATION), initialOffsetX = { -it }) + fadeIn(animationSpec = tween(DURATION)),
            initialContentExit = slideOutHorizontally(animationSpec = tween(DURATION), targetOffsetX = { it }) + fadeOut(animationSpec = tween(DURATION))
        )
    }

    // Shared axis Z: scale + fade
    fun sharedAxisZ(): ContentTransform {
        return ContentTransform(
            targetContentEnter = scaleIn(animationSpec = tween(DURATION), initialScale = 0.92f) + fadeIn(animationSpec = tween(DURATION)),
            initialContentExit = scaleOut(animationSpec = tween(DURATION), targetScale = 0.92f) + fadeOut(animationSpec = tween(DURATION))
        )
    }

    // Helper to pick transform based on booleans
    fun chooseContentTransform(oldIsEdit: Boolean, newIsEdit: Boolean, oldModeHorizontal: Boolean, newModeHorizontal: Boolean): ContentTransform {
        // If edit state changed -> use Z
        if (oldIsEdit != newIsEdit) return sharedAxisZ()
        // If horizontal/vertical changed -> X with direction depending on newModeHorizontal
        if (oldModeHorizontal != newModeHorizontal) {
            return if (newModeHorizontal) sharedAxisXForward() else sharedAxisXBackward()
        }
        // default
        return sharedAxisXForward()
    }
}
