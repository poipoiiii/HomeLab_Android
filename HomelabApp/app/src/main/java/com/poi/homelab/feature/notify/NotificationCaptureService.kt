package com.poi.homelab.feature.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 支付宝 / 微信 / 银行 App 的支付通知。
 *
 * 需要在系统设置里手动授予「通知使用权」，App 无法自行申请。
 */
class NotificationCaptureService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val packageName = sbn?.packageName ?: return
        if (packageName == this.packageName) return
        if (!NotifyPrefs.notificationEnabled(this)) return
        if (!NotifyPrefs.allAppsEnabled(this) && packageName !in CapturedFilter.PACKAGES) return
        if (sbn.isOngoing) return

        val notification = sbn.notification ?: return
        // 折叠摘要通常只是「2 条新消息」，跳过避免重复上报
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString().orEmpty()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString("\n").orEmpty()

        val text = listOf(title, body, lines).filter { it.isNotBlank() }.joinToString("\n")
        if (text.isBlank()) return
        if (!CapturedFilter.isTransactionText(text)) return

        Uploader.submit(applicationContext, "notification", packageName, text)
    }
}
