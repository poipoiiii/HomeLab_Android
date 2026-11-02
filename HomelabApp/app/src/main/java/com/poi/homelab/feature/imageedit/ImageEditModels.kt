package com.poi.homelab.feature.imageedit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 提交给网关的单张参考图，[b64] 是不带 data URI 前缀的纯 base64。 */
@Serializable
data class ImageEditInput(
    val mime: String = "image/jpeg",
    val b64: String,
)

@Serializable
data class ImageEditRequest(
    val prompt: String,
    val label: String? = null,
    val images: List<ImageEditInput>,
    val size: String? = null,
    val steps: Int = 40,
    @SerialName("guidance_scale") val guidanceScale: Double = 1.0,
    @SerialName("negative_prompt") val negativePrompt: String = "",
    val seed: Int? = null,
    @SerialName("request_id") val requestId: String,
)

@Serializable
data class ImageEditError(
    val code: String = "",
    val message: String = "",
)

@Serializable
data class ImageEditJob(
    val id: String,
    @SerialName("batch_id") val batchId: String? = null,
    val label: String = "",
    val status: String,
    val stage: String? = null,
    @SerialName("queue_position") val queuePosition: Int = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("started_at") val startedAt: Long? = null,
    @SerialName("finished_at") val finishedAt: Long? = null,
    @SerialName("elapsed_ms") val elapsedMs: Long = 0,
    @SerialName("input_count") val inputCount: Int = 1,
    @SerialName("input_thumbs") val inputThumbs: List<String> = emptyList(),
    val width: Int = 0,
    val height: Int = 0,
    val bytes: Long = 0,
    @SerialName("thumb_url") val thumbUrl: String? = null,
    @SerialName("result_url") val resultUrl: String? = null,
    val cached: Boolean = false,
    val error: ImageEditError? = null,
) {
    val active: Boolean
        get() = status == "queued" || status == "running" || status == "cancel_requested"

    val succeeded: Boolean
        get() = status == "succeeded"
}

@Serializable
data class ImageEditJobPage(
    val jobs: List<ImageEditJob> = emptyList(),
    @SerialName("next_cursor") val nextCursor: Long? = null,
)

@Serializable
data class ImageEditCreateAck(
    val id: String,
    val status: String,
    @SerialName("queue_position") val queuePosition: Int = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    val deduplicated: Boolean = false,
    val cached: Boolean = false,
)

@Serializable
data class ImageEditBatchAckJob(
    val id: String,
    val status: String,
    @SerialName("queue_position") val queuePosition: Int = 0,
)

@Serializable
data class ImageEditBatchAck(
    @SerialName("batch_id") val batchId: String = "",
    val jobs: List<ImageEditBatchAckJob> = emptyList(),
)

@Serializable
data class ImageEditQueue(
    val running: Int = 0,
    val queued: Int = 0,
    val capacity: Int = 0,
)

@Serializable
data class ImageEditHealth(
    val status: String = "",
    val model: String = "",
    val backend: String = "",
    val queue: ImageEditQueue = ImageEditQueue(),
    @SerialName("gpu_vram_used_mb") val vramUsedMb: Int? = null,
    val detail: String = "",
)

/** 一次修图的可调参数，独立页与 Agent 工具共用。 */
data class ImageEditOptions(
    val size: String? = null,
    val steps: Int = 40,
    val seed: Int? = null,
    val negativePrompt: String = "",
)

/** 已保存到相册的结果。 */
data class SavedEdit(
    val jobId: String,
    val path: String,
    val galleryUri: String,
    val alreadySaved: Boolean = false,
)
