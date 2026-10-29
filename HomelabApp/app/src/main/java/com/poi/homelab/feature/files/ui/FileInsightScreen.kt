package com.poi.homelab.feature.files.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.poi.homelab.core.ui.components.CreamCard
import com.poi.homelab.core.ui.components.DarkCard
import com.poi.homelab.core.ui.components.DarkStat
import com.poi.homelab.core.ui.components.EmptyState
import com.poi.homelab.core.ui.components.InlineAction
import com.poi.homelab.core.ui.components.LoadingRow
import com.poi.homelab.core.ui.components.Pill
import com.poi.homelab.core.ui.components.PrimaryButton
import com.poi.homelab.core.ui.components.SecondaryButton
import com.poi.homelab.core.ui.components.ThinDivider
import com.poi.homelab.core.ui.theme.Amber
import com.poi.homelab.core.ui.theme.Coral
import com.poi.homelab.core.ui.theme.Danger
import com.poi.homelab.core.ui.theme.Muted
import com.poi.homelab.core.ui.theme.OnDarkSoft
import com.poi.homelab.core.ui.theme.Success
import com.poi.homelab.core.ui.theme.Teal
import com.poi.homelab.core.ui.theme.Warning
import com.poi.homelab.core.util.fileSize
import com.poi.homelab.feature.ai.ui.MarkdownText
import com.poi.homelab.feature.files.CategoryStat
import com.poi.homelab.feature.files.FileCategory
import com.poi.homelab.feature.files.FileEntry
import com.poi.homelab.feature.files.FileInsight
import com.poi.homelab.feature.files.FileSort
import com.poi.homelab.feature.files.FileView
import com.poi.homelab.navigation.FileUiState
import com.poi.homelab.navigation.UiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val scanTimeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

private val rowDateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

