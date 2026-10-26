package com.rong.homelab

import android.app.Application
import com.rong.homelab.data.AppContainer

class HomelabApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
