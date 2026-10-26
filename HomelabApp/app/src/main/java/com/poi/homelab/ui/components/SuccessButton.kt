package com.poi.homelab.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.poi.homelab.ui.theme.SuccessStrong
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * 点击后收缩成圆形打勾，并迸出一圈小粒子，1.5 秒后自动恢复。
 * 只用于本地保存这类明确完成的操作。
 */
@Composable
fun SuccessButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    var completed by remember { mutableStateOf(false) }
    var burstKey by remember { mutableIntStateOf(0) }
    val active = enabled && !loading

    LaunchedEffect(completed) {
        if (completed) {
            delay(180)
            burstKey++
            delay(1400)
            completed = false
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth().height(48.dp)) {
        val fullWidth = maxWidth
        val width by animateDpAsState(
            targetValue = if (completed) 48.dp else fullWidth,
            animationSpec = tween(360, easing = FastOutSlowInEasing),
            label = "successButtonWidth",
        )
        val corner by animateDpAsState(
            targetValue = if (completed) 24.dp else 12.dp,
            animationSpec = tween(360, easing = FastOutSlowInEasing),
            label = "successButtonCorner",
        )
        val container by animateColorAsState(
            targetValue = if (completed) SuccessStrong else MaterialTheme.colorScheme.primary,
            animationSpec = tween(300),
            label = "successButtonColor",
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(width)
                .height(48.dp)
                .clip(RoundedCornerShape(corner))
                .background(container)
                .clickable(enabled = active && !completed) {
                    onClick()
                    completed = true
                },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = completed,
                transitionSpec = {
                    fadeIn(tween(160)) togetherWith fadeOut(tween(100))
                },
                label = "successButtonContent",
            ) { done ->
                if (done) {
                    CheckGlyph(Modifier.size(22.dp))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.size(10.dp))
                        }
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        if (burstKey > 0) {
            ParticleBurst(
                trigger = burstKey,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(fullWidth)
                    .height(48.dp),
            )
        }
    }
}

@Composable
private fun CheckGlyph(modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
    }
    Canvas(modifier) {
        val start = Offset(size.width * 0.16f, size.height * 0.54f)
        val middle = Offset(size.width * 0.42f, size.height * 0.8f)
        val end = Offset(size.width * 0.86f, size.height * 0.26f)
        val first = (progress.value / 0.45f).coerceIn(0f, 1f)
        val second = ((progress.value - 0.45f) / 0.55f).coerceIn(0f, 1f)
        val stroke = 3.dp.toPx()
        if (first > 0f) {
            drawLine(
                color = Color.White,
                start = start,
                end = Offset(
                    start.x + (middle.x - start.x) * first,
                    start.y + (middle.y - start.y) * first,
                ),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        if (second > 0f) {
            drawLine(
                color = Color.White,
                start = middle,
                end = Offset(
                    middle.x + (end.x - middle.x) * second,
                    middle.y + (end.y - middle.y) * second,
                ),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun ParticleBurst(trigger: Int, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    val particleColor = MaterialTheme.colorScheme.primary
    LaunchedEffect(trigger) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(680, easing = LinearOutSlowInEasing))
    }
    Canvas(modifier) {
        val t = progress.value
        if (t <= 0f || t >= 1f) return@Canvas
        val origin = Offset(24.dp.toPx(), size.height / 2f)
        val angles = listOf(
            -165f, -135f, -105f, -75f, -45f, -15f,
            15f, 45f, 75f, 105f, 135f, 165f,
        )
        angles.forEachIndexed { index, degrees ->
            val radians = degrees * PI / 180.0
            val reach = (24.dp.toPx() + (index % 3) * 5.dp.toPx()) * t
            val center = Offset(
                origin.x + (cos(radians) * reach).toFloat(),
                origin.y + (sin(radians) * reach).toFloat(),
            )
            drawCircle(
                color = particleColor.copy(alpha = (1f - t) * 0.9f),
                radius = (2.6.dp.toPx() * (1f - t * 0.55f)),
                center = center,
            )
        }
    }
}
