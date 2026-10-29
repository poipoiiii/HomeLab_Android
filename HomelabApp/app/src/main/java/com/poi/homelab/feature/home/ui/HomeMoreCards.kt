package com.poi.homelab.feature.home.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.poi.homelab.core.settings.AppSettings
import com.poi.homelab.core.ui.components.CreamCard
import com.poi.homelab.core.ui.components.DarkCard
import com.poi.homelab.core.ui.components.DarkStat
import com.poi.homelab.core.ui.components.InlineAction
import com.poi.homelab.core.ui.components.LoadingRow
import com.poi.homelab.core.ui.components.StatBlock
import com.poi.homelab.core.ui.components.Tag
import com.poi.homelab.core.ui.components.ThinDivider
import com.poi.homelab.core.ui.theme.Coral
import com.poi.homelab.core.ui.theme.Danger
import com.poi.homelab.core.ui.theme.OnDark
import com.poi.homelab.core.ui.theme.OnDarkSoft
import com.poi.homelab.core.ui.theme.Success
import com.poi.homelab.core.ui.theme.Teal
import com.poi.homelab.core.ui.theme.Warning
import com.poi.homelab.core.util.balanceSourceLabel
import com.poi.homelab.core.util.money
import com.poi.homelab.core.util.shortIsoTimestamp
import com.poi.homelab.feature.bills.BillOverview
import com.poi.homelab.feature.calendar.CALENDAR_TRAVEL
import com.poi.homelab.feature.calendar.CalendarEntry
import com.poi.homelab.feature.lab.DiskSection
import com.poi.homelab.feature.lab.HealthSnapshot
import com.poi.homelab.feature.photos.PhotoSyncStatus
import com.poi.homelab.feature.weather.HomeWeather
import com.poi.homelab.navigation.UsageRank
import java.time.LocalDate
import kotlin.math.roundToInt

