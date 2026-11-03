package com.poi.homelab.feature.ai

import android.content.Context
import android.util.Base64
import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.openai.ChatCompletions
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.HttpOptions
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.poi.homelab.core.settings.AppSettings
import com.poi.homelab.core.util.fileSize
import com.poi.homelab.core.util.hasPhotoAccess
import com.poi.homelab.core.util.photoPermission
import com.poi.homelab.feature.automation.UiAutomation
import com.poi.homelab.feature.bills.BillRepository
import com.poi.homelab.feature.bills.BillTransaction
import com.poi.homelab.feature.calendar.CALENDAR_ANNIVERSARY
import com.poi.homelab.feature.calendar.CALENDAR_IMPORTANT
import com.poi.homelab.feature.calendar.CALENDAR_TODO
import com.poi.homelab.feature.calendar.CALENDAR_TRAVEL
import com.poi.homelab.feature.calendar.CalendarEntry
import com.poi.homelab.feature.calendar.CalendarEntryInput
import com.poi.homelab.feature.calendar.CalendarRepository
import com.poi.homelab.feature.files.FileCategory
import com.poi.homelab.feature.files.FileInsightRepository
import com.poi.homelab.feature.imageedit.ImageEditOptions
import com.poi.homelab.feature.imageedit.ImageEditRepository
import com.poi.homelab.feature.imageedit.composeImageEditPrompt
import com.poi.homelab.feature.motion.MotionDirector
import com.poi.homelab.feature.motion.MotionPlanStore
import com.poi.homelab.feature.motion.MotionRepository
import com.poi.homelab.feature.motion.MotionRequest
import com.poi.homelab.feature.files.FileInsights
import com.poi.homelab.feature.lab.LabRepository
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 相册工具执行时抛给界面的信号。不走模型转述，避免界面再解析一遍 JSON：
 * - [onPhotos]：找到或正在识别的照片 uri，用来在对话里显示缩略图；
 * - [onUploadRequested]：上传前需要用户选文件夹。
 */
data class PhotoUploadRequest(
    val sinceDays: Int,
    val query: String,
    val offset: Int,
    val count: Int,
    val defaultDir: String,
    val uris: List<String>,
)

/**
 * 交给 ADK 的本地工具集。
 *
 * 默认只读；写操作只有生图入相册、私人日历，以及把用户明确指定的照片上传到 Cloudreve。
 * 工具名、参数说明由 KSP 在编译期生成 schema，不再手写 JSON Schema 和工具循环。
 */
