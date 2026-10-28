package com.poi.homelab.navigation

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.poi.homelab.core.AppContainer
import com.poi.homelab.core.settings.AppSettings
import com.poi.homelab.core.settings.DEFAULT_LAB_URL
import com.poi.homelab.core.ui.theme.Success
import com.poi.homelab.feature.ai.AgentTools
import com.poi.homelab.feature.ai.AiAgent
import com.poi.homelab.feature.ai.AiClient
import com.poi.homelab.feature.ai.AiProvider
import com.poi.homelab.feature.ai.ChatMessage
import com.poi.homelab.feature.ai.DeviceTools
import com.poi.homelab.feature.ai.PhotoUploadRequest
import com.poi.homelab.feature.ai.requiresKey
import com.poi.homelab.feature.bills.AnalysisResponse
import com.poi.homelab.feature.bills.BillCommitResult
import com.poi.homelab.feature.bills.BillImportHistory
import com.poi.homelab.feature.bills.BillImportItem
import com.poi.homelab.feature.bills.BillImportStats
import com.poi.homelab.feature.bills.BillMeta
import com.poi.homelab.feature.bills.BillOverview
import com.poi.homelab.feature.bills.BillSums
import com.poi.homelab.feature.bills.BillTransaction
import com.poi.homelab.feature.bills.TransactionPage
import com.poi.homelab.feature.calendar.CalendarEntry
import com.poi.homelab.feature.homarr.HomarrApp
import com.poi.homelab.feature.homarr.HomarrBoard
import com.poi.homelab.feature.homarr.WeatherQuery
import com.poi.homelab.feature.homarr.WeatherSnapshot
import com.poi.homelab.feature.home.defaultTodoItems
import com.poi.homelab.feature.home.TodoItem
import com.poi.homelab.feature.lab.HealthSnapshot
import com.poi.homelab.feature.notify.NotifyPrefs
import com.poi.homelab.feature.photos.PhotoAutoSync
import java.io.IOException
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class PendingAction(
    val name: String,
    val action: String,
    val token: String,
    val command: String,
)

/** 上传前等用户选文件夹的状态。 */
data class PendingUpload(
    val seq: Long,
    val sinceDays: Int,
    val query: String,
    val limit: Int,
    val offset: Int,
    val count: Int,
    val defaultDir: String,
    val uris: List<String>,
    val folders: List<String> = emptyList(),
    val foldersLoading: Boolean = true,
    val foldersError: String? = null,
    val busy: Boolean = false,
    val status: String? = null,
)

data class BillImportBatch(
    val fileName: String,
    val source: String,
    val itemCount: Int,
    val stats: BillImportStats? = null,
    val imageUri: String? = null,
)

private data class PickedImportSource(
    val uri: Uri,
    val name: String,
    val mime: String,
)

private const val MAX_IMAGE_BYTES = 8L * 1024 * 1024

private fun String.importExtension(): String = substringAfterLast('.', "").lowercase(Locale.ROOT)

private fun String.isImageFile(): Boolean = importExtension() in setOf(
    "jpg", "jpeg", "png", "webp", "heic", "heif", "bmp", "gif",
)

private fun String.isCsvOrXlsx(): Boolean = importExtension() in setOf("csv", "xlsx")

