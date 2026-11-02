package com.poi.homelab.feature.imageedit.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.poi.homelab.feature.imageedit.ImageEditJob
import com.poi.homelab.feature.imageedit.ImageEditOptions
import com.poi.homelab.feature.imageedit.IMAGE_EDIT_PRESETS
import com.poi.homelab.core.ui.components.CreamCard
import com.poi.homelab.core.ui.components.EmptyState
import com.poi.homelab.core.ui.components.InlineAction
import com.poi.homelab.core.ui.components.LoadingRow
import com.poi.homelab.core.ui.components.Pill
import com.poi.homelab.core.ui.components.SecondaryButton
import com.poi.homelab.core.ui.components.SectionHeader
import com.poi.homelab.core.ui.components.StatusDot
import com.poi.homelab.core.ui.components.ThinDivider

/** 独立修图页：上图 → 写指令 → 提交 → 在「任务」里看进度与结果。 */
@Composable
fun ImageEditScreen(
    jobs: List<ImageEditJob>,
    jobsLoading: Boolean,
    submitting: Boolean,
    error: String?,
    thumbUrl: (String) -> String?,
    onSubmitSingle: (uris: List<String>, instruction: String, options: ImageEditOptions) -> Unit,
    onSubmitBatch: (uris: List<String>, instruction: String, options: ImageEditOptions) -> Unit,
    onRefreshJobs: () -> Unit,
    onCancelJob: (String) -> Unit,
    onSaveJob: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf("edit") }
    var mode by rememberSaveable { mutableStateOf("single") }
    var picked by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var instruction by rememberSaveable { mutableStateOf("") }
    var presetId by rememberSaveable { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(8),
    ) { uris ->
        if (uris.isNotEmpty()) picked = uris.take(8).map { it.toString() }
    }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("AI 修图", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            InlineAction("设置", onOpenSettings)
            InlineAction("关闭", onClose)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Pill("修图", tab == "edit", onClick = { tab = "edit" })
            Pill(
                "任务 ${jobs.count { it.active }.takeIf { it > 0 } ?: ""}".trim(),
                tab == "jobs",
                onClick = {
                    tab = "jobs"
                    onRefreshJobs()
                },
            )
        }

        when (tab) {
            "edit" -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    CreamCard {
                        Text("已选照片", style = MaterialTheme.typography.titleMedium)
                        if (picked.isEmpty()) {
                            Text(
                                "还没选照片。单张模式传 1 张；合成模式可加参考图；批量模式会按同一句指令逐张处理。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                            )
                        } else {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(picked) { uri ->
                                    AsyncImage(
                                        model = uri,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        SecondaryButton(
                            text = if (picked.isEmpty()) "选择照片（最多 8 张）" else "重新选择",
                            onClick = {
                                picker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Pill("单张", mode == "single", onClick = { mode = "single" })
                            Pill("多图合成", mode == "compose", onClick = { mode = "compose" })
                            Pill("批量", mode == "batch", onClick = { mode = "batch" })
                        }
                    }
                }

                item {
                    CreamCard {
                        Text("效果预设", style = MaterialTheme.typography.titleMedium)
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(IMAGE_EDIT_PRESETS) { preset ->
                                Pill(
                                    preset.title,
                                    presetId == preset.id,
                                    onClick = {
                                        presetId = preset.id
                                        instruction = preset.prompt
                                    },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = instruction,
                            onValueChange = { instruction = it.take(2000) },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            label = { Text("修图指令") },
                            placeholder = { Text("例如：把背景换成傍晚海边，保留人物脸部和姿势不变") },
                            minLines = 3,
                            shape = RoundedCornerShape(12.dp),
                        )
                        Text(
                            "图片只会发到家里的修图网关，不经过 AI 服务商。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        SecondaryButton(
                            text = when {
                                submitting -> "提交中…"
                                mode == "batch" -> "提交批量任务"
                                else -> "开始修图"
                            },
                            onClick = {
                                val options = ImageEditOptions()
                                if (mode == "batch") {
                                    onSubmitBatch(picked, instruction, options)
                                } else {
                                    // 单张只取第一张；合成最多 3 张参考图，顺序即图1/图2/图3
                                    val targets = if (mode == "single") picked.take(1) else picked.take(3)
                                    onSubmitSingle(targets, instruction, options)
                                }
                            },
                            enabled = !submitting && picked.isNotEmpty() && instruction.isNotBlank(),
                            loading = submitting,
                        )
                        error?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SectionHeader(
                        title = "修图任务",
                        subtitle = "服务端异步执行，切后台也不会中断",
                        action = { InlineAction("刷新", onRefreshJobs) },
                    )
                }
                if (jobsLoading && jobs.isEmpty()) {
                    item { CreamCard { LoadingRow("正在读取任务…") } }
                } else if (jobs.isEmpty()) {
                    item { CreamCard { EmptyState("还没有修图任务") } }
                } else {
                    items(jobs, key = { it.id }) { job ->
                        JobCard(
                            job = job,
                            thumb = thumbUrl(job.thumbUrl.orEmpty()),
                            onCancel = { onCancelJob(job.id) },
                            onSave = { onSaveJob(job.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JobCard(
    job: ImageEditJob,
    thumb: String?,
    onCancel: () -> Unit,
    onSave: () -> Unit,
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
                text = job.label.ifBlank { job.id },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            Text(
                text = statusLabel(job),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (thumb != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(thumb).crossfade(true).build(),
                contentDescription = job.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(14.dp)),
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

        if (job.active || job.succeeded) {
            ThinDivider(Modifier.padding(vertical = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (job.active) {
                    InlineAction("取消", onCancel)
                }
                if (job.succeeded) {
                    InlineAction("保存到相册", onSave)
                }
            }
        }
    }
}

private fun statusLabel(job: ImageEditJob): String = when (job.status) {
    "queued" -> if (job.queuePosition > 0) "排队中 · 前面 ${job.queuePosition} 个" else "排队中"
    "running" -> "生成中"
    "cancel_requested" -> "正在取消"
    "succeeded" -> if (job.elapsedMs > 0) "已完成 · ${job.elapsedMs / 1000}s" else "已完成"
    "failed" -> "失败"
    "canceled" -> "已取消"
    else -> job.status
}
