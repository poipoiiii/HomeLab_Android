package com.poi.homelab.feature.motion

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 台账模板的一条记录。 */
@Serializable
data class MotionItem(
    val label: String,
    val value: String = "",
    val note: String = "",
)

@Serializable
data class MotionSection(
    val heading: String,
    val caption: String = "",
    val items: List<MotionItem>,
)

/** 导演模式生成的一场戏；字段按类型取用，未用到的保持默认。 */
@Serializable
data class MotionScene(
    val type: String,
    val variant: String = "",
    val title: String = "",
    val subtitle: String = "",
    val caption: String = "",
    val heading: String = "",
    val text: String = "",
    val author: String = "",
    val kicker: String = "",
    val footer: String = "",
    val duration: Double? = null,
    val side: String = "",
    val accent: String = "",
    val keywords: List<String> = emptyList(),
    val items: List<MotionItem> = emptyList(),
    val count: Int? = null,
)

/** 提交给 motion-studio 的任务规格；导演模式用 scenes，手动模式用 sections。 */
@Serializable
data class MotionRequest(
    val template: String = "ledger",
    val theme: String = "ink",
    val resolution: String = "1080p",
    val fps: Int = 30,
    val kicker: String = "",
    val title: String = "",
    val subtitle: String = "",
    val footer: String = "",
    @SerialName("ending_title") val endingTitle: String = "",
    @SerialName("ending_subtitle") val endingSubtitle: String = "",
    val sections: List<MotionSection> = emptyList(),
    val scenes: List<MotionScene> = emptyList(),
    val audio: Boolean = true,
    val verify: Boolean = true,
)

/** 导演的一轮回复：先追问，信息够了再给 plan。 */
@Serializable
data class DirectorReply(
    val status: String = "ask",
    val message: String = "",
    val plan: MotionRequest? = null,
)

data class DirectorTurn(
    val role: String,
    val text: String,
)

@Serializable
data class MotionError(
    val code: String = "",
    val message: String = "",
)

@Serializable
data class MotionChecks(
    val passed: Boolean = true,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class MotionJob(
    val id: String,
    val status: String,
    val stage: String = "",
    val progress: Double = 0.0,
    @SerialName("frames_done") val framesDone: Int = 0,
    @SerialName("frames_total") val framesTotal: Int = 0,
    @SerialName("queue_position") val queuePosition: Int = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("started_at") val startedAt: Long? = null,
    @SerialName("finished_at") val finishedAt: Long? = null,
    @SerialName("elapsed_ms") val elapsedMs: Long = 0,
    val template: String = "ledger",
    val theme: String = "ink",
    val title: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    @SerialName("duration_ms") val durationMs: Long = 0,
    val bytes: Long = 0,
    @SerialName("video_url") val videoUrl: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    val checks: MotionChecks? = null,
    val error: MotionError? = null,
) {
    val active: Boolean
        get() = status == "queued" || status == "running" || status == "cancel_requested"

    val succeeded: Boolean
        get() = status == "succeeded"
}

@Serializable
data class MotionJobPage(
    val jobs: List<MotionJob> = emptyList(),
    @SerialName("next_cursor") val nextCursor: Long? = null,
)

@Serializable
data class MotionQueue(
    val running: Int = 0,
    val queued: Int = 0,
    val capacity: Int = 0,
)

@Serializable
data class MotionHealth(
    val status: String = "",
    val version: String = "",
    val ffmpeg: Boolean = false,
    @SerialName("cjk_fonts") val cjkFonts: Boolean = false,
    val queue: MotionQueue = MotionQueue(),
)

/** 已保存到相册的成片。 */
data class SavedMotion(
    val path: String,
    val galleryUri: String,
)

/** 每行一条：名称 | 数值 | 备注（后两项可省略）。 */
fun parseMotionItems(text: String): List<MotionItem> {
    return text.lineSequence().mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty()) return@mapNotNull null
        val parts = line.split('|', '｜').map { it.trim() }
        val label = parts.getOrNull(0).orEmpty().take(40)
        if (label.isEmpty()) return@mapNotNull null
        MotionItem(
            label = label,
            value = parts.getOrNull(1).orEmpty().take(20),
            note = parts.getOrNull(2).orEmpty().take(80),
        )
    }.take(40).toList()
}

fun motionStatusLabel(job: MotionJob): String = when (job.status) {
    "queued" -> if (job.queuePosition > 0) "排队中 · 前面 ${job.queuePosition} 个" else "排队中"
    "running" -> if (job.progress > 0) "渲染中 ${(job.progress * 100).toInt()}%" else "准备中"
    "cancel_requested" -> "正在取消"
    "succeeded" -> if (job.elapsedMs > 0) "已完成 · ${job.elapsedMs / 1000}s" else "已完成"
    "failed" -> "失败"
    "canceled" -> "已取消"
    else -> job.status
}
