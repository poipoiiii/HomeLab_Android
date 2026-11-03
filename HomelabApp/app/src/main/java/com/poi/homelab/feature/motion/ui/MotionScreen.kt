package com.poi.homelab.feature.motion.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.poi.homelab.HomelabApplication
import com.poi.homelab.core.ui.components.CreamCard
import com.poi.homelab.core.ui.components.EmptyState
import com.poi.homelab.core.ui.components.InlineAction
import com.poi.homelab.core.ui.components.LoadingRow
import com.poi.homelab.core.ui.components.Pill
import com.poi.homelab.core.ui.components.PrimaryButton
import com.poi.homelab.core.ui.components.SecondaryButton
import com.poi.homelab.core.ui.components.SectionHeader
import com.poi.homelab.core.ui.components.StatusDot
import com.poi.homelab.core.ui.components.ThinDivider
import com.poi.homelab.core.ui.theme.Warning
import com.poi.homelab.feature.motion.DirectorTurn
import com.poi.homelab.feature.motion.MotionJob
import com.poi.homelab.feature.motion.MotionRequest
import com.poi.homelab.feature.motion.MotionScene
import com.poi.homelab.feature.motion.MotionSection
import com.poi.homelab.feature.motion.MotionUiState
import com.poi.homelab.feature.motion.MotionViewModel
import com.poi.homelab.feature.motion.motionStatusLabel
import com.poi.homelab.feature.motion.parseMotionItems
import kotlin.math.roundToInt

private data class SectionDraft(
    val heading: String = "",
    val caption: String = "",
    val items: String = "",
)

private val THEMES = listOf(
    Triple("ink", "墨", "深墨底、米白字、朱红印章，适合台账与纪录片"),
    Triple("paper", "纸", "米纸底、墨黑字、朱红批注，适合榜单与说明书"),
    Triple("signal", "信", "深蓝黑、电光青、琥珀，适合数据播报"),
)

