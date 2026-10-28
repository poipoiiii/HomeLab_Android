package com.poi.homelab.feature.bills.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.poi.homelab.core.ui.components.CreamCard
import com.poi.homelab.core.ui.components.PrimaryButton
import com.poi.homelab.core.ui.components.SecondaryButton
import com.poi.homelab.core.ui.components.SectionHeader
import com.poi.homelab.core.ui.components.ThinDivider
import com.poi.homelab.core.ui.theme.Danger
import com.poi.homelab.core.ui.theme.Muted
import com.poi.homelab.core.ui.theme.SuccessStrong
import com.poi.homelab.core.ui.theme.Warning
import com.poi.homelab.core.util.typeLabel
import com.poi.homelab.feature.bills.BillCommitResult
import com.poi.homelab.feature.bills.BillImportItem
import com.poi.homelab.feature.bills.BillImportStats
import com.poi.homelab.feature.bills.BillMeta
import com.poi.homelab.feature.bills.BillTransaction
import com.poi.homelab.navigation.BillImportBatch
import com.poi.homelab.navigation.UiState
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.abs

private val IMPORT_MIME_TYPES = arrayOf(
    "text/csv",
    "text/comma-separated-values",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/octet-stream",
)

private val TYPE_OPTIONS = listOf(
    "withdrawal" to "支出",
    "deposit" to "收入",
    "transfer" to "转账",
)

