package com.poi.homelab.feature.motion

import android.content.Context
import com.poi.homelab.feature.ai.DeviceTools
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 视频生成业务层：提交任务、轮询、播放地址、保存到相册。 */
class MotionRepository(
    private val client: MotionClient,
    private val device: DeviceTools,
    private val context: Context,
) {

    fun url(): String = MotionPrefs.url(context)

    fun token(): String = MotionPrefs.token(context)

    fun configured(): Boolean = url().isNotBlank()

    fun saveConfig(url: String, token: String) {
        MotionPrefs.save(context, url, token)
    }

    suspend fun health(): MotionHealth = client.health(url(), token())

    suspend fun jobs(limit: Int = 30): List<MotionJob> = client.jobs(url(), token(), limit)

    suspend fun submit(request: MotionRequest): MotionJob = client.create(url(), token(), request)

    suspend fun cancel(id: String): MotionJob = client.cancel(url(), token(), id)

    suspend fun delete(id: String) = client.delete(url(), token(), id)

    fun posterUrl(job: MotionJob): String? =
        job.posterUrl?.takeIf { it.isNotBlank() }?.let { client.absolute(url(), it, token()) }

    fun videoUrl(job: MotionJob): String? =
        job.videoUrl?.takeIf { it.isNotBlank() }?.let { client.absolute(url(), it, token()) }

    suspend fun saveVideo(job: MotionJob): SavedMotion {
        val path = job.videoUrl?.takeIf { it.isNotBlank() }
            ?: throw MotionException("no_result", "这个任务没有可保存的成片")
        val bytes = client.download(url(), token(), path)
        val meta = device.saveVideo(bytes, "mp4")
        return SavedMotion(
            path = meta["path"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            galleryUri = meta["gallery_uri"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }
}
