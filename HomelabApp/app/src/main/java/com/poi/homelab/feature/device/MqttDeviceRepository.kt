package com.poi.homelab.feature.device

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * 订阅家里 broker 上 rong 的状态与遥测。
 * 只在前台可见时保持连接，断线按 2/4/8/16/30 秒退避重试。
 */
class MqttDeviceRepository(context: Context, private val json: Json) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(DeviceUiState(config = DevicePrefs.load(appContext)))
    val state: StateFlow<DeviceUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var session = 0

    fun start() {
        val config = _state.value.config
        if (!config.configured) return
        if (job?.isActive == true) return
        val id = ++session
        job = scope.launch { runSession(id, config) }
    }

    fun stop() {
        session++
        job?.cancel()
        job = null
        _state.update { it.copy(link = DeviceLink.Idle) }
    }

    fun saveConfig(config: DeviceConfig) {
        stop()
        DevicePrefs.save(appContext, config)
        _state.update {
            it.copy(config = DevicePrefs.load(appContext), error = null, link = DeviceLink.Idle)
        }
    }

    private suspend fun runSession(id: Int, config: DeviceConfig) {
        var attempt = 0
        while (coroutineContext.isActive && id == session) {
            var client: MqttAsyncClient? = null
            try {
                setState(id) { it.copy(link = DeviceLink.Connecting, error = null) }
                val created = MqttAsyncClient(
                    "tcp://${config.host}:${config.port}",
                    "homelab-app-${UUID.randomUUID().toString().take(8)}",
                    MemoryPersistence(),
                )
                client = created
                created.setCallback(callback(id))
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    keepAliveInterval = 60
                    connectionTimeout = 10
                    userName = config.user
                    if (config.password.isNotEmpty()) password = config.password.toCharArray()
                }
                created.connect(options).waitForCompletion(15_000)
                created.subscribe(
                    arrayOf(
                        config.topic("state"),
                        config.topic("telemetry"),
                        config.topic("status"),
                    ),
                    intArrayOf(0, 0, 0),
                ).waitForCompletion(10_000)
                setState(id) { it.copy(link = DeviceLink.Connected, error = null) }
                attempt = 0
                while (id == session && coroutineContext.isActive && created.isConnected) {
                    delay(1000)
                }
                if (id != session) break
                setState(id) { it.copy(link = DeviceLink.Failed, error = "与 broker 的连接已断开") }
            } catch (error: MqttException) {
                setState(id) { it.copy(link = DeviceLink.Failed, error = readable(error)) }
            } catch (error: Throwable) {
                setState(id) { it.copy(link = DeviceLink.Failed, error = readable(error)) }
            } finally {
                client?.let { closing ->
                    runCatching {
                        if (closing.isConnected) closing.disconnect().waitForCompletion(2_000)
                    }
                    runCatching { closing.close() }
                }
            }
            attempt++
            delay(backoffMillis(attempt))
        }
    }

    private fun callback(id: Int): MqttCallback = object : MqttCallback {
        override fun connectionLost(cause: Throwable?) {
            setState(id) { it.copy(link = DeviceLink.Failed, error = cause?.message ?: "连接已断开") }
        }

        override fun messageArrived(topic: String?, message: MqttMessage?) {
            val name = topic ?: return
            val payload = message?.payload?.toString(Charsets.UTF_8) ?: return
            val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return
            val now = System.currentTimeMillis()
            setState(id) { current ->
                when {
                    name.endsWith("/state") -> current.copy(
                        snapshot = DeviceSnapshot(
                            uptimeSeconds = obj["up"]?.jsonPrimitive?.intOrNull,
                            led = obj["led"]?.jsonPrimitive?.intOrNull,
                            at = now,
                        ),
                        lastMessageAt = now,
                        deviceOffline = false,
                    )
                    name.endsWith("/telemetry") -> current.copy(
                        telemetry = DeviceTelemetry(
                            ip = obj["ip"]?.jsonPrimitive?.contentOrNull,
                            rssi = obj["rssi"]?.jsonPrimitive?.intOrNull,
                            at = now,
                        ),
                        lastMessageAt = now,
                        deviceOffline = false,
                    )
                    name.endsWith("/status") -> current.copy(
                        deviceOffline = payload.trim().equals("offline", ignoreCase = true),
                        lastMessageAt = now,
                    )
                    else -> current
                }
            }
        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
    }

    private fun setState(id: Int, block: (DeviceUiState) -> DeviceUiState) {
        if (id != session) return
        _state.update(block)
    }

    private fun backoffMillis(attempt: Int): Long {
        val seconds = when (attempt) {
            1 -> 2L
            2 -> 4L
            3 -> 8L
            4 -> 16L
            else -> 30L
        }
        return seconds * 1000
    }

    private fun readable(error: Throwable): String = when (error) {
        is MqttException -> when (error.reasonCode) {
            MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt(),
            MqttException.REASON_CODE_NOT_AUTHORIZED.toInt(),
            -> "用户名或密码被 broker 拒绝"
            MqttException.REASON_CODE_BROKER_UNAVAILABLE.toInt(),
            -> "连不上 broker，检查地址、端口和网络"
            MqttException.REASON_CODE_CLIENT_TIMEOUT.toInt() -> "连接 broker 超时"
            else -> error.message ?: "MQTT 连接失败"
        }
        else -> error.message ?: "MQTT 连接失败"
    }
}
