package com.poi.homelab.feature.photos

import android.app.job.JobParameters
import android.app.job.JobService
import com.poi.homelab.HomelabApplication
import com.poi.homelab.core.util.hasPhotoAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 自动同步放在 JobService 里跑：上传多张照片可能超过广播接收器的 10 秒限制。 */
class PhotoSyncJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        val app = applicationContext as HomelabApplication
        val manual = PhotoAutoSync.isManual(params)
        scope.launch {
            val message = runCatching { sync(app, manual) }
                .getOrElse { "同步失败：${it.message ?: it.javaClass.simpleName}" }
            PhotoSyncStatus.record(app, message)
            jobFinished(params, false)
            stopSelf()
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        scope.coroutineContext.cancelChildren()
        return true
    }

    private suspend fun sync(app: HomelabApplication, manual: Boolean): String {
        val settings = app.container.settings.settings.first()
        if (!manual && !settings.photoAutoSync) return "自动同步已关闭，跳过本次"
        if (settings.cloudreveUrl.isBlank() || settings.cloudreveUser.isBlank() ||
            settings.cloudrevePassword.isBlank()
        ) {
            return "还没有配置 Cloudreve，去 设置 → 连接 填写地址和账密"
        }
        if (!hasPhotoAccess(app)) return "没有相册权限，请到 设置 → 权限 授权相册"

        val photos = app.container.deviceTools.recentPhotos(
            query = "",
            sinceDays = PhotoAutoSync.LOOKBACK_DAYS,
            limit = PhotoAutoSync.MAX_PHOTOS,
        )
        if (photos.isEmpty()) return "最近 ${PhotoAutoSync.LOOKBACK_DAYS} 天没有新照片"

        val result = app.container.cloudreve.uploadAll(
            baseUrl = settings.cloudreveUrl,
            user = settings.cloudreveUser,
            password = settings.cloudrevePassword,
            remoteDir = settings.cloudreveDir.trim().ifBlank { "手机照片" },
            photos = photos,
            readBytes = { app.container.deviceTools.photoBytes(it.uri) },
        )
        val failed = result.failed.firstOrNull()
            ?.let { "，失败 ${result.failed.size} 张（${it.name}：${it.error}）" }
            .orEmpty()
        return "上传 ${result.uploaded.size} 张，跳过 ${result.skipped.size} 张$failed"
    }
}
