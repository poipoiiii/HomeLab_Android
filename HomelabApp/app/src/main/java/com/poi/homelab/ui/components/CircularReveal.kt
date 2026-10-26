package com.poi.homelab.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.math.hypot
import kotlin.math.max

/**
 * 圆形扩散转场：新页面从 [origin] 开始以水波圆的方式铺满旧页面。
 * [origin] 是相对内容区域的坐标。
 */
@Composable
fun <T> CircularReveal(
    target: T,
    origin: Offset,
    modifier: Modifier = Modifier,
    animationSpec: FiniteAnimationSpec<Float> = tween(430, easing = FastOutSlowInEasing),
    content: @Composable (T) -> Unit,
) {
    val progress = remember { Animatable(1f) }
    var current by remember { mutableStateOf(target) }
    var previous by remember { mutableStateOf<T?>(null) }

    LaunchedEffect(target) {
        if (target == current) return@LaunchedEffect
        previous = current
        current = target
        progress.snapTo(0f)
        progress.animateTo(1f, animationSpec)
        previous = null
    }

    Box(modifier = modifier.fillMaxSize()) {
        previous?.let { old ->
            key(old) { content(old) }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    val animated = progress.value
                    if (animated >= 1f) {
                        drawContent()
                        return@drawWithContent
                    }
                    val maxRadius = hypot(
                        max(origin.x, size.width - origin.x),
                        max(origin.y, size.height - origin.y),
                    )
                    val circle = Path().apply {
                        addOval(Rect(center = origin, radius = maxRadius * animated))
                    }
                    clipPath(circle) {
                        this@drawWithContent.drawContent()
                    }
                },
        ) {
            key(current) { content(current) }
        }
    }
}
