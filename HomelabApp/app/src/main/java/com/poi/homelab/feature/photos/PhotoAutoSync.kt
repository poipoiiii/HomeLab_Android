package com.poi.homelab.feature.photos

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * 照片自动同步：用 JobScheduler 的持久化周期任务，每天跑一次。
 * 持久化任务由系统在重启后自动恢复，不需要额外的开机广播。
 */
object PhotoAutoSync {

    private const val JOB_ID = 0x5048
    private const val ONCE_JOB_ID = JOB_ID + 1
    private const val EXTRA_MANUAL = "manual"

    /** 每次向前找 3 天；同名文件在云端会被跳过，重复扫描不会产生副本。 */
    const val LOOKBACK_DAYS = 3
    const val MAX_PHOTOS = 200

    /** 幂等：已排过就不重复排，关掉时只取消周期任务。 */
    fun apply(context: Context, enabled: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val scheduled = scheduler.allPendingJobs.any { it.id == JOB_ID }
        when {
            enabled && !scheduled -> {
                scheduler.schedule(
                    JobInfo.Builder(JOB_ID, component(context))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPeriodic(TimeUnit.DAYS.toMillis(1), TimeUnit.HOURS.toMillis(1))
                        .setPersisted(true)
                        .build(),
                )
                // 刚打开开关时先跑一次，不必等到第二天
                runNow(context)
            }
            !enabled && scheduled -> scheduler.cancel(JOB_ID)
        }
    }

    /** 手动触发一次：网络不通也会在 deadline 到点后开跑，失败结果照常写进状态。 */
    fun runNow(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        scheduler.schedule(
            JobInfo.Builder(ONCE_JOB_ID, component(context))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(TimeUnit.MINUTES.toMillis(5))
                .setTransientExtras(Bundle().apply { putBoolean(EXTRA_MANUAL, true) })
                .build(),
        )
    }

    internal fun isManual(params: android.app.job.JobParameters?): Boolean =
        params?.transientExtras?.getBoolean(EXTRA_MANUAL) == true

    private fun component(context: Context) =
        ComponentName(context, PhotoSyncJobService::class.java)
}

/** 同步结果只记时间和一句摘要，不落账号、地址等凭证。 */
object PhotoSyncStatus {

    private const val PREFS = "photo_sync_status"
    private const val KEY_TIME = "time"
    private const val KEY_TEXT = "text"
    private val timeFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm")

    fun record(context: Context, message: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_TIME, System.currentTimeMillis())
            .putString(KEY_TEXT, message)
            .apply()
    }

    fun describe(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val time = prefs.getLong(KEY_TIME, 0L)
        if (time == 0L) return "还没有同步记录"
        val stamp = timeFormat.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
        return "$stamp · ${prefs.getString(KEY_TEXT, "").orEmpty()}"
    }
}
