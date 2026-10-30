package com.poi.homelab.feature.calendar

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.poi.homelab.MainActivity
import com.poi.homelab.R
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 日历提醒：每条开启提醒的记录各占一个 AlarmManager 闹钟，到点发一条本地通知。
 * 闹钟是一次性的，记录增删改后由 [syncAll] 统一重排。
 */
object CalendarReminder {

    private const val PREFS = "calendar_reminders"
    private const val KEY_IDS = "ids"
    private const val CHANNEL_ID = "calendar_reminder"
    private const val DEFAULT_TIME = "09:00"

    /** 按当前记录重排全部提醒；已删除或关闭提醒的记录会顺带取消。 */
    fun syncAll(context: Context, entries: List<CalendarEntry>) {
        val app = context.applicationContext
        val alarm = app.getSystemService(AlarmManager::class.java) ?: return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val planned = entries
            .filter { it.reminder && it.id > 0L }
            .mapNotNull { entry -> triggerAt(entry)?.let { if (it > now) entry.id to it else null } }

        val keep = planned.map { it.first }.toSet()
        prefs.getStringSet(KEY_IDS, emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .filterNot { it in keep }
            .forEach { alarm.cancel(pendingFor(app, it)) }

        planned.forEach { (id, at) ->
            val pending = pendingFor(app, id)
            alarm.cancel(pending)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                // 没有精确闹钟权限时系统可能延迟送达，但不会丢
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }

        prefs.edit().putStringSet(KEY_IDS, keep.map { it.toString() }.toSet()).apply()
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun notify(context: Context, entry: CalendarEntry) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "日历提醒",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "生日、纪念日、待办和重要事项的到点提醒" },
            )
        }
        val detail = buildString {
            append(calendarTypeLabel(entry.type))
            if (entry.time.isNotBlank()) append(" · ").append(entry.time)
            if (entry.location.isNotBlank()) append(" · ").append(entry.location)
        }
        val tap = PendingIntent.getActivity(
            context,
            (entry.id and 0x7FFFFFFF).toInt(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_calendar)
            .setContentTitle(entry.title)
            .setContentText(detail)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        NotificationManagerCompat.from(context).notify((entry.id and 0x7FFFFFFF).toInt(), notification)
    }

    private fun triggerAt(entry: CalendarEntry): Long? = runCatching {
        val time = LocalTime.parse(entry.time.ifBlank { DEFAULT_TIME })
        LocalDate.parse(entry.date)
            .atTime(time)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()

    private fun pendingFor(context: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        (id and 0x7FFFFFFF).toInt(),
        Intent(context, CalendarReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** 到点：重新读一遍本机记录，记录被删或关了提醒就不再打扰。 */
class CalendarReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        if (id <= 0L) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val entry = CalendarStore(app).load().firstOrNull { it.id == id }
                if (entry != null && entry.reminder) CalendarReminder.notify(app, entry)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}

/** 重启或覆盖安装后按记录重排提醒（AlarmManager 的闹钟不跨重启）。 */
class CalendarReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                CalendarReminder.syncAll(app, CalendarStore(app).load())
            } finally {
                pending.finish()
            }
        }
    }
}