/**
 * 截图 / CSV / XLSX → bill-ai 解析 → 客户端核对 → 整批确认入库。
 *
 * 服务端没有 preview id，核对和编辑状态全部留在本页；关掉页面后由 ViewModel 继续持有。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillImportScreen(
    state: UiState,
    onClose: () -> Unit,
    onLoadMeta: () -> Unit,
    onPickFiles: (List<Uri>, String?) -> Unit,
    onUpdateItem: (Int, BillImportItem) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onCommit: () -> Unit,
    onClear: () -> Unit,
    onRefreshCloud: () -> Unit,
    isPreview: Boolean = false,
) {
    val items = state.billImportItems
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var screenshotAccount by rememberSaveable { mutableStateOf("") }
    var viewerUri by remember { mutableStateOf<String?>(null) }
    val accountChoices = state.billImportMeta?.assets
        ?.takeIf { it.isNotEmpty() }
        ?.map { it.name to it.name }
        .orEmpty()

    val imageLauncher = if (isPreview) {
        null
    } else {
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) {
                onPickFiles(uris, screenshotAccount.trim().ifBlank { null })
            }
        }
    }
    val fileLauncher = if (isPreview) {
        null
    } else {
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) onPickFiles(uris, null)
        }
    }

    if (!isPreview) {
        BackHandler(onBack = onClose)
        LaunchedEffect(Unit) { onLoadMeta() }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClose) { Text("关闭") }
                Column(Modifier.weight(1f)) {
                    Text("导入账单", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "截图 / CSV / XLSX 先解析，核对后再写入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = onClear,
                    enabled = !state.billImportBusy &&
                        (items.isNotEmpty() || state.billImportResult != null),
                ) {
                    Text("清空")
                }
            }
            ThinDivider()

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    CreamCard {
                        Text("从哪来", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "支付宝 / 微信截图可以多选；账单文件支持 CSV 和 XLSX。图片单张不超过 8 MB。",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                        )
                        if (accountChoices.isEmpty()) {
                            OutlinedTextField(
                                value = screenshotAccount,
                                onValueChange = { screenshotAccount = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("截图付款账户（可选）") },
                                placeholder = { Text("账户候选未加载，可手填") },
                                singleLine = true,
                            )
                        } else {
                            ChoiceField(
                                label = "截图付款账户",
                                value = screenshotAccount,
                                options = listOf("" to "不指定（按识别结果）") + accountChoices,
                                onSelect = { screenshotAccount = it },
                            )
                        }
                        Text(
                            text = "本次选的截图会统一记到这个账户；一次选同一账户的截图，CSV/XLSX 以文件内容为准。",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                        )
                        if (screenshotAccount.isNotBlank()) {
                            val last = state.billImportLastSync[screenshotAccount.trim()]
                            Text(
                                text = if (last != null) {
                                    "「$screenshotAccount」上次同步到 $last，从这天之后开始截图即可。"
                                } else {
                                    "「$screenshotAccount」还没有同步记录。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (last != null) SuccessStrong else Muted,
                                modifier = Modifier.padding(bottom = 10.dp),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryButton(
                                text = "选截图",
                                onClick = { imageLauncher?.launch("image/*") },
                                modifier = Modifier.weight(1f),
                                enabled = !state.billImportBusy && imageLauncher != null,
                            )
                            SecondaryButton(
                                text = "选 CSV / XLSX",
                                onClick = { fileLauncher?.launch(IMPORT_MIME_TYPES) },
                                modifier = Modifier.weight(1f),
                                enabled = !state.billImportBusy && fileLauncher != null,
                            )
                        }
                    }
                }

                if (state.billImportLastSync.isNotEmpty()) {
                    item { SyncProgressCard(state.billImportLastSync) }
                }

                if (state.billImportBusy || state.billImportProgress != null) {
                    item {
                        CreamCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = state.billImportProgress ?: "处理中…",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }

                if (state.billImportErrors.isNotEmpty()) {
                    item { ImportErrorsCard(state.billImportErrors) }
                }

                if (state.billImportBatches.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "解析结果",
                            subtitle = "共 ${state.billImportBatches.size} 个文件",
                        )
                    }
                    itemsIndexed(
                        items = state.billImportBatches,
                        key = { index, batch -> "${batch.fileName}-$index" },
                    ) { _, batch ->
                        BatchCard(batch = batch, onViewSource = { viewerUri = it })
                    }
                }

                if (state.billImportCloudLoading ||
                    state.billImportCloudError != null ||
                    state.billImportCloudTransactions.isNotEmpty() ||
                    state.billImportItems.isNotEmpty()
                ) {
                    item {
                        CloudCompareCard(
                            loading = state.billImportCloudLoading,
                            error = state.billImportCloudError,
                            loadedCount = state.billImportCloudTransactions.size,
                            onRefresh = onRefreshCloud,
                        )
                    }
                }

                state.billImportResult?.let { result ->
                    item { CommitResultCard(result) }
                }

                if (items.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = "待核对",
                            subtitle = "共 ${items.size} 笔，点一行修改",
                        )
                    }
                    itemsIndexed(
                        items = items,
                        key = { index, item -> "${item.orderId}-$index" },
                    ) { index, item ->
                        ImportItemCard(
                            item = item,
                            cloudTransactions = state.billImportCloudTransactions,
                            onEdit = { editingIndex = index },
                            onRemove = { onRemoveItem(index) },
                            onViewSource = { viewerUri = it },
                        )
                    }
                } else if (!state.billImportBusy &&
                    state.billImportErrors.isEmpty() &&
                    state.billImportBatches.isEmpty() &&
                    state.billImportResult == null
                ) {
                    item {
                        CreamCard {
                            Text("还没有解析结果", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "先选截图或账单文件，解析完会出现在这里。",
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            if (items.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${items.size} 笔待写入", style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = "确认后写入本地账本",
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted,
                            )
                        }
                        PrimaryButton(
                            text = "确认写入",
                            onClick = onCommit,
                            modifier = Modifier.width(150.dp),
                            enabled = !state.billImportBusy,
                            loading = state.billImportBusy,
                        )
                    }
                }
            }
        }
    }

    val editIndex = editingIndex
    val editItem = editIndex?.let { items.getOrNull(it) }
    if (editIndex != null && editItem != null) {
        BillItemEditorSheet(
            item = editItem,
            meta = state.billImportMeta,
            cloudTransactions = state.billImportCloudTransactions,
            onViewSource = { viewerUri = it },
            onDismiss = { editingIndex = null },
            onSave = { updated ->
                onUpdateItem(editIndex, updated)
                editingIndex = null
            },
        )
    }

    viewerUri?.let { uri ->
        ScreenshotViewer(uri = uri, onClose = { viewerUri = null })
    }
}

@Composable
private fun ImportErrorsCard(errors: List<String>) {
    CreamCard {
        Text("没有全部成功", style = MaterialTheme.typography.titleMedium, color = Warning)
        errors.forEach { error ->
            Text(
                text = "• $error",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun BatchCard(batch: BillImportBatch, onViewSource: (String) -> Unit) {
    CreamCard {
        Text(
            text = batch.fileName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${sourceLabel(batch.source)} · ${batch.itemCount} 笔",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            modifier = Modifier.padding(top = 2.dp),
        )
        batch.imageUri?.let { uri ->
            AsyncImage(
                model = Uri.parse(uri),
                contentDescription = "截图 ${batch.fileName}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onViewSource(uri) },
            )
            Text(
                text = "点击查看原图，双指放大对照",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        batch.stats?.let { stats ->
            StatsSummary(stats)
        }
    }
}

@Composable
private fun StatsSummary(stats: BillImportStats) {
    ThinDivider(Modifier.padding(vertical = 8.dp))
    Text(
        text = "识别 ${stats.included}/${stats.rows} 行 · 跳过状态 ${stats.skippedStatus} · 重复 ${stats.skippedDuplicate}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        text = "支出合计 ${stats.totalOut} · 收入合计 ${stats.totalIn}",
        style = MaterialTheme.typography.bodySmall,
        color = Muted,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun ImportItemCard(
    item: BillImportItem,
    cloudTransactions: List<BillTransaction>,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onViewSource: (String) -> Unit,
) {
    val matches = remember(item, cloudTransactions) {
        similarTransactions(item, cloudTransactions)
    }
    CreamCard(Modifier.clickable(onClick = onEdit)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = typeLabel(item.type),
                        style = MaterialTheme.typography.labelMedium,
                        color = typeColor(item.type),
                    )
                    Text(
                        text = "  ${item.date}${if (item.time.isNotBlank()) " ${item.time}" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
                Text(
                    text = item.merchant.ifBlank {
                        item.description.ifBlank { "未识别商户" }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 3.dp),
                )
                Text(
                    text = buildString {
                        append(item.account.ifBlank { "未选账户" })
                        append(" · ")
                        append(item.category.ifBlank { "未分类" })
                        if (item.type == "transfer" && item.toAccount.isNotBlank()) {
                            append(" → ")
                            append(item.toAccount)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (item.confidence < 0.8) {
                    Text(
                        text = "置信度 ${(item.confidence * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = Warning,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                CloudMatchLines(matches, Modifier.padding(top = 4.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "¥${item.amount}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (item.type == "deposit") SuccessStrong else MaterialTheme.colorScheme.onSurface,
                )
                item.sourceUri?.let { uri ->
                    TextButton(onClick = { onViewSource(uri) }) {
                        Text("原图")
                    }
                }
                TextButton(onClick = onRemove) {
                    Text("删除", color = Danger)
                }
            }
        }
    }
}

@Composable
private fun CloudMatchLines(
    matches: List<BillTransaction>,
    modifier: Modifier = Modifier,
) {
    if (matches.isEmpty()) return
    Column(modifier) {
        Text(
            text = "云端疑似已有 ${matches.size} 笔",
            style = MaterialTheme.typography.labelSmall,
            color = Warning,
        )
        matches.take(2).forEach { tx ->
            Text(
                text = "${tx.date} ${tx.time} · ¥${tx.amount} · " +
                    tx.merchant.ifBlank { tx.description.ifBlank { "未命名" } },
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun SyncProgressCard(lastSync: Map<String, String>) {
    CreamCard {
        Text("账户同步进度", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "上次成功写入到哪一天；下次截图从这天之后开始。",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )
        lastSync.entries.sortedByDescending { it.value }.forEach { (account, date) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = account,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = date,
                    style = MaterialTheme.typography.labelMedium,
                    color = SuccessStrong,
                )
            }
        }
    }
}

@Composable
private fun CloudCompareCard(
    loading: Boolean,
    error: String?,
    loadedCount: Int,
    onRefresh: () -> Unit,
) {
    CreamCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("云端比对", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = when {
                        loading -> "正在拉取云端流水…"
                        error != null -> "拉取失败：$error"
                        loadedCount > 0 -> "已加载 $loadedCount 笔云端流水；下方标记「云端疑似已有」的记录建议先核对。"
                        else -> "没有加载到云端流水。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) Warning else Muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            TextButton(onClick = onRefresh, enabled = !loading) {
                Text("重新比对")
            }
        }
    }
}

@Composable
private fun CommitResultCard(result: BillCommitResult) {
    CreamCard {
        Text("写入结果", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "成功 ${result.committed} 笔 · 重复跳过 ${result.skipped} 笔 · 失败 ${result.failed} 笔",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        result.details.filter { it.status == "failed" }.take(8).forEach { detail ->
            Text(
                text = "${detail.merchant.ifBlank { "未识别商户" }} ${detail.amount}：" +
                    detail.error.ifBlank { "未知原因" },
                style = MaterialTheme.typography.bodySmall,
                color = Danger,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        if (result.failed > 0) {
            Text(
                text = "失败项已保留在待核对里，改完可以再次提交。",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BillItemEditorSheet(
    item: BillImportItem,
    meta: BillMeta?,
    cloudTransactions: List<BillTransaction>,
    onViewSource: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (BillImportItem) -> Unit,
) {
    var draft by remember(item) { mutableStateOf(item) }
    val dateValid = runCatching { LocalDate.parse(draft.date) }.isSuccess
    val amountValid = draft.amount.toDoubleOrNull()?.let { it > 0 } == true
    val transferValid = draft.type != "transfer" ||
        (draft.toAccount.isNotBlank() && draft.toAccount != draft.account)
    val matches = remember(draft, cloudTransactions) {
        similarTransactions(draft, cloudTransactions)
    }
    val accountOptions = meta?.assets
        ?.takeIf { it.isNotEmpty() }
        ?.let { assets -> listOf("" to "不指定") + assets.map { it.name to it.name } }
        .orEmpty()
    val categoryOptions = meta?.categories
        ?.takeIf { it.isNotEmpty() }
        ?.let { categories -> listOf("" to "未分类") + categories.map { it.name to it.name } }
        .orEmpty()
    val toAccountOptions = meta?.assets?.map { it.name to it.name }.orEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            Text("核对记录", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "类型、日期、金额是必填；日期格式 yyyy-MM-dd。",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(top = 4.dp),
            )
            item.sourceUri?.let { uri ->
                AsyncImage(
                    model = Uri.parse(uri),
                    contentDescription = "截图原图",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onViewSource(uri) },
                )
                Text(
                    text = "点击截图放大对照",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            CloudMatchLines(matches, Modifier.padding(top = 8.dp))
            ThinDivider(Modifier.padding(vertical = 12.dp))

            ChoiceField(
                label = "类型",
                value = draft.type,
                options = TYPE_OPTIONS,
                onSelect = { type ->
                    draft = draft.copy(
                        type = type,
                        toAccount = if (type == "transfer") draft.toAccount else "",
                    )
                },
            )
            OutlinedTextField(
                value = draft.date,
                onValueChange = { draft = draft.copy(date = it) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text("日期") },
                singleLine = true,
                isError = !dateValid,
                supportingText = if (dateValid) null else {
                    { Text("格式 yyyy-MM-dd") }
                },
            )
            OutlinedTextField(
                value = draft.time,
                onValueChange = { draft = draft.copy(time = it) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text("时间") },
                placeholder = { Text("HH:mm，可留空") },
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.amount,
                onValueChange = { draft = draft.copy(amount = it) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text("金额") },
                singleLine = true,
                isError = !amountValid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = if (amountValid) null else {
                    { Text("金额必须大于 0") }
                },
            )
            OutlinedTextField(
                value = draft.merchant,
                onValueChange = { draft = draft.copy(merchant = it) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text("商户") },
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.description,
                onValueChange = { draft = draft.copy(description = it) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text("说明") },
                singleLine = true,
            )

            EditableChoice(
                label = "账户",
                value = draft.account,
                options = accountOptions,
                onValueChange = { draft = draft.copy(account = it) },
                onSelect = { draft = draft.copy(account = it) },
            )
            if (draft.type == "transfer") {
                EditableChoice(
                    label = "转入账户",
                    value = draft.toAccount,
                    options = toAccountOptions,
                    onValueChange = { draft = draft.copy(toAccount = it) },
                    onSelect = { draft = draft.copy(toAccount = it) },
                )
                if (draft.toAccount.isNotBlank() && draft.toAccount == draft.account) {
                    Text(
                        text = "转入账户不能和转出账户相同",
                        style = MaterialTheme.typography.bodySmall,
                        color = Danger,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            EditableChoice(
                label = "分类",
                value = draft.category,
                options = categoryOptions,
                onValueChange = { draft = draft.copy(category = it) },
                onSelect = { draft = draft.copy(category = it) },
            )
            OutlinedTextField(
                value = draft.orderId,
                onValueChange = { draft = draft.copy(orderId = it) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text("订单号（用于去重，可留空）") },
                singleLine = true,
                supportingText = {
                    Text("订单号是服务端去重键，改错可能导致重复入库")
                },
            )
            if (draft.confidence < 1.0) {
                Text(
                    text = "识别置信度 ${(draft.confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = Warning,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SecondaryButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = "保存",
                    onClick = {
                        onSave(
                            draft.copy(
                                date = draft.date.trim(),
                                time = draft.time.trim(),
                                amount = draft.amount.trim(),
                                merchant = draft.merchant.trim(),
                                description = draft.description.trim(),
                                account = draft.account.trim(),
                                toAccount = draft.toAccount.trim(),
                                category = draft.category.trim(),
                                orderId = draft.orderId.trim(),
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                    enabled = dateValid && amountValid && transferValid,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun EditableChoice(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onValueChange: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    if (options.isEmpty()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            label = { Text(label) },
            singleLine = true,
        )
    } else {
        ChoiceField(
            label = label,
            value = value,
            options = options,
            onSelect = onSelect,
        )
    }
}

@Composable
private fun ChoiceField(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
        Box(Modifier.padding(top = 4.dp)) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = options.firstOrNull { it.first == value }?.second
                        ?: value.ifBlank { "选择$label" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("▾")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (key, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            onSelect(key)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenshotViewer(uri: String, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = Uri.parse(uri),
                contentDescription = "截图原图",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
            TextButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding(),
            ) {
                Text("关闭", color = Color.White)
            }
            Text(
                text = "双指缩放 / 拖动查看",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            )
        }
    }
}

private val KNOWN_CLOUD_TYPES = setOf("expense", "income", "transfer")

/** 日期相同、金额相同，且时间差不超过 2 小时，就提示云端疑似已有。 */
private fun similarTransactions(
    item: BillImportItem,
    cloud: List<BillTransaction>,
): List<BillTransaction> {
    if (item.date.isBlank()) return emptyList()
    val amount = amountKey(item.amount)
    if (amount.isBlank()) return emptyList()
    val type = importTypeToCloud(item.type)
    return cloud.filter { tx ->
        val cloudType = importTypeToCloud(tx.type)
        tx.date == item.date &&
            amountKey(tx.amount) == amount &&
            (cloudType.isBlank() || type.isBlank() ||
                cloudType !in KNOWN_CLOUD_TYPES || cloudType == type) &&
            timeClose(item.time, tx.time)
    }
}

