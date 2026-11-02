package com.poi.homelab.feature.imageedit

import com.poi.homelab.core.settings.AppSettings
import com.poi.homelab.feature.ai.DeviceTools
import com.poi.homelab.feature.ai.PhotoFile
import com.poi.homelab.feature.photos.CloudreveRepository
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 修图业务层：压缩上传、提交任务、轮询、保存相册、转发 Cloudreve。
 *
 * 图片只发给家里的网关；这里不记录 base64、Token 或指令原文之外的内容。
 */
class ImageEditRepository(
    private val client: ImageEditClient,
    private val device: DeviceTools,
    private val store: ImageEditStore,
    private val cloudreve: CloudreveRepository,
) {

    fun configured(settings: AppSettings): Boolean =
        settings.imageEditUrl.isNotBlank() && settings.imageEditToken.isNotBlank()

    suspend fun health(settings: AppSettings): ImageEditHealth =
        client.health(settings.imageEditUrl, settings.imageEditToken)

    fun thumbUrl(settings: AppSettings, path: String?): String? {
        val clean = path?.takeIf { it.isNotBlank() } ?: return null
        val url = client.absolute(settings.imageEditUrl, clean)
        if (settings.imageEditToken.isBlank()) return url
        // 结果图由 Coil 直接加载，带不上 Authorization 头，只能用 ?token=
        val token = URLEncoder.encode(settings.imageEditToken.trim(), "UTF-8")
        return url + (if (url.contains('?')) "&" else "?") + "token=$token"
    }

    // ---------------------------------------------------------------- 提交
    suspend fun submit(
        settings: AppSettings,
        uris: List<String>,
        instruction: String,
        options: ImageEditOptions = ImageEditOptions(),
        picked: Boolean = false,
    ): ImageEditJob {
        requireConfigured(settings)
        val targets = uris.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(3)
        if (targets.isEmpty()) throw ImageEditException("no_input", "先选一张照片")
        val images = targets.map { input(settings, it, picked) }
        val ack = client.createJob(
            settings.imageEditUrl,
            settings.imageEditToken,
            ImageEditRequest(
                prompt = instruction.trim(),
                label = instruction.trim().replace("\n", " ").take(20),
                images = images,
                size = options.size,
                steps = options.steps.coerceIn(20, 60),
                seed = options.seed,
                negativePrompt = options.negativePrompt,
                requestId = UUID.randomUUID().toString(),
            ),
        )
        return client.job(settings.imageEditUrl, settings.imageEditToken, ack.id)
    }

    suspend fun submitBatch(
        settings: AppSettings,
        uris: List<String>,
        instruction: String,
        options: ImageEditOptions = ImageEditOptions(),
        picked: Boolean = false,
    ): ImageEditBatchAck {
        requireConfigured(settings)
        val targets = uris.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (targets.size < 2) throw ImageEditException("bad_batch", "批量至少 2 张")
        if (targets.size > 8) throw ImageEditException("bad_batch", "批量最多 8 张")
        val requests = targets.mapIndexed { index, uri ->
            ImageEditRequest(
                prompt = instruction.trim(),
                label = "${instruction.trim().replace("\n", " ").take(16)}-${index + 1}",
                images = listOf(input(settings, uri, picked)),
                size = options.size,
                steps = options.steps.coerceIn(20, 60),
                seed = options.seed,
                negativePrompt = options.negativePrompt,
                requestId = UUID.randomUUID().toString(),
            )
        }
        return client.createBatch(settings.imageEditUrl, settings.imageEditToken, requests)
    }

    // ---------------------------------------------------------------- 轮询
    suspend fun await(
        settings: AppSettings,
        jobId: String,
        timeoutSeconds: Long = 300,
    ): ImageEditJob {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        var latest = client.job(settings.imageEditUrl, settings.imageEditToken, jobId)
        while (latest.active && System.currentTimeMillis() < deadline) {
            delay(2000)
            latest = client.job(settings.imageEditUrl, settings.imageEditToken, jobId)
        }
        return latest
    }

    suspend fun jobs(
        settings: AppSettings,
        status: String = "all",
        limit: Int = 30,
    ): List<ImageEditJob> {
        requireConfigured(settings)
        return client.jobs(settings.imageEditUrl, settings.imageEditToken, status, limit).jobs
    }

    suspend fun job(settings: AppSettings, jobId: String): ImageEditJob =
        client.job(settings.imageEditUrl, settings.imageEditToken, jobId)

    suspend fun cancel(settings: AppSettings, jobId: String): ImageEditJob =
        client.cancel(settings.imageEditUrl, settings.imageEditToken, jobId)

    /** 删除历史任务；服务端会一并清掉结果图与输入缩略图。 */
    suspend fun remove(settings: AppSettings, jobId: String) {
        requireConfigured(settings)
        client.remove(settings.imageEditUrl, settings.imageEditToken, jobId)
        store.forget(jobId)
    }

    // ---------------------------------------------------------------- 保存
    suspend fun saveResult(settings: AppSettings, job: ImageEditJob): SavedEdit {
        val saved = store.savedIds()
        if (job.id in saved) {
            return SavedEdit(job.id, path = "", galleryUri = "", alreadySaved = true)
        }
        val url = job.resultUrl
            ?: throw ImageEditException(
                job.error?.code ?: "no_result",
                job.error?.message?.takeIf { it.isNotBlank() } ?: "这个任务没有可保存的结果",
            )
        val bytes = client.download(settings.imageEditUrl, settings.imageEditToken, url)
        val meta = device.saveImage(bytes, "png")
        val path = meta["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val gallery = meta["gallery_uri"]?.jsonPrimitive?.contentOrNull.orEmpty()
        store.markSaved(job.id)
        return SavedEdit(job.id, path, gallery, alreadySaved = false)
    }

    /** 页面重新进入时补存：只保存成功且没存过的结果。 */
    suspend fun saveAllSucceeded(settings: AppSettings, jobs: List<ImageEditJob>): List<SavedEdit> {
        if (!settings.imageEditAutoSave) return emptyList()
        val saved = store.savedIds()
        return jobs
            .filter { it.succeeded && it.resultUrl != null && it.id !in saved }
            .mapNotNull { job -> runCatching { saveResult(settings, job) }.getOrNull() }
    }

    suspend fun uploadToCloudreve(
        settings: AppSettings,
        job: ImageEditJob,
    ): CloudreveRepository.BatchUploadResult {
        val url = job.resultUrl
            ?: throw ImageEditException("no_result", "这个任务没有可上传的结果")
        require(settings.cloudreveUrl.isNotBlank()) { "先配置 Cloudreve" }
        val bytes = client.download(settings.imageEditUrl, settings.imageEditToken, url)
        val photo = PhotoFile(
            uri = "image-edit://${job.id}",
            name = "ai_${job.id}.png",
            album = "AI 修图",
            addedAt = System.currentTimeMillis() / 1000,
            capturedAt = System.currentTimeMillis() / 1000,
            sizeBytes = bytes.size.toLong(),
            width = job.width,
            height = job.height,
            mime = "image/png",
        )
        return cloudreve.uploadAll(
            baseUrl = settings.cloudreveUrl,
            user = settings.cloudreveUser,
            password = settings.cloudrevePassword,
            remoteDir = settings.cloudreveDir,
            photos = listOf(photo),
            readBytes = { bytes },
        )
    }

    // ---------------------------------------------------------------- 内部
    private suspend fun input(settings: AppSettings, uri: String, picked: Boolean): ImageEditInput {
        val maxSide = settings.imageEditMaxSide.coerceIn(768, 2048)
        val b64 = if (picked) {
            device.encodePickedImage(uri, maxSide)
        } else {
            device.imageBase64(uri, maxSide)
        }
        return ImageEditInput(mime = "image/jpeg", b64 = b64)
    }

    private fun requireConfigured(settings: AppSettings) {
        if (settings.imageEditUrl.isBlank()) {
            throw ImageEditException("not_configured", "还没有配置修图网关，去 设置 → 小砂糖 → AI 修图")
        }
        if (settings.imageEditToken.isBlank()) {
            throw ImageEditException("not_configured", "还没有填写修图网关的 Token")
        }
    }
}
