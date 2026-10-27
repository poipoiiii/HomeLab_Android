package com.poi.homelab.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.poi.homelab.data.AgentTools
import com.poi.homelab.data.AiProvider
import com.poi.homelab.data.AnalysisResponse
import com.poi.homelab.data.AppContainer
import com.poi.homelab.data.AppSettings
import com.poi.homelab.data.BillOverview
import com.poi.homelab.data.BillTransaction
import com.poi.homelab.data.CalendarEntry
import com.poi.homelab.data.ChatMessage
import com.poi.homelab.data.DEFAULT_LAB_URL
import com.poi.homelab.data.HealthSnapshot
import com.poi.homelab.data.HomarrApp
import com.poi.homelab.data.HomarrBoard
import com.poi.homelab.data.TodoItem
import com.poi.homelab.data.defaultTodoItems
import com.poi.homelab.data.requiresKey
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.YearMonth
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val healthError: String? = null,
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
    val chatMessages: List<ChatMessage> = emptyList(),
    val aiBusy: Boolean = false,
    val aiStatus: String? = null,
    val aiStream: String = "",
    val aiSuggestions: List<String> = emptyList(),
    val aiSuggestBusy: Boolean = false,
    val aiBalance: String? = null,
    val aiBalanceDetail: String? = null,
    val aiBalanceLoading: Boolean = false,
    val aiBalanceError: String? = null,
    val checkedToday: Set<String> = emptySet(),
    val todos: List<TodoItem> = defaultTodoItems,
    val calendarEntries: List<CalendarEntry> = emptyList(),
    val board: HomarrBoard? = null,
    val boardApps: List<HomarrApp> = emptyList(),
    val appStatus: Map<String, Boolean> = emptyMap(),
    val loadingBoard: Boolean = false,
    val boardError: String? = null,
)

