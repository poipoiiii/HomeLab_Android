package com.poi.homelab.feature.market.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.poi.homelab.core.ui.theme.Danger
import com.poi.homelab.core.ui.theme.Success

/** 行情统一配色：红涨绿跌；没有数据或持平用次要文字色。 */
@Composable
fun marketChangeColor(percent: Double?): Color = when {
    percent == null -> MaterialTheme.colorScheme.onSurfaceVariant
    percent > 0 -> Danger
    percent < 0 -> Success
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
