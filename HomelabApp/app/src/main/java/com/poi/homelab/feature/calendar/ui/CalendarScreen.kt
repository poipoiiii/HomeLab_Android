package com.poi.homelab.feature.calendar.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.poi.homelab.core.ui.components.CreamCard
import com.poi.homelab.core.ui.components.InlineAction
import com.poi.homelab.core.ui.components.SectionHeader
import com.poi.homelab.core.ui.components.SuccessButton
import com.poi.homelab.core.ui.components.ThinDivider
import com.poi.homelab.core.ui.theme.Amber
import com.poi.homelab.core.ui.theme.Danger
import com.poi.homelab.core.ui.theme.Muted
import com.poi.homelab.core.ui.theme.Success
import com.poi.homelab.core.ui.theme.Teal
import com.poi.homelab.core.util.money
import com.poi.homelab.core.util.plainAmount
import com.poi.homelab.core.util.signedMoney
import com.poi.homelab.feature.bills.BillTransaction
import com.poi.homelab.feature.calendar.CALENDAR_ANNIVERSARY
import com.poi.homelab.feature.calendar.CALENDAR_IMPORTANT
import com.poi.homelab.feature.calendar.CALENDAR_TODO
import com.poi.homelab.feature.calendar.CALENDAR_TRAVEL
import com.poi.homelab.feature.calendar.CalendarEntry
import com.poi.homelab.feature.calendar.CalendarReminder
import com.poi.homelab.feature.calendar.calendarTypeLabel
import com.poi.homelab.navigation.UiState
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val WEEKDAYS = listOf("一", "二", "三", "四", "五", "六", "日")

