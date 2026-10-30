package com.poi.homelab.feature.watch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.poi.homelab.MainActivity
import com.poi.homelab.R

/** 规则命中时的本地通知；不需要任何外部通道和配置。 */
object WatchNotifier {

    private const val CHANNEL_ID = "watch_alerts"
    private const val CHANNEL_NAME = "价格监控"
    private const val TEST_ID = 0x5748

    fun canNotify(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "监控规则命中时提醒" }
        manager.createNotificationChannel(channel)
    }

    fun notifyHits(context: Context, rule: WatchRule, items: List<WatchItem>, total: Int = 0) {
        if (items.isEmpty()) return
        ensureChannel(context)
        val expanded = items.take(5).joinToString("\n") { item ->
            val suffix = if (item.fairShow) " · 公示期" else ""
            "¥${item.priceText} · 编号 ${item.eid} · 收藏 ${item.collectNum}$suffix"
        }
        val summary = buildString {
            append("最低 ¥${items.first().priceText}")
            if (total > items.size) append(" · 在售 $total 件")
        }
        post(
            context = context,
            id = rule.id.hashCode(),
            title = "${rule.displayTitle} · ${items.size} 件新上架",
            text = summary,
            expanded = expanded,
        )
    }

    fun notifyTest(context: Context) {
        ensureChannel(context)
        post(
            context = context,
            id = TEST_ID,
            title = "价格监控",
            text = "通知渠道正常，规则命中后会在这里提醒。",
            expanded = null,
        )
    }

    private fun post(context: Context, id: Int, title: String, text: String, expanded: String?) {
        if (!canNotify(context)) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_watch)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expanded ?: text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        runCatching { NotificationManagerCompat.from(context).notify(id, builder.build()) }
    }
}
