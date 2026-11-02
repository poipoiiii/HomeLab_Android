package com.poi.homelab.feature.motion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.poi.homelab.core.settings.SettingsStore
import com.poi.homelab.feature.ai.requiresKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class MotionUiState(
    val url: String = "",
    val token: String = "",
    val configured: Boolean = false,
    val jobs: List<MotionJob> = emptyList(),
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val createdId: String? = null,
    val health: MotionHealth? = null,
    val healthError: String? = null,
    val testing: Boolean = false,
    val savingIds: Set<String> = emptySet(),
    val directorTurns: List<DirectorTurn> = emptyList(),
    val directorLoading: Boolean = false,
    val directorError: String? = null,
    val pendingPlan: MotionRequest? = null,
    val lastPlan: MotionRequest? = null,
    val aiReady: Boolean = false,
)

class MotionViewModel(
    private val repository: MotionRepository,
    private val settingsStore: SettingsStore,
    json: Json,
) : ViewModel() {

    private val director = MotionDirector(json)

    private val _state = MutableStateFlow(
        MotionUiState(
            url = repository.url(),
            token = repository.token(),
            configured = repository.configured(),
        ),
    )
    val state: StateFlow<MotionUiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var visible = false

    fun onVisible() {
        if (visible) return
        visible = true
        refresh()
        refreshAiReady()
        if (pollJob == null) {
            pollJob = viewModelScope.launch {
                while (isActive && visible) {
                    delay(2500)
                    if (_state.value.jobs.any { it.active }) refresh(silent = true)
                }
            }
        }
    }

    fun onHidden() {
        visible = false
        pollJob?.cancel()
        pollJob = null
    }

    private fun refreshAiReady() {
        viewModelScope.launch {
            val settings = settingsStore.settings.first()
            val provider = settings.aiProviders.firstOrNull { it.id == settings.aiSelectedId }
                ?: settings.aiProviders.firstOrNull()
            val ready = provider != null &&
                provider.baseUrl.isNotBlank() &&
                provider.model.isNotBlank() &&
                (provider.apiKey.isNotBlank() || !provider.requiresKey)
            _state.update { it.copy(aiReady = ready) }
        }
    }

    /** 用户说一句话，导演先追问或直接给方案。 */
    fun sendBrief(input: String) {
        val text = input.trim()
        if (text.isEmpty() || _state.value.directorLoading) return

        // 方案卡在等确认时，允许用户直接打字「确认」，不必去点按钮
        val confirmations = setOf("确认", "确认生成", "可以", "就这个", "开始", "生成吧", "没问题", "就这样")
        if (_state.value.pendingPlan != null && text in confirmations) {
            _state.update {
                it.copy(directorTurns = it.directorTurns + DirectorTurn("user", text))
            }
            confirmPlan()
            return
        }

        val history = _state.value.directorTurns
        _state.update {
            it.copy(
                directorTurns = it.directorTurns + DirectorTurn("user", text),
                directorLoading = true,
                directorError = null,
            )
        }
        viewModelScope.launch {
            val settings = settingsStore.settings.first()
            val provider = settings.aiProviders.firstOrNull { it.id == settings.aiSelectedId }
                ?: settings.aiProviders.firstOrNull()
            if (provider == null) {
                _state.update {
                    it.copy(directorLoading = false, directorError = "还没有可用的 AI 服务商")
                }
                return@launch
            }
            runCatching { director.reply(provider, history, text, _state.value.lastPlan) }
                .onSuccess { reply ->
                    _state.update {
                        it.copy(
                            directorLoading = false,
                            directorTurns = it.directorTurns + DirectorTurn("assistant", reply.message),
                            pendingPlan = reply.plan,
                            lastPlan = reply.plan ?: it.lastPlan,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(directorLoading = false, directorError = readable(error)) }
                }
        }
    }

    /** 用户点「确认生成」后才真正提交渲染。 */
    fun confirmPlan() {
        val plan = _state.value.pendingPlan ?: return
        if (_state.value.submitting) return
        if (!repository.configured()) {
            _state.update { it.copy(directorError = "先填写视频服务地址和 Token") }
            return
        }
        _state.update { it.copy(submitting = true, directorError = null) }
        viewModelScope.launch {
            runCatching { repository.submit(plan) }
                .onSuccess { job ->
                    _state.update {
                        it.copy(
                            submitting = false,
                            pendingPlan = null,
                            lastPlan = null,
                            jobs = listOf(job) + it.jobs.filter { old -> old.id != job.id },
                            message = "方案已提交，开始渲染",
                            createdId = job.id,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(submitting = false, directorError = readable(error)) }
                }
        }
    }

    fun discardPlan() {
        _state.update { it.copy(pendingPlan = null) }
    }

    fun clearDirector() {
        _state.update {
            it.copy(
                directorTurns = emptyList(),
                pendingPlan = null,
                lastPlan = null,
                directorError = null,
                directorLoading = false,
            )
        }
    }

    fun refresh(silent: Boolean = false) {
        if (!repository.configured()) {
            _state.update { it.copy(configured = false, loading = false) }
            return
        }
        if (!silent) _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.jobs(30) }
                .onSuccess { jobs ->
                    _state.update {
                        it.copy(
                            configured = true,
                            url = repository.url(),
                            jobs = jobs,
                            loading = false,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(loading = false, error = if (silent) it.error else readable(error))
                    }
                }
        }
    }

    fun submit(request: MotionRequest) {
        if (_state.value.submitting) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.submit(request) }
                .onSuccess { job ->
                    _state.update {
                        it.copy(
                            submitting = false,
                            jobs = listOf(job) + it.jobs.filter { old -> old.id != job.id },
                            message = "任务已提交，正在渲染",
                            createdId = job.id,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(submitting = false, error = readable(error)) }
                }
        }
    }

    fun cancel(id: String) {
        viewModelScope.launch {
            runCatching { repository.cancel(id) }
                .onSuccess { job ->
                    _state.update { state ->
                        state.copy(
                            jobs = state.jobs.map { if (it.id == job.id) job else it },
                            message = "已请求取消",
                        )
                    }
                }
                .onFailure { error -> _state.update { state -> state.copy(error = readable(error)) } }
        }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            runCatching { repository.delete(id) }
                .onSuccess {
                    _state.update { state ->
                        state.copy(jobs = state.jobs.filterNot { it.id == id }, message = "任务已删除")
                    }
                }
                .onFailure { error -> _state.update { state -> state.copy(error = readable(error)) } }
        }
    }

    fun saveVideo(id: String) {
        val job = _state.value.jobs.firstOrNull { it.id == id } ?: return
        if (id in _state.value.savingIds) return
        _state.update { it.copy(savingIds = it.savingIds + id) }
        viewModelScope.launch {
            runCatching { repository.saveVideo(job) }
                .onSuccess { _state.update { it.copy(message = "已保存到相册") } }
                .onFailure { error -> _state.update { it.copy(error = readable(error)) } }
            _state.update { it.copy(savingIds = it.savingIds - id) }
        }
    }

    fun videoUrl(job: MotionJob): String? = repository.videoUrl(job)

    fun posterUrl(job: MotionJob): String? = repository.posterUrl(job)

    fun saveConfig(url: String, token: String) {
        repository.saveConfig(url, token)
        _state.update {
            it.copy(
                url = repository.url(),
                token = repository.token(),
                configured = repository.configured(),
                error = null,
                health = null,
                healthError = null,
            )
        }
        refresh()
        testConnection()
    }

    fun testConnection() {
        if (_state.value.testing) return
        if (!repository.configured()) {
            _state.update { it.copy(healthError = "先填写服务地址") }
            return
        }
        _state.update { it.copy(testing = true, healthError = null, health = null) }
        viewModelScope.launch {
            runCatching { repository.health() }
                .onSuccess { health ->
                    _state.update {
                        it.copy(
                            testing = false,
                            health = health,
                            healthError = when {
                                !health.ffmpeg -> "服务在跑，但容器里没有 ffmpeg"
                                !health.cjkFonts -> "服务在跑，但缺少中文字体，画面会出方块"
                                else -> null
                            },
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(testing = false, healthError = readable(error)) }
                }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    fun consumeCreated() {
        _state.update { it.copy(createdId = null) }
    }

    private fun readable(error: Throwable): String = when (error) {
        is MotionException -> error.userMessage
        else -> error.message ?: "请求失败"
    }

    companion object {
        fun factory(repository: MotionRepository, settingsStore: SettingsStore, json: Json) =
            viewModelFactory {
                initializer { MotionViewModel(repository, settingsStore, json) }
            }
    }
}
