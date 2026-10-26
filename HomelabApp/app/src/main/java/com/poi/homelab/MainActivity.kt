package com.poi.homelab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.poi.homelab.ui.HomelabApp
import com.poi.homelab.ui.MainViewModel
import com.poi.homelab.ui.components.LaunchSplashHost
import com.poi.homelab.ui.theme.HomelabTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as HomelabApplication).container

        setContent {
            val viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory(container))
            val state by viewModel.state.collectAsStateWithLifecycle()
            HomelabTheme(themeMode = state.settings.themeMode) {
                LaunchSplashHost {
                    HomelabApp(viewModel)
                }
            }
        }
    }
}