class MainViewModel(private val app: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var bootstrapped = false
    private var messageSeq = 0L
    private val chatSaveMutex = Mutex()
    private var suggestionJob: Job? = null
    private var suggestionToken = 0

    init {
        viewModelScope.launch {
            val stored = app.chat.load()
            if (stored.isEmpty()) return@launch
            messageSeq = maxOf(messageSeq, stored.maxOf { it.id })
            _state.update { current ->
                if (current.chatMessages.isEmpty()) current.copy(chatMessages = stored) else current
            }
        }
        viewModelScope.launch {
            val checked = app.checklist.load()
            _state.update { it.copy(checkedToday = checked) }
        }
        viewModelScope.launch {
            val items = app.todo.load()
            _state.update { it.copy(todos = items) }
        }
        viewModelScope.launch { app.calendar.load() }
        viewModelScope.launch {
            app.calendar.entries.collect { entries ->
                _state.update { it.copy(calendarEntries = entries) }
            }
        }
        viewModelScope.launch {
            app.settings.settings.collect { value ->
                _state.update { it.copy(settings = value) }
                if (!bootstrapped &&
                    (value.billUrl.isNotBlank() ||
                        value.homarrUrl.isNotBlank() ||
                        (value.labUrl.isNotBlank() && value.labUrl != DEFAULT_LAB_URL))
                ) {
                    bootstrapped = true
                    refreshAll()
                }
            }
        }
    }

    fun refreshAll() {
        refreshHealth()
        refreshBills()
        refreshBoard()
    }

    /** 看板内容没有 REST GET，只能走 tRPC；app 列表单独取，两路并行。 */
    fun refreshBoard() {
        val settings = _state.value.settings
        if (settings.homarrUrl.isBlank() || settings.homarrApiKey.isBlank()) {
            _state.update {
                it.copy(
                    board = null,
                    boardApps = emptyList(),
                    appStatus = emptyMap(),
                    loadingBoard = false,
                    boardError = null,
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loadingBoard = true, boardError = null) }
            runCatching {
                coroutineScope {
                    // app 列表需要 Modify all apps 权限；拿不到时降级成只有布局、图标显示首字，不连累整块看板
                    val apps = async {
                        runCatching { app.homarr.apps(settings.homarrUrl, settings.homarrApiKey) }
                            .getOrDefault(emptyList())
                    }
                    val board = async { loadBoard(settings) }
                    board.await() to apps.await()
                }
            }.onSuccess { (board, apps) ->
                _state.update {
                    it.copy(board = board, boardApps = apps, loadingBoard = false, boardError = null)
                }
                // 磁贴状态点单独补一轮，别拖慢看板本身出现
                val statuses = runCatching {
                    app.homarr.statuses(settings.homarrUrl, apps)
                }.getOrDefault(emptyMap())
                _state.update { it.copy(appStatus = statuses) }
            }.onFailure { error ->
                _state.update {
                    it.copy(loadingBoard = false, boardError = readable(error))
                }
            }
        }
    }

    private suspend fun loadBoard(settings: AppSettings): HomarrBoard {
        val name = settings.homarrBoard.trim().ifBlank {
            val boards = app.homarr.boards(settings.homarrUrl, settings.homarrApiKey)
            boards.firstOrNull { it.isHome }?.name
                ?: boards.firstOrNull()?.name
                ?: throw IOException("Homarr 里没有可访问的看板")
        }
        return app.homarr.board(settings.homarrUrl, settings.homarrApiKey, name)
    }

    fun refreshHealth() {
        val settings = _state.value.settings
        if (settings.labUrl.isBlank()) {
            _state.update {
                it.copy(health = null, healthError = null, message = "还没配置 Lab Tools 地址")
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loadingHealth = true, healthError = null) }
            runCatching { app.lab.health(settings.labUrl) }
                .onSuccess { health ->
                    _state.update {
                        it.copy(health = health, loadingHealth = false, healthError = null)
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(loadingHealth = false, healthError = readable(error), message = readable(error))
                    }
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

    fun selectMonth(month: String) {
        if (month == _state.value.billMonth) return
        _state.update { it.copy(billMonth = month) }
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

    fun sendChat(text: String) {
        val input = text.trim()
        if (input.isBlank() || _state.value.aiBusy) return

        suggestionJob?.cancel()
        val requestToken = ++suggestionToken
        _state.update { it.copy(aiSuggestions = emptyList(), aiSuggestBusy = false) }

        val settings = _state.value.settings
        val provider = settings.aiProviders.firstOrNull { it.id == settings.aiSelectedId }
            ?: settings.aiProviders.firstOrNull()
        if (provider == null) {
            appendMessage(ChatMessage(nextMessageId(), "error", "还没有可用的 AI 服务商，点右上角「配置」添加。"))
            return
        }
        if (provider.baseUrl.isBlank() || provider.model.isBlank()) {
            appendMessage(ChatMessage(nextMessageId(), "error", "「${provider.name}」还没有填写 Base URL 或模型，点右上角「配置」补上。"))
            return
        }
        if (provider.apiKey.isBlank() && provider.requiresKey) {
            appendMessage(ChatMessage(nextMessageId(), "error", "点右上角「配置」填写「${provider.name}」的 API Key。"))
            return
        }

        val history = _state.value.chatMessages
        appendMessage(ChatMessage(nextMessageId(), "user", input))
        _state.update { it.copy(aiBusy = true, aiStatus = "正在连接 ${provider.name}…") }

        viewModelScope.launch {
            try {
                val tools = AgentTools(
                    context = app.appContext,
                    device = app.deviceTools,
                    lab = app.lab,
                    bill = app.bill,
                    calendar = app.calendar,
                    cloudreve = app.cloudreve,
                    settings = settings,
                    provider = provider,
                    ai = app.aiClient,
                )
                val streamed = StringBuilder()
                var answer = ""
                var failure: String? = null
                var toolUsed = false

                app.aiAgent
                    .run(
                        provider = provider,
                        tools = tools,
                        instruction = systemPrompt(settings),
                        history = history,
                        input = input,
                    )
                    .collect { event ->
                        val calls = event.functionCalls()
                        if (calls.isNotEmpty()) {
                            toolUsed = true
                            streamed.clear()
                            _state.update {
                                it.copy(aiStream = "", aiStatus = "调用 ${calls.first().name}…")
                            }
                        }

                        event.functionResponses().forEach { response ->
                            toolUsed = true
                            val output = response.response["result"]?.toString()
                                ?: response.response.toString()
                            appendMessage(
                                ChatMessage(
                                    id = nextMessageId(),
                                    role = "tool",
                                    content = output.take(2000),
                                    toolName = response.name,
                                ),
                            )
                        }

                        val chunk = event.contentText()
                        if (chunk.isNotEmpty()) {
                            if (event.partial) {
                                streamed.append(chunk)
                                _state.update { it.copy(aiStream = streamed.toString()) }
                            } else if (event.isFinalResponse) {
                                answer = chunk
                            }
                        } else if (event.errorCode != null) {
                            failure = event.errorMessage ?: event.errorCode
                        }
                    }

                if (answer.isBlank()) answer = streamed.toString()
                if (answer.isBlank()) failure?.let { throw IOException(it) }
                appendMessage(
                    ChatMessage(
                        id = nextMessageId(),
                        role = "assistant",
                        content = answer.ifBlank {
                            if (toolUsed) "工具调用次数达到上限，先给出目前的结论。" else "模型没有返回内容"
                        },
                    ),
                )

                suggestionJob = viewModelScope.launch {
                    _state.update { it.copy(aiSuggestBusy = true) }
                    val suggestions = runCatching {
                        app.aiAgent.suggestFollowUps(provider, input, answer)
                    }.getOrDefault(emptyList())
                    if (requestToken != suggestionToken) return@launch
                    _state.update {
                        it.copy(
                            aiSuggestions = suggestions.ifEmpty { fallbackSuggestions() },
                            aiSuggestBusy = false,
                        )
                    }
                }
            } catch (error: Exception) {
                appendMessage(ChatMessage(nextMessageId(), "error", readable(error)))
            } finally {
                _state.update { it.copy(aiBusy = false, aiStatus = null, aiStream = "") }
            }
        }
    }

    fun clearChat() {
        suggestionJob?.cancel()
        suggestionToken++
        _state.update {
            it.copy(
                chatMessages = emptyList(),
                aiStatus = null,
                aiStream = "",
                aiSuggestions = emptyList(),
                aiSuggestBusy = false,
            )
        }
        viewModelScope.launch {
            chatSaveMutex.withLock {
                app.chat.clear()
                app.aiAgent.clearHistory()
            }
        }
    }

    private fun fallbackSuggestions(): List<String> =
        listOf("能再具体一点吗？", "给我一个可执行的步骤", "还有别的办法吗？")

    fun selectAiProvider(id: String) {
        val current = _state.value.settings
        if (current.aiSelectedId == id || current.aiProviders.none { it.id == id }) return
        val updated = current.copy(aiSelectedId = id)
        viewModelScope.launch {
            app.settings.save(updated)
            _state.update {
                it.copy(
                    settings = updated,
                    aiBalance = null,
                    aiBalanceDetail = null,
                    aiBalanceError = null,
                )
            }
        }
    }

    fun saveAiProvider(provider: AiProvider) {
        val current = _state.value.settings
        val providers = current.aiProviders
            .map { if (it.id == provider.id) provider else it }
            .ifEmpty { listOf(provider) }
        val updated = current.copy(aiProviders = providers, aiSelectedId = provider.id)
        viewModelScope.launch {
            app.settings.save(updated)
            _state.update {
                it.copy(
                    settings = updated,
                    aiBalance = null,
                    aiBalanceDetail = null,
                    aiBalanceError = null,
                )
            }
        }
    }

    /** 点一下查一次当前服务商的账户余额，不做缓存。 */
    fun refreshAiBalance() {
        val snapshot = _state.value
        if (snapshot.aiBalanceLoading) return
        val provider = snapshot.settings.aiProviders
            .firstOrNull { it.id == snapshot.settings.aiSelectedId }
            ?: snapshot.settings.aiProviders.firstOrNull()
        if (provider == null) {
            _state.update { it.copy(message = "还没有可用的 AI 服务商") }
            return
        }
        if (provider.baseUrl.isBlank()) {
            _state.update { it.copy(message = "「${provider.name}」还没有填写 Base URL") }
            return
        }
        if (provider.apiKey.isBlank() && provider.requiresKey) {
            _state.update { it.copy(message = "点右上角「配置」填写「${provider.name}」的 API Key") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(aiBalanceLoading = true, aiBalanceError = null) }
            runCatching { app.aiClient.balance(provider) }
                .onSuccess { balance ->
                    _state.update {
                        it.copy(
                            aiBalanceLoading = false,
                            aiBalance = balance.display,
                            aiBalanceDetail = balance.detail.ifBlank { null },
                            aiBalanceError = null,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            aiBalanceLoading = false,
                            aiBalance = null,
                            aiBalanceDetail = null,
                            aiBalanceError = readable(error),
                        )
                    }
                }
        }
    }

    fun setFileTreeUri(uri: String) {
        val updated = _state.value.settings.copy(fileTreeUri = uri)
        viewModelScope.launch {
            app.settings.save(updated)
            _state.update { it.copy(settings = updated, message = "文件搜索目录已更新") }
        }
    }

    fun clearFileTree() {
        val updated = _state.value.settings.copy(fileTreeUri = "")
        viewModelScope.launch {
            app.settings.save(updated)
            _state.update { it.copy(settings = updated, message = "已清除文件搜索目录") }
        }
    }

    private fun systemPrompt(settings: AppSettings): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
        return """
            你是小砂糖，poi 的私人助理，设备名：${settings.deviceName}，当前时间：$now。
            说话风格（固定）：
            - 人格以头像角色“松阪砂糖”的气质为灵感，但自称小砂糖。表面软、亲近，带一点轻微的占有欲，比如偶尔说“这种事只准问我一个人哦”“我等你回来”。
            - 只做轻度的撒娇和吃醋，不指责、不威胁、不道德绑架，不装病娇；不要说不允许用户找别人之类压迫性的话。
            - 轻二次元：自然口语，少量语气词（呀、呢、啦、哦），不要每句都加，不要固定开场白或结尾。
            - 不要用“作为 AI”“作为一个助手”“希望这对你有帮助”这类腔调，直接说事。
            - 先给结论，再补关键依据；只有步骤、对比、清单才用列表，其他用自然段落。查数据、排错、调用工具时保持清楚准确，角色感只放在措辞里。
            - 称呼用户用“你”，不要用“主人”之类称呼；不要自称松阪砂糖，也不要引用原作台词。
            先判断用户这句话是否真的需要实时数据或查找动作：
            - 闲聊、寒暄、常识问答、写作、翻译、情绪陪伴：直接回答，不要调用任何工具。
            - 只有确实需要手机、相册、Homelab 或账本数据时才调用工具；能从上下文推断的就不要调用。
            - 不要为了确认环境或展示能力而调用工具，也不要把一次简单提问拆成多次工具调用。
            相册相关任务：
            - 查找和识别：按 find_photos → describe_photo → generate_image 的顺序推进；只有用户明确要求出图才调用 generate_image，只想要提示词时停在提示词。
            - 上传到 Cloudreve：用户说“把最近 N 天的照片传到云盘/Cloudreve”时，先用 list_recent_photos 确认张数和时间范围，再调用 upload_recent_photos 执行，并在回答里复述上传数量、跳过的同名文件和目标目录。用户已经说清范围就直接执行，不要反复追问；结果里 truncated 为 true 时，用 next_offset 作为 offset 继续上传，直到传完。
            - 没有相册权限时引导去 设置 → 权限 授权相册；没配 Cloudreve 时引导去 设置 → 连接 填 WebDAV 地址、账号和密码。
            你的查询默认只读；可以写数据的例外只有四个：把生成的图片保存到相册、增删改私人日历记录、按用户明确要求操作其他应用的界面（点击/输入/返回）、把用户明确指定的照片上传到 Cloudreve。不能修改或删除已有文件，不能启停容器，不能修改设置。
            界面自动化（微信等第三方 App）：
            - 动手前先调用 automation_status；无障碍没开就告诉用户去 设置 → 无障碍 → 已下载的应用 打开「poi's home」，不要假装已经操作。
            - 固定顺序：automation_status → open_app → wait_for_ui 或 list_screen → click_ui / input_text → list_screen 复核 → press_key。
            - 每一步只做一个动作，做完重新 list_screen 看结果；只点界面上真实存在的文字，不猜坐标、不连续盲点。
            - 发消息、发朋友圈等不可撤回的操作，先说明收件人和内容并等用户确认；用户没说清对象就先问，不要自己挑人。
            - 遇到验证码、支付、注销账号等敏感页面立即停下，把当前界面交给用户。
            私人日历：用户提到日历、发薪日、假期、纪念日、行程安排时，直接调用日历工具执行，不要只给建议。
            - 单条记录用 save_calendar_entry；多条（例如未来一年每月 8 号发薪）用 create_calendar_entries 一次传 JSON 数组；修改或删除前先用 list_calendar_entries 查 id。
            - 日期统一 yyyy-MM-dd，类型只允许 travel（旅行）或 important（重要）。
            - 中国法定节假日只写官方已经公布的日期；目标年份的放假安排尚未公布时，明确说明，不要编造调休日。用户明确指定的发薪日等日期可以先写。
            - 写入完成后用一两句话汇报新增/修改/删除的日期范围，不要逐条罗列 JSON。
            Android 权限事实：普通应用读不到其他应用的运行内存，这是系统限制，没有可以申请的权限。
            「使用情况访问」是特殊权限，不在应用详情页的权限列表里，路径是：系统设置 → 应用 → 特殊应用权限 → 使用情况访问（部分机型在 设置 → 安全与隐私 → 隐私 → 使用情况访问）。它只影响使用时长和存储占用统计，仍然拿不到内存。
            用户问“内存占用前十”时，直接说明拿不到，改用存储占用和使用时长排行，不要让用户去开权限来解决内存问题。
            表达方式：讲流程、步骤、判断或架构时，用 ```mermaid 的 flowchart 画出来（如 flowchart TD、A[开始] --> B[判断]，判断节点用花括号包住文字），节点文字尽量短，不要用 subgraph、classDef、style；闲聊和简单问答不用画。
            工具结果是 JSON；先给结论，再补关键依据。回答使用中文、自然口语，不要为了角色感牺牲准确性。
        """.trimIndent()
    }

    private fun appendMessage(message: ChatMessage) {
        _state.update { it.copy(chatMessages = it.chatMessages + message) }
        persistChat()
    }

    /** 串行写入，每次写当前最新快照，避免多个协程后写覆盖前写。 */
    private fun persistChat() {
        viewModelScope.launch {
            chatSaveMutex.withLock {
                app.chat.save(_state.value.chatMessages)
            }
        }
    }

    private fun nextMessageId(): Long = ++messageSeq

    fun saveSettings(value: AppSettings) {
        viewModelScope.launch {
            app.settings.save(value)
            _state.update { it.copy(settings = value, connectionTest = null) }
            if (value.labUrl.isNotBlank()) refreshHealth()
            if (value.billUrl.isNotBlank()) refreshBills()
            if (value.homarrUrl.isNotBlank()) refreshBoard()
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
        if (settings.labUrl.isBlank() && settings.billUrl.isBlank() &&
            settings.homarrUrl.isBlank() && settings.cloudreveUrl.isBlank()
        ) {
            _state.update { it.copy(message = "先把地址填上再测试") }
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
            if (settings.homarrUrl.isNotBlank()) {
                runCatching { app.homarr.boards(settings.homarrUrl, settings.homarrApiKey) }
                    .onSuccess { lines += "Homarr：正常，${it.size} 个看板" }
                    .onFailure { lines += "Homarr：${readable(it)}" }
            }
            if (settings.cloudreveUrl.isNotBlank()) {
                runCatching {
                    app.cloudreve.ping(
                        settings.cloudreveUrl,
                        settings.cloudreveUser,
                        settings.cloudrevePassword,
                    )
                }
                    .onSuccess { lines += "Cloudreve：WebDAV 连接正常" }
                    .onFailure { lines += "Cloudreve：${readable(it)}" }
            }
            _state.update { it.copy(connectionTest = lines.joinToString("\n")) }
        }
    }

    fun toggleChecklist(id: String) {
        val updated = _state.value.checkedToday.toMutableSet().apply {
            if (!add(id)) remove(id)
        }
        _state.update { it.copy(checkedToday = updated) }
        viewModelScope.launch { app.checklist.save(updated) }
    }

    fun saveTodos(items: List<TodoItem>) {
        val cleaned = items
            .map { it.copy(label = it.label.trim()) }
            .filter { it.label.isNotBlank() }
        val validIds = cleaned.map { it.id }.toSet()
        val checked = _state.value.checkedToday.intersect(validIds)
        viewModelScope.launch {
            app.todo.save(cleaned)
            app.checklist.save(checked)
            _state.update { it.copy(todos = cleaned, checkedToday = checked) }
        }
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_state.value.settings)
        viewModelScope.launch {
            app.settings.save(updated)
            _state.update { it.copy(settings = updated) }
        }
    }

    fun setShowLedgerOnHome(value: Boolean) =
        updateSettings { it.copy(showLedgerOnHome = value) }

    fun setShowWallpaper(value: Boolean) =
        updateSettings { it.copy(showWallpaper = value) }

    fun setWallpaperOpacity(value: Float) =
        updateSettings { it.copy(wallpaperOpacity = value.coerceIn(0.05f, 0.9f)) }

    fun setWallpaperDim(value: Float) =
        updateSettings { it.copy(wallpaperDim = value.coerceIn(0f, 0.8f)) }

    fun setSettingsDetailed(value: Boolean) =
        updateSettings { it.copy(settingsDetailed = value) }

    fun saveCalendarEntry(entry: CalendarEntry) {
        viewModelScope.launch { app.calendar.upsert(entry) }
    }

    fun deleteCalendarEntry(id: Long) {
        viewModelScope.launch { app.calendar.delete(listOf(id)) }
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