/** 首页「更多」里的净资产卡：净资产 + 各账户余额，数据来自账本 /api/overview。 */
@Composable
internal fun NetWorthCard(overview: BillOverview, onOpenBills: () -> Unit) {
    DarkCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DarkStat("净资产", money(overview.netWorth), Modifier.weight(1f))
            InlineAction("账单", onOpenBills)
        }
        val accounts = overview.accounts
        // 取最近一次被确认的余额时间，服务端没给就整行不显示
        val freshest = accounts.maxByOrNull { it.balanceUpdatedAt.orEmpty() }
            ?.takeIf { !it.balanceUpdatedAt.isNullOrBlank() }
        if (freshest != null) {
            Text(
                text = "余额更新于 ${shortIsoTimestamp(freshest.balanceUpdatedAt)}" +
                    " · ${balanceSourceLabel(freshest.balanceSource)}",
                style = MaterialTheme.typography.bodySmall,
                color = OnDarkSoft,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (accounts.isNotEmpty()) {
            ThinDivider(Modifier.padding(vertical = 12.dp))
            accounts.take(4).forEach { account ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = account.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnDarkSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = money(account.balance),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnDark,
                    )
                }
            }
            if (accounts.size > 4) {
                Text(
                    text = "还有 ${accounts.size - 4} 个账户",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnDarkSoft,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        if (overview.pendingBalanceCandidates > 0) {
            ThinDivider(Modifier.padding(vertical = 12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${overview.pendingBalanceCandidates} 条余额待确认",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Warning,
                    modifier = Modifier.weight(1f),
                )
                InlineAction("去确认", onOpenBills)
            }
        }
    }
}

/** 首页「更多」里的日程卡：只看今天到未来 7 天，纯本地数据。 */
@Composable
internal fun UpcomingCalendarCard(entries: List<CalendarEntry>, onOpenCalendar: () -> Unit) {
    val today = LocalDate.now()
    val upcoming = remember(entries, today) {
        val start = today.toString()
        val end = today.plusDays(7).toString()
        entries.filter { it.date in start..end }.take(4)
    }
    CreamCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "日程",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            InlineAction("日历", onOpenCalendar)
        }
        if (upcoming.isEmpty()) {
            Text(
                text = "未来 7 天没有安排。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            upcoming.forEachIndexed { index, entry ->
                if (index > 0) ThinDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dayLabel(entry.date, today),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(52.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (entry.location.isNotBlank()) {
                            Text(
                                text = entry.location,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    if (entry.type == CALENDAR_TRAVEL) {
                        Tag("出行", Teal)
                    } else {
                        Tag("重要", Coral)
                    }
                }
            }
        }
    }
}

private fun dayLabel(date: String, today: LocalDate): String = when (date) {
    today.toString() -> "今天"
    today.plusDays(1).toString() -> "明天"
    else -> runCatching {
        val parsed = LocalDate.parse(date)
        "${parsed.monthValue}月${parsed.dayOfMonth}日"
    }.getOrDefault(date)
}

/** 首页「更多」里的主机卡：容器概况 + Docker 磁盘占用。 */
@Composable
internal fun HostHealthCard(health: HealthSnapshot, onOpenServices: () -> Unit) {
    val summary = health.summary
    val disk = health.disk
    CreamCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "主机",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            InlineAction("服务", onOpenServices)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatBlock("容器", "${summary.running}/${summary.total} 运行", Modifier.weight(1f))
            StatBlock(
                label = "异常",
                value = summary.unhealthy.toString(),
                modifier = Modifier.weight(1f),
                valueColor = if (summary.unhealthy > 0) Danger else Success,
            )
            StatBlock("待处理", summary.attention.toString(), Modifier.weight(1f))
        }
        ThinDivider(Modifier.padding(vertical = 12.dp))
        Text("磁盘", style = MaterialTheme.typography.titleSmall)
        diskRow("镜像", disk.images)
        diskRow("容器", disk.containers)
        diskRow("卷", disk.volumes)
        diskRow("构建缓存", disk.buildCache)
        if (disk.totalReclaimable.isNotBlank()) {
            Text(
                text = "可回收 ${disk.totalReclaimable}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (health.attention.isNotEmpty()) {
            Text(
                text = "待处理：${health.attention.take(3).joinToString("、") { it.name }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun diskRow(label: String, section: DiskSection) {
    if (section.count <= 0 && section.size.isBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = listOf("${section.count} 个", section.size).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** 首页「更多」里的天气卡：城市来自设置，数据直接问 Open-Meteo。 */
@Composable
internal fun HomeWeatherCard(
    weather: HomeWeather?,
    error: String?,
    loading: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    CreamCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = weather?.city?.takeIf { it.isNotBlank() } ?: "天气",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            InlineAction("设置", onOpenSettings)
        }
        when {
            loading && weather == null -> LoadingRow("正在取天气…")
            weather != null -> Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = "${weather.temperature.roundToInt()}°",
                    style = MaterialTheme.typography.displaySmall,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = weatherLabel(weather.code),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "高 ${weather.high.roundToInt()}° / 低 ${weather.low.roundToInt()}°",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            error != null -> {
                Text(
                    text = "天气没取到：$error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                InlineAction("重试", onRetry)
            }
            else -> Text(
                text = "天气还没加载，下拉刷新试试。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** WMO 天气代码 → 中文，取常用的几档。 */
private fun weatherLabel(code: Int): String = when (code) {
    0 -> "晴"
    1 -> "少云"
    2 -> "多云"
    3 -> "阴"
    45, 48 -> "雾"
    51, 53, 55 -> "毛毛雨"
    56, 57, 66, 67 -> "冻雨"
    61 -> "小雨"
    63 -> "中雨"
    65 -> "大雨"
    71 -> "小雪"
    73 -> "中雪"
    75, 77 -> "大雪"
    80, 81 -> "阵雨"
    82 -> "强阵雨"
    85, 86 -> "阵雪"
    95 -> "雷阵雨"
    96, 99 -> "雷暴冰雹"
    else -> "多云"
}

/** 首页「更多」里的照片同步卡：只显示上次结果摘要，凭证不落在这里。 */
@Composable
internal fun PhotoSyncCard(settings: AppSettings, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val status = remember { PhotoSyncStatus.describe(context) }
    CreamCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "照片自动同步",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            InlineAction("设置", onOpenSettings)
        }
        Text(
            text = "每天 ${"%02d".format(settings.photoSyncHour)}:00 · 最近 ${settings.photoSyncDays} 天 · 最多 ${settings.photoSyncLimit} 张",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 首页「更多」里的手机使用卡：今天用得最多和占空间最多的应用。 */
@Composable
internal fun DeviceUsageCard(
    loading: Boolean,
    hasAccess: Boolean,
    usageRows: List<UsageRank>,
    storageRows: List<UsageRank>,
    onOpenSettings: () -> Unit,
) {
    CreamCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "手机使用",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            InlineAction("权限", onOpenSettings)
        }
        when {
            loading -> LoadingRow("正在统计使用情况…")
            !hasAccess -> {
                Text(
                    text = "要读取使用时长和存储占用，需要「使用情况访问」权限。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                InlineAction("去授权", onOpenSettings)
            }
            else -> {
                if (usageRows.isNotEmpty()) {
                    Text("今天用得最多", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                    usageRows.forEach { rankRow(it) }
                }
                if (storageRows.isNotEmpty()) {
                    ThinDivider(Modifier.padding(vertical = 10.dp))
                    Text("占空间最多", style = MaterialTheme.typography.titleSmall)
                    storageRows.forEach { rankRow(it) }
                }
                if (usageRows.isEmpty() && storageRows.isEmpty()) {
                    Text(
                        text = "还没有可用的统计。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun rankRow(row: UsageRank) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = row.value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
