package com.poi.homelab.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.poi.homelab.ui.theme.SuccessStrong

data class ChecklistItem(val id: String, val label: String)

@Composable
fun DailyChecklistCard(
    items: List<ChecklistItem>,
    checked: Set<String>,
    onToggle: (String) -> Unit,
    onEdit: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val done = items.count { it.id in checked }
    val progress = if (items.isEmpty()) 0f else done / items.size.toFloat()
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(520),
        label = "checklistProgress",
    )

    CreamCard(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Todo", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (items.isEmpty()) {
                        "还没有事项，点编辑添加"
                    } else if (progress >= 1f) {
                        "今天全部完成"
                    } else {
                        "完成 $done / ${items.size} 项"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (onEdit != null) {
                InlineAction("编辑", onEdit)
            }
            ProgressRing(
                progress = animatedProgress,
                label = "${(progress * 100).toInt()}%",
            )
        }

        Column(
            modifier = Modifier.padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items.forEach { item ->
                val isChecked = item.id in checked
                ChecklistRow(
                    label = item.label,
                    checked = isChecked,
                    onClick = { onToggle(item.id) },
                )
            }
        }
    }
}

@Composable
private fun ChecklistRow(label: String, checked: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (checked) 1f else 0.86f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = 500f),
        label = "checkScale",
    )
    val textColor by animateColorAsState(
        targetValue = if (checked) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(220),
        label = "checkTextColor",
    )
    val outline = MaterialTheme.colorScheme.outline

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (checked) SuccessStrong else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                Modifier
                    .size(15.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
            ) {
                if (checked) {
                    val stroke = 2.dp.toPx()
                    drawLine(
                        color = Color.White,
                        start = Offset(size.width * 0.12f, size.height * 0.52f),
                        end = Offset(size.width * 0.4f, size.height * 0.8f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(size.width * 0.4f, size.height * 0.8f),
                        end = Offset(size.width * 0.9f, size.height * 0.2f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
            if (!checked) {
                Canvas(Modifier.size(24.dp)) {
                    drawCircle(
                        color = outline,
                        radius = size.minDimension / 2f - 0.75.dp.toPx(),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            fontWeight = if (checked) FontWeight.Normal else FontWeight.Medium,
            textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None,
            modifier = Modifier.padding(start = 12.dp).weight(1f),
        )
    }
}

@Composable
private fun ProgressRing(progress: Float, label: String) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val accent = SuccessStrong
    Box(
        modifier = Modifier.size(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(48.dp)) {
            val stroke = 4.dp.toPx()
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (progress > 0f) {
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
