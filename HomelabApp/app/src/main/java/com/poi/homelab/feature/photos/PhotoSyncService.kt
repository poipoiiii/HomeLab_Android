package com.poi.homelab.feature.photos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.poi.homelab.MainActivity
import com.poi.homelab.core.util.fileSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 照片同步的前台服务：通知栏常驻一条进度，用户可以随时取消，
 * 息屏或切到别的页面也会继续传（前台服务不受后台限制）。
 */
class PhotoSyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            return START_NOT_STICKY
        }
        val manual = intent?.getBooleanExtra(EXTRA_MANUAL, false) ?: false
        startForeground(NOTIFICATION_ID, buildNotification("准备同步照片…", null, true))
        // 息屏后 CPU 可能挂起，持一把 partial wakelock 保证上传能跑完
        acquireWakeLock()
        job = scope.launch {
            val startedAt = System.currentTimeMillis()
            var lastNotify = 0L
            var success = false
            val message = try {
                val result = PhotoSync.run(this@PhotoSyncService, manual) { done, total, written, totalBytes ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotify < 800 && done < total) return@run
                    lastNotify = now
                    val seconds = ((System.currentTimeMillis() - startedAt) / 1000.0)
                        .coerceAtLeast(1.0)
                    val text = buildString {
                        append("正在上传 ").append(done).append(" / ").append(total).append(" 张")
                        if (totalBytes > 0) {
                            append(" · ").append(fileSize(written))
                            append(" / ").append(fileSize(totalBytes))
                        }
                        if (written > 0) {
                            append(" · ").append(fileSize((written / seconds).toLong())).append("/s")
                        }
                    }
                    notify(buildNotification(text, written to totalBytes, true))
                }
                success = result.success
                result.message
            } catch (cancelled: CancellationException) {
                "已取消同步，剩余照片不再上传，已传完的文件保留在云盘"
            } catch (error: Exception) {
                "同步失败：${error.message ?: error.javaClass.simpleName}"
            }
            PhotoSyncStatus.record(this@PhotoSyncService, message, success)
            notify(buildNotification(message, null, false))
            stopForeground(STOP_FOREGROUND_DETACH)
            releaseWakeLock()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "poi:PhotoSync").apply {
            setReferenceCounted(false)
            acquire(2 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notify(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        text: String,
        progress: Pair<Long, Long>?,
        ongoing: Boolean,
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("照片同步")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
        if (progress != null && progress.second > 0) {
            val percent = (progress.first * 100 / progress.second).toInt().coerceIn(0, 100)
            builder.setProgress(100, percent, false)
        } else if (ongoing) {
            builder.setProgress(0, 0, true)
        }
        if (ongoing) {
            val cancel = PendingIntent.getService(
                this,
                1,
                Intent(this, PhotoSyncService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "取消", cancel)
        }
        return builder.build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "照片同步",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "每天自动把手机照片同步到 Cloudreve 时显示进度"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "photo_sync"
        private const val NOTIFICATION_ID = 0x5049
        private const val ACTION_CANCEL = "com.poi.homelab.action.PHOTO_SYNC_CANCEL"
        private const val EXTRA_MANUAL = "manual"

        /** 拉起前台服务；系统不允许后台启动时退回 JobScheduler（无通知但会照常同步）。 */
        fun start(context: Context, manual: Boolean) {
            val intent = Intent(context, PhotoSyncService::class.java)
                .putExtra(EXTRA_MANUAL, manual)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: Exception) {
                PhotoSyncJobService.enqueue(context, manual)
            }
        }
    }
}
