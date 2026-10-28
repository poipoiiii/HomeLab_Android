package com.poi.homelab.feature.calendar.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.poi.homelab.core.ui.components.CreamCard
import com.poi.homelab.core.ui.components.InlineAction
import com.poi.homelab.core.ui.components.Pill
import com.poi.homelab.core.ui.components.SectionHeader
import com.poi.homelab.core.ui.components.SuccessButton
import com.poi.homelab.core.ui.components.Tag
import com.poi.homelab.core.ui.components.ThinDivider
import com.poi.homelab.core.ui.theme.Danger
import com.poi.homelab.core.ui.theme.Teal
import com.poi.homelab.feature.calendar.CALENDAR_IMPORTANT
import com.poi.homelab.feature.calendar.CALENDAR_TRAVEL
import com.poi.homelab.feature.calendar.CalendarEntry
import com.poi.homelab.navigation.UiState
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val WEEKDAYS = listOf("一", "二", "三", "四", "五", "六", "日")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    state: UiState,
    onSave: (CalendarEntry) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val today = remember { LocalDate.now() }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var selectedDate by rememberSaveable { mutableStateOf(today.toString()) }
    val month = remember(monthText) { YearMonth.parse(monthText) }
    val entries = state.calendarEntries
    val entriesByDate = remember(entries) { entries.groupBy { it.date } }
    val monthEntries = remember(entries, monthText) {
        entries.filter { it.date.startsWith(month.toString()) }
            .sortedWith(compareBy({ it.date }, { it.id }))
    }
    val dayEntries = entriesByDate[selectedDate].orEmpty()

    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editorDate by rememberSaveable { mutableStateOf(selectedDate) }
    var editorEntryId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editorEntry = remember(editorEntryId, entries) {
        editorEntryId?.let { id -> entries.firstOrNull { it.id == id } }
    }
    var deleting by remember { mutableStateOf<CalendarEntry?>(null) }
    val scope = rememberCoroutineScope()

    fun openNew(date: String) {
        editorEntryId = null
        editorDate = date
        editorOpen = true
    }

    fun openEdit(entry: CalendarEntry) {
        editorEntryId = entry.id
        editorDate = entry.date
        editorOpen = true
    }

    fun shiftMonth(delta: Long) {
        val next = month.plusMonths(delta)
        monthText = next.toString()
        selectedDate = if (next == YearMonth.from(today)) {
            today.toString()
        } else {
            next.atDay(1).toString()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionHeader(
                title = "日历",
                subtitle = "旅行记录与重要事项，只保存在本机",
            )
        }

        item {
            MonthCard(
                month = month,
                today = today,
                selectedDate = selectedDate,
                entries = entries,
                entriesByDate = entriesByDate,
                onPrev = { shiftMonth(-1) },
                onNext = { shiftMonth(1) },
                onToday = {
                    monthText = YearMonth.from(today).toString()
                    selectedDate = today.toString()
                },
                onSelectDate = { selectedDate = it.toString() },
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = dayLabel(selectedDate),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (dayEntries.isEmpty()) {
                            "这天还没有记录"
                        } else {
                            "${dayEntries.size} 条记录"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                InlineAction("添加记录", { openNew(selectedDate) })
            }
        }

        if (dayEntries.isEmpty()) {
            item {
                CreamCard {
                    Text(
                        "旅行、出差、值得记住的小事，都可以记在这一天。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(dayEntries, key = { "day-${it.id}" }) { entry ->
                CalendarEntryCard(entry, onClick = { openEdit(entry) })
            }
        }

        item {
            SectionHeader(
                title = "本月记录",
                subtitle = monthSummary(monthEntries),
            )
        }

        if (monthEntries.isEmpty()) {
            item {
                CreamCard {
                    Text(
                        "这个月还没有记录。选中日期后点「添加记录」开始。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(monthEntries, key = { "month-${it.id}" }) { entry ->
                CalendarEntryCard(entry, onClick = { openEdit(entry) })
            }
        }
    }

    if (editorOpen) {
        ModalBottomSheet(
            onDismissRequest = { editorOpen = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            CalendarEntryEditor(
                date = editorDate,
                initial = editorEntry,
                onSave = { entry ->
                    onSave(entry)
                    runCatching { LocalDate.parse(entry.date) }.getOrNull()?.let { saved ->
                        selectedDate = entry.date
                        monthText = YearMonth.from(saved).toString()
                    }
                    scope.launch {
                        delay(900)
                        editorOpen = false
                    }
                },
                onDelete = { entry -> deleting = entry },
                onClose = { editorOpen = false },
            )
        }
    }

    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("删除记录") },
            text = { Text("确定删除「${entry.title}」吗？删除后无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(entry.id)
                        deleting = null
                        editorOpen = false
                    },
                ) {
                    Text("删除", color = Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun MonthCard(
    month: YearMonth,
    today: LocalDate,
    selectedDate: String,
    entries: List<CalendarEntry>,
    entriesByDate: Map<String, List<CalendarEntry>>,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
) {
    val monthEntries = entries.filter { it.date.startsWith(month.toString()) }
    val travelCount = monthEntries.count { it.type == CALENDAR_TRAVEL }
    val importantCount = monthEntries.count { it.type == CALENDAR_IMPORTANT }
    val leading = (month.atDay(1).dayOfWeek.value + 6) % 7
    val days = month.lengthOfMonth()
    val rows = (leading + days + 6) / 7

    CreamCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InlineAction("‹", onPrev)
            Text(
                text = "${month.year}年${month.monthValue}月",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            InlineAction("›", onNext)
            Spacer(Modifier.weight(1f))
            if (month != YearMonth.from(today)) {
                InlineAction("今天", onToday)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            WEEKDAYS.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            for (row in 0 until rows) {
                Row(Modifier.fillMaxWidth()) {
                    for (column in 0..6) {
                        val index = row * 7 + column
                        val day = index - leading + 1
                        if (day in 1..days) {
                            val date = month.atDay(day)
                            DayCell(
                                day = day,
                                date = date,
                                selected = date.toString() == selectedDate,
                                today = date == today,
                                entries = entriesByDate[date.toString()].orEmpty(),
                                onClick = { onSelectDate(date) },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Box(Modifier.weight(1f).aspectRatio(1f))
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Tag("旅行 $travelCount", Teal)
            Tag("重要 $importantCount", MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun DayCell(
    day: Int,
    date: LocalDate,
    selected: Boolean,
    today: Boolean,
    entries: List<CalendarEntry>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primary
            today -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            else -> Color.Transparent
        },
        animationSpec = tween(180),
        label = "dayBackground",
    )
    val textColor = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        today -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = day.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected || today) FontWeight.SemiBold else FontWeight.Normal,
                color = textColor,
            )
            Row(
                modifier = Modifier.height(8.dp).padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                entries.take(3).forEach { entry ->
                    Box(
                        Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(dotColor(entry, selected)),
                    )
                }
            }
        }
    }
}

@Composable
private fun dotColor(entry: CalendarEntry, selected: Boolean): Color = when {
    selected -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
    entry.type == CALENDAR_TRAVEL -> Teal
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun CalendarEntryCard(entry: CalendarEntry, onClick: () -> Unit) {
    val accent = if (entry.type == CALENDAR_TRAVEL) Teal else MaterialTheme.colorScheme.primary
    CreamCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier.width(58.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = dayOfMonth(entry.date).toString(),
                    style = MaterialTheme.typography.titleLarge,
                    color = accent,
                )
                Text(
                    text = weekdayLabel(entry.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Tag(
                        text = if (entry.type == CALENDAR_TRAVEL) "旅行" else "重要",
                        color = accent,
                    )
                }
                if (entry.location.isNotBlank()) {
                    Text(
                        text = "地点 · ${entry.location}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
                if (entry.note.isNotBlank()) {
                    Text(
                        text = entry.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarEntryEditor(
    date: String,
    initial: CalendarEntry?,
    onSave: (CalendarEntry) -> Unit,
    onDelete: (CalendarEntry) -> Unit,
    onClose: () -> Unit,
) {
    var type by rememberSaveable(initial?.id, date) {
        mutableStateOf(initial?.type ?: CALENDAR_TRAVEL)
    }
    var title by rememberSaveable(initial?.id, date) {
        mutableStateOf(initial?.title.orEmpty())
    }
    var location by rememberSaveable(initial?.id, date) {
        mutableStateOf(initial?.location.orEmpty())
    }
    var note by rememberSaveable(initial?.id, date) {
        mutableStateOf(initial?.note.orEmpty())
    }
    var entryDate by rememberSaveable(initial?.id, date) {
        mutableStateOf(date)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (initial == null) "添加记录" else "编辑记录",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            InlineAction("关闭", onClose)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InlineAction("‹", { entryDate = shiftDate(entryDate, -1) })
            Text(
                text = dayLabel(entryDate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            InlineAction("›", { entryDate = shiftDate(entryDate, 1) })
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Pill(
                text = "旅行",
                selected = type == CALENDAR_TRAVEL,
                onClick = { type = CALENDAR_TRAVEL },
            )
            Pill(
                text = "重要",
                selected = type == CALENDAR_IMPORTANT,
                onClick = { type = CALENDAR_IMPORTANT },
            )
        }

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            label = { Text("标题") },
            placeholder = {
                Text(
                    if (type == CALENDAR_TRAVEL) "例如：去京都看枫叶" else "例如：交房租 / 体检",
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )

        if (type == CALENDAR_TRAVEL) {
            OutlinedTextField(
                value = location,
                onValueChange = { location = it },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                label = { Text("地点（可选）") },
                placeholder = { Text("城市 / 景点 / 酒店") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
        }

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            label = { Text("备注（可选）") },
            placeholder = { Text("路线、同行人、花销、想吃的东西……") },
            minLines = 3,
            maxLines = 6,
            shape = RoundedCornerShape(12.dp),
        )

        ThinDivider(Modifier.padding(top = 18.dp, bottom = 14.dp))

        SuccessButton(
            text = "保存记录",
            enabled = title.isNotBlank(),
            onClick = {
                onSave(
                    (initial ?: CalendarEntry(date = entryDate, title = title.trim())).copy(
                        date = entryDate,
                        title = title.trim(),
                        type = type,
                        location = if (type == CALENDAR_TRAVEL) location.trim() else "",
                        note = note.trim(),
                    ),
                )
            },
        )

        if (initial != null) {
            InlineAction(
                text = "删除记录",
                onClick = { onDelete(initial) },
                color = Danger,
            )
        }
    }
}

private fun monthSummary(entries: List<CalendarEntry>): String {
    if (entries.isEmpty()) return "还没有记录"
    val travel = entries.count { it.type == CALENDAR_TRAVEL }
    val important = entries.size - travel
    return "旅行 $travel · 重要 $important"
}

private fun shiftDate(date: String, days: Long): String =
    runCatching { LocalDate.parse(date).plusDays(days).toString() }.getOrDefault(date)

private fun dayLabel(date: String): String {
    val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
    val weekday = WEEKDAYS[(parsed.dayOfWeek.value + 6) % 7]
    return "${parsed.monthValue}月${parsed.dayOfMonth}日 周$weekday"
}

private fun dayOfMonth(date: String): Int =
    runCatching { LocalDate.parse(date).dayOfMonth }.getOrDefault(0)

private fun weekdayLabel(date: String): String {
    val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return ""
    return "周" + WEEKDAYS[(parsed.dayOfWeek.value + 6) % 7]
}
