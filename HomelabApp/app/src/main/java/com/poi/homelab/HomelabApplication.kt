package com.poi.homelab

import android.app.Application
import coil.Coil
import coil.decode.SvgDecoder
import coil.ImageLoader
import com.poi.homelab.core.AppContainer
import com.poi.homelab.feature.notify.CaptureFlushReceiver

class HomelabApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Homarr 的 app 图标混杂 PNG 与 SVG，显式注册 SvgDecoder 才能显示占多数的 .svg 图标
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .components { add(SvgDecoder.Factory()) }
                .crossfade(true)
                .build(),
        )
        // 采集队列每 30 分钟补发一次；重复调用只会刷新同一条 alarm
        CaptureFlushReceiver.schedule(this)
    }
}
