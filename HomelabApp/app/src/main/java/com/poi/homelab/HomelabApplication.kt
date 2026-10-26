package com.poi.homelab

import android.app.Application
import com.poi.homelab.data.AppContainer

class HomelabApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