private fun String.guessMime(): String = when (importExtension()) {
    "csv" -> "text/csv"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "heic", "heif" -> "image/heic"
    "bmp" -> "image/bmp"
    "gif" -> "image/gif"
    else -> "application/octet-stream"
}

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
    val billImportItems: List<BillImportItem> = emptyList(),
    val billImportMeta: BillMeta? = null,
    val billImportBatches: List<BillImportBatch> = emptyList(),
    val billImportErrors: List<String> = emptyList(),
    val billImportBusy: Boolean = false,
    val billImportProgress: String? = null,
    val billImportResult: BillCommitResult? = null,
    val billImportCloudTransactions: List<BillTransaction> = emptyList(),
    val billImportCloudLoading: Boolean = false,
    val billImportCloudError: String? = null,
    val billImportLastSync: Map<String, String> = emptyMap(),
    val actionBusy: Boolean = false,
    val pendingAction: PendingAction? = null,
    val pendingUpload: PendingUpload? = null,
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
    private var uploadSeq = 0L

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
        viewModelScope.launch {
            val lastSync = BillImportHistory.load(app.appContext)
            _state.update { it.copy(billImportLastSync = lastSync) }
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
                // 采集服务读不了 DataStore，这里把账本地址与凭证镜像给 SharedPreferences
                NotifyPrefs.sync(app.appContext, value.billUrl, value.token)
                // 照片自动同步是持久化周期任务，重复调用只会检查/补齐，不会重置排期
                PhotoAutoSync.apply(app.appContext, value.photoAutoSync)
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

    /** 首页 weather 磁贴：失败只让磁贴自己降级，不弹全局提示。 */
    suspend fun loadWeather(query: WeatherQuery): WeatherSnapshot? =
        runCatching { app.homarr.weather(query) }.getOrNull()

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
                it.copy(health = null, message = "还没配置 Lab Tools 地址")
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loadingHealth = true) }
            runCatching { app.lab.health(settings.labUrl) }
                .onSuccess { health ->
                    _state.update { it.copy(health = health, loadingHealth = false) }
                }
                .onFailure {
                    // 后台健康检查失败不打扰首页；需要结果时用设置页的「测试连接」
                    _state.update { it.copy(loadingHealth = false) }
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
                    val page = async { loadMonthTransactions(settings, month) }
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

    /**
     * 账单页按天聚合，必须把整月流水取完。
     * 服务端默认只返回 100 条，会把月初的日期整段漏掉；这里按 total 分页补齐。
     */
    private suspend fun loadMonthTransactions(
        settings: AppSettings,
        month: String,
    ): TransactionPage {
        val pageSize = 500
        val items = mutableListOf<BillTransaction>()
        var total = 0
        var sums = BillSums()
        var offset = 0
        while (items.size < 10_000) {
            val page = app.bill.transactions(
                settings.billUrl,
                settings.token,
                month,
                limit = pageSize,
                offset = offset,
            )
            total = page.total
            sums = page.sums
            if (page.items.isEmpty()) break
            items += page.items
            offset += page.items.size
            if (items.size >= total) break
        }
        return TransactionPage(items = items, total = maxOf(total, items.size), sums = sums)
    }

    fun selectMonth(month: String) {
        if (month == _state.value.billMonth) return
        _state.update { it.copy(billMonth = month) }
        refreshBills()
    }

    /** 核对列表的账户 / 分类候选。 */
    fun loadBillMeta() {
        val settings = _state.value.settings
        if (settings.billUrl.isBlank() || _state.value.billImportMeta != null) return
        viewModelScope.launch {
            runCatching { app.bill.meta(settings.billUrl) }
                .onSuccess { meta -> _state.update { it.copy(billImportMeta = meta) } }
        }
    }

    /** 选中的截图 / CSV / XLSX 先逐份解析，合并后在客户端核对。 */
    fun extractBillFiles(uris: List<Uri>, accountOverride: String? = null) {
        if (uris.isEmpty()) return
        val settings = _state.value.settings
        if (settings.billUrl.isBlank()) {
            _state.update { it.copy(message = "还没配置账本地址") }
            return
        }
        val fixedAccount = accountOverride?.trim().orEmpty()
        viewModelScope.launch {
            _state.update {
                it.copy(
                    billImportBusy = true,
                    billImportProgress = "正在读取 ${uris.size} 个文件…",
                    billImportErrors = emptyList(),
                    billImportResult = null,
                )
            }

            val context = app.appContext
            val errors = mutableListOf<String>()
            val sources = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    try {
                        Result.success(readImportSource(context, uri))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                }
            }.mapNotNull { result ->
                result.onFailure { errors += "读取文件失败：${readable(it)}" }
                result.getOrNull()
            }.filter { source ->
                if (!source.mime.startsWith("image/") &&
                    !source.name.isImageFile() &&
                    !source.name.isCsvOrXlsx()
                ) {
                    errors += "${source.name}：只支持图片、CSV、XLSX"
                    false
                } else {
                    true
                }
            }
            if (sources.isEmpty()) {
                _state.update {
                    it.copy(
                        billImportBusy = false,
                        billImportProgress = null,
                        billImportErrors = errors,
                    )
                }
                return@launch
            }

            val meta = _state.value.billImportMeta
                ?: runCatching { app.bill.meta(settings.billUrl) }.getOrNull()
            val completed = AtomicInteger(0)
            val semaphore = Semaphore(2)
            val outcomes = coroutineScope {
                sources.map { source ->
                    async {
                        semaphore.withPermit {
                            val isImage = source.mime.startsWith("image/") ||
                                source.name.isImageFile()
                            val outcome = try {
                                val bytes = withContext(Dispatchers.IO) {
                                    readImportBytes(context, source)
                                }
                                if (isImage && bytes.size > MAX_IMAGE_BYTES) {
                                    Result.failure(IOException("${source.name}：图片超过 8 MB"))
                                } else {
                                    Result.success(
                                        app.bill.extract(
                                            settings.billUrl,
                                            source.name,
                                            source.mime,
                                            bytes,
                                        ),
                                    )
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Result.failure(error)
                            }
                            val done = completed.incrementAndGet()
                            _state.update {
                                it.copy(
                                    billImportProgress = "已解析 $done/${sources.size} 个文件，正在合并…",
                                )
                            }
                            source to outcome
                        }
                    }
                }.map { it.await() }
            }

            val extracted = mutableListOf<BillImportItem>()
            val batches = mutableListOf<BillImportBatch>()
            outcomes.forEach { (source, outcome) ->
                outcome.onSuccess { response ->
                    val isImage = source.mime.startsWith("image/") || source.name.isImageFile()
                    val items = if (isImage) {
                        response.items.map {
                            it.copy(
                                account = fixedAccount.ifBlank { it.account },
                                sourceUri = source.uri.toString(),
                            )
                        }
                    } else {
                        response.items
                    }
                    extracted += items
                    batches += BillImportBatch(
                        fileName = source.name,
                        source = response.source,
                        itemCount = items.size,
                        stats = response.stats,
                        imageUri = source.uri.toString().takeIf { isImage },
                    )
                }.onFailure { error ->
                    val message = readable(error)
                    errors += if (message.contains(source.name)) message else "${source.name}：$message"
                }
            }

            val seenKeys = mutableSetOf<String>()
            val merged = (_state.value.billImportItems + extracted)
                .filter { seenKeys.add(it.importKey()) }
                .sortedWith(compareBy({ it.date }, { it.time }))
            _state.update {
                it.copy(
                    billImportItems = merged,
                    billImportMeta = meta,
                    billImportBatches = it.billImportBatches + batches,
                    billImportErrors = errors,
                    billImportBusy = false,
                    billImportProgress = null,
                )
            }
            loadImportCloudTransactions(settings, merged)
        }
    }

    /** 拉取待核对记录所在月份的云端流水，供页面按日期/金额/时间比对。 */
    private suspend fun loadImportCloudTransactions(
        settings: AppSettings,
        items: List<BillImportItem>,
    ) {
        val months = items.mapNotNull { item ->
            item.date.take(7).takeIf { it.matches(Regex("""\d{4}-\d{2}""")) }
        }.distinct()
        if (months.isEmpty()) {
            _state.update {
                it.copy(
                    billImportCloudTransactions = emptyList(),
                    billImportCloudLoading = false,
                    billImportCloudError = null,
                )
            }
            return
        }
        _state.update { it.copy(billImportCloudLoading = true, billImportCloudError = null) }
        val loaded = mutableListOf<BillTransaction>()
        var firstError: String? = null
        months.forEach { month ->
            runCatching {
                loadMonthTransactions(settings, month)
            }.onSuccess { page ->
                loaded += page.items
            }.onFailure { error ->
                if (firstError == null) firstError = readable(error)
            }
        }
        _state.update {
            it.copy(
                billImportCloudTransactions = loaded,
                billImportCloudLoading = false,
                billImportCloudError = firstError,
            )
        }
    }

    fun refreshBillImportCloud() {
        val settings = _state.value.settings
        val items = _state.value.billImportItems
        if (settings.billUrl.isBlank() || items.isEmpty()) return
        viewModelScope.launch { loadImportCloudTransactions(settings, items) }
    }

    fun updateBillImportItem(index: Int, item: BillImportItem) {
        _state.update { current ->
            if (index !in current.billImportItems.indices) return@update current
            current.copy(
                billImportItems = current.billImportItems.toMutableList().apply { this[index] = item },
            )
        }
    }

    fun removeBillImportItem(index: Int) {
        _state.update { current ->
            if (index !in current.billImportItems.indices) return@update current
            current.copy(
                billImportItems = current.billImportItems.toMutableList().apply { removeAt(index) },
            )
        }
    }

    fun commitBillImport() {
        val settings = _state.value.settings
        val items = _state.value.billImportItems
        if (settings.billUrl.isBlank() || items.isEmpty()) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    billImportBusy = true,
                    billImportProgress = "正在写入账本…",
                    billImportResult = null,
                )
            }
            try {
                val result = app.bill.commit(settings.billUrl, items)
                val failed = failedImportItems(items, result)
                val successful = items.filterNot { failed.contains(it) }
                val syncDates = successful
                    .filter { it.account.isNotBlank() && it.date.isNotBlank() }
                    .groupBy { it.account.trim() }
                    .mapValues { (_, group) -> group.maxOf { it.date } }
                val lastSync = if (syncDates.isEmpty()) {
                    _state.value.billImportLastSync
                } else {
                    withContext(Dispatchers.IO) {
                        BillImportHistory.record(app.appContext, syncDates)
                    }
                }
                _state.update {
                    it.copy(
                        billImportBusy = false,
                        billImportProgress = null,
                        billImportItems = failed,
                        billImportResult = result,
                        billImportLastSync = lastSync,
                    )
                }
                refreshBills()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        billImportBusy = false,
                        billImportProgress = null,
                        billImportErrors = it.billImportErrors + "写入失败：${readable(error)}",
                    )
                }
            }
        }
    }

    fun clearBillImport() {
        _state.update {
            it.copy(
                billImportItems = emptyList(),
                billImportBatches = emptyList(),
                billImportErrors = emptyList(),
                billImportBusy = false,
                billImportProgress = null,
                billImportResult = null,
                billImportCloudTransactions = emptyList(),
                billImportCloudLoading = false,
                billImportCloudError = null,
            )
        }
    }

    /** 提交后只保留失败项，方便改完再提交。 */
    private fun failedImportItems(
        items: List<BillImportItem>,
        result: BillCommitResult,
    ): List<BillImportItem> {
        if (result.failed <= 0) return emptyList()
        val pending = result.details
            .filter { it.status == "failed" }
            .map { it.merchant to it.amount }
            .toMutableList()
        if (pending.isEmpty()) return items
        val remaining = mutableListOf<BillImportItem>()
        items.forEach { item ->
            val index = pending.indexOfFirst {
                it.first == item.merchant && sameImportAmount(it.second, item.amount)
            }
            if (index >= 0) {
                pending.removeAt(index)
                remaining += item
            }
        }
        return if (remaining.isEmpty()) items else remaining
    }

    private fun sameImportAmount(first: String, second: String): Boolean {
        val a = first.trim().toBigDecimalOrNull()
            ?.abs()
            ?.setScale(2, RoundingMode.HALF_UP)
            ?.toPlainString()
        val b = second.trim().toBigDecimalOrNull()
            ?.abs()
            ?.setScale(2, RoundingMode.HALF_UP)
            ?.toPlainString()
        return if (a != null && b != null) a == b else first.trim() == second.trim()
    }

    private fun readImportSource(context: Context, uri: Uri): PickedImportSource {
        val resolver = context.contentResolver
        val name = resolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?.takeIf { it.isNotBlank() }
            ?: "bill_${System.currentTimeMillis()}"
        val mime = resolver.getType(uri).orEmpty().ifBlank { name.guessMime() }
        return PickedImportSource(uri, name, mime)
    }

    private fun readImportBytes(context: Context, source: PickedImportSource): ByteArray =
        context.contentResolver.openInputStream(source.uri)?.use { it.readBytes() }
            ?: throw IOException("打不开这个文件：${source.name}")

    /**
     * 与 bill-ai 的提交去重规则保持一致：有订单号按订单号，否则按字段指纹。
     * 这样重叠截图里重复出现的同一笔交易，在核对阶段就会合并掉。
     */
    private fun BillImportItem.importKey(): String {
        val order = orderId.trim()
        if (order.isNotEmpty()) return "order:$order"
        val normalizedAmount = amount.trim()
            .toBigDecimalOrNull()
            ?.abs()
            ?.setScale(2, RoundingMode.HALF_UP)
            ?.toPlainString()
            ?: amount.trim()
        return listOf(type, date, normalizedAmount, merchant, account, toAccount, category)
            .joinToString("|") { it.trim() }
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
                // 工具执行时顺手把找到的照片交给界面；不走模型转述，避免再解析 JSON
                val pendingPhotos = ConcurrentHashMap<String, List<String>>()
                val tools = AgentTools(
                    context = app.appContext,
                    device = app.deviceTools,
                    lab = app.lab,
                    bill = app.bill,
                    calendar = app.calendar,
                    settings = settings,
                    provider = provider,
                    ai = app.aiClient,
                    onPhotos = { tool, uris ->
                        if (uris.isNotEmpty()) pendingPhotos[tool] = uris
                    },
                    onUploadRequested = { request -> openUploadPicker(request) },
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
                                    photos = pendingPhotos.remove(response.name).orEmpty(),
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

    /** 上传工具只负责找照片；这里弹出文件夹选择，等用户确认后再真正写入云盘。 */
    private fun openUploadPicker(request: PhotoUploadRequest) {
        val settings = _state.value.settings
        val seq = ++uploadSeq
        _state.update {
            it.copy(
                pendingUpload = PendingUpload(
                    seq = seq,
                    sinceDays = request.sinceDays,
                    query = request.query,
                    limit = request.limit,
                    offset = request.offset,
                    count = request.count,
                    defaultDir = request.defaultDir,
                    uris = request.uris,
                ),
            )
        }
        viewModelScope.launch {
            val result = runCatching {
                val root = app.cloudreve.listFolders(
                    settings.cloudreveUrl,
                    settings.cloudreveUser,
                    settings.cloudrevePassword,
                )
                val sub = runCatching {
                    app.cloudreve.listFolders(
                        settings.cloudreveUrl,
                        settings.cloudreveUser,
                        settings.cloudrevePassword,
                        request.defaultDir,
                    )
                }.getOrDefault(emptyList())
                val prefix = request.defaultDir.trim().trim('/')
                (listOf(request.defaultDir.trim()) + root + sub.map {
                    if (prefix.isBlank()) it else "$prefix/$it"
                })
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(40)
            }
            _state.update { current ->
                val pending = current.pendingUpload
                if (pending == null || pending.seq != seq) {
                    current
                } else {
                    current.copy(
                        pendingUpload = pending.copy(
                            folders = result.getOrDefault(emptyList()),
                            foldersLoading = false,
                            foldersError = result.exceptionOrNull()?.let { readable(it) },
                        ),
                    )
                }
            }
        }
    }

    /** 用户在弹窗里选好目录后真正开始上传，分批处理超过单批上限的照片。 */
    fun startPendingUpload(dir: String) {
        val pending = _state.value.pendingUpload ?: return
        if (pending.busy) return
        val target = dir.trim().ifBlank { pending.defaultDir }
        val settings = _state.value.settings
        _state.update {
            it.copy(
                pendingUpload = pending.copy(
                    busy = true,
                    status = "准备上传 ${pending.count} 张…",
                ),
            )
        }
        viewModelScope.launch {
            var uploaded = 0
            var skipped = 0
            val failures = mutableListOf<String>()
            var error: String? = null
            try {
                var offset = pending.offset
                var batches = 0
                while (batches < 10) {
                    val candidates = app.deviceTools.recentPhotos(
                        query = pending.query,
                        sinceDays = pending.sinceDays,
                        limit = pending.limit + 1,
                        offset = offset,
                    )
                    val photos = candidates.take(pending.limit)
                    if (photos.isEmpty()) break
                    val batch = app.cloudreve.uploadAll(
                        baseUrl = settings.cloudreveUrl,
                        user = settings.cloudreveUser,
                        password = settings.cloudrevePassword,
                        remoteDir = target,
                        photos = photos,
                        readBytes = { app.deviceTools.photoBytes(it.uri) },
                        onProgress = { done, _ ->
                            _state.update { current ->
                                current.pendingUpload?.let { state ->
                                    current.copy(
                                        pendingUpload = state.copy(
                                            status = "正在上传 ${uploaded + done} 张…",
                                        ),
                                    )
                                } ?: current
                            }
                        },
                    )
                    uploaded += batch.uploaded.size
                    skipped += batch.skipped.size
                    failures += batch.failed.map { "${it.name}：${it.error}" }
                    offset += photos.size
                    batches++
                    if (candidates.size <= pending.limit) break
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = readable(failure)
            }
            _state.update { it.copy(pendingUpload = null) }
            val summary = buildString {
                append("上传到「").append(target).append("」：")
                if (error != null) {
                    append("失败 —— ").append(error)
                } else {
                    append("成功 ").append(uploaded).append(" 张")
                    if (skipped > 0) append("，跳过 ").append(skipped).append(" 张同名文件")
                    if (failures.isNotEmpty()) {
                        append("，失败 ").append(failures.size).append(" 张：")
                        append(failures.take(3).joinToString("；"))
                    }
                }
            }
            appendMessage(
                ChatMessage(
                    id = nextMessageId(),
                    role = "tool",
                    content = summary,
                    toolName = "upload_recent_photos",
                ),
            )
            _state.update { it.copy(message = summary.take(120)) }
        }
    }

    fun cancelPendingUpload() {
        val pending = _state.value.pendingUpload ?: return
        if (pending.busy) return
        _state.update { it.copy(pendingUpload = null) }
        appendMessage(
            ChatMessage(
                id = nextMessageId(),
                role = "tool",
                content = "已取消上传，云盘没有改动。",
                toolName = "upload_recent_photos",
            ),
        )
    }

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
            - 查找和识别：find_photos 返回后，应用会把照片缩略图显示在对话里；调用 describe_photo 时把 uri 原样传过去。如果 describe_photo 返回 error（打不开图片），重新 find_photos 拿新 uri，或把照片文件名传给 name 再试一次，不要用同一个失效 uri 连续重试。
            - 只想要提示词就停在 describe_photo；只有用户明确要求出图才调用 generate_image。
            - 上传到 Cloudreve：用户说“把最近 N 天的照片传到云盘/Cloudreve”时，直接调用 upload_recent_photos。调用后应用会弹出文件夹选择，用户选定目录后才真正上传；回答里只说明找到多少张、请用户在弹出的窗口里选目录，不要说“已上传完成”。用户取消弹窗时不要自动重试上传。
            - 没有相册权限时引导去 设置 → 权限 授权相册；没配 Cloudreve 时引导去 设置 → 连接 填 WebDAV 地址、注册邮箱和 Cloudreve WebDAV 账户生成的密码（不是登录密码）。
            你的查询默认只读；可以写数据的例外只有四个：把生成的图片保存到相册、增删改私人日历记录、按用户明确要求操作其他应用的界面（点击/输入/返回）、把用户明确指定的照片上传到 Cloudreve。不能修改或删除已有文件，不能启停容器，不能修改设置。
            账本问题：
            - 用户问某个月/最近一个月的大额消费、支出排行、某笔之外还有什么时，调用 get_recent_expenses；不要把默认返回条数当成全部流水。
            - 明确月份就传 month=yyyy-MM；不确定时用本月。
            - 只看消费传 type=expense；大额消费再传 minAmount（例如 1000）和 sort=amount，limit 建议 30 到 50，最多 200。
            - 返回里的 sums 是整月合计，filtered_sums 才是按 month/type/minAmount 筛选后的合计；算大额消费合计用后者。
            - 用户点名排除的转账或某笔（例如给老爸转的 4000）先从结果里剔除，并说明它是转账而不是消费；不要把转账混进消费合计。
            - 回答先给结论或合计，再列金额最大的几笔；如果 count 大于 returned，说明只列了前 N 笔。
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
