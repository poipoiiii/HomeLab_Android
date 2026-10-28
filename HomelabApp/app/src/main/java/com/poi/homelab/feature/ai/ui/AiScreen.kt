package com.poi.homelab.feature.ai.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.poi.homelab.core.ui.components.CreamCard
import com.poi.homelab.core.ui.components.InlineAction
import com.poi.homelab.core.ui.components.LoadingRow
import com.poi.homelab.core.ui.components.PrimaryButton
import com.poi.homelab.core.ui.theme.Danger
import com.poi.homelab.feature.ai.ChatMessage
import com.poi.homelab.feature.ai.requiresKey
import com.poi.homelab.feature.ai.ui.MarkdownText
import com.poi.homelab.navigation.PendingUpload
import com.poi.homelab.navigation.UiState
import com.poi.homelab.R
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiScreen(
    state: UiState,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onRefreshBalance: () -> Unit = {},
    onChooseUploadDir: (String) -> Unit = {},
    onCancelUpload: () -> Unit = {},
    isPreview: Boolean = false,
) {
    val settings = state.settings
    val provider = settings.aiProviders.firstOrNull { it.id == settings.aiSelectedId }
        ?: settings.aiProviders.firstOrNull()
    var input by rememberSaveable { mutableStateOf("") }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var clearConfirm by rememberSaveable { mutableStateOf(false) }
    var previewPhoto by remember { mutableStateOf<String?>(null) }
    var stickToBottom by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val timeline = remember(state.chatMessages) { buildTimeline(state.chatMessages) }

    val needsSetup = provider == null ||
        provider.baseUrl.isBlank() ||
        provider.model.isBlank() ||
        (provider.apiKey.isBlank() && provider.requiresKey)

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collectLatest { (scrolling, canForward) ->
                if (scrolling) stickToBottom = !canForward
            }
    }

    LaunchedEffect(
        timeline.size,
        state.aiBusy,
        state.aiStream.length,
        state.aiSuggestions.size,
        state.aiSuggestBusy,
    ) {
        if (timeline.isEmpty()) return@LaunchedEffect
        val lastRole = state.chatMessages.lastOrNull()?.role
        if (stickToBottom || lastRole == "user") {
            listState.scrollToItem(timeline.lastIndex)
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isNotEmpty() && !state.aiBusy) {
            onSend(text)
            input = ""
        }
    }

    fun jumpTo(message: ChatMessage) {
        val index = timeline.indexOfFirst {
            it is TimelineItem.Message && it.message.id == message.id
        }
        if (index >= 0) {
            stickToBottom = false
            scope.launch { listState.animateScrollToItem(index) }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AiAvatar(size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("小砂糖", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = if (provider == null) {
                        "未配置服务商"
                    } else {
                        buildString {
                            append(provider.name)
                            if (provider.model.isNotBlank()) {
                                append(" · ")
                                append(provider.model)
                            }
                            if (state.chatMessages.isNotEmpty()) {
                                append(" · 本机 ")
                                append(state.chatMessages.size)
                                append(" 条")
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (provider != null) {
                    Text(
                        text = when {
                            state.aiBalanceLoading -> "余额刷新中…"
                            state.aiBalanceError != null ->
                                "余额失败：${state.aiBalanceError}，点击重试"
                            state.aiBalance != null -> "余额 ${state.aiBalance}"
                            else -> "查看余额"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            state.aiBalanceError != null -> Danger
                            state.aiBalance != null -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = !state.aiBalanceLoading && !isPreview) {
                                onRefreshBalance()
                            },
                    )
                    if (state.aiBalance != null && !state.aiBalanceDetail.isNullOrBlank()) {
                        Text(
                            text = state.aiBalanceDetail.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            InlineAction("配置", onOpenAiSettings)
        }

        if (state.chatMessages.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InlineAction("历史记录", { historyOpen = true })
                Spacer(Modifier.weight(1f))
                InlineAction(
                    text = "清空",
                    onClick = { clearConfirm = true },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (needsSetup) {
                    item {
                        CreamCard {
                            Text("先配置 AI 服务商", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = if (provider == null) {
                                    "选一个服务商，再填入 Base URL、模型和 API Key。"
                                } else {
                                    "「${provider.name}」还缺 Base URL、模型或 API Key。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                            )
                            PrimaryButton("打开 AI 配置", onOpenAiSettings)
                        }
                    }
                }

                if (state.chatMessages.isEmpty() && !needsSetup && !state.aiBusy) {
                    item {
                        EmptyPrompt(
                            onSend = {
                                if (state.aiBusy.not()) {
                                    onSend(it)
                                }
                            },
                        )
                    }
                }

                items(items = timeline, key = { it.key }) { item ->
                    when (item) {
                        is TimelineItem.Day -> DayDivider(item.label)
                        is TimelineItem.Message -> ChatBubble(
                            message = item.message,
                            onOpenPhoto = { previewPhoto = it },
                        )
                    }
                }

                if (!state.aiBusy && state.aiSuggestions.isNotEmpty()) {
                    item {
                        SuggestionBlock(
                            suggestions = state.aiSuggestions,
                            onSend = { if (state.aiBusy.not()) onSend(it) },
                        )
                    }
                } else if (!state.aiBusy && state.aiSuggestBusy) {
                    item { LoadingRow("正在想你可能要问的…") }
                }

                if (state.aiBusy) {
                    if (state.aiStream.isBlank()) {
                        item { LoadingRow(state.aiStatus ?: "小砂糖正在想…") }
                    } else {
                        item { StreamingBubble(state.aiStream) }
                    }
                }
            }

            if (!stickToBottom && timeline.isNotEmpty()) {
                Surface(
                    onClick = {
                        stickToBottom = true
                        scope.launch { listState.animateScrollToItem(timeline.lastIndex) }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shadowElevation = 4.dp,
                ) {
                    Text(
                        text = "回到最新",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("问点什么，或让我查实验室…") },
                maxLines = 4,
                enabled = !state.aiBusy,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = { send() },
                enabled = !state.aiBusy && input.isNotBlank(),
                modifier = Modifier.height(56.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("发送")
            }
        }
    }

    if (historyOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { historyOpen = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("对话记录", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "共 ${state.chatMessages.size} 条，保存在本机",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (state.chatMessages.isNotEmpty()) {
                        InlineAction(
                            text = "清空",
                            onClick = {
                                historyOpen = false
                                clearConfirm = true
                            },
                            color = Danger,
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (state.chatMessages.isEmpty()) {
                    Text(
                        text = "还没有对话记录。发一句话就会出现在这里。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                        items(items = state.chatMessages, key = { it.id }) { message ->
                            HistoryRow(
                                message = message,
                                onClick = {
                                    historyOpen = false
                                    jumpTo(message)
                                },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text("清空对话记录？") },
            text = {
                Text(
                    "本机保存的对话和 ADK 会话都会被删除，无法恢复。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearConfirm = false
                        onClear()
                    },
                ) {
                    Text("清空", color = Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }

    state.pendingUpload?.let { pending ->
        UploadFolderSheet(
            pending = pending,
            onConfirm = onChooseUploadDir,
            onCancel = onCancelUpload,
        )
    }

    previewPhoto?.let { uri ->
        PhotoViewer(uri = uri, onClose = { previewPhoto = null })
    }
}

private sealed interface TimelineItem {
    val key: String

    data class Day(override val key: String, val label: String) : TimelineItem
    data class Message(override val key: String, val message: ChatMessage) : TimelineItem
}

private fun buildTimeline(messages: List<ChatMessage>): List<TimelineItem> {
    val timeline = mutableListOf<TimelineItem>()
    var lastDay: String? = null
    messages.forEach { message ->
        val day = dayKey(message.timestamp)
        if (day != lastDay) {
            timeline += TimelineItem.Day("day:$day", dayLabel(message.timestamp))
            lastDay = day
        }
        timeline += TimelineItem.Message("msg:${message.id}", message)
    }
    return timeline
}

private fun dayKey(timestamp: Long): String =
    if (timestamp <= 0L) {
        "unknown"
    } else {
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }

private fun dayLabel(timestamp: Long): String {
    if (timestamp <= 0L) return "更早"
    val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> "${date.monthValue}月${date.dayOfMonth}日"
    }
}

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun timeLabel(timestamp: Long): String =
    if (timestamp <= 0L) {
        ""
    } else {
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(timeFormat)
    }

@Composable
private fun DayDivider(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun EmptyPrompt(onSend: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Text("今天想查什么？", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "实验室、账本、相册和本机文件，都可以问我。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = "只准问我一个人哦。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 3.dp),
        )
        Spacer(Modifier.height(16.dp))
        listOf(
            "看看实验室现在怎么样",
            "这个月账本花了多少",
            "帮我找一张最近的截图",
        ).forEachIndexed { index, prompt ->
            PromptRow(prompt, onSend = { onSend(prompt) })
            if (index < 2) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun SuggestionBlock(suggestions: List<String>, onSend: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(
            text = "还想问我什么？",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        suggestions.forEachIndexed { index, suggestion ->
            PromptRow(suggestion, onSend = { onSend(suggestion) })
            if (index < suggestions.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun PromptRow(text: String, onSend: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onSend)
            .padding(horizontal = 4.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "问这个",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, onOpenPhoto: (String) -> Unit) {
    when (message.role) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.widthIn(max = 300.dp).padding(12.dp),
                )
            }
        }

        "assistant" -> AssistantBubble(message.content)

        "tool" -> ToolTrace(message, onOpenPhoto)

        else -> Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun ToolTrace(message: ChatMessage, onOpenPhoto: (String) -> Unit) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    val canExpand = message.content.length > 90
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.dp))
                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
            Text(
                text = "工具调用 · ${message.toolName ?: "unknown"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            if (message.photos.isNotEmpty()) {
                PhotoStrip(message.photos, onOpenPhoto)
            }
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 3.dp)
                    .then(
                        if (canExpand) {
                            Modifier.clickable { expanded = !expanded }
                        } else {
                            Modifier
                        },
                    ),
            )
            if (canExpand) {
                Text(
                    text = if (expanded) "收起结果" else "展开结果",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clickable { expanded = !expanded },
                )
            }
        }
    }
}

/** 工具找到的照片：横向缩略图，点开看大图。 */
@Composable
private fun PhotoStrip(photos: List<String>, onOpenPhoto: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        photos.forEach { uri ->
            AsyncImage(
                model = uri,
                contentDescription = "照片",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(92.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onOpenPhoto(uri) },
            )
        }
    }
}

@Composable
private fun PhotoViewer(uri: String, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = uri,
                contentDescription = "照片预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(12.dp),
            )
            Text(
                text = "轻触关闭",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
            )
        }
    }
}

/** 上传前选文件夹：列表里的目录直接点，也可以自己填一个新路径。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadFolderSheet(
    pending: PendingUpload,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var dir by remember(pending.seq) { mutableStateOf(pending.defaultDir) }

    ModalBottomSheet(
        onDismissRequest = { if (!pending.busy) onCancel() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Text("上传到哪个文件夹？", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "找到 ${pending.count} 张照片 · 最近 ${pending.sinceDays} 天",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )

            OutlinedTextField(
                value = dir,
                onValueChange = { dir = it },
                label = { Text("云盘目录") },
                singleLine = true,
                enabled = !pending.busy,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Text(
                text = "点下面的文件夹直接选用，也可以手动改成新目录（会自动创建）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            if (pending.foldersLoading) {
                LoadingRow("正在读取 Cloudreve 文件夹…")
            } else {
                if (pending.foldersError != null) {
                    Text(
                        text = "读取文件夹失败：${pending.foldersError}，可以直接手填目录。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Danger,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (pending.folders.isNotEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(top = 4.dp),
                    ) {
                        pending.folders.forEach { folder ->
                            FolderRow(
                                name = folder,
                                selected = dir.trim().trim('/') == folder.trim().trim('/'),
                                enabled = !pending.busy,
                                onClick = { dir = folder },
                            )
                        }
                    }
                }
            }

            if (pending.busy) {
                Row(
                    modifier = Modifier.padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = pending.status ?: "正在上传…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel, enabled = !pending.busy) {
                    Text("取消")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onConfirm(dir) },
                    enabled = !pending.busy && dir.isNotBlank(),
                ) {
                    Text(
                        text = if (pending.busy) {
                            "上传中…"
                        } else {
                            "上传到「${dir.trim().ifBlank { pending.defaultDir }}」"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderRow(
    name: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (selected) "→" else "·",
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(
                text = "选这里",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun AssistantBubble(content: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AiAvatar(size = 28.dp)
        AssistantSurface(Modifier.weight(1f)) {
            MarkdownText(
                text = content,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun StreamingBubble(content: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AiAvatar(size = 28.dp)
        AssistantSurface(Modifier.weight(1f)) {
            MarkdownText(
                text = content,
                color = MaterialTheme.colorScheme.onSurface,
            )
            StreamingCaret(Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun AiAvatar(size: Dp) {
    Image(
        painter = painterResource(R.drawable.ai_avatar),
        contentDescription = "小砂糖",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

@Composable
private fun AssistantSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun StreamingCaret(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "streaming")
    val alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "caret-alpha",
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "小砂糖正在写…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryRow(message: ChatMessage, onClick: () -> Unit) {
    val label = when (message.role) {
        "user" -> "你"
        "assistant" -> "小砂糖"
        "tool" -> "工具"
        else -> "错误"
    }
    val labelColor = when (message.role) {
        "user" -> MaterialTheme.colorScheme.primary
        "assistant" -> MaterialTheme.colorScheme.secondary
        "tool" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> Danger
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
            modifier = Modifier.width(58.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = message.content.ifBlank { "（空）" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = timeLabel(message.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
