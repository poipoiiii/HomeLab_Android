package com.poi.homelab.feature.photos

import android.content.Context
import com.poi.homelab.HomelabApplication
import com.poi.homelab.core.util.hasPhotoAccess
import kotlinx.coroutines.flow.first

/** 一次同步的结果：给用户看的摘要，以及是否算“跑成功”（看门狗据此判断要不要补跑）。 */
data class PhotoSyncResult(val message: String, val success: Boolean)

/** 手动上传和每日定时任务共用的一次同步执行体。 */
object PhotoSync {

    suspend fun run(
        context: Context,
        manual: Boolean,
        onProgress: (done: Int, total: Int, written: Long, totalBytes: Long) -> Unit =
            { _, _, _, _ -> },
    ): PhotoSyncResult {
        val app = context.applicationContext as? HomelabApplication
            ?: return PhotoSyncResult("同步失败：应用上下文不可用", false)
        val settings = app.container.settings.settings.first()
        if (!manual && !settings.photoAutoSync) return PhotoSyncResult("自动同步已关闭，跳过本次", false)
        if (settings.cloudreveUrl.isBlank() || settings.cloudreveUser.isBlank() ||
            settings.cloudrevePassword.isBlank()
        ) {
            return PhotoSyncResult("还没有配置 Cloudreve，去 设置 → 连接 填写地址和账密", false)
        }
        if (!hasPhotoAccess(app)) return PhotoSyncResult("没有相册权限，请到 设置 → 权限 授权相册", false)

        val days = settings.photoSyncDays.coerceIn(1, 30)
        val limit = settings.photoSyncLimit.coerceIn(1, 500)
        val photos = app.container.deviceTools.recentPhotos("", days, limit)
        if (photos.isEmpty()) return PhotoSyncResult("最近 $days 天没有新照片", true)

        val result = app.container.cloudreve.uploadAll(
            baseUrl = settings.cloudreveUrl,
            user = settings.cloudreveUser,
            password = settings.cloudrevePassword,
            remoteDir = settings.cloudreveDir.trim().ifBlank { "手机照片" },
            photos = photos,
            deviceName = settings.deviceName,
            readBytes = { app.container.deviceTools.photoBytes(it.uri) },
            onProgress = onProgress,
        )
        val failed = result.failed.firstOrNull()
            ?.let { "，失败 ${result.failed.size} 张（${it.name}：${it.error}）" }
            .orEmpty()
        return PhotoSyncResult(
            "上传 ${result.uploaded.size} 张，跳过 ${result.skipped.size} 张$failed",
            result.failed.isEmpty(),
        )
    }
}
