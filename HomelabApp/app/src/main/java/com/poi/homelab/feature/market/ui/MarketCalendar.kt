package com.poi.homelab.feature.market.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.poi.homelab.core.ui.components.InlineAction
import java.time.LocalDate
import java.time.YearMonth

/** 行情页和详情页共用的月历骨架，具体格子里画什么由调用方决定。 */
@Composable
fun MarketMonthCalendar(
    month: YearMonth,
    onMonthChange: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
    dayCell: @Composable (LocalDate) -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${month.year} 年 ${month.monthValue} 月",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            InlineAction("上月", { onMonthChange(month.minusMonths(1)) })
            InlineAction("下月", { onMonthChange(month.plusMonths(1)) })
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val offset = month.atDay(1).dayOfWeek.value - 1
        val days = month.lengthOfMonth()
        val rows = (offset + days + 6) / 7
        for (row in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (column in 0 until 7) {
                    val day = row * 7 + column - offset + 1
                    Box(
                        modifier = Modifier.weight(1f).height(48.dp).padding(2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (day in 1..days) dayCell(month.atDay(day))
                    }
                }
            }
        }
    }
}
