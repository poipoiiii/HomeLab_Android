package com.poi.homelab.notify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/** 采集模块的权限与队列状态，供设置页展示。 */
object CaptureStatus {

    /** 用户是否在系统设置里勾选了本 App 的「通知使用权」。 */
    fun notificationAccessGranted(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        val packageName = context.packageName
        return enabled.split(":").any { it.contains(packageName) }
    }

    fun smsGranted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    fun pending(context: Context): Int = Outbox.pending(context)

    /** 服务器地址是否已经从上层的账本设置镜像过来。 */
    fun configured(context: Context): Boolean =
        NotifyPrefs.serverUrl(context).isNotBlank() && NotifyPrefs.token(context).isNotBlank()
}
