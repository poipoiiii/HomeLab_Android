package com.poi.homelab.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 惯性滚轮：拖动有阻尼，松手带惯性，滑过头会像橡皮筋一样弹回并吸附到中间项。
 */
@Composable
fun InertiaWheel(
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 38.dp,
    visibleCount: Int = 3,
) {
    if (items.isEmpty()) return
    val density = LocalDensity.current
    val itemPx = with(density) { itemHeight.toPx() }
    val scope = rememberCoroutineScope()
    val decay = remember(density) { splineBasedDecay<Float>(density) }
    val position = remember {
        Animatable(selectedIndex.coerceIn(0, items.lastIndex).toFloat())
    }
    val maxIndex = items.lastIndex.toFloat()

    LaunchedEffect(selectedIndex) {
        val target = selectedIndex.coerceIn(0, items.lastIndex).toFloat()
        if (!position.isRunning && abs(position.value - target) > 0.01f) {
            position.animateTo(target, spring(dampingRatio = 0.75f, stiffness = 320f))
        }
    }

    fun resisted(value: Float): Float = when {
        value < 0f -> value * 0.32f
        value > maxIndex -> maxIndex + (value - maxIndex) * 0.32f
        else -> value
    }

    val background = MaterialTheme.colorScheme.background
    val bandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(itemHeight * visibleCount)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    scope.launch {
                        position.snapTo(resisted(position.value - delta / itemPx))
                    }
                },
                onDragStopped = { velocity ->
                    val fling = -velocity / itemPx
                    scope.launch {
                        if (abs(fling) > 0.08f) {
                            position.animateDecay(fling, decay)
                        }
                        val target = position.value.roundToInt().coerceIn(0, items.lastIndex)
                        position.animateTo(
                            target.toFloat(),
                            spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow,
                            ),
                        )
                        onSelected(target)
                    }
                },
            ),
    ) {
        val heightPx = with(density) { maxHeight.toPx() }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(itemHeight)
                .padding(horizontal = 10.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(bandColor),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = heightPx / 2f - itemPx / 2f - position.value * itemPx
                },
        ) {
            items.forEachIndexed { index, label ->
                val distance = abs(index - position.value).coerceAtMost(1.6f)
                val emphasis = 1f - distance / 1.6f
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .clickable {
                            scope.launch {
                                position.animateTo(
                                    index.toFloat(),
                                    spring(dampingRatio = 0.75f, stiffness = 320f),
                                )
                                onSelected(index)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (emphasis > 0.65f) FontWeight.SemiBold else FontWeight.Normal,
                        color = lerp(
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            MaterialTheme.colorScheme.onSurface,
                            emphasis,
                        ),
                        modifier = Modifier.graphicsLayer {
                            alpha = 0.22f + 0.78f * emphasis
                        },
                    )
                }
            }
        }

        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(24.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(background, Color.Transparent),
                    ),
                ),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(24.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, background),
                    ),
                ),
        )
    }
}