/**
 * 文件洞察：本机可访问文件的概览、筛选列表和 AI 分析。
 * 只读元数据，不打开、不删除、不上传文件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileInsightScreen(
    state: UiState,
    onLoad: () -> Unit,
    onClose: () -> Unit,
    onRootSelected: (String) -> Unit,
    onRootRemoved: (String) -> Unit,
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    onViewChange: (FileView) -> Unit,
    onCategoryChange: (FileCategory?) -> Unit,
    onQueryChange: (String) -> Unit,
    onSortChange: (FileSort) -> Unit,
    onLoadMore: () -> Unit,
    onAnalyze: () -> Unit,
    onShowAnalysis: () -> Unit,
    onCloseAnalysis: () -> Unit,
    isPreview: Boolean = false,
) {
    val files = state.files
    val insight = files.insight
    val context = LocalContext.current
    val rootUris = state.settings.fileTreeUris

    var roots by remember(rootUris, isPreview) {
        mutableStateOf(
            if (isPreview) listOf("content://preview/dcim" to "DCIM") else emptyList(),
        )
    }
    LaunchedEffect(rootUris, isPreview) {
        roots = if (isPreview) {
            listOf("content://preview/dcim" to "DCIM")
        } else {
            val used = mutableListOf<String>()
            rootUris.mapIndexed { index, uri ->
                val base = runCatching {
                    DocumentFile.fromTreeUri(context, Uri.parse(uri))?.name
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: "文件夹 ${index + 1}"
                var name = base
                var suffix = 2
                while (used.contains(name)) {
                    name = "$base ($suffix)"
                    suffix++
                }
                used += name
                uri to name
            }
        }
    }
    val authorized = roots.isNotEmpty()

    val removeRoot: (String) -> Unit = { uri ->
        if (!isPreview) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onRootRemoved(uri)
        }
    }

    val treeLauncher = if (isPreview) {
        null
    } else {
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                onRootSelected(uri.toString())
            }
        }
    }

    if (!isPreview) {
        BackHandler(onBack = onClose)
        LaunchedEffect(Unit) { onLoad() }
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
                    Text("文件洞察", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "本机可访问文件 · ${state.settings.deviceName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ThinDivider()

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    ScopeCard(
                        roots = roots,
                        files = files,
                        onAdd = { treeLauncher?.launch(null) },
                        onRemove = removeRoot,
                        onScan = onScan,
                        onCancelScan = onCancelScan,
                        canAdd = treeLauncher != null,
                    )
                }

                if (files.scanning) {
                    item { ScanProgressCard(files) }
                }

                files.scanError?.let { error ->
                    item {
                        CreamCard {
                            Text("扫描没有完成", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = Danger,
                                modifier = Modifier.padding(top = 5.dp),
                            )
                            SecondaryButton(
                                text = "重试",
                                onClick = onScan,
                                modifier = Modifier.padding(top = 12.dp),
                                enabled = authorized && !files.scanning,
                            )
                        }
                    }
                }

                if (insight != null) {
                    item { OverviewCard(insight) }
                    item { CategoryCard(insight) }
                    if (hasCleanupCandidates(insight)) {
                        item { CleanupCard(insight, onViewChange) }
                    }
                    if (insight.topDirs.isNotEmpty()) {
                        item { TopDirsCard(insight) }
                    }
                    item {
                        AnalysisCard(
                            files = files,
                            onAnalyze = onAnalyze,
                            onShowAnalysis = onShowAnalysis,
                        )
                    }
                    item {
                        FileListControls(
                            files = files,
                            onViewChange = onViewChange,
                            onCategoryChange = onCategoryChange,
                            onQueryChange = onQueryChange,
                            onSortChange = onSortChange,
                        )
                    }
                    if (files.rows.isEmpty()) {
                        item {
                            CreamCard {
                                if (files.loading) {
                                    LoadingRow("正在整理文件列表…")
                                } else {
                                    EmptyState(
                                        if (files.totalMatches == 0) {
                                            "这个筛选条件下没有文件"
                                        } else {
                                            "还没有加载到文件"
                                        },
                                    )
                                }
                            }
                        }
                    }
                    items(files.rows, key = { it.key }) { entry ->
                        FileRow(entry)
                        ThinDivider()
                    }
                    if (files.rows.size < files.totalMatches) {
                        item {
                            SecondaryButton(
                                text = "加载更多（还有 ${files.totalMatches - files.rows.size} 个）",
                                onClick = onLoadMore,
                            )
                        }
                    }
                } else if (!files.scanning) {
                    item {
                        CreamCard {
                            EmptyState("选好范围后点「开始扫描」，这里会显示文件总览。")
                        }
                    }
                }
            }
        }
    }

    if (files.analysisOpen) {
        AnalysisSheet(
            files = files,
            onClose = onCloseAnalysis,
            onAnalyze = onAnalyze,
        )
    }
}

@Composable
private fun ScopeCard(
    roots: List<Pair<String, String>>,
    files: FileUiState,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    canAdd: Boolean,
) {
    val index = files.index
    CreamCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("扫描范围", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (roots.isEmpty()) {
                        "还没有添加文件夹"
                    } else {
                        "已添加 ${roots.size} 个文件夹"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            InlineAction(
                text = "添加文件夹",
                onClick = onAdd,
                enabled = canAdd && !files.scanning,
            )
        }

        if (roots.isNotEmpty()) {
            Column(Modifier.padding(top = 4.dp)) {
                roots.forEachIndexed { i, root ->
                    if (i > 0) ThinDivider(Modifier.padding(vertical = 2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = root.second,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        InlineAction(
                            text = "移除",
                            onClick = { onRemove(root.first) },
                            enabled = !files.scanning,
                        )
                    }
                }
            }
        }

        Text(
            text = "Android 11+ 通常不允许选择内部存储根目录，请逐个添加 DCIM、Download、Documents 等文件夹。" +
                "Android/data、其他应用私有目录无法读取。这里只读元数据，不会删除或上传文件。",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (index != null) {
            Text(
                text = "上次扫描：${timeLabel(index.scannedAt)} · ${index.totalFiles} 个文件" +
                    if (index.truncated) " · 已截断" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (index.truncated) Warning else Muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (roots.isEmpty()) {
                PrimaryButton(
                    text = "添加文件夹",
                    onClick = onAdd,
                    modifier = Modifier.weight(1f),
                    enabled = canAdd,
                )
            } else {
                PrimaryButton(
                    text = when {
                        files.scanning -> "扫描中…"
                        index != null -> "重新扫描"
                        else -> "开始扫描"
                    },
                    onClick = onScan,
                    modifier = Modifier.weight(1f),
                    enabled = !files.scanning,
                    loading = files.scanning,
                )
                if (files.scanning) {
                    SecondaryButton(
                        text = "取消",
                        onClick = onCancelScan,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanProgressCard(files: FileUiState) {
    CreamCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = "已扫描 ${files.scannedFiles} 个文件",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (files.currentDir.isNotBlank()) {
            Text(
                text = "当前：${files.currentDir}",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text(
            text = "扫描期间请保持应用在前台；点「取消」会保留上一次的索引。",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun OverviewCard(insight: FileInsight) {
    DarkCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DarkStat("统计大小", fileSize(insight.totalBytes), Modifier.weight(1f))
            DarkStat("剩余空间", fileSize(insight.freeBytes), Modifier.weight(1f))
            DarkStat("文件数", insight.totalFiles.toString(), Modifier.weight(1f))
        }
        Text(
            text = "范围：${insight.rootName} · ${insight.totalDirs} 个目录 · " +
                "耗时 ${insight.durationMs / 1000} 秒",
            style = MaterialTheme.typography.bodySmall,
            color = OnDarkSoft,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (insight.truncated) {
            Text(
                text = "已达到 5 万个文件上限，结果不完整。",
                style = MaterialTheme.typography.bodySmall,
                color = Amber,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun CategoryCard(insight: FileInsight) {
    CreamCard {
        Text("类型构成", style = MaterialTheme.typography.titleMedium)
        if (insight.categories.isEmpty() || insight.totalBytes <= 0) {
            EmptyState("还没有可统计的文件")
            return@CreamCard
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(50)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            insight.categories.filter { it.bytes > 0 }.forEach { stat ->
                Box(
                    Modifier
                        .weight(stat.bytes.toFloat())
                        .fillMaxHeight()
                        .background(categoryColor(stat.category)),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            insight.categories.forEach { stat ->
                LegendItem(
                    stat = stat,
                    percent = (stat.bytes * 100 / insight.totalBytes).toInt(),
                )
            }
        }
    }
}

@Composable
private fun LegendItem(stat: CategoryStat, percent: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(categoryColor(stat.category)),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = "${stat.category.label} $percent%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CleanupCard(insight: FileInsight, onViewChange: (FileView) -> Unit) {
    CreamCard {
        Text("清理候选", style = MaterialTheme.typography.titleMedium)
        if (insight.duplicateCount > 0) {
            CandidateRow(
                title = "重复文件",
                detail = "${insight.duplicateCount} 组 · 可回收 ${fileSize(insight.duplicateBytes)}",
                onClick = { onViewChange(FileView.Duplicates) },
            )
        }
        if (insight.tempCount > 0) {
            ThinDivider()
            CandidateRow(
                title = "缓存 / 临时文件",
                detail = "${insight.tempCount} 个 · ${fileSize(insight.tempBytes)}",
                onClick = { onViewChange(FileView.Temp) },
            )
        }
        if (insight.oldLargeCount > 0) {
            ThinDivider()
            CandidateRow(
                title = "超大旧文件",
                detail = "${insight.oldLargeCount} 个 · ${fileSize(insight.oldLargeBytes)}",
                onClick = { onViewChange(FileView.OldLarge) },
            )
        }
        Text(
            text = "按文件名、路径和大小推测，删除前请自己确认；应用不会操作文件。",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun CandidateRow(title: String, detail: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = "查看 ›",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun TopDirsCard(insight: FileInsight) {
    CreamCard {
        Text("占空间目录", style = MaterialTheme.typography.titleMedium)
        insight.topDirs.take(5).forEachIndexed { index, dir ->
            if (index > 0) ThinDivider(Modifier.padding(vertical = 2.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = dir.path,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${dir.count} 个文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
                Text(
                    text = fileSize(dir.bytes),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun AnalysisCard(
    files: FileUiState,
    onAnalyze: () -> Unit,
    onShowAnalysis: () -> Unit,
) {
    CreamCard {
        Text("让小砂糖分析", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "只发送统计和文件名，不发送文件内容；分析结果只是一些建议，不会动文件。",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            modifier = Modifier.padding(top = 5.dp),
        )
        files.analysisError?.takeIf { !files.analysisOpen }?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = Danger,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        PrimaryButton(
            text = when {
                files.analysisLoading -> "分析中…"
                files.analysis != null -> "查看上次分析"
                else -> "开始分析"
            },
            onClick = { if (files.analysis != null) onShowAnalysis else onAnalyze() },
            modifier = Modifier.padding(top = 12.dp),
            enabled = files.insight != null && !files.scanning,
            loading = files.analysisLoading,
        )
    }
}

@Composable
private fun FileListControls(
    files: FileUiState,
    onViewChange: (FileView) -> Unit,
    onCategoryChange: (FileCategory?) -> Unit,
    onQueryChange: (String) -> Unit,
    onSortChange: (FileSort) -> Unit,
) {
    Column {
        OutlinedTextField(
            value = files.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索文件名或路径") },
            singleLine = true,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FileView.entries.forEach { view ->
                Pill(
                    text = view.label,
                    selected = files.view == view,
                    onClick = { onViewChange(view) },
                )
            }
        }
        if (files.view == FileView.All) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Pill(
                    text = "全部类型",
                    selected = files.category == null,
                    onClick = { onCategoryChange(null) },
                )
                FileCategory.entries.forEach { category ->
                    Pill(
                        text = category.label,
                        selected = files.category == category,
                        onClick = { onCategoryChange(category) },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FileSort.entries.forEach { sort ->
                    Pill(
                        text = "按${sort.label}",
                        selected = files.sort == sort,
                        onClick = { onSortChange(sort) },
                    )
                }
            }
        }
        Text(
            text = "共 ${files.totalMatches} 个 · 已显示 ${files.rows.size} 个",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun FileRow(entry: FileEntry) {
    val color = categoryColor(entry.category)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = entry.extension.uppercase().take(4).ifBlank { "?" },
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.displayPath,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(fileSize(entry.size), style = MaterialTheme.typography.bodyMedium)
            if (entry.modified > 0) {
                Text(
                    text = rowDateFormat.format(Instant.ofEpochMilli(entry.modified)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalysisSheet(
    files: FileUiState,
    onClose: () -> Unit,
    onAnalyze: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "小砂糖的文件分析",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                InlineAction("关闭", onClose)
            }
            files.insight?.let { insight ->
                Text(
                    text = "基于 ${insight.totalFiles} 个文件 · ${insight.rootName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            when {
                files.analysisLoading -> LoadingRow("正在分析文件统计…")
                files.analysisError != null -> {
                    Text(
                        text = files.analysisError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Danger,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    SecondaryButton(
                        text = "重试",
                        onClick = onAnalyze,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                else -> MarkdownText(
                    text = files.analysis.orEmpty(),
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
            if (!files.analysisLoading) {
                Row(Modifier.padding(top = 18.dp)) {
                    InlineAction("重新分析", onAnalyze)
                }
            }
        }
    }
}

private fun hasCleanupCandidates(insight: FileInsight): Boolean =
    insight.duplicateCount > 0 || insight.tempCount > 0 || insight.oldLargeCount > 0

private fun timeLabel(millis: Long): String =
    if (millis <= 0) "未知" else scanTimeFormat.format(Instant.ofEpochMilli(millis))

@Composable
private fun categoryColor(category: FileCategory): Color = when (category) {
    FileCategory.Image -> Coral
    FileCategory.Video -> Teal
    FileCategory.Audio -> Amber
    FileCategory.Document -> Success
    FileCategory.Archive -> Warning
    FileCategory.Apk -> MaterialTheme.colorScheme.primary
    FileCategory.Other -> Muted
}
