package com.rong.homelab.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rong.homelab.data.AnalysisResponse
import com.rong.homelab.data.AppContainer
import com.rong.homelab.data.AppSettings
import com.rong.homelab.data.BillOverview
import com.rong.homelab.data.BillTransaction
import com.rong.homelab.data.HealthSnapshot
import java.time.YearMonth
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class PendingAction(
    val name: String,
    val action: String,
    val token: String,
    val command: String,
)

data class UiState(
    val settings: AppSettings = AppSettings(),
    val health: HealthSnapshot? = null,
    val overview: BillOverview? = null,
    val transactions: List<BillTransaction> = emptyList(),
    val billMonth: String = YearMonth.now().toString(),
    val analysis: AnalysisResponse? = null,
    val loadingHealth: Boolean = false,
    val loadingBills: Boolean = false,
    val loadingAnalysis: Boolean = false,
    val actionBusy: Boolean = false,
    val pendingAction: PendingAction? = null,
    val logsTitle: String? = null,
    val logs: String? = null,
    val logsLoading: Boolean = false,
    val downloadBusy: Boolean = false,
    val downloadResult: JsonObject? = null,
    val connectionTest: String? = null,
    val message: String? = null,
)

class MainViewModel(private val app: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var bootstrapped = false

    init {
        viewModelScope.launch {
            app.settings.settings.collect { value ->
                _state.update { it.copy(settings = value) }
                if (!bootstrapped && (value.labUrl.isNotBlank() || value.billUrl.isNotBlank())) {
                    bootstrapped = true
                    refreshAll()
                }
            }
        }
    }

    fun refreshAll() {
        refreshHealth()
        refreshBills()
    }

    fun refreshHealth() {
        val settings = _state.value.settings
        if (settings.labUrl.isBlank()) {
            _state.update { it.copy(health = null, message = "还没配置 Lab Tools 地址") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loadingHealth = true) }
            runCatching { app.lab.health(settings.labUrl) }
                .onSuccess { health ->
                    _state.update { it.copy(health = health, loadingHealth = false) }
                }
                .onFailure { error ->
                    _state.update { it.copy(loadingHealth = false, message = readable(error)) }
                }
        }
    }

    fun refreshBills() {
        val settings = _state.value.settings
        if (settings.billUrl.isBlank()) {
            _state.update {
                it.copy(overview = null, transactions = emptyList(), message = "还没配置账本地址")
            }
            return
        }
        val month = _state.value.billMonth
        viewModelScope.launch {
            _state.update { it.copy(loadingBills = true) }
            runCatching {
                coroutineScope {
                    val overview = async { app.bill.overview(settings.billUrl, settings.token, month) }
                    val page = async { app.bill.transactions(settings.billUrl, settings.token, month) }
                    overview.await() to page.await()
                }
            }.onSuccess { (overview, page) ->
                _state.update {
                    it.copy(
                        overview = overview,
                        transactions = page.items,
                        loadingBills = false,
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(loadingBills = false, message = readable(error)) }
            }
        }
    }

    fun changeMonth(offset: Long) {
        val current = runCatching { YearMonth.parse(_state.value.billMonth) }
            .getOrElse { YearMonth.now() }
        _state.update { it.copy(billMonth = current.plusMonths(offset).toString()) }
        refreshBills()
    }

    fun requestServiceAction(name: String, action: String) {
        val settings = _state.value.settings
        if (settings.labUrl.isBlank()) {
            _state.update { it.copy(message = "还没配置 Lab Tools 地址") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(actionBusy = true) }
            runCatching {
                when (action) {
                    "start" -> app.lab.startService(settings.labUrl, name)
                    "restart" -> app.lab.restartService(settings.labUrl, name)
                    else -> app.lab.stopService(settings.labUrl, name)
                }
            }.onSuccess { result ->
                val error = result.string("error")
                if (!error.isNullOrBlank()) {
                    _state.update { it.copy(actionBusy = false, message = error) }
                    return@onSuccess
                }
                if (action == "start") {
                    _state.update { it.copy(actionBusy = false, message = "已启动 $name") }
                    refreshHealth()
                } else {
                    val token = result.string("confirm_token")
                    val command = result.string("command")
                        ?: "${if (action == "stop") "停止" else "重启"} $name"
                    if (token.isNullOrBlank()) {
                        _state.update { it.copy(actionBusy = false, message = "服务器没有返回确认 token") }
                    } else {
                        _state.update {
                            it.copy(
                                actionBusy = false,
                                pendingAction = PendingAction(name, action, token, command),
                            )
                        }
                    }
                }
            }.onFailure { error ->
                _state.update { it.copy(actionBusy = false, message = readable(error)) }
            }
        }
    }

    fun confirmPendingAction() {
        val pending = _state.value.pendingAction ?: return
        val settings = _state.value.settings
        if (settings.labUrl.isBlank()) {
            _state.update { it.copy(pendingAction = null, message = "还没配置 Lab Tools 地址") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(actionBusy = true) }
            runCatching {
                if (pending.action == "stop") {
                    app.lab.stopService(settings.labUrl, pending.name, pending.token)
                } else {
                    app.lab.restartService(settings.labUrl, pending.name, pending.token)
                }
            }.onSuccess { result ->
                val error = result.string("error")
                val done = if (pending.action == "stop") "已停止" else "已重启"
                _state.update {
                    it.copy(
                        actionBusy = false,
                        pendingAction = null,
                        message = error ?: "$done ${pending.name}",
                    )
                }
                if (error.isNullOrBlank()) refreshHealth()
            }.onFailure { error ->
                _state.update {
                    it.copy(actionBusy = false, pendingAction = null, message = readable(error))
                }
            }
        }
    }

    fun cancelPendingAction() {
        _state.update { it.copy(pendingAction = null) }
    }

    fun loadLogs(name: String) {
        val settings = _state.value.settings
        if (settings.labUrl.isBlank()) {
            _state.update { it.copy(message = "还没配置 Lab Tools 地址") }
            return
        }
        _state.update { it.copy(logsTitle = name, logs = null, logsLoading = true) }
        viewModelScope.launch {
            runCatching { app.lab.serviceLogs(settings.labUrl, name) }
                .onSuccess { text ->
                    _state.update { it.copy(logs = text, logsLoading = false) }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(logs = "读取失败：${readable(error)}", logsLoading = false)
                    }
                }
        }
    }

    fun closeLogs() {
        _state.update { it.copy(logsTitle = null, logs = null, logsLoading = false) }
    }

    fun loadAnalysis() {
        val settings = _state.value.settings
        if (settings.billUrl.isBlank()) {
            _state.update { it.copy(message = "还没配置账本地址") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loadingAnalysis = true) }
            runCatching { app.bill.analyze(settings.billUrl, settings.token, 90) }
                .onSuccess { analysis ->
                    _state.update { it.copy(analysis = analysis, loadingAnalysis = false) }
                }
                .onFailure { error ->
                    _state.update { it.copy(loadingAnalysis = false, message = readable(error)) }
                }
        }
    }

    fun closeAnalysis() {
        _state.update { it.copy(analysis = null) }
    }

    fun submitDownload(
        url: String,
        query: String,
        target: String,
        source: String,
        series: String,
        limit: Int,
        items: String,
    ) {
        val settings = _state.value.settings
        if (settings.labUrl.isBlank()) {
            _state.update { it.copy(message = "还没配置 Lab Tools 地址") }
            return
        }
        if (url.isBlank() && query.isBlank()) {
            _state.update { it.copy(message = "链接和片名至少填一个") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(downloadBusy = true, downloadResult = null) }
            runCatching {
                app.lab.download(
                    baseUrl = settings.labUrl,
                    url = url,
                    query = query,
                    target = target,
                    source = source,
                    series = series,
                    limit = limit.coerceAtLeast(0),
                    items = items,
                )
            }.onSuccess { result ->
                val error = result.string("error")
                _state.update {
                    it.copy(
                        downloadBusy = false,
                        downloadResult = result,
                        message = error ?: "已提交下载",
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(downloadBusy = false, message = readable(error)) }
            }
        }
    }

    fun saveSettings(value: AppSettings) {
        viewModelScope.launch {
            app.settings.save(value)
            _state.update {
                it.copy(settings = value, connectionTest = null, message = "设置已保存")
            }
            if (value.labUrl.isNotBlank()) refreshHealth()
            if (value.billUrl.isNotBlank()) refreshBills()
        }
    }

    fun setThemeMode(mode: String) {
        val updated = _state.value.settings.copy(themeMode = mode)
        viewModelScope.launch {
            app.settings.save(updated)
            _state.update { it.copy(settings = updated) }
        }
    }

    fun testConnections() {
        val settings = _state.value.settings
        if (settings.labUrl.isBlank() && settings.billUrl.isBlank()) {
            _state.update { it.copy(message = "先把两个地址填上再测试") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(connectionTest = "正在测试…") }
            val lines = mutableListOf<String>()
            if (settings.billUrl.isNotBlank()) {
                runCatching { app.bill.health(settings.billUrl) }
                    .onSuccess { lines += "账本：连接正常" }
                    .onFailure { lines += "账本：${readable(it)}" }
            }
            if (settings.labUrl.isNotBlank()) {
                runCatching { app.lab.listServices(settings.labUrl) }
                    .onSuccess { lines += "Lab Tools：正常，${it.size} 个容器" }
                    .onFailure { lines += "Lab Tools：${readable(it)}" }
            }
            _state.update { it.copy(connectionTest = lines.joinToString("\n")) }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun readable(error: Throwable): String {
        val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        return message.take(300)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    class Factory(private val app: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(app) as T
        }
    }
}