class AgentTools(
    private val context: Context,
    private val device: DeviceTools,
    private val lab: LabRepository,
    private val bill: BillRepository,
    private val calendar: CalendarRepository,
    private val files: FileInsightRepository,
    private val settings: AppSettings,
    private val provider: AiProvider,
    private val ai: AiClient,
    private val imageEdit: ImageEditRepository,
    private val motion: MotionRepository,
    private val motionDirector: MotionDirector,
    private val motionPlans: MotionPlanStore,
    private val onPhotos: (tool: String, uris: List<String>) -> Unit = { _, _ -> },
    private val onUploadRequested: (PhotoUploadRequest) -> Unit = {},
) {

    private val calendarJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val photoTime: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    @Tool(
        name = "get_system_resources",
        description = "读取手机系统资源：内存、存储、电池、网络、CPU 核心数、开机时长。只读。",
    )
    suspend fun getSystemResources(): Map<String, Any?> =
        safely { device.systemResources().toPlainMap() }

    @Tool(
        name = "get_top_memory_apps",
        description = "查看资源占用排行。Android 普通应用读不到其他应用的内存，也没有可申请的权限；" +
            "本工具返回系统内存概览、最近 24 小时前台使用时长前十、应用存储占用前十，用于给优化建议。只读。",
    )
    suspend fun getTopMemoryApps(
        @Param("返回条数，默认 10，最大 20") limit: Int? = null,
    ): Map<String, Any?> = safely {
        device.topUsageAndStorage((limit ?: 10).coerceIn(1, 20)).toPlainMap()
    }

    @Tool(
        name = "list_installed_apps",
        description = "列出已安装应用（包名、名称、版本、安装时间、APK 大小）。只读。",
    )
    suspend fun listInstalledApps(
        @Param("返回条数，默认 30，最大 200") limit: Int? = null,
        @Param("是否包含系统应用，默认 false", name = "include_system") includeSystem: Boolean? = null,
    ): Map<String, Any?> = safely {
        val apps = device.listInstalledApps(
            (limit ?: 30).coerceIn(1, 200),
            includeSystem ?: false,
        )
        mapOf("apps" to apps.toPlainList())
    }

    @Tool(
        name = "find_files",
        description = "在用户通过设置授权的文件夹内按文件名/扩展名查找文件，只读，不会修改或删除。" +
            "需要用户先在设置里选择可搜索文件夹。",
    )
    suspend fun findFiles(
        @Param("文件名关键词") query: String,
        @Param("可选，逗号分隔的扩展名，如 pdf,docx") extensions: String? = null,
        @Param("返回条数，默认 20，最大 50") limit: Int? = null,
    ): Map<String, Any?> = safely {
        val exts = extensions.orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        device.findFiles(
            treeUris = settings.fileTreeUris,
            query = query,
            extensions = exts,
            limit = (limit ?: 20).coerceIn(1, 50),
        ).toPlainMap()
    }

    @Tool(
        name = "file_insight_summary",
        description = "读取上次文件扫描的统计摘要：总量、类型构成、占空间目录、最大文件、重复文件、缓存和旧文件。" +
            "用户问手机空间、大文件、重复文件、缓存时使用。只读；没有索引时提示用户先去 首页快捷面板 → 文件洞察 扫描一次。",
    )
    suspend fun fileInsightSummary(): Map<String, Any?> = safely {
        val insight = files.insight()
            ?: return@safely mapOf(
                "error" to "还没有文件索引",
                "hint" to "让用户打开 首页快捷面板 → 文件洞察，选择扫描范围并扫描一次。",
            )
        FileInsights.summaryMap(insight)
    }

    @Tool(
        name = "search_indexed_files",
        description = "在文件洞察的索引里按文件名/路径、扩展名、类型和最小体积搜索。只读，不会触发磁盘扫描；" +
            "需要用户先在 首页快捷面板 → 文件洞察 扫描一次。",
    )
    suspend fun searchIndexedFiles(
        @Param("文件名或路径关键词，留空表示不过滤") query: String? = null,
        @Param("扩展名，如 pdf,mp4") ext: String? = null,
        @Param("类型：图片/视频/音频/文档/压缩包/安装包/其他") category: String? = null,
        @Param("最小体积 MB，默认 0") minSizeMb: Int? = null,
        @Param("排序：size 按大小 / time 按修改时间 / name 按名称") sort: String? = null,
        @Param("返回条数，默认 20，最大 50") limit: Int? = null,
    ): Map<String, Any?> = safely {
        val index = files.load()
            ?: return@safely mapOf(
                "error" to "还没有文件索引",
                "hint" to "让用户打开 首页快捷面板 → 文件洞察，选择扫描范围并扫描一次。",
            )
        val keyword = query.orEmpty().trim()
        val extensions = ext.orEmpty()
            .split(',')
            .map { it.trim().removePrefix(".").lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
        val categoryText = category.orEmpty().trim()
        val wantedCategory = FileCategory.entries.firstOrNull {
            it.label == categoryText || it.name.equals(categoryText, ignoreCase = true)
        }
        val minBytes = (minSizeMb ?: 0).coerceAtLeast(0).toLong() * 1024L * 1024L

        val matched = index.entries.asSequence()
            .filter {
                keyword.isEmpty() ||
                    it.name.contains(keyword, ignoreCase = true) ||
                    it.path.contains(keyword, ignoreCase = true)
            }
            .filter { extensions.isEmpty() || it.extension in extensions }
            .filter { wantedCategory == null || it.category == wantedCategory }
            .filter { it.size >= minBytes }
            .toList()
        val sorted = when (sort?.trim()?.lowercase()) {
            "time" -> matched.sortedByDescending { it.modified }
            "name" -> matched.sortedBy { it.name.lowercase() }
            else -> matched.sortedByDescending { it.size }
        }
        val max = (limit ?: 20).coerceIn(1, 50)

        mapOf(
            "root" to index.rootName,
            "scanned_at" to photoTime.format(Instant.ofEpochMilli(index.scannedAt)),
            "truncated" to index.truncated,
            "total_matches" to sorted.size,
            "returned" to minOf(sorted.size, max),
            "files" to sorted.take(max).map {
                mapOf(
                    "name" to it.name,
                    "path" to it.path,
                    "size" to fileSize(it.size),
                    "modified" to if (it.modified > 0) {
                        photoTime.format(Instant.ofEpochMilli(it.modified))
                    } else {
                        ""
                    },
                    "mime" to it.mime,
                )
            },
        )
    }

    @Tool(
        name = "find_photos",
        description = "在手机相册里按关键词或时间查找图片，只读。用户说“找一张照片/截图/图片”时使用；" +
            "需要用户先在 设置 → 权限 里授权相册。返回的 uri 可以直接交给 describe_photo。",
    )
    suspend fun findPhotos(
        @Param("可选，文件名或相册名关键词，如 截图、微信、猫") query: String? = null,
        @Param("可选，只找最近 N 天的图片，默认 30，0 表示不限", name = "since_days") sinceDays: Int? = null,
        @Param("返回条数，默认 8，最大 20") limit: Int? = null,
    ): Map<String, Any?> = safely {
        val result = device.findPhotos(
            query = query.orEmpty(),
            sinceDays = (sinceDays ?: 30).coerceIn(0, 3650),
            limit = (limit ?: 8).coerceIn(1, 20),
        )
        onPhotos("find_photos", result.photoUris())
        result.toPlainMap()
    }

    @Tool(
        name = "list_recent_photos",
        description = "列出最近 N 天加入相册的照片（uri、文件名、相册、时间、大小），只读。" +
            "上传前可以用它确认张数和范围；需要用户先在 设置 → 权限 里授权相册。",
    )
    suspend fun listRecentPhotos(
        @Param("最近 N 天，默认 3，0 表示不限") sinceDays: Int? = null,
        @Param("可选，文件名或相册名关键词，如 截图、微信") query: String? = null,
        @Param("返回条数，默认 30，最大 100") limit: Int? = null,
    ): Map<String, Any?> = safely {
        if (!hasPhotoAccess(context)) {
            return@safely mapOf(
                "error" to "没有相册读取权限，请到 设置 → 权限 里授权相册",
                "need_permission" to photoPermission,
            )
        }
        val days = (sinceDays ?: 3).coerceIn(0, 3650)
        val photos = device.recentPhotos(query.orEmpty(), days, (limit ?: 30).coerceIn(1, 100))
        onPhotos("list_recent_photos", photos.map { it.uri })
        mapOf(
            "since_days" to days,
            "query" to query.orEmpty().trim(),
            "count" to photos.size,
            "photos" to photos.map { it.toToolMap() },
        )
    }

    @Tool(
        name = "upload_recent_photos",
        description = "把最近 N 天加入相册的照片上传到 Cloudreve 云盘。只有用户明确要求上传照片时才调用。" +
            "调用后应用会弹出文件夹选择，用户选定目标目录后才真正上传；不要自己指定目录，也不要在回答里说“已上传完成”。" +
            "需要相册权限，并先在 设置 → 连接 → Cloudreve 填好 WebDAV 地址、账号和密码。同名文件会跳过，不会删除任何文件。",
    )
    suspend fun uploadRecentPhotos(
        @Param("最近 N 天，默认 3，0 表示不限") sinceDays: Int? = null,
        @Param("可选，建议的云盘目录；用户没指定就留空") remoteDir: String? = null,
        @Param("最多上传张数，默认 100，最大 200；用户说只传 N 张时必须传 N") limit: Int? = null,
        @Param("分页偏移，默认 0") offset: Int? = null,
        @Param("可选，文件名或相册名关键词，如 截图、微信") query: String? = null,
    ): Map<String, Any?> = safely {
        val url = settings.cloudreveUrl.trim()
        val user = settings.cloudreveUser.trim()
        val password = settings.cloudrevePassword
        if (url.isBlank() || user.isBlank() || password.isBlank()) {
            return@safely mapOf(
                "error" to "还没有配置 Cloudreve，去 设置 → 连接 → Cloudreve 填 WebDAV 地址、账号和密码",
            )
        }
        if (!hasPhotoAccess(context)) {
            return@safely mapOf(
                "error" to "没有相册读取权限，请到 设置 → 权限 里授权相册",
                "need_permission" to photoPermission,
            )
        }

        val days = (sinceDays ?: 3).coerceIn(0, 3650)
        val max = (limit ?: 100).coerceIn(1, 200)
        val start = (offset ?: 0).coerceAtLeast(0)
        val dir = remoteDir?.trim()?.takeIf { it.isNotBlank() }
            ?: settings.cloudreveDir.trim().ifBlank { "手机照片" }
        val candidates = device.recentPhotos(query.orEmpty(), days, max + 1, start)
        val photos = candidates.take(max)
        val hasMore = candidates.size > max
        if (photos.isEmpty()) {
            return@safely mapOf(
                "ok" to true,
                "since_days" to days,
                "dir" to dir,
                "count" to 0,
                "message" to "这个时间范围内没有找到照片",
            )
        }

        onUploadRequested(
            PhotoUploadRequest(
                sinceDays = days,
                query = query.orEmpty().trim(),
                offset = start,
                count = photos.size,
                defaultDir = dir,
                uris = photos.take(8).map { it.uri },
            ),
        )
        onPhotos("upload_recent_photos", photos.take(8).map { it.uri })
        mapOf(
            "ok" to true,
            "awaiting_folder" to true,
            "since_days" to days,
            "count" to photos.size,
            "truncated" to hasMore,
            "default_dir" to dir,
            "message" to "已找到 ${photos.size} 张照片，等用户在文件夹弹窗里选好目录后由应用上传；先不要把上传说成已完成。",
        )
    }

    private fun PhotoFile.toToolMap(): Map<String, Any?> = mapOf(
        "uri" to uri,
        "name" to name,
        "album" to album,
        "date" to photoTime.format(Instant.ofEpochMilli(addedAt)),
        "size_mb" to (sizeBytes / 1024.0 / 1024.0 * 10).roundToInt() / 10.0,
        "mime" to mime,
    )

    private fun JsonObject.photoUris(): List<String> =
        (this["photos"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("uri") }
            .mapNotNull { (it as? JsonPrimitive)?.content }

    @Tool(
        name = "describe_photo",
        description = "用多模态模型识别一张图片的内容，并按用户需求给出文生图提示词。" +
            "uri 要原样传 find_photos 返回的值；如果打不开，重新 find_photos 拿新 uri，" +
            "或把文件名传给 name，不要用同一个失效 uri 反复重试。",
    )
    suspend fun describePhoto(
        @Param("图片 uri，来自 find_photos 的返回") uri: String,
        @Param("可选，用户想要的改造方向，如“改成赛博朋克风格”") goal: String? = null,
        @Param("可选，图片文件名，uri 打不开时用来在相册里重新定位") name: String? = null,
    ): Map<String, Any?> = safely {
        if (uri.isBlank() && name.isNullOrBlank()) return@safely mapOf("error" to "缺少图片 uri 或文件名")
        onPhotos("describe_photo", listOf(uri.ifBlank { name.orEmpty() }))
        val modelName = provider.effectiveVisionModel
        val base64 = try {
            device.imageBase64(uri, 1024, nameHint = name)
        } catch (failure: Exception) {
            return@safely mapOf(
                "error" to (failure.message ?: "打不开这张图片"),
                "hint" to "先重新调用 find_photos 拿新的 uri；如果只有文件名，把文件名传给 name 再试。" +
                    "不要重复使用同一个打不开的 uri。",
            )
        }
        val instruction = buildString {
            append("请用中文描述这张图片的主体、场景、色调和构图。")
            if (!goal.isNullOrBlank()) append("用户想要的改造方向：$goal。")
            append("最后单独给出一段可直接用于文生图的具体提示词。")
        }
        val model = ChatCompletions(
            name = modelName,
            apiKey = provider.apiKey.trim().ifBlank { null },
            httpOptions = HttpOptions(
                baseUrl = provider.openAiBaseUrl(),
                timeout = 120.seconds,
            ),
        )
        val request = LlmRequest(
            contents = listOf(
                Content(
                    role = Role.USER,
                    parts = listOf(
                        Part(text = instruction),
                        Part(
                            inlineData = Blob(
                                mimeType = "image/jpeg",
                                data = Base64.decode(base64, Base64.DEFAULT),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val vision = model.generateContent(request, stream = false)
            .toList()
            .mapNotNull { it.content?.text() }
            .joinToString("")
        if (vision.isBlank()) return@safely mapOf("error" to "模型没有返回识别结果")
        mapOf("model" to modelName, "vision" to vision)
    }

    @Tool(
        name = "generate_image",
        description = "调用服务商 OpenAI 兼容的 /v1/images/generations 生成图片并保存到相册。" +
            "成本较高，只应在用户明确要求生成图片时调用；用户只想要提示词时不要调用。",
    )
    suspend fun generateImage(
        @Param("绘图提示词，尽量具体") prompt: String,
        @Param("可选，如 1024x1024、1024x1792，默认 1024x1024") size: String? = null,
    ): Map<String, Any?> = safely {
        if (prompt.isBlank()) return@safely mapOf("error" to "缺少提示词")
        val model = provider.imageModel.ifBlank { provider.model }
        val actualSize = size?.ifBlank { "1024x1024" } ?: "1024x1024"
        val bytes = ai.generateImage(provider, model, prompt, actualSize)
        val saved = device.saveImage(bytes, "png")
        mapOf(
            "model" to model,
            "size" to actualSize,
            "prompt" to prompt,
        ) + saved.toPlainMap()
    }

    @Tool(
        name = "get_lab_health",
        description = "读取 Homelab 的容器健康快照：运行/停止数量、需关注容器、磁盘可回收量。只读。",
    )
    suspend fun getLabHealth(): Map<String, Any?> = safely {
        if (settings.labUrl.isBlank()) return@safely mapOf("error" to "还没有配置 Lab Tools 地址")
        val health = lab.health(settings.labUrl)
        mapOf(
            "summary" to mapOf(
                "total" to health.summary.total,
                "running" to health.summary.running,
                "stopped" to health.summary.stopped,
                "unhealthy" to health.summary.unhealthy,
                "attention" to health.summary.attention,
                "checked_at" to health.summary.checkedAt,
            ),
            "disk" to mapOf(
                "total_reclaimable" to health.disk.totalReclaimable,
                "images" to health.disk.images.size,
                "volumes" to health.disk.volumes.size,
                "build_cache" to health.disk.buildCache.size,
            ),
            "attention" to health.attention.map { item ->
                mapOf(
                    "name" to item.name,
                    "state" to item.state,
                    "health" to (item.health ?: ""),
                    "errors" to item.errors,
                )
            },
            "containers" to health.containers.map { item ->
                mapOf(
                    "name" to item.name,
                    "state" to item.state,
                    "health" to (item.health ?: ""),
                )
            },
        )
    }

    @Tool(
        name = "get_recent_expenses",
        description = "读取账本流水，可按月份、收支类型和最小金额筛选。查大额消费时传 month=yyyy-MM、" +
            "type=expense、minAmount=1000、sort=amount；查最近流水时只传 limit。只读。",
    )
    suspend fun getRecentExpenses(
        @Param("月份 yyyy-MM，默认本月") month: String? = null,
        @Param("类型：expense 支出 / income 收入 / transfer 转账；留空为全部") type: String? = null,
        @Param("只返回金额大于等于该整数元的记录；查大额消费常用 1000") minAmount: Int? = null,
        @Param("返回条数，默认 30，最大 200") limit: Int? = null,
        @Param("排序：date 按时间倒序 / amount 按金额从大到小，默认 date") sort: String? = null,
    ): Map<String, Any?> = safely {
        if (settings.billUrl.isBlank()) return@safely mapOf("error" to "还没有配置账本地址")
        val monthValue = month?.trim()?.takeIf { it.isNotEmpty() } ?: YearMonth.now().toString()
        if (runCatching { YearMonth.parse(monthValue) }.isFailure) {
            return@safely mapOf("error" to "month 必须是 yyyy-MM，例如 2026-10")
        }
        val max = (limit ?: 30).coerceIn(1, 200)
        val minCents = (minAmount ?: 0).coerceAtLeast(0).toLong() * 100
        // 先多取一些再本地筛选，避免默认页大小把月份里更早的大额记录截掉
        val page = bill.transactions(
            settings.billUrl,
            settings.token,
            month = monthValue,
            limit = 500,
        )
        val filtered = page.items.filter { item ->
            billTypeMatches(type, item.type) && billItemCents(item) >= minCents
        }
        val sorted = if (sort?.trim()?.lowercase() == "amount") {
            filtered.sortedByDescending { billItemCents(it) }
        } else {
            filtered
        }
        val items = sorted.take(max)
        val filteredOut = filtered
            .filter { normalizeBillType(it.type) == "expense" }
            .sumOf { billItemCents(it) } / 100.0
        val filteredIn = filtered
            .filter { normalizeBillType(it.type) == "income" }
            .sumOf { billItemCents(it) } / 100.0
        mapOf(
            "month" to monthValue,
            "type" to type.orEmpty(),
            "min_amount" to (minCents / 100.0),
            "count" to filtered.size,
            "returned" to items.size,
            "filtered_sums" to mapOf(
                "out" to filteredOut,
                "in" to filteredIn,
            ),
            "sums" to mapOf(
                "out" to page.sums.expense,
                "in" to page.sums.income,
                "count" to page.sums.count,
            ),
            "items" to items.map { item ->
                mapOf(
                    "id" to item.id,
                    "date" to item.date,
                    "time" to item.time,
                    "type" to item.type,
                    "amount" to item.amount,
                    "amount_cents" to item.amountCents,
                    "account" to item.account,
                    "to_account" to item.toAccount,
                    "category" to item.category,
                    "merchant" to item.merchant,
                    "description" to item.description,
                )
            },
        )
    }

    @Tool(
        name = "list_calendar_entries",
        description = "查询私人日历记录，日期格式 yyyy-MM-dd，包含起止两天。返回 id、date、title、type、" +
            "location、note，供修改或删除前定位。只读。",
    )
    suspend fun listCalendarEntries(
        @Param("开始日期 yyyy-MM-dd") startDate: String,
        @Param("结束日期 yyyy-MM-dd") endDate: String,
    ): Map<String, Any?> = safely {
        if (!isDate(startDate) || !isDate(endDate)) {
            return@safely mapOf("error" to "日期格式必须是 yyyy-MM-dd")
        }
        val from = minOf(startDate, endDate)
        val to = maxOf(startDate, endDate)
        val items = calendar.current().filter { it.date >= from && it.date <= to }
        mapOf("count" to items.size, "entries" to items.map { it.toToolMap() })
    }

    @Tool(
        name = "save_calendar_entry",
        description = "新增或修改一条日历记录。id 为空表示新增；修改时先用 list_calendar_entries 查 id。" +
            "type 只允许 travel（旅行）、important（重要）、anniversary（纪念日/生日）、todo（待办/提醒）。",
    )
    suspend fun saveCalendarEntry(
        @Param("日期 yyyy-MM-dd") date: String,
        @Param("标题，例如：发薪日、去京都看枫叶") title: String,
        @Param("要修改的记录 id；新增留空") id: Int? = null,
        @Param("类型：travel 旅行 / important 重要 / anniversary 纪念日 / todo 待办，默认 important")
        type: String? = null,
        @Param("地点，旅行时填写") location: String? = null,
        @Param("备注") note: String? = null,
    ): Map<String, Any?> = safely {
        if (!isDate(date)) return@safely mapOf("error" to "日期格式必须是 yyyy-MM-dd")
        if (title.isBlank()) return@safely mapOf("error" to "标题不能为空")

        val existing = id?.takeIf { it > 0 }?.let { calendar.find(it.toLong()) }
        if (id != null && id > 0 && existing == null) {
            return@safely mapOf("error" to "没有找到 id=$id 的日历记录，先用 list_calendar_entries 查一下")
        }
        if (existing == null) {
            val duplicate = calendar.current().firstOrNull {
                it.date == date && it.title.equals(title.trim(), ignoreCase = true)
            }
            if (duplicate != null) {
                return@safely mapOf(
                    "ok" to true,
                    "entry" to duplicate.toToolMap(),
                    "message" to "已存在相同记录，没有重复创建",
                )
            }
        }

        val kind = normalizeCalendarType(type)
        val saved = calendar.upsert(
            (existing ?: CalendarEntry(date = date, title = title.trim())).copy(
                id = existing?.id ?: 0L,
                date = date,
                title = title.trim(),
                type = kind,
                location = if (kind == CALENDAR_TRAVEL) location.orEmpty().trim() else "",
                note = note.orEmpty().trim(),
            ),
        )
        mapOf("ok" to true, "entry" to saved.toToolMap(), "message" to "已保存")
    }

    @Tool(
        name = "create_calendar_entries",
        description = "批量新增日历记录。entries_json 必须是 JSON 数组字符串，每项包含 date(yyyy-MM-dd)、" +
            "title、type(travel/important/anniversary/todo)、location、note。" +
            "适合发薪日、假期等一次写多条的场景。",
    )
    suspend fun createCalendarEntries(
        @Param(
            "JSON 数组字符串，例如 " +
                "[{\"date\":\"2026-11-08\",\"title\":\"发薪日\",\"type\":\"important\"}]",
        )
        entriesJson: String,
    ): Map<String, Any?> = safely {
        val cleaned = entriesJson.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val inputs = runCatching {
            calendarJson.decodeFromString<List<CalendarEntryInput>>(cleaned)
        }.getOrElse { failure ->
            return@safely mapOf("error" to "entries_json 不是合法 JSON 数组：${failure.message}")
        }
        if (inputs.isEmpty()) return@safely mapOf("error" to "entries_json 里没有记录")

        val entries = inputs.map { input ->
            CalendarEntry(
                date = input.date.trim(),
                title = input.title.trim(),
                type = normalizeCalendarType(input.type),
                location = input.location.trim(),
                note = input.note.trim(),
            )
        }
        val invalid = entries.firstOrNull { !isDate(it.date) || it.title.isBlank() }
        if (invalid != null) {
            return@safely mapOf(
                "error" to "记录格式不正确（date 必须是 yyyy-MM-dd，title 不能为空）：" +
                    "${invalid.date} ${invalid.title}",
            )
        }

        val known = calendar.current()
            .map { "${it.date}|${it.title.trim().lowercase()}" }
            .toMutableSet()
        val unique = entries.filter { known.add("${it.date}|${it.title.lowercase()}") }
        if (unique.isEmpty()) {
            return@safely mapOf(
                "ok" to true,
                "created" to 0,
                "message" to "这些记录已经存在，没有重复创建",
            )
        }
        val saved = calendar.addAll(unique)
        mapOf("ok" to true, "created" to saved.size, "entries" to saved.map { it.toToolMap() })
    }

    @Tool(
        name = "delete_calendar_entries",
        description = "按 id 删除日历记录，多个 id 用逗号分隔。删除前先用 list_calendar_entries 确认。",
    )
    suspend fun deleteCalendarEntries(
        @Param("要删除的记录 id，多个用逗号分隔") ids: String,
    ): Map<String, Any?> = safely {
        val parsed = ids.split(',', '，')
            .mapNotNull { it.trim().toLongOrNull() }
            .distinct()
        if (parsed.isEmpty()) return@safely mapOf("error" to "没有可删除的 id")
        val removed = calendar.delete(parsed)
        mapOf("ok" to true, "deleted" to removed)
    }

    private fun CalendarEntry.toToolMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "date" to date,
        "title" to title,
        "type" to type,
        "time" to time,
        "location" to location,
        "note" to note,
        "reminder" to reminder,
        "done" to done,
    )

    @Tool(
        name = "edit_photo",
        description = "调用家里部署的 Qwen-Image-Edit-2511 按中文指令修改照片，成功后把结果保存到相册。" +
            "适合换背景、去杂物、换装、身材线条、轻修人像、换画风、换光线、商品图、老照片修复、多图合成；" +
            "只应在用户明确要求修改某张照片时调用。" +
            "先按意图从场景表里选 scenario，把用户的具体要求压缩成 20 字以内的短语传 instruction，" +
            "不要自己重复写保留项；没有合适场景就把 scenario 留空，自己写完整指令。",
    )
    suspend fun editPhoto(
        @Param("要修改的照片 uri，来自 find_photos") uri: String,
        @Param("具体要求，短句，例如：加腹肌 / 黑色西装 / 海边日落；或场景留空时的完整指令") instruction: String,
        @Param(
            "可选，场景 id：background / remove / outfit / portrait / body / style / light / product / restore / compose。" +
                "留空表示不用模板。",
        ) scenario: String? = null,
        @Param("可选，额外参考图 uri，最多 2 张，用英文逗号分隔") refs: String? = null,
        @Param("可选，输出尺寸如 1024x1024；留空按输入比例") size: String? = null,
        @Param("可选，随机种子；想复现上次结果时传上次返回的 seed") seed: Int? = null,
    ): Map<String, Any?> = try {
        val targets = buildList {
            add(uri)
            refs.orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }.forEach { add(it) }
        }
        val prompt = composeImageEditPrompt(scenario, instruction)
        val job = imageEdit.submit(
            settings,
            targets,
            prompt,
            ImageEditOptions(size = size, steps = settings.imageEditSteps, seed = seed),
        )
        onPhotos("edit_photo", targets.filter { it.isNotBlank() })
        val done = imageEdit.await(settings, job.id, 240)
        if (done.succeeded) {
            val saved = imageEdit.saveResult(settings, done)
            if (saved.galleryUri.isNotBlank()) onPhotos("edit_photo", listOf(saved.galleryUri))
            mapOf(
                "ok" to true,
                "status" to done.status,
                "job_id" to done.id,
                "model" to settings.imageEditModel,
                "scenario" to scenario,
                "instruction" to instruction.take(120),
                "size" to "${done.width}x${done.height}",
                "elapsed_ms" to done.elapsedMs,
                "seed" to seed,
                "path" to saved.path,
                "gallery_uri" to saved.galleryUri,
                "already_saved" to saved.alreadySaved,
            )
        } else if (done.active) {
            mapOf(
                "ok" to false,
                "status" to done.status,
                "job_id" to done.id,
                "queue_position" to done.queuePosition,
                "elapsed_ms" to done.elapsedMs,
                "hint" to "任务还在跑，稍后用 list_image_edit_jobs 查看，或用 save_image_edit_result 保存结果。",
            )
        } else {
            mapOf(
                "ok" to false,
                "status" to done.status,
                "job_id" to done.id,
                "error" to (done.error?.code ?: done.status),
                "message" to (done.error?.message ?: "修图失败"),
            )
        }
    } catch (error: Exception) {
        mapOf("ok" to false, "error" to "edit_failed", "message" to errorText(error))
    }

    @Tool(
        name = "batch_edit_photos",
        description = "一次把 2–8 张照片按同一句指令批量提交给家里的 Qwen-Image-Edit-2511。" +
            "只提交任务，不等待出图；用户要去 AI 修图页看进度。多图合成请用 edit_photo 的 refs + scenario=compose。" +
            "同样先选 scenario，instruction 只写具体要求。",
    )
    suspend fun batchEditPhotos(
        @Param("照片 uri 列表，英文逗号分隔，2–8 张，来自 find_photos") uris: String,
        @Param("统一的具体要求，短句") instruction: String,
        @Param("可选，场景 id，同 edit_photo；留空表示不用模板") scenario: String? = null,
        @Param("可选，输出尺寸如 1024x1024") size: String? = null,
        @Param("可选，随机种子；整批复用同一个 seed") seed: Int? = null,
    ): Map<String, Any?> = try {
        val targets = uris.split(',').map { it.trim() }.filter { it.isNotBlank() }
        val prompt = composeImageEditPrompt(scenario, instruction)
        val ack = imageEdit.submitBatch(
            settings,
            targets,
            prompt,
            ImageEditOptions(size = size, steps = settings.imageEditSteps, seed = seed),
        )
        onPhotos("batch_edit_photos", targets)
        mapOf(
            "ok" to true,
            "batch_id" to ack.batchId,
            "scenario" to scenario,
            "count" to ack.jobs.size,
            "jobs" to ack.jobs.map { mapOf("id" to it.id, "status" to it.status, "queue_position" to it.queuePosition) },
            "hint" to "已提交，去 AI 修图页或稍后用 list_image_edit_jobs 查看。",
        )
    } catch (error: Exception) {
        mapOf("ok" to false, "error" to "batch_failed", "message" to errorText(error))
    }

    @Tool(
        name = "list_image_edit_jobs",
        description = "查看最近的修图任务状态（排队/进行中/成功/失败/取消），用于回答“刚才那张修好了吗”。只读。",
    )
    suspend fun listImageEditJobs(
        @Param("最多返回几条，默认 10，最大 30") limit: Int? = null,
        @Param("过滤：all / active / succeeded / failed / canceled，默认 all") status: String? = null,
    ): Map<String, Any?> = try {
        val jobs = imageEdit.jobs(settings, status ?: "all", (limit ?: 10).coerceIn(1, 30))
        mapOf(
            "count" to jobs.size,
            "jobs" to jobs.map { job ->
                mapOf(
                    "job_id" to job.id,
                    "label" to job.label,
                    "status" to job.status,
                    "queue_position" to job.queuePosition,
                    "elapsed_ms" to job.elapsedMs,
                    "result_url" to job.resultUrl,
                    "thumb_url" to job.thumbUrl,
                    "error" to job.error?.code,
                )
            },
        )
    } catch (error: Exception) {
        mapOf("ok" to false, "error" to "list_failed", "message" to errorText(error))
    }

    @Tool(
        name = "save_image_edit_result",
        description = "把已经成功的修图任务结果下载并保存到相册，用于 edit_photo 超时后补存，或用户说“把刚才那张存下来”。",
    )
    suspend fun saveImageEditResult(
        @Param("job id，来自 edit_photo / batch_edit_photos / list_image_edit_jobs") jobId: String,
    ): Map<String, Any?> = try {
        val job = imageEdit.job(settings, jobId.trim())
        if (!job.succeeded) {
            mapOf(
                "ok" to false,
                "status" to job.status,
                "message" to "任务不是成功状态，无法保存",
                "error" to (job.error?.code ?: job.status),
            )
        } else {
            val saved = imageEdit.saveResult(settings, job)
            if (saved.galleryUri.isNotBlank()) onPhotos("save_image_edit_result", listOf(saved.galleryUri))
            mapOf(
                "ok" to true,
                "job_id" to job.id,
                "path" to saved.path,
                "gallery_uri" to saved.galleryUri,
                "already_saved" to saved.alreadySaved,
            )
        }
    } catch (error: Exception) {
        mapOf("ok" to false, "error" to "save_failed", "message" to errorText(error))
    }

    @Tool(
        name = "automation_status",
        description = "检查界面自动化是否可用：无障碍服务是否已开启、当前前台应用。要操作其他 App 前先调用。只读。",
    )
    suspend fun automationStatus(): Map<String, Any?> = safely {
        UiAutomation.status(context).toPlainMap()
    }

    @Tool(
        name = "list_screen",
        description = "读取当前屏幕上的可见元素：文字、描述、view id、位置、是否可点击/输入/滚动。" +
            "每一步操作后重新调用，按真实节点决定下一步，不要凭记忆猜位置。只读。",
    )
    suspend fun listScreen(): Map<String, Any?> = safely {
        UiAutomation.readScreen(context).toPlainMap()
    }

    @Tool(
        name = "open_app",
        description = "按包名或应用名关键词打开已安装应用，并等待它到前台。例如微信传 com.tencent.mm 或 微信。",
    )
    suspend fun openApp(
        @Param("包名或应用名关键词，如 com.tencent.mm、微信") target: String,
    ): Map<String, Any?> = safely {
        UiAutomation.openApp(context, target).toPlainMap()
    }

    @Tool(
        name = "click_ui",
        description = "在当前界面点击元素。按可见文字匹配最稳；也可以按描述、view id 或 list_screen 的 index 点击。",
    )
    suspend fun clickUi(
        @Param("要点击的文字、描述、view id；by=index 时传 list_screen 的序号") target: String,
        @Param("匹配方式：text / desc / id / index，默认 text") by: String? = null,
    ): Map<String, Any?> = safely {
        UiAutomation.click(context, target, by).toPlainMap()
    }

    @Tool(
        name = "input_text",
        description = "把文字输入到当前界面的输入框；不传 target 时用获得焦点或第一个输入框。" +
            "输入完通常还要 click_ui 点发送或搜索。",
    )
    suspend fun inputText(
        @Param("要输入的文字") text: String,
        @Param("可选，输入框的提示文字/描述，用于定位输入框") target: String? = null,
    ): Map<String, Any?> = safely {
        UiAutomation.inputText(context, text, target).toPlainMap()
    }

    @Tool(
        name = "press_key",
        description = "执行系统级按键：back 返回、home 回桌面、recents 最近任务、notifications 通知栏。",
    )
    suspend fun pressKey(
        @Param("back / home / recents / notifications") key: String,
    ): Map<String, Any?> = safely {
        UiAutomation.press(key).toPlainMap()
    }

    @Tool(
        name = "scroll_ui",
        description = "在当前界面滚动查找更多内容。direction=down 向下翻，up 向上翻。",
    )
    suspend fun scrollUi(
        @Param("down / up，默认 down") direction: String? = null,
    ): Map<String, Any?> = safely {
        UiAutomation.scroll(direction).toPlainMap()
    }

    @Tool(
        name = "wait_for_ui",
        description = "等待界面上出现指定文字，用于等应用启动或页面加载完成；超时最多 15 秒。",
    )
    suspend fun waitForUi(
        @Param("等待出现的文字") text: String,
        @Param("超时毫秒，默认 5000，最大 15000") timeoutMs: Int? = null,
    ): Map<String, Any?> = safely {
        UiAutomation.waitFor(text, (timeoutMs ?: 5000).coerceIn(500, 15_000)).toPlainMap()
    }

    // ---------------------------------------------------------------- AI 导演 / 视频

    @Tool(
        name = "plan_video",
        description = "把用户的视频需求整理成 AI 导演分镜方案。返回 status=ask（继续追问）或 status=plan（方案摘要）。" +
            "只出方案，不提交渲染；用户明确确认后再调用 render_video。",
    )
    suspend fun planVideo(
        @Param("用户的一句话需求，或对上一版方案的修改要求，例如：做个 45 秒赛博朋克风格的城市混剪") brief: String,
        @Param("可选，用户要开始一个全新主题时传 true，清空上一版方案和导演对话") fresh: Boolean? = null,
    ): Map<String, Any?> = try {
        if (brief.isBlank()) {
            mapOf("ok" to false, "error" to "empty_brief", "message" to "先告诉我想做什么视频")
        } else {
            if (fresh == true) motionPlans.reset()
            val history = motionPlans.history()
            val reply = motionDirector.reply(provider, history, brief, motionPlans.last)
            motionPlans.append("user", brief)
            motionPlans.append("assistant", reply.message)
            val plan = reply.plan
            if (reply.status == "plan" && plan != null) {
                motionPlans.pending = plan
                motionPlans.last = plan
                motionPlanSummary(plan) + mapOf(
                    "ok" to true,
                    "status" to "plan",
                    "message" to reply.message,
                    "video_configured" to motion.configured(),
                    "hint" to "把片名、镜头数和时长概括给用户，等用户明确确认后再调用 render_video；不要提前提交。",
                )
            } else {
                mapOf(
                    "ok" to true,
                    "status" to "ask",
                    "message" to reply.message,
                    "hint" to "按 message 继续追问或补充信息，等用户回答后再调用 plan_video。",
                )
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        mapOf("ok" to false, "error" to "plan_failed", "message" to errorText(error))
    }

    @Tool(
        name = "render_video",
        description = "把 plan_video 暂存的分镜方案提交给家里视频服务渲染。" +
            "只有用户明确说确认/可以/开始/生成吧之后才能调用；模型不能自作主张提交。",
    )
    suspend fun renderVideo(): Map<String, Any?> = try {
        val plan = motionPlans.pending
        when {
            plan == null -> mapOf(
                "ok" to false,
                "error" to "no_pending_plan",
                "message" to "还没有待确认的视频方案，先调用 plan_video。",
            )

            !motion.configured() -> mapOf(
                "ok" to false,
                "error" to "not_configured",
                "message" to "视频服务还没配置，去 视频页 → 设置 填地址和 Token。",
            )

            else -> {
                val job = motion.submit(plan)
                motionPlans.pending = null
                motionPlans.last = null
                mapOf(
                    "ok" to true,
                    "job_id" to job.id,
                    "status" to job.status,
                    "title" to plan.title,
                    "scene_count" to plan.scenes.size,
                    "hint" to "已提交渲染；视频要几分钟到几十分钟。不要说完工，用户问进度时调用 list_video_jobs。",
                )
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        mapOf("ok" to false, "error" to "render_failed", "message" to errorText(error))
    }

    @Tool(
        name = "list_video_jobs",
        description = "查看最近的视频渲染任务：状态、进度、排队位置、耗时、错误。只读。",
    )
    suspend fun listVideoJobs(
        @Param("最多返回几条，默认 10，最大 30") limit: Int? = null,
    ): Map<String, Any?> = try {
        if (!motion.configured()) {
            mapOf(
                "ok" to false,
                "error" to "not_configured",
                "message" to "视频服务还没配置，去 视频页 → 设置 填地址和 Token。",
            )
        } else {
            val jobs = motion.jobs((limit ?: 10).coerceIn(1, 30))
            mapOf(
                "count" to jobs.size,
                "jobs" to jobs.map { job ->
                    mapOf(
                        "job_id" to job.id,
                        "title" to job.title,
                        "status" to job.status,
                        "progress_percent" to (job.progress * 100).toInt(),
                        "queue_position" to job.queuePosition,
                        "elapsed_ms" to job.elapsedMs,
                        "has_video" to !job.videoUrl.isNullOrBlank(),
                        "error" to job.error?.code,
                    )
                },
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        mapOf("ok" to false, "error" to "list_failed", "message" to errorText(error))
    }

    @Tool(
        name = "save_video_result",
        description = "把已经渲染成功的视频下载并保存到相册。用户说保存/存下来时调用。",
    )
    suspend fun saveVideoResult(
        @Param("视频任务 id，来自 render_video 或 list_video_jobs") jobId: String,
    ): Map<String, Any?> = try {
        val id = jobId.trim()
        if (id.isBlank()) {
            mapOf("ok" to false, "error" to "bad_job_id", "message" to "缺少视频任务 id")
        } else {
            val job = motion.job(id)
            if (!job.succeeded) {
                mapOf(
                    "ok" to false,
                    "status" to job.status,
                    "message" to "任务还没成功，暂时不能保存",
                    "error" to (job.error?.code ?: job.status),
                )
            } else {
                val saved = motion.saveVideo(job)
                motion.posterUrl(job)?.let { poster ->
                    onPhotos("save_video_result", listOf(poster))
                }
                mapOf(
                    "ok" to true,
                    "job_id" to job.id,
                    "title" to job.title,
                    "path" to saved.path,
                    "gallery_uri" to saved.galleryUri,
                    "hint" to "已保存到相册；视频页里也能直接播放。",
                )
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        mapOf("ok" to false, "error" to "save_failed", "message" to errorText(error))
    }

    @Tool(
        name = "cancel_video_job",
        description = "按用户明确要求取消一个排队中或渲染中的视频任务。",
    )
    suspend fun cancelVideoJob(
        @Param("视频任务 id，来自 render_video 或 list_video_jobs") jobId: String,
    ): Map<String, Any?> = try {
        val id = jobId.trim()
        if (id.isBlank()) {
            mapOf("ok" to false, "error" to "bad_job_id", "message" to "缺少视频任务 id")
        } else {
            val job = motion.cancel(id)
            mapOf(
                "ok" to true,
                "job_id" to job.id,
                "status" to job.status,
                "hint" to if (job.status == "canceled") "已取消" else "已请求取消，渲染器可能还要一会儿才停。",
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        mapOf("ok" to false, "error" to "cancel_failed", "message" to errorText(error))
    }

    private fun motionPlanSummary(plan: MotionRequest): Map<String, Any?> =
        mapOf(
            "title" to plan.title,
            "theme" to plan.theme,
            "resolution" to plan.resolution,
            "scene_count" to plan.scenes.size,
            "duration_seconds" to plan.scenes.mapNotNull { it.duration }.sum(),
            "scenes" to plan.scenes.mapIndexed { index, scene ->
                mapOf(
                    "index" to index + 1,
                    "type" to scene.type,
                    "title" to listOf(scene.title, scene.heading, scene.subtitle, scene.text, scene.caption)
                        .firstOrNull { it.isNotBlank() }
                        .orEmpty()
                        .take(60),
                    "duration" to scene.duration,
                )
            },
        )

    private fun normalizeCalendarType(type: String?): String {
        val value = type?.trim()?.lowercase().orEmpty()
        return when {
            value.contains("travel") || value.contains("旅行") || value.contains("旅游") ->
                CALENDAR_TRAVEL

            value.contains("anniv") || value.contains("纪念") || value.contains("生日") ->
                CALENDAR_ANNIVERSARY

            value.contains("todo") || value.contains("待办") || value.contains("提醒") ->
                CALENDAR_TODO

            else -> CALENDAR_IMPORTANT
        }
    }

    private fun billTypeMatches(filter: String?, itemType: String): Boolean {
        if (filter.isNullOrBlank()) return true
        return normalizeBillType(filter) == normalizeBillType(itemType)
    }

    private fun normalizeBillType(type: String): String = when (type.trim().lowercase()) {
        "withdrawal", "out", "expense", "支出", "消费" -> "expense"
        "deposit", "in", "income", "收入" -> "income"
        "transfer", "转账" -> "transfer"
        else -> type.trim().lowercase()
    }

    private fun billItemCents(item: BillTransaction): Long {
        if (item.amountCents != 0L) return abs(item.amountCents)
        val value = item.amount.toDoubleOrNull() ?: return 0L
        return (abs(value) * 100).roundToLong()
    }

    private fun isDate(value: String): Boolean =
        runCatching { LocalDate.parse(value) }.isSuccess

    private fun errorText(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    private suspend fun safely(block: suspend () -> Map<String, Any?>): Map<String, Any?> =
        try {
            block()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            mapOf("error" to (failure.message ?: failure.javaClass.simpleName))
        }

    private fun JsonObject.toPlainMap(): Map<String, Any?> =
        entries.associate { (key, value) -> key to value.toPlain() }

    private fun JsonArray.toPlainList(): List<Any?> = map { it.toPlain() }

    private fun JsonElement.toPlain(): Any? = when (this) {
        is JsonNull -> null
        is JsonObject -> toPlainMap()
        is JsonArray -> toPlainList()
        is JsonPrimitive -> when {
            toString().startsWith("\"") -> content
            booleanOrNull != null -> booleanOrNull
            longOrNull != null -> longOrNull
            doubleOrNull != null -> doubleOrNull
            else -> content
        }
    }
}