/** 左右滑动的月份范围：以当前月为中间点，前后各 50 年足够用。 */
private const val MONTH_WINDOW = 1201
private const val MONTH_PAGE_BASE = MONTH_WINDOW / 2
private val DAY_CELL_HEIGHT = 46.dp
private val DAY_NAME_FORMAT = DateTimeFormatter.ofPattern("M月d日")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    state: UiState,
    onSave: (CalendarEntry) -> Unit,
    onDelete: (Long) -> Unit,
    onToggleDone: (Long, Boolean) -> Unit,
    onLoadMonth: (String) -> Unit,
    onOpenBills: (String) -> Unit,
    isPreview: Boolean = false,
) {
    val today = remember { LocalDate.now() }
    val currentMonth = remember(today) { YearMonth.from(today) }
    val pagerState = rememberPagerState(initialPage = MONTH_PAGE_BASE, pageCount = { MONTH_WINDOW })
    var monthText by rememberSaveable { mutableStateOf(currentMonth.toString()) }
    var selectedDate by rememberSaveable { mutableStateOf(today.toString()) }
    val month = remember(monthText) { YearMonth.parse(monthText) }
    val scope = rememberCoroutineScope()

    fun monthAt(page: Int): YearMonth =
        currentMonth.plusMonths((page - MONTH_PAGE_BASE).toLong())

    // 滑动落定后把标题和选中日切到那个月
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val target = monthAt(page)
            val text = target.toString()
            if (text == monthText) return@collect
            monthText = text
            selectedDate = if (target == currentMonth) today.toString() else target.atDay(1).toString()
        }
    }

    LaunchedEffect(monthText) { onLoadMonth(monthText) }

    val entries = state.calendarEntries
    val entriesByDate = remember(entries) { entries.groupBy { it.date } }
    val monthEntries = remember(entries, monthText) {
        entries.filter { it.date.startsWith(monthText) }
            .sortedWith(compareBy({ it.date }, { it.id }))
    }
    val monthGroups = remember(monthEntries) {
        monthEntries.groupBy { it.date }.toSortedMap().map { (date, list) -> date to list }
    }
    val dayEntries = entriesByDate[selectedDate].orEmpty()

    val billsConfigured = state.settings.billUrl.isNotBlank()
    val monthTransactions = remember(state.calendarTransactions, state.calendarBillsMonth, monthText) {
        if (state.calendarBillsMonth == monthText) state.calendarTransactions else emptyList()
    }
    val transactionsByDate = remember(monthTransactions) { monthTransactions.groupBy { it.date } }
    val billsReady = state.calendarBillsMonth == monthText && !state.calendarBillsLoading
    val dayTransactions = transactionsByDate[selectedDate].orEmpty()
    val monthExpense = remember(monthTransactions) {
        monthTransactions.filter { it.type == "expense" }.sumOf { plainAmount(it.amount) }
    }
    val monthIncome = remember(monthTransactions) {
        monthTransactions.filter { it.type == "income" }.sumOf { plainAmount(it.amount) }
    }

    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editorDate by rememberSaveable { mutableStateOf(selectedDate) }
    var editorType by rememberSaveable { mutableStateOf(CALENDAR_TRAVEL) }
    var editorEntryId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editorEntry = remember(editorEntryId, entries) {
        editorEntryId?.let { id -> entries.firstOrNull { it.id == id } }
    }
    var deleting by remember { mutableStateOf<CalendarEntry?>(null) }

    fun openNew(date: String, type: String) {
        editorEntryId = null
        editorDate = date
        editorType = type
        editorOpen = true
    }

    fun openEdit(entry: CalendarEntry) {
        editorEntryId = entry.id
        editorDate = entry.date
        editorType = entry.type
        editorOpen = true
    }

    fun goToday() {
        selectedDate = today.toString()
        scope.launch { pagerState.animateScrollToPage(MONTH_PAGE_BASE) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionHeader(
                title = "日历",
                subtitle = "轻点选日期，长按直接新建；流水来自账本，只读展示",
                action = {
                    if (month != currentMonth) {
                        InlineAction("回到今天", { goToday() })
                    }
                },
            )
        }

        item {
            MonthCard(
                month = month,
                today = today,
                selectedDate = selectedDate,
                pagerState = pagerState,
                entriesByDate = entriesByDate,
                billDates = transactionsByDate.keys,
                entryCount = monthEntries.size,
                billsConfigured = billsConfigured,
                billsLoading = state.calendarBillsLoading && state.calendarBillsMonth == monthText,
                expense = monthExpense,
                income = monthIncome,
                monthAt = ::monthAt,
                onSelectDate = { selectedDate = it.toString() },
                onCreate = { openNew(it.toString(), CALENDAR_TRAVEL) },
                onPrev = {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                },
                onNext = {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                },
            )
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = dayLabel(selectedDate),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = daySummary(dayEntries, dayTransactions, billsConfigured),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickAddChip("旅行", Teal, Modifier.weight(1f)) {
                    openNew(selectedDate, CALENDAR_TRAVEL)
                }
                QuickAddChip("重要", MaterialTheme.colorScheme.primary, Modifier.weight(1f)) {
                    openNew(selectedDate, CALENDAR_IMPORTANT)
                }
                QuickAddChip("纪念日", Amber, Modifier.weight(1f)) {
                    openNew(selectedDate, CALENDAR_ANNIVERSARY)
                }
                QuickAddChip("待办", Success, Modifier.weight(1f)) {
                    openNew(selectedDate, CALENDAR_TODO)
                }
            }
        }

        if (dayEntries.isEmpty()) {
            item {
                CreamCard {
                    Text(
                        text = "这天还没有安排。点上面的类型记一条，长按月历里的日期也可以。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            item {
                AgendaCard(
                    entries = dayEntries,
                    onEdit = ::openEdit,
                    onToggleDone = onToggleDone,
                )
            }
        }

        if (billsConfigured) {
            item {
                DayBillsCard(
                    transactions = dayTransactions,
                    loading = state.calendarBillsMonth != monthText || state.calendarBillsLoading,
                    error = state.calendarBillsError?.takeIf { state.calendarBillsMonth == monthText },
                    ready = billsReady,
                    onOpenBills = { onOpenBills(monthText) },
                )
            }
        }

        item {
            SectionHeader(
                title = "本月记录",
                subtitle = monthSummary(monthEntries),
            )
        }

        if (monthGroups.isEmpty()) {
            item {
                CreamCard {
                    Text(
                        text = "这个月还是空白。选中日期，点一个类型就能开始。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(monthGroups, key = { "month-${it.first}" }) { (date, list) ->
                MonthDayGroupCard(
                    date = date,
                    entries = list,
                    today = today,
                    onEdit = ::openEdit,
                    onToggleDone = onToggleDone,
                )
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
                initialType = editorType,
                isPreview = isPreview,
                onSave = { entry ->
                    onSave(entry)
                    runCatching { LocalDate.parse(entry.date) }.getOrNull()?.let { saved ->
                        selectedDate = entry.date
                        monthText = YearMonth.from(saved).toString()
                        scope.launch { pagerState.scrollToPage(MONTH_PAGE_BASE + monthsBetween(currentMonth, YearMonth.from(saved))) }
                    }
                    editorOpen = false
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonthCard(
    month: YearMonth,
    today: LocalDate,
    selectedDate: String,
    pagerState: androidx.compose.foundation.pager.PagerState,
    entriesByDate: Map<String, List<CalendarEntry>>,
    billDates: Set<String>,
    entryCount: Int,
    billsConfigured: Boolean,
    billsLoading: Boolean,
    expense: Double,
    income: Double,
    monthAt: (Int) -> YearMonth,
    onSelectDate: (LocalDate) -> Unit,
    onCreate: (LocalDate) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    CreamCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "${month.year}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${month.monthValue}月",
                    style = MaterialTheme.typography.headlineLarge,
                )
            }
            RoundArrow("‹", onPrev)
            Spacer(Modifier.width(8.dp))
            RoundArrow("›", onNext)
        }

        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
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

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(DAY_CELL_HEIGHT * 6)
                .padding(top = 4.dp),
            beyondViewportPageCount = 1,
        ) { page ->
            MonthPage(
                month = monthAt(page),
                today = today,
                selectedDate = selectedDate,
                entriesByDate = entriesByDate,
                billDates = billDates,
                onSelectDate = onSelectDate,
                onCreate = onCreate,
            )
        }

        ThinDivider(Modifier.padding(top = 10.dp, bottom = 10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "本月 $entryCount 条日程",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (billsConfigured) {
                Text(
                    text = if (billsLoading) {
                        "流水加载中…"
                    } else {
                        "支出 ${money(expense)} · 收入 ${money(income)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RoundArrow(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.size(34.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun MonthPage(
    month: YearMonth,
    today: LocalDate,
    selectedDate: String,
    entriesByDate: Map<String, List<CalendarEntry>>,
    billDates: Set<String>,
    onSelectDate: (LocalDate) -> Unit,
    onCreate: (LocalDate) -> Unit,
) {
    val leading = (month.atDay(1).dayOfWeek.value + 6) % 7
    val days = month.lengthOfMonth()
    Column(Modifier.fillMaxWidth()) {
        for (row in 0 until 6) {
            Row(Modifier.fillMaxWidth()) {
                for (column in 0..6) {
                    val index = row * 7 + column
                    val day = index - leading + 1
                    if (day in 1..days) {
                        val date = month.atDay(day)
                        DayCell(
                            day = day,
                            selected = date.toString() == selectedDate,
                            today = date == today,
                            entries = entriesByDate[date.toString()].orEmpty(),
                            hasBills = date.toString() in billDates,
                            onClick = { onSelectDate(date) },
                            onLongClick = { onCreate(date) },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Box(Modifier.weight(1f).height(DAY_CELL_HEIGHT))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayCell(
    day: Int,
    selected: Boolean,
    today: Boolean,
    entries: List<CalendarEntry>,
    hasBills: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primary
            today -> MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
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
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier
            .height(DAY_CELL_HEIGHT)
            .padding(2.dp)
            .clip(shape)
            .background(background)
            .then(
                if (today && !selected) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), shape)
                } else {
                    Modifier
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = day.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected || today) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor,
        )
        Row(
            modifier = Modifier.height(6.dp).padding(top = 1.dp),
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
        Box(
            Modifier
                .height(4.dp)
                .width(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (hasBills && !selected) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Amber.copy(alpha = 0.85f)),
                )
            }
        }
    }
}

@Composable
private fun dotColor(entry: CalendarEntry, selected: Boolean): Color = when {
    selected -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
    entry.type == CALENDAR_TRAVEL -> Teal
    entry.type == CALENDAR_ANNIVERSARY -> Amber
    entry.type == CALENDAR_TODO -> if (entry.done) Muted.copy(alpha = 0.5f) else Success
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun typeColor(type: String): Color = when (type) {
    CALENDAR_TRAVEL -> Teal
    CALENDAR_ANNIVERSARY -> Amber
    CALENDAR_TODO -> Success
    else -> MaterialTheme.colorScheme.primary
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickAddChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "+ $text",
                style = MaterialTheme.typography.labelLarge,
                color = color,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AgendaCard(
    entries: List<CalendarEntry>,
    onEdit: (CalendarEntry) -> Unit,
    onToggleDone: (Long, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    CreamCard(modifier = modifier) {
        entries.forEachIndexed { index, entry ->
            if (index > 0) ThinDivider(Modifier.padding(vertical = 2.dp))
            AgendaRow(entry = entry, onClick = { onEdit(entry) }, onToggleDone = onToggleDone)
        }
    }
}

@Composable
private fun AgendaRow(
    entry: CalendarEntry,
    onClick: () -> Unit,
    onToggleDone: (Long, Boolean) -> Unit,
) {
    val accent = typeColor(entry.type)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.time.ifBlank { "全天" },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(42.dp),
        )
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (entry.done) TextDecoration.LineThrough else null,
                color = if (entry.done) Muted else MaterialTheme.colorScheme.onSurface,
            )
            val meta = buildString {
                append(calendarTypeLabel(entry.type))
                if (entry.location.isNotBlank()) append(" · ").append(entry.location)
                if (entry.reminder) append(" · 已设提醒")
            }
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (entry.type == CALENDAR_TODO) {
            Spacer(Modifier.width(8.dp))
            TodoCheck(
                done = entry.done,
                color = if (entry.done) Success else Muted,
                onClick = { onToggleDone(entry.id, !entry.done) },
            )
        }
    }
}

@Composable
private fun TodoCheck(done: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .border(1.dp, color, CircleShape)
            .background(if (done) color else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.labelMedium,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.surface,
            )
        }
    }
}

@Composable
private fun DayBillsCard(
    transactions: List<BillTransaction>,
    loading: Boolean,
    error: String?,
    ready: Boolean,
    onOpenBills: () -> Unit,
) {
    val expense = transactions.filter { it.type == "expense" }.sumOf { plainAmount(it.amount) }
    val income = transactions.filter { it.type == "income" }.sumOf { plainAmount(it.amount) }
    CreamCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "账本流水",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "只看不改",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }
        when {
            loading -> Text(
                text = "正在读取这个月的流水…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            error != null -> Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = Danger,
                modifier = Modifier.padding(top = 8.dp),
            )

            ready && transactions.isEmpty() -> Text(
                text = "这天没有账本流水。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            else -> {
                Text(
                    text = "支出 ${money(expense)} · 收入 ${money(income)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                transactions.forEachIndexed { index, txn ->
                    if (index > 0) ThinDivider(Modifier.padding(vertical = 2.dp))
                    DayTransactionRow(txn)
                }
            }
        }
        if (ready) {
            InlineAction("去账单页看全部", onOpenBills)
        }
    }
}

@Composable
private fun DayTransactionRow(txn: BillTransaction) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = txn.merchant.ifBlank { txn.description.ifBlank { "未命名交易" } },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildString {
                if (txn.time.isNotBlank()) append(txn.time).append(" · ")
                append(txn.category.ifBlank { if (txn.type == "income") "收入" else "支出" })
                if (txn.account.isNotBlank()) append(" · ").append(txn.account)
            }
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = signedMoney(txn.amount, txn.type),
            style = MaterialTheme.typography.titleSmall,
            color = if (txn.type == "income") Success else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MonthDayGroupCard(
    date: String,
    entries: List<CalendarEntry>,
    today: LocalDate,
    onEdit: (CalendarEntry) -> Unit,
    onToggleDone: (Long, Boolean) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = groupDateLabel(date, today),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${entries.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AgendaCard(
            entries = entries,
            onEdit = onEdit,
            onToggleDone = onToggleDone,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarEntryEditor(
    date: String,
    initial: CalendarEntry?,
    initialType: String,
    isPreview: Boolean,
    onSave: (CalendarEntry) -> Unit,
    onDelete: (CalendarEntry) -> Unit,
    onClose: () -> Unit,
) {
    var type by rememberSaveable(initial?.id, date, initialType) {
        mutableStateOf(initial?.type ?: initialType)
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
    var time by rememberSaveable(initial?.id, date) {
        mutableStateOf(initial?.time.orEmpty())
    }
    var reminder by rememberSaveable(initial?.id, date) {
        mutableStateOf(initial?.reminder ?: false)
    }
    var done by rememberSaveable(initial?.id, date) {
        mutableStateOf(initial?.done ?: false)
    }
    var timeDialog by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current || isPreview
    val permissionLauncher = if (inPreview) {
        null
    } else {
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionDenied = !granted
        }
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
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TypePill("旅行", Teal, type == CALENDAR_TRAVEL) { type = CALENDAR_TRAVEL }
            TypePill(
                "重要",
                MaterialTheme.colorScheme.primary,
                type == CALENDAR_IMPORTANT,
            ) { type = CALENDAR_IMPORTANT }
            TypePill("纪念日", Amber, type == CALENDAR_ANNIVERSARY) { type = CALENDAR_ANNIVERSARY }
            TypePill("待办", Success, type == CALENDAR_TODO) { type = CALENDAR_TODO }
        }

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            label = { Text("标题") },
            placeholder = { Text(titleHint(type)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = { timeDialog = true },
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Text(
                    text = if (time.isBlank()) "选择时间" else time,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (time.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                )
            }
            if (time.isNotBlank()) {
                InlineAction("清除", {
                    time = ""
                    reminder = false
                })
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "提醒",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(
                checked = reminder,
                onCheckedChange = { checked ->
                    reminder = checked
                    if (checked) {
                        if (time.isBlank()) time = "09:00"
                        if (!inPreview && !CalendarReminder.canNotify(context)) {
                            permissionLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                },
            )
        }
        if (reminder) {
            Text(
                text = if (permissionDenied) {
                    "通知权限没开，到点不会弹提醒。"
                } else {
                    "到 ${time.ifBlank { "09:00" }} 会发一条本地通知。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (permissionDenied) Danger else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 2.dp),
            )
        }

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

        if (type == CALENDAR_TODO) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "已完成",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = done, onCheckedChange = { done = it })
            }
        }

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            label = { Text("备注（可选）") },
            placeholder = { Text("同行人、路线、要买的东西……") },
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
                        time = time.trim(),
                        reminder = reminder && time.isNotBlank(),
                        done = type == CALENDAR_TODO && done,
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

    if (timeDialog) {
        val parsed = runCatching { LocalTime.parse(time.ifBlank { "09:00" }) }
            .getOrDefault(LocalTime.of(9, 0))
        val pickerState = rememberTimePickerState(
            initialHour = parsed.hour,
            initialMinute = parsed.minute,
            is24Hour = true,
        )
        Dialog(onDismissRequest = { timeDialog = false }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("选择时间", style = MaterialTheme.typography.titleMedium)
                    TimePicker(state = pickerState, modifier = Modifier.padding(top = 12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { timeDialog = false }) { Text("取消") }
                        TextButton(
                            onClick = {
                                time = "%02d:%02d".format(pickerState.hour, pickerState.minute)
                                timeDialog = false
                            },
                        ) {
                            Text("确定")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypePill(text: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) color.copy(alpha = 0.16f) else Color.Transparent,
        border = BorderStroke(
            1.dp,
            if (selected) color else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
        )
    }
}

private fun titleHint(type: String): String = when (type) {
    CALENDAR_TRAVEL -> "例如：去京都看枫叶"
    CALENDAR_ANNIVERSARY -> "例如：领证两周年"
    CALENDAR_TODO -> "例如：交房租 / 体检 / 取快递"
    else -> "例如：交房租 / 体检"
}

private fun daySummary(
    entries: List<CalendarEntry>,
    transactions: List<BillTransaction>,
    billsConfigured: Boolean,
): String {
    if (entries.isEmpty() && (!billsConfigured || transactions.isEmpty())) {
        return "这天还没有安排"
    }
    val parts = mutableListOf<String>()
    if (entries.isNotEmpty()) parts += "${entries.size} 条日程"
    if (billsConfigured) {
        val expense = transactions.filter { it.type == "expense" }.sumOf { plainAmount(it.amount) }
        if (transactions.isNotEmpty()) parts += "支出 ${money(expense)}"
    }
    return parts.joinToString(" · ")
}

private fun monthSummary(entries: List<CalendarEntry>): String {
    if (entries.isEmpty()) return "还没有记录"
    val travel = entries.count { it.type == CALENDAR_TRAVEL }
    val anniversary = entries.count { it.type == CALENDAR_ANNIVERSARY }
    val todo = entries.count { it.type == CALENDAR_TODO }
    val important = entries.size - travel - anniversary - todo
    return buildList {
        if (travel > 0) add("旅行 $travel")
        if (important > 0) add("重要 $important")
        if (anniversary > 0) add("纪念日 $anniversary")
        if (todo > 0) add("待办 $todo")
    }.joinToString(" · ").ifBlank { "还没有记录" }
}

private fun monthsBetween(from: YearMonth, to: YearMonth): Int =
    ((to.year - from.year) * 12 + (to.monthValue - from.monthValue))

private fun shiftDate(date: String, days: Long): String =
    runCatching { LocalDate.parse(date).plusDays(days).toString() }.getOrDefault(date)

private fun groupDateLabel(date: String, today: LocalDate): String {
    val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
    val prefix = when (date) {
        today.toString() -> "今天 · "
        today.plusDays(1).toString() -> "明天 · "
        else -> ""
    }
    val weekday = WEEKDAYS[(parsed.dayOfWeek.value + 6) % 7]
    return prefix + dayLabel(date) + " 周" + weekday
}

private fun dayLabel(date: String): String {
    val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
    return DAY_NAME_FORMAT.format(parsed)
}
