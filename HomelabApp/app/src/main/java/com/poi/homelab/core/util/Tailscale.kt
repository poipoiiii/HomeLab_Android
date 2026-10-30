package com.poi.homelab.core.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Tailscale 没有公开 SDK，但它的 IPNReceiver 监听 CONNECT_VPN / DISCONNECT_VPN 广播。
 * 发这个显式广播就能连上或断开隧道，不用打开它的界面。
 */
object Tailscale {

    private const val PKG = "com.tailscale.ipn"
    private const val RECEIVER = "com.tailscale.ipn.IPNReceiver"
    private const val ACTION_CONNECT = "com.tailscale.ipn.CONNECT_VPN"
    private const val ACTION_DISCONNECT = "com.tailscale.ipn.DISCONNECT_VPN"

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PKG, 0) }.isSuccess

    fun connect(context: Context): Boolean = send(context, ACTION_CONNECT)

    fun disconnect(context: Context): Boolean = send(context, ACTION_DISCONNECT)

    /** 广播发出去返回 true；被系统以“未导出”拒绝时返回 false，调用方再退回打开 App。 */
    private fun send(context: Context, action: String): Boolean = runCatching {
        context.sendBroadcast(Intent(action).setComponent(ComponentName(PKG, RECEIVER)))
        true
    }.getOrDefault(false)

    fun openApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(PKG) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
