package com.poi.homelab.feature.photos

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.poi.homelab.core.settings.SettingsStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 照片自动同步的排期：AlarmManager 每天在用户设定的整点唤醒一次，
 * 由 [PhotoSyncService] 以前台服务 + 通知的形式真正上传。
 * 闹钟是一次性的，每次触发后和开机后都会重新排下一天。
 */
object PhotoAutoSync {

    private const val REQUEST_CODE = 0x5048

    /** 打开/关闭自动同步；开启时把下一次闹钟排到最近的设定整点。 */
    fun apply(context: Context, enabled: Boolean, hour: Int) {
        PhotoSyncJobService.cancelLegacy(context)
        if (enabled) {
            PhotoSyncJobService.scheduleWatchdog(context)
        } else {
            PhotoSyncJobService.cancelWatchdog(context)
        }
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context)
        alarm.cancel(pending)
        if (!enabled) return
        val trigger = nextTrigger(hour.coerceIn(0, 23))
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()
        if (exact) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        } else {
            // 没给精确闹钟权限时退化成省电友好的近似时间，系统可能延迟几十分钟
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        }
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return false
        return alarm.canScheduleExactAlarms()
    }

    /** 系统是否已允许本应用「忽略电池优化」（后台不被冻结）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 手动立即同步：直接拉起前台服务，失败时退回 JobScheduler。 */
    fun runNow(context: Context) {
        PhotoSyncService.start(context.applicationContext, manual = true)
    }

    private fun nextTrigger(hour: Int): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, PhotoSyncAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** 每天到点：先排下一天的闹钟，再拉起同步服务。 */
class PhotoSyncAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsStore(appContext).settings.first()
                if (settings.photoAutoSync) {
                    PhotoAutoSync.apply(appContext, true, settings.photoSyncHour)
                    PhotoSyncService.start(appContext, manual = false)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** 重启或覆盖安装后重新排上每天的闹钟。 */
class PhotoSyncBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsStore(appContext).settings.first()
                PhotoAutoSync.apply(appContext, settings.photoAutoSync, settings.photoSyncHour)
            } finally {
                pending.finish()
            }
        }
    }
}

/** 同步结果只记时间和一句摘要，不落账号、地址等凭证。 */
object PhotoSyncStatus {

    private const val PREFS = "photo_sync_status"
    private const val KEY_TIME = "time"
    private const val KEY_TEXT = "text"
    private const val KEY_SUCCESS_TIME = "success_time"
    private val timeFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm")

    fun record(context: Context, message: String, success: Boolean) {
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_TIME, now)
            .putString(KEY_TEXT, message)
            .apply {
                if (success) putLong(KEY_SUCCESS_TIME, now)
            }
            .apply()
    }

    /** 最近一次成功同步的时间，看门狗用它判断要不要补跑。 */
    fun lastSuccessTime(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_SUCCESS_TIME, 0L)

    fun describe(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val time = prefs.getLong(KEY_TIME, 0L)
        if (time == 0L) return "还没有同步记录"
        val stamp = timeFormat.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
        return "$stamp · ${prefs.getString(KEY_TEXT, "").orEmpty()}"
    }
}