/** 视频页：填内容 → 提交渲染 → 在「任务」里看进度、播放或保存。 */
@Composable
fun MotionScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as HomelabApplication).container
    val vm: MotionViewModel = viewModel(
        factory = MotionViewModel.factory(container.motion, container.settings, container.json),
    )
    val state by vm.state.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        vm.onVisible()
        onDispose { vm.onHidden() }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onVisible() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.onHidden() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        vm.consumeMessage()
    }

    var tab by rememberSaveable { mutableStateOf("director") }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<MotionJob?>(null) }

    LaunchedEffect(state.createdId) {
        if (state.createdId != null) {
            tab = "jobs"
            vm.consumeCreated()
        }
    }

    val activeCount = state.jobs.count { it.active }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "AI-Web-Video",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            InlineAction("设置", onClick = { settingsOpen = true })
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Pill("AI 导演", tab == "director", onClick = { tab = "director" })
            Pill(
                text = if (activeCount > 0) "任务 $activeCount" else "任务",
                selected = tab == "jobs",
                onClick = {
                    tab = "jobs"
                    vm.refresh()
                },
            )
            Pill("手动", tab == "manual", onClick = { tab = "manual" })
        }

        when (tab) {
            "director" -> MotionDirectorTab(
                state = state,
                onSend = vm::sendBrief,
                onConfirm = {
                    if (!state.configured) settingsOpen = true else vm.confirmPlan()
                },
                onDiscard = vm::discardPlan,
                onClear = vm::clearDirector,
            )
            "manual" -> NewMotionTab(
                state = state,
                onOpenSettings = { settingsOpen = true },
                onSubmit = vm::submit,
            )
            else -> MotionJobsTab(
                state = state,
                onRefresh = { vm.refresh() },
                onCancel = vm::cancel,
                onDelete = { deleteTarget = it },
                onSave = vm::saveVideo,
                onPlay = { job ->
                    runCatching {
                        val url = vm.videoUrl(job) ?: error("没有成片地址")
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(url), "video/mp4")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    }.onFailure {
                        Toast.makeText(context, "没有可用的视频播放器", Toast.LENGTH_SHORT).show()
                    }
                },
                posterUrl = vm::posterUrl,
            )
        }
    }

    if (settingsOpen) {
        MotionSettingsSheet(
            state = state,
            onDismiss = { settingsOpen = false },
            onSave = { url, token ->
                vm.saveConfig(url, token)
                settingsOpen = false
            },
            onTest = { url, token -> vm.saveConfig(url, token) },
        )
    }

    deleteTarget?.let { job ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除任务") },
            text = { Text("会一起删除成片与封面，无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.remove(job.id)
                        deleteTarget = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun MotionDirectorTab(
    state: MotionUiState,
    onSend: (String) -> Unit,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
    onClear: () -> Unit,
) {
    var brief by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.directorTurns.size, state.directorLoading, state.pendingPlan) {
        if (state.directorTurns.isNotEmpty()) {
            runCatching {
                listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.directorTurns.isEmpty()) {
                item {
                    DirectorIntro(aiReady = state.aiReady, onPick = onSend)
                }
            }
            items(state.directorTurns.size) { index ->
                DirectorBubble(state.directorTurns[index])
            }
            if (state.directorLoading) {
                item { CreamCard { LoadingRow("正在拆解需求…") } }
            }
            state.pendingPlan?.let { plan ->
                item {
                    DirectorPlanCard(
                        plan = plan,
                        submitting = state.submitting,
                        onConfirm = onConfirm,
                        onDiscard = onDiscard,
                    )
                }
            }
            state.directorError?.let { error ->
                item {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (state.directorTurns.isNotEmpty() && !state.directorLoading) {
                item { InlineAction("清空对话", onClear) }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = brief,
                onValueChange = { brief = it.take(500) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("说一句话，比如：做一条 60 秒的拖延症短片") },
                minLines = 1,
                maxLines = 3,
                shape = RoundedCornerShape(14.dp),
            )
            Box(Modifier.width(84.dp)) {
                PrimaryButton(
                    text = "发送",
                    onClick = {
                        val text = brief.trim()
                        if (text.isNotEmpty()) {
                            onSend(text)
                            brief = ""
                        }
                    },
                    enabled = brief.isNotBlank() && !state.directorLoading && !state.submitting,
                    loading = state.directorLoading,
                )
            }
        }
    }
}

@Composable
private fun DirectorIntro(aiReady: Boolean, onPick: (String) -> Unit) {
    CreamCard {
        Text("一句话，我来拆成分镜", style = MaterialTheme.typography.titleMedium)
        Text(
            "先说主题、时长或语气里的任意一个，缺的我会追问。方案确认之后才开始渲染。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
        )
        listOf(
            "做一条 60 秒的概念介绍片",
            "把我们这周的数据做成分镜",
            "给我的项目做一条片头",
        ).forEach { suggestion ->
            Pill(suggestion, selected = false, onClick = { onPick(suggestion) })
            Spacer(Modifier.height(8.dp))
        }
        if (!aiReady) {
            Text(
                "还没有可用的 AI 服务商，先到 设置 → 小砂糖 → AI 配置 选一个。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun DirectorBubble(turn: DirectorTurn) {
    if (turn.role == "user") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f))
                    .padding(14.dp),
            ) {
                Text(turn.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    } else {
        CreamCard {
            Text(turn.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DirectorPlanCard(
    plan: MotionRequest,
    submitting: Boolean,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
) {
    CreamCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "分镜方案",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = themeName(plan.theme),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = plan.title.ifBlank { "未命名方案" },
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (plan.subtitle.isNotBlank()) {
            Text(
                text = plan.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = "${plan.scenes.size} 个镜头 · 约 ${estimatePlanSeconds(plan)} 秒 · ${plan.resolution}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        ThinDivider(Modifier.padding(vertical = 12.dp))
        plan.scenes.take(16).forEachIndexed { index, scene ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    text = pad2(index + 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(26.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = sceneTypeLabel(scene.type),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = sceneSummary(scene).ifBlank { sceneTypeLabel(scene.type) },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        ThinDivider(Modifier.padding(vertical = 12.dp))
        Text(
            "确认后才会开始渲染；渲染期间可以切到「任务」看进度。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SecondaryButton(
                text = "再改改",
                onClick = onDiscard,
                modifier = Modifier.weight(1f),
                enabled = !submitting,
            )
            PrimaryButton(
                text = if (submitting) "提交中…" else "确认生成",
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                loading = submitting,
            )
        }
    }
}

private fun pad2(value: Int): String = value.toString().padStart(2, '0')

private fun themeName(theme: String): String = when (theme) {
    "paper" -> "纸"
    "signal" -> "信"
    else -> "墨"
}

private fun sceneTypeLabel(type: String): String = when (type) {
    "cover" -> "封面"
    "portrait" -> "人像"
    "section" -> "章节"
    "list" -> "条目"
    "stat" -> "数字"
    "quote" -> "引用"
    "end" -> "结尾"
    else -> type
}

private fun sceneSummary(scene: MotionScene): String = when (scene.type) {
    "cover" -> listOf(scene.kicker, scene.title, scene.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
    "portrait" -> listOf(scene.title, scene.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
    "section" -> listOf(scene.heading, scene.caption).filter { it.isNotBlank() }.joinToString(" · ")
    "list" -> (listOf(scene.heading) + scene.items.take(2).map { it.label })
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    "stat" -> scene.items.joinToString(" · ") { "${it.value} ${it.label}".trim() }
    "quote" -> scene.text
    "end" -> listOf(scene.title, scene.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
    else -> scene.title
}

private fun estimatePlanSeconds(plan: MotionRequest): Int {
    val total = plan.scenes.sumOf { scene ->
        scene.duration ?: when (scene.type) {
            "cover", "portrait", "stat" -> 4.0
            "section" -> 2.5
            "list" -> 3.5
            "quote", "end" -> 4.5
            else -> 3.5
        }
    }
    return total.roundToInt()
}

@Composable
private fun NewMotionTab(
    state: MotionUiState,
    onOpenSettings: () -> Unit,
    onSubmit: (MotionRequest) -> Unit,
) {
    var kicker by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var subtitle by rememberSaveable { mutableStateOf("") }
    var footer by rememberSaveable { mutableStateOf("") }
    var theme by rememberSaveable { mutableStateOf("ink") }
    var resolution by rememberSaveable { mutableStateOf("1080p") }
    var formError by remember { mutableStateOf<String?>(null) }
    val sections = remember { mutableStateListOf(SectionDraft()) }

    fun collectSections(): List<MotionSection>? {
        if (sections.isEmpty()) {
            formError = "至少添加一个章节"
            return null
        }
        val result = mutableListOf<MotionSection>()
        sections.forEachIndexed { index, draft ->
            val items = parseMotionItems(draft.items)
            if (draft.heading.isBlank()) {
                formError = "第 ${index + 1} 章还没有标题"
                return null
            }
            if (items.isEmpty()) {
                formError = "第 ${index + 1} 章还没有条目"
                return null
            }
            result += MotionSection(
                heading = draft.heading.trim().take(32),
                caption = draft.caption.trim().take(80),
                items = items,
            )
        }
        return result
    }

    val estimate = estimateSeconds(sections)
    val selectedTheme = THEMES.firstOrNull { it.first == theme } ?: THEMES.first()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionHeader(
                title = "生成视频",
                subtitle = "标题 + 章节 + 条目，服务端逐帧渲染",
                action = { InlineAction("设置", onOpenSettings) },
            )
        }

        if (!state.configured) {
            item {
                CreamCard {
                    Text("还没有连接视频服务", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "先在设置里填写家里 motion-studio 的地址和 Token。服务渲染完会返回一个 MP4，手机上可以直接播放或保存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                    )
                    SecondaryButton("填写服务设置", onOpenSettings)
                }
            }
            return@LazyColumn
        }

        item {
            CreamCard {
                Text("风格与画质", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    THEMES.forEach { (id, name, _) ->
                        Pill(name, theme == id, onClick = { theme = id })
                    }
                }
                Text(
                    selectedTheme.third,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                ThinDivider(Modifier.padding(vertical = 12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Pill("1080p", resolution == "1080p", onClick = { resolution = "1080p" })
                    Pill("720p", resolution == "720p", onClick = { resolution = "720p" })
                    Text(
                        text = "30fps · 约 $estimate 秒",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                    )
                }
            }
        }

        item {
            CreamCard {
                Text("封面", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = kicker,
                    onValueChange = { if (it.length <= 40) kicker = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    label = { Text("眉标（可留空）") },
                    placeholder = { Text("例如：开盒网暴台账") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 60) title = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    label = { Text("标题") },
                    placeholder = { Text("例如：24 条记录的完整还原") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    supportingText = { Text("${title.length}/60") },
                )
                OutlinedTextField(
                    value = subtitle,
                    onValueChange = { if (it.length <= 120) subtitle = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    label = { Text("副标题（可留空）") },
                    placeholder = { Text("例如：从采集到核验，48 秒看完") },
                    minLines = 2,
                    maxLines = 3,
                    shape = RoundedCornerShape(12.dp),
                )
                OutlinedTextField(
                    value = footer,
                    onValueChange = { if (it.length <= 120) footer = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    label = { Text("页脚（可留空）") },
                    placeholder = { Text("例如：数据来源：本地采集台账") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
            }
        }

        item {
            CreamCard {
                Text("章节与条目", style = MaterialTheme.typography.titleMedium)
                Text(
                    "条目每行一条，用竖线分隔：名称 | 数值 | 备注。数值和备注可以省略。每页最多 5 条，服务端自动分页。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                sections.forEachIndexed { index, draft ->
                    ThinDivider(Modifier.padding(vertical = 12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "第 ${index + 1} 章",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (sections.size > 1) {
                            InlineAction("删除", onClick = { sections.removeAt(index) })
                        }
                    }
                    OutlinedTextField(
                        value = draft.heading,
                        onValueChange = { if (it.length <= 32) sections[index] = draft.copy(heading = it) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("章节标题") },
                        placeholder = { Text("例如：第一章 · 起点") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                    OutlinedTextField(
                        value = draft.caption,
                        onValueChange = { if (it.length <= 80) sections[index] = draft.copy(caption = it) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("章节说明（可留空）") },
                        placeholder = { Text("例如：2026-10-30") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                    OutlinedTextField(
                        value = draft.items,
                        onValueChange = { sections[index] = draft.copy(items = it) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("条目") },
                        placeholder = { Text("首条记录 | #001 | 采集时间 09:12\n关联账号 | 3 个") },
                        minLines = 3,
                        maxLines = 8,
                        shape = RoundedCornerShape(12.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                SecondaryButton(
                    text = "添加章节",
                    onClick = {
                        if (sections.size < 12) sections.add(SectionDraft())
                    },
                )
            }
        }

        item {
            PrimaryButton(
                text = if (state.submitting) "提交中…" else "生成视频",
                onClick = {
                    formError = null
                    val built = collectSections() ?: return@PrimaryButton
                    onSubmit(
                        MotionRequest(
                            theme = theme,
                            resolution = resolution,
                            kicker = kicker.trim(),
                            title = title.trim(),
                            subtitle = subtitle.trim(),
                            footer = footer.trim(),
                            sections = built,
                        ),
                    )
                },
                enabled = title.isNotBlank() && !state.submitting,
                loading = state.submitting,
            )
            (formError ?: state.error)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Text(
                "成片无音轨；单任务最多 120 条、240 秒。任务提交后切后台也会继续渲染。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun MotionJobsTab(
    state: MotionUiState,
    onRefresh: () -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (MotionJob) -> Unit,
    onSave: (String) -> Unit,
    onPlay: (MotionJob) -> Unit,
    posterUrl: (MotionJob) -> String?,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                title = "渲染任务",
                subtitle = "服务端异步执行，切后台不会中断",
                action = { InlineAction("刷新", onRefresh) },
            )
        }
        if (state.loading && state.jobs.isEmpty()) {
            item { CreamCard { LoadingRow("正在读取任务…") } }
        } else if (state.jobs.isEmpty()) {
            item { CreamCard { EmptyState("还没有生成过视频") } }
        } else {
            items(state.jobs.size) { index ->
                MotionJobCard(
                    job = state.jobs[index],
                    poster = posterUrl(state.jobs[index]),
                    saving = state.jobs[index].id in state.savingIds,
                    onCancel = { onCancel(state.jobs[index].id) },
                    onDelete = { onDelete(state.jobs[index]) },
                    onSave = { onSave(state.jobs[index].id) },
                    onPlay = { onPlay(state.jobs[index]) },
                )
            }
        }
        state.error?.let { error ->
            item {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun MotionJobCard(
    job: MotionJob,
    poster: String?,
    saving: Boolean,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSave: () -> Unit,
    onPlay: () -> Unit,
) {
    val context = LocalContext.current
    CreamCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dotColor = when (job.status) {
                "succeeded" -> MaterialTheme.colorScheme.primary
                "failed", "canceled" -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.tertiary
            }
            StatusDot(dotColor)
            Text(
                text = job.title.ifBlank { job.id },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            Text(
                text = motionStatusLabel(job),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (job.succeeded && poster != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(poster).crossfade(true).build(),
                contentDescription = job.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }

        if (job.active) {
            LinearProgressIndicator(
                progress = { job.progress.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }

        val info = buildList {
            if (job.width > 0) add("${job.width}×${job.height}")
            if (job.durationMs > 0) add("${job.durationMs / 1000} 秒")
            if (job.bytes > 0) add("${job.bytes / 1048576} MB")
            if (job.checks != null && !job.checks.passed) add("核验有提示")
        }
        if (info.isNotEmpty()) {
            Text(
                text = info.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        job.error?.message?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        job.checks?.takeIf { !it.passed && it.warnings.isNotEmpty() }?.let { checks ->
            Text(
                text = "核验提示：${checks.warnings.first()}",
                style = MaterialTheme.typography.bodySmall,
                color = Warning,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (job.active || job.succeeded || job.status == "failed" || job.status == "canceled") {
            ThinDivider(Modifier.padding(vertical = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (job.active) {
                    InlineAction("取消", onCancel)
                }
                if (job.succeeded) {
                    InlineAction("播放", onPlay)
                    InlineAction(if (saving) "保存中…" else "保存", onSave)
                }
                if (!job.active) {
                    InlineAction("删除", onDelete)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MotionSettingsSheet(
    state: MotionUiState,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onTest: (String, String) -> Unit,
) {
    var url by rememberSaveable(state.url) { mutableStateOf(state.url) }
    var token by rememberSaveable(state.token) { mutableStateOf(state.token) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "视频服务设置",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                InlineAction("关闭", onDismiss)
            }
            Text(
                "填家里 motion-studio 的地址，默认端口 8095。手机在外面时用 tailnet 地址或 SSH 隧道。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                label = { Text("服务地址") },
                placeholder = { Text("http://100.x.x.x:8095") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                label = { Text("Token") },
                placeholder = { Text("与服务端 MOTION_TOKEN 一致") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(12.dp),
            )

            state.health?.let { health ->
                Text(
                    text = buildString {
                        append("已连接 · v${health.version}")
                        append(" · 队列 ${health.queue.running}/${health.queue.capacity}")
                        if (!health.cjkFonts) append(" · 缺少中文字体")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            state.healthError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SecondaryButton(
                    text = if (state.testing) "测试中…" else "测试连接",
                    onClick = { onTest(url, token) },
                    modifier = Modifier.weight(1f),
                    loading = state.testing,
                )
                PrimaryButton(
                    text = "保存",
                    onClick = { onSave(url, token) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun estimateSeconds(sections: List<SectionDraft>): Int {
    var total = 4.6 + 5.0
    sections.forEach { draft ->
        total += 2.8
        var remaining = parseMotionItems(draft.items).size
        if (remaining == 0) return@forEach
        while (remaining > 0) {
            val count = minOf(5, remaining)
            total += 1.3 + count * 0.56 + 1.2
            remaining -= count
        }
    }
    return total.roundToInt()
}
