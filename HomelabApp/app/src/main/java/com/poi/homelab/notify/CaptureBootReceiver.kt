package com.poi.homelab.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 重启或升级后重新排上定时补发。 */
class CaptureBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CaptureFlushReceiver.schedule(context.applicationContext)
    }
}
