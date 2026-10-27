package com.poi.homelab

import android.app.Application
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.Coil
import com.poi.homelab.data.AppContainer

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
    }
}
