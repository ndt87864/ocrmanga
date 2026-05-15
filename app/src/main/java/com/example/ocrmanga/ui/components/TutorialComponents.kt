package com.example.ocrmanga.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

data class TutorialStep(
    val title: String,
    val description: String,
    val targetTag: String? = null
)

@Composable
fun AppTutorialOverlay(
    steps: List<TutorialStep>,
    onComplete: () -> Unit,
    targetPositions: Map<String, Rect> = emptyMap()
) {
    var currentStepIdx by remember { mutableStateOf(0) }
    val currentStep = steps.getOrNull(currentStepIdx) ?: return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(9999f)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = {} // Intercept all clicks
            )
    ) {
        val targetRect = currentStep.targetTag?.let { targetPositions[it] }

        // Spotlight Effect
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(alpha = 0.99f) // Important for BlendMode.Clear to work
        ) {
            drawRect(Color.Black.copy(alpha = 0.7f))
            
            if (targetRect != null && targetRect != Rect.Zero) {
                val padding = 12.dp.toPx()
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = androidx.compose.ui.geometry.Offset(targetRect.left - padding, targetRect.top - padding),
                    size = androidx.compose.ui.geometry.Size(targetRect.width + padding * 2, targetRect.height + padding * 2),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    blendMode = BlendMode.Clear
                )
            }
        }

        // Content Card
        AnimatedContent(
            targetState = currentStepIdx,
            transitionSpec = {
                fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
            },
            modifier = Modifier.align(Alignment.Center),
            label = "tutorialCard"
        ) { idx ->
            val step = steps[idx]
            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = step.description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onComplete) {
                        Text("Bỏ qua", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    
                    Button(
                        onClick = {
                            if (currentStepIdx < steps.size - 1) {
                                currentStepIdx++
                            } else {
                                onComplete()
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (currentStepIdx < steps.size - 1) "Tiếp theo" else "Bắt đầu ngay")
                        if (currentStepIdx < steps.size - 1) {
                            Icon(Icons.Default.NavigateNext, null)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Modifier helper to report position for tutorial spotlight
 */
fun Modifier.tutorialTag(
    tag: String,
    onPositionReported: (String, Rect) -> Unit
): Modifier = this.onGloballyPositioned { coords ->
    val pos = coords.positionInRoot()
    val size = coords.size
    onPositionReported(tag, Rect(pos, androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat())))
}
