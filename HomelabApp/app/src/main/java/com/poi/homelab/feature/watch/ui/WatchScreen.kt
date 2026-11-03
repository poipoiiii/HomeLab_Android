package com.poi.homelab.feature.watch.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.poi.homelab.core.ui.components.CreamCard
import com.poi.homelab.core.ui.components.InlineAction
import com.poi.homelab.core.ui.components.LoadingRow
import com.poi.homelab.core.ui.components.Pill
import com.poi.homelab.core.ui.components.PrimaryButton
import com.poi.homelab.core.ui.components.SectionHeader
import com.poi.homelab.core.ui.components.Tag
import com.poi.homelab.core.ui.components.ThinDivider
import com.poi.homelab.core.ui.theme.Danger
import com.poi.homelab.core.ui.theme.Muted
import com.poi.homelab.core.ui.theme.Success
import com.poi.homelab.feature.watch.CbgTarget
import com.poi.homelab.feature.watch.RailStation
import com.poi.homelab.feature.watch.TrainTarget
import com.poi.homelab.feature.watch.WatchKind
import com.poi.homelab.feature.watch.WatchNotifier
import com.poi.homelab.feature.watch.WatchRule
import com.poi.homelab.feature.watch.cbgCategories
import com.poi.homelab.feature.watch.intervalLabel
import com.poi.homelab.feature.watch.seatLabel
import com.poi.homelab.feature.watch.trainSeatOptions
import com.poi.homelab.feature.watch.watchIntervalOptions
import com.poi.homelab.navigation.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WatchScreen(
    state: UiState,
    onTestNotification: () -> Unit,
    onSaveRule: (WatchRule) -> Unit,
    onDeleteRule: (String) -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    onRunNow: (String?) -> Unit,
    onLoadTypes: (Int) -> Unit,
    onLoadStations: () -> Unit,
) {
    val watch = state.watch
    val context = LocalContext.current
    var notifyEnabled by remember { mutableStateOf(WatchNotifier.canNotify(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notifyEnabled = WatchNotifier.canNotify(context) }
    var editorRule by remember { mutableStateOf<WatchRule?>(null) }
    var editorOpen by remember { mutableStateOf(false) }

    LaunchedEffect(watch.rules.size) { notifyEnabled = WatchNotifier.canNotify(context) }

    // 编辑页在同一个 Tab 里，返回键先退出编辑，而不是直接回首页
    BackHandler(enabled = editorOpen) { editorOpen = false }

    if (editorOpen) {
        WatchRuleEditorScreen(
            initial = editorRule ?: WatchRule(target = defaultTarget(watch.types)),
            isNew = editorRule == null,
            types = watch.types,
            typesLoading = watch.typesLoading,
            typesError = watch.typesError,
            stations = watch.stations,
            stationsLoading = watch.stationsLoading,
            stationsError = watch.stationsError,
            onLoadTypes = onLoadTypes,
            onLoadStations = onLoadStations,
            onBack = { editorOpen = false },
            onSave = { rule ->
                onSaveRule(rule)
                editorOpen = false
            },
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionHeader(
                title = "价格监控",
                subtitle = "按规则定时检查在售商品，命中条件后发送系统通知",
                action = {
                    InlineAction(
                        text = "新建规则",
                        onClick = {
                            editorRule = null
                            editorOpen = true
                        },
                    )
                },
            )
        }

        item {
            CreamCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "通知",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Tag(
                        text = if (notifyEnabled) "已开启" else "未开启",
                        color = if (notifyEnabled) Success else Danger,
                    )
                }
                Text(
                    text = "命中后发送系统通知，点击通知可直接回到本应用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InlineAction(
                        text = "发送测试通知",
                        onClick = onTestNotification,
                        enabled = notifyEnabled,
                    )
                    Spacer(Modifier.weight(1f))
                    InlineAction("全部检测一次", { onRunNow(null) }, enabled = !watch.running)
                }
                if (!notifyEnabled) {
                    PrimaryButton(
                        text = "开启通知权限",
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    },
                                )
                            }
                        },
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }

        if (watch.running) {
            item { LoadingRow("正在查询藏宝阁…") }
        }

        val rules = watch.rules
        if (rules.isEmpty()) {
            item {
                CreamCard {
                    Text("尚未创建规则", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "选择商品并设置价格、公示期与星格条件，系统会按设定的间隔检查，" +
                            "命中后发送系统通知。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    PrimaryButton(
                        text = "新建规则",
                        onClick = {
                            editorRule = null
                            editorOpen = true
                        },
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
        } else {
            items(rules.size) { index ->
                val rule = rules[index]
                CreamCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(rule.displayTitle, style = MaterialTheme.typography.titleMedium)
                            if (!rule.isTrain && rule.target.ownerLabel.isNotBlank()) {
                                Text(
                                    text = rule.target.ownerLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { onToggleRule(rule.id, it) },
                        )
                    }
                    Text(
                        text = rule.filterTags().joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text(
                        text = lastRunLabel(rule),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    ThinDivider(Modifier.padding(top = 10.dp, bottom = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InlineAction(
                            text = if (watch.runningRuleId == rule.id) "检测中…" else "立即检测",
                            onClick = { onRunNow(rule.id) },
                            enabled = !watch.running,
                        )
                        InlineAction(
                            text = "编辑",
                            onClick = {
                                editorRule = rule
                                editorOpen = true
                            },
                        )
                        Spacer(Modifier.weight(1f))
                        InlineAction("删除", { onDeleteRule(rule.id) }, color = Danger)
                    }
                }
            }
        }

        item {
            Text(
                text = "说明：保存规则后会立即检测一次并通知当前命中的商品，之后按间隔检查，只提醒新增条目。" +
                    "仅读取公开在售列表，不涉及登录与交易操作；最小间隔 15 分钟。",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
}

private fun defaultTarget(types: List<CbgTarget>): CbgTarget =
    types.firstOrNull() ?: CbgTarget(kindId = 3, searchType = "role_skin")

private fun lastRunLabel(rule: WatchRule): String {
    if (rule.lastRunAt <= 0L) return "尚未检测"
    val stamp = SimpleDateFormat("M-d HH:mm", Locale.CHINA).format(Date(rule.lastRunAt))
    val result = rule.lastResult.ifBlank { "已检测" }
    return "$stamp · $result"
}

/**
 * 规则编辑用整页而不是底部弹窗：底部弹窗在拉起输入法时会被顶起来，还会和外层滚动打架。
 * 整页只有一个 LazyColumn，输入时只重绘当前项。
 */
@Composable
private fun WatchRuleEditorScreen(
    initial: WatchRule,
    isNew: Boolean,
    types: List<CbgTarget>,
    typesLoading: Boolean,
    typesError: String?,
    stations: List<RailStation>,
    stationsLoading: Boolean,
    stationsError: String?,
    onLoadTypes: (Int) -> Unit,
    onLoadStations: () -> Unit,
    onBack: () -> Unit,
    onSave: (WatchRule) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var kind by remember { mutableStateOf(initial.kind) }
    var kindId by remember { mutableStateOf(initial.target.kindId) }
    var train by remember { mutableStateOf(initial.train) }
    var dailyHour by remember { mutableStateOf(initial.dailyHour) }
    var minPrice by remember { mutableStateOf(initial.minPriceYuan?.toString().orEmpty()) }
    var maxPrice by remember { mutableStateOf(initial.maxPriceYuan?.toString().orEmpty()) }
    var title by remember { mutableStateOf(initial.title) }
    var pickerOpen by remember { mutableStateOf(false) }
    var stationPicker by remember { mutableStateOf("") }

    LaunchedEffect(kindId) { onLoadTypes(kindId) }
    LaunchedEffect(kind) { if (kind == WatchKind.TrainTicket) onLoadStations() }

    val isTrain = kind == WatchKind.TrainTicket
    val canSave = if (isTrain) train.valid else draft.target.equipType.isNotBlank()
    val commit: () -> Unit = {
        onSave(
            draft.copy(
                kind = kind,
                train = train,
                dailyHour = dailyHour,
                // 火车票规则不保留藏宝阁商品，避免卡片上出现「红 · 卫青」这类残留
                target = if (isTrain) CbgTarget() else draft.target,
                title = title.trim(),
                minPriceYuan = minPrice.toIntOrNull(),
                maxPriceYuan = maxPrice.toIntOrNull(),
                lastRunAt = 0L,
                lastHitAt = 0L,
                lastResult = "",
                seen = emptyList(),
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InlineAction("返回", onBack)
            Text(
                text = if (isNew) "新建规则" else "编辑规则",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
            InlineAction("保存", commit, enabled = canSave)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 40.dp),
        ) {
            item {
                EditorLabel("监控类型")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("藏宝阁商品", !isTrain, onClick = { kind = WatchKind.CbgEquip })
                    Pill("火车票", isTrain, onClick = {
                        kind = WatchKind.TrainTicket
                        if (dailyHour == null) dailyHour = 8
                    })
                }
            }

            if (isTrain) {
                item {
                    EditorLabel("出发站", top = 20.dp)
                    StationRow(train.fromName, "选择出发站") { stationPicker = "from" }
                }
                item {
                    EditorLabel("到达站", top = 20.dp)
                    StationRow(train.toName, "选择到达站") { stationPicker = "to" }
                }
                item {
                    EditorLabel("席别", top = 20.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        trainSeatOptions.forEach { (code, label) ->
                            Pill(label, train.seat == code, onClick = {
                                train = train.copy(seat = code)
                            })
                        }
                    }
                }
                item {
                    EditorLabel("检测时间", top = 20.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(7, 8, 9, 10).forEach { hour ->
                            Pill("%02d:00".format(hour), dailyHour == hour, onClick = {
                                dailyHour = hour
                            })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("按间隔", dailyHour == null, onClick = { dailyHour = null })
                    }
                    Text(
                        text = "每天到这个点查一次最近假期的首日前一天和末日次日，两个日期各取最低价。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            } else {
                item {
                    EditorLabel("商品类目", top = 20.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        cbgCategories.forEach { category ->
                            Pill(
                                text = category.label,
                                selected = category.kindId == kindId,
                                onClick = {
                                    if (kindId != category.kindId) {
                                        kindId = category.kindId
                                        draft = draft.copy(target = CbgTarget(kindId = category.kindId))
                                    }
                                },
                            )
                        }
                    }
                }

                item {
                    EditorLabel("商品", top = 20.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            .clickable { pickerOpen = true }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = draft.target.equipTypeName.ifBlank { "选择商品" },
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (canSave) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    Muted
                                },
                            )
                            Text(
                                text = draft.target.ownerLabel.ifBlank {
                                    when {
                                        typesLoading -> "正在读取列表…"
                                        typesError != null -> "列表读取失败，重试请返回再进入"
                                        else -> "点击选择要监控的商品"
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Text(
                            text = "选择",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                item {
                    EditorLabel("价格区间（元，留空不限）", top = 20.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = minPrice,
                            onValueChange = { minPrice = it.filter(Char::isDigit).take(7) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("最低") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        Text("—", color = Muted)
                        OutlinedTextField(
                            value = maxPrice,
                            onValueChange = { maxPrice = it.filter(Char::isDigit).take(7) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("最高") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }

                item {
                    EditorLabel("公示期", top = 20.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("不限", draft.fairShow == null, onClick = { draft = draft.copy(fairShow = null) })
                        Pill("只看公示期", draft.fairShow == 1, onClick = { draft = draft.copy(fairShow = 1) })
                        Pill("排除公示期", draft.fairShow == 0, onClick = { draft = draft.copy(fairShow = 0) })
                    }
                }

                item {
                    EditorLabel("星格", top = 20.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("不限", draft.star == null, onClick = { draft = draft.copy(star = null) })
                        (1..4).forEach { star ->
                            Pill("$star" + "星", draft.star == star, onClick = {
                                draft = draft.copy(star = star)
                            })
                        }
                    }
                }

                item {
                    EditorLabel("检测间隔", top = 20.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        watchIntervalOptions.take(4).forEach { minutes ->
                            Pill(intervalLabel(minutes), draft.intervalMinutes == minutes, onClick = {
                                draft = draft.copy(intervalMinutes = minutes)
                            })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        watchIntervalOptions.drop(4).forEach { minutes ->
                            Pill(intervalLabel(minutes), draft.intervalMinutes == minutes, onClick = {
                                draft = draft.copy(intervalMinutes = minutes)
                            })
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    singleLine = true,
                    label = { Text("规则名（可留空）") },
                    placeholder = { Text(draft.target.equipTypeName) },
                )
                Text(
                    text = if (isTrain) {
                        "每天按设定时间查询 12306，取最近假期首日前一天和末日次日的该席别最低价。"
                    } else {
                        "价格按元填写，提交时换算为分；星格 1–4 星与藏宝阁网页筛选一致。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(top = 10.dp),
                )
                PrimaryButton(
                    text = "保存",
                    onClick = commit,
                    modifier = Modifier.padding(top = 20.dp),
                    enabled = canSave,
                )
            }
        }
    }

    if (pickerOpen) {
        CbgTypePicker(
            types = types,
            loading = typesLoading,
            error = typesError,
            selected = draft.target.equipType,
            onSelect = { target ->
                draft = draft.copy(target = target)
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }

    if (stationPicker.isNotEmpty()) {
        StationPicker(
            title = if (stationPicker == "from") "选择出发站" else "选择到达站",
            stations = stations,
            loading = stationsLoading,
            error = stationsError,
            selected = if (stationPicker == "from") train.fromCode else train.toCode,
            onSelect = { station ->
                train = if (stationPicker == "from") {
                    train.copy(fromCode = station.code, fromName = station.name)
                } else {
                    train.copy(toCode = station.code, toName = station.name)
                }
                stationPicker = ""
            },
            onDismiss = { stationPicker = "" },
        )
    }
}

@Composable
private fun StationRow(name: String, placeholder: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name.ifBlank { placeholder },
            style = MaterialTheme.typography.bodyLarge,
            color = if (name.isBlank()) Muted else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "选择",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun EditorLabel(text: String, top: Dp = 0.dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = top, bottom = 8.dp),
    )
}

/** 商品选择器单独弹窗，避免和外层滚动嵌套。 */
@Composable
private fun CbgTypePicker(
    types: List<CbgTarget>,
    loading: Boolean,
    error: String?,
    selected: String,
    onSelect: (CbgTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(types, query) {
        if (query.isBlank()) {
            types
        } else {
            types.filter {
                it.equipTypeName.contains(query, ignoreCase = true) ||
                    it.equipTypeDesc.contains(query, ignoreCase = true)
            }
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("选择商品", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    singleLine = true,
                    label = { Text("搜索名称或英雄") },
                )
                when {
                    loading -> LoadingRow("正在读取商品列表…")
                    error != null -> Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = Danger,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    filtered.isEmpty() -> Text(
                        text = "没有匹配的商品",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    else -> LazyColumn(
                        modifier = Modifier.heightIn(max = 380.dp).padding(top = 8.dp),
                    ) {
                        items(filtered.size) { index ->
                            val type = filtered[index]
                            val active = type.equipType == selected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onSelect(type) }
                                    .padding(horizontal = 10.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = type.equipTypeName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (active) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    if (type.ownerLabel.isNotBlank()) {
                                        Text(
                                            text = type.ownerLabel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Muted,
                                        )
                                    }
                                }
                                type.minPriceYuan?.let {
                                    Text(
                                        text = "¥$it 起",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    InlineAction("关闭", onDismiss)
                }
            }
        }
    }
}

/** 车站选择器：12306 的车站表有 3000 多条，只展示匹配的前 80 条。 */
@Composable
private fun StationPicker(
    title: String,
    stations: List<RailStation>,
    loading: Boolean,
    error: String?,
    selected: String,
    onSelect: (RailStation) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(stations, query) {
        if (query.isBlank()) {
            stations.take(60)
        } else {
            val keyword = query.trim()
            stations.filter {
                it.name.contains(keyword) ||
                    it.pinyin.contains(keyword.lowercase()) ||
                    it.code.equals(keyword, ignoreCase = true)
            }.take(80)
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    singleLine = true,
                    label = { Text("搜索车站名或拼音") },
                )
                when {
                    loading -> LoadingRow("正在读取车站列表…")
                    error != null -> Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = Danger,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    filtered.isEmpty() -> Text(
                        text = "没有匹配的车站",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    else -> LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp).padding(top = 8.dp),
                    ) {
                        items(filtered.size) { index ->
                            val station = filtered[index]
                            val active = station.code == selected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onSelect(station) }
                                    .padding(horizontal = 10.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = station.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (active) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = station.code,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Muted,
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    InlineAction("关闭", onDismiss)
                }
            }
        }
    }
}
