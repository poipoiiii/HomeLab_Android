package com.poi.homelab.feature.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.StateFlow

class DeviceViewModel(private val repository: MqttDeviceRepository) : ViewModel() {

    val state: StateFlow<DeviceUiState> = repository.state

    private var visible = false

    fun onVisible() {
        if (visible) return
        visible = true
        repository.start()
    }

    fun onHidden() {
        visible = false
        repository.stop()
    }

    fun saveConfig(config: DeviceConfig) {
        repository.saveConfig(config)
        if (visible) repository.start()
    }

    companion object {
        fun factory(repository: MqttDeviceRepository) = viewModelFactory {
            initializer { DeviceViewModel(repository) }
        }
    }
}
