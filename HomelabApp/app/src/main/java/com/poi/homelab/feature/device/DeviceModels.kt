package com.poi.homelab.feature.device

/** MQTT 连接参数。地址/密码只存本机 SharedPreferences，仓库里不写默认实例。 */
data class DeviceConfig(
    val host: String = "",
    val port: Int = 1883,
    val user: String = "app",
    val password: String = "",
    val site: String = "dorm",
    val dev: String = "rong",
) {
    val configured: Boolean
        get() = host.isNotBlank() && user.isNotBlank() && password.isNotBlank()

    fun topic(suffix: String): String = "home/$site/stm32/$dev/$suffix"
}

enum class DeviceLink { Idle, Connecting, Connected, Failed }

data class DeviceSnapshot(
    val uptimeSeconds: Int? = null,
    val led: Int? = null,
    val at: Long = 0L,
)

data class DeviceTelemetry(
    val ip: String? = null,
    val rssi: Int? = null,
    val at: Long = 0L,
)

data class DeviceUiState(
    val config: DeviceConfig = DeviceConfig(),
    val link: DeviceLink = DeviceLink.Idle,
    val error: String? = null,
    val snapshot: DeviceSnapshot? = null,
    val telemetry: DeviceTelemetry? = null,
    val deviceOffline: Boolean = false,
    val lastMessageAt: Long = 0L,
)
