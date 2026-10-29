package com.poi.homelab.feature.photos

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.poi.homelab.HomelabApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 前台服务被系统拒绝时的兜底：用 JobScheduler 静默上传，没有通知。 */
class PhotoSyncJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        val app = applicationContext as HomelabApplication
        val watchdog = params?.jobId == WATCHDOG_JOB_ID
        val manual = params?.transientExtras?.getBoolean(EXTRA_MANUAL) == true
        scope.launch {
            if (watchdog) {
                watchdogRun(app)?.let { PhotoSyncStatus.record(app, it.message, it.success) }
            } else {
                val result = runCatching { PhotoSync.run(app, manual) }
                    .getOrElse { PhotoSyncResult("同步失败：${it.message ?: it.javaClass.simpleName}", false) }
                PhotoSyncStatus.record(app, result.message, result.success)
            }
            jobFinished(params, false)
            stopSelf()
        }
        return true
    }

    /** 看门狗：闹钟/前台服务都被系统拦下时，超过 20 小时没成功就补跑一次。 */
    private suspend fun watchdogRun(app: HomelabApplication): PhotoSyncResult? {
        val settings = app.container.settings.settings.first()
        if (!settings.photoAutoSync) return null
        if (System.currentTimeMillis() - PhotoSyncStatus.lastSuccessTime(app) < WATCHDOG_GAP_MS) return null
        return runCatching { PhotoSync.run(app, manual = false) }
            .getOrElse { PhotoSyncResult("同步失败：${it.message ?: it.javaClass.simpleName}", false) }
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        scope.coroutineContext.cancelChildren()
        return true
    }

    companion object {
        private const val JOB_ID = 0x504A
        private const val LEGACY_JOB_ID = 0x5048
        private const val WATCHDOG_JOB_ID = 0x504B
        private const val EXTRA_MANUAL = "manual"
        private const val WATCHDOG_GAP_MS = 20 * 60 * 60 * 1000L

        /** 旧版本用过 0x5048 的周期任务，升级后要取消，避免和新闹钟重复同步。 */
        fun cancelLegacy(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(LEGACY_JOB_ID)
        }

        /**
         * 兜底周期任务：即使精确闹钟或前台服务被 ROM 拦掉，
         * 每 6 小时检查一次，超过 20 小时没成功同步就静默补跑。
         */
        fun scheduleWatchdog(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.allPendingJobs.any { it.id == WATCHDOG_JOB_ID }) return
            scheduler.schedule(
                JobInfo.Builder(WATCHDOG_JOB_ID, ComponentName(context, PhotoSyncJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(6 * 60 * 60 * 1000L, 60 * 60 * 1000L)
                    .setPersisted(true)
                    .build(),
            )
        }

        fun cancelWatchdog(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(WATCHDOG_JOB_ID)
        }

        fun enqueue(context: Context, manual: Boolean) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, PhotoSyncJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setOverrideDeadline(5 * 60 * 1000L)
                    .setTransientExtras(android.os.Bundle().apply { putBoolean(EXTRA_MANUAL, manual) })
                    .build(),
            )
        }
    }
}