private fun importTypeToCloud(type: String): String = when (type) {
    "withdrawal" -> "expense"
    "deposit" -> "income"
    else -> type
}

private fun amountKey(value: String): String =
    value.trim().toBigDecimalOrNull()
        ?.abs()
        ?.setScale(2, RoundingMode.HALF_UP)
        ?.toPlainString()
        ?: value.trim()

private fun timeClose(first: String, second: String): Boolean {
    if (first.isBlank() || second.isBlank()) return true
    val a = minutesOf(first) ?: return true
    val b = minutesOf(second) ?: return true
    return abs(a - b) <= 120
}

private fun minutesOf(time: String): Int? {
    val parts = time.trim().split(':')
    if (parts.size < 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].take(2).toIntOrNull() ?: return null
    return hour * 60 + minute
}

private fun typeLabel(type: String): String = when (type) {
    "deposit" -> "收入"
    "transfer" -> "转账"
    else -> "支出"
}

@Composable
private fun typeColor(type: String) = when (type) {
    "deposit" -> SuccessStrong
    "transfer" -> Warning
    else -> MaterialTheme.colorScheme.primary
}

private fun sourceLabel(source: String): String = when {
    source.contains("alipay", ignoreCase = true) -> "支付宝账单"
    source.contains("wechat", ignoreCase = true) || source.contains("wx", ignoreCase = true) -> "微信账单"
    source.isBlank() -> "截图 / 图片"
    else -> source
}
