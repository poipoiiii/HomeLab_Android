package com.poi.homelab.data

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
import com.poi.homelab.automation.UiAutomation
import com.poi.homelab.util.hasPhotoAccess
import com.poi.homelab.util.photoPermission
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

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
    private val cloudreve: CloudreveRepository,
    private val settings: AppSettings,
    private val provider: AiProvider,
    private val ai: AiClient,
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
        description = "在用户通过设置授权的目录内按文件名/扩展名查找文件，只读，不会修改或删除。" +
            "需要用户先在设置里选择可搜索目录。",
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
            treeUri = settings.fileTreeUri,
            query = query,
            extensions = exts,
            limit = (limit ?: 20).coerceIn(1, 50),
        ).toPlainMap()
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
        device.findPhotos(
            query = query.orEmpty(),
            sinceDays = (sinceDays ?: 30).coerceIn(0, 3650),
            limit = (limit ?: 8).coerceIn(1, 20),
        ).toPlainMap()
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
        mapOf(
            "since_days" to days,
            "query" to query.orEmpty().trim(),
            "count" to photos.size,
            "photos" to photos.map { it.toToolMap() },
        )
    }

    @Tool(
        name = "upload_recent_photos",
        description = "把最近 N 天加入相册的照片上传到 Cloudreve 云盘。只有用户明确要求上传照片时才调用；" +
            "动手前先复述时间范围、张数和目标目录，用户已经说清楚就直接执行。需要相册权限，" +
            "并先在 设置 → 连接 → Cloudreve 填好 WebDAV 地址、账号和密码。同名文件会跳过，不会删除任何文件。" +
            "返回 truncated 为 true 时，把 next_offset 传给 offset 再调一次，直到所有照片都处理完。",
    )
    suspend fun uploadRecentPhotos(
        @Param("最近 N 天，默认 3，0 表示不限") sinceDays: Int? = null,
        @Param("可选，云盘目录，默认用设置里的 Cloudreve 目录") remoteDir: String? = null,
        @Param("最多上传张数，默认 100，最大 200") limit: Int? = null,
        @Param("分页偏移，默认 0；返回 truncated 为 true 时用 next_offset 继续") offset: Int? = null,
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

        val uploaded = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<Map<String, Any?>>()
        photos.forEach { photo ->
            try {
                val bytes = device.photoBytes(photo.uri)
                val result = cloudreve.uploadPhoto(url, user, password, dir, photo.name, photo.mime, bytes)
                if (result.status == "uploaded") {
                    uploaded += photo.name
                } else {
                    skipped += photo.name
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failed += mapOf(
                    "name" to photo.name,
                    "error" to (failure.message ?: failure.javaClass.simpleName),
                )
            }
        }
        mapOf(
            "ok" to failed.isEmpty(),
            "since_days" to days,
            "dir" to dir,
            "total" to photos.size,
            "uploaded" to uploaded.size,
            "skipped" to skipped.size,
            "failed" to failed.size,
            "truncated" to hasMore,
            "files" to uploaded.take(50),
            "failures" to failed.take(10),
        ).toMutableMap().apply {
            if (hasMore) put("next_offset", start + photos.size)
        }
    }

    private fun PhotoFile.toToolMap(): Map<String, Any?> = mapOf(
        "uri" to uri,
        "name" to name,
        "album" to album,
        "date" to photoTime.format(Instant.ofEpochMilli(addedAt)),
        "size_mb" to (sizeBytes / 1024.0 / 1024.0 * 10).roundToInt() / 10.0,
        "mime" to mime,
    )

    @Tool(
        name = "describe_photo",
        description = "用多模态模型识别一张图片的内容，并按用户需求给出文生图提示词。" +
            "先用 find_photos 拿到 uri；需要当前服务商支持图片输入。",
    )
    suspend fun describePhoto(
        @Param("图片 uri，来自 find_photos 的返回") uri: String,
        @Param("可选，用户想要的改造方向，如“改成赛博朋克风格”") goal: String? = null,
    ): Map<String, Any?> = safely {
        if (uri.isBlank()) return@safely mapOf("error" to "缺少图片 uri")
        val modelName = provider.effectiveVisionModel
        val base64 = device.imageBase64(uri, 1024)
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
        description = "读取账本最近流水（金额、分类、商户、时间）。只读。",
    )
    suspend fun getRecentExpenses(
        @Param("返回条数，默认 10，最大 30") limit: Int? = null,
    ): Map<String, Any?> = safely {
        if (settings.billUrl.isBlank()) return@safely mapOf("error" to "还没有配置账本地址")
        val page = bill.transactions(
            settings.billUrl,
            settings.token,
            month = null,
            limit = (limit ?: 10).coerceIn(1, 30),
        )
        mapOf(
            "sums" to mapOf(
                "out" to page.sums.expense,
                "in" to page.sums.income,
                "count" to page.sums.count,
            ),
            "items" to page.items.map { item ->
                mapOf(
                    "date" to item.date,
                    "time" to item.time,
                    "type" to item.type,
                    "amount" to item.amount,
                    "account" to item.account,
                    "category" to item.category,
                    "merchant" to item.merchant,
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
            "type 只允许 travel（旅行）或 important（重要）。",
    )
    suspend fun saveCalendarEntry(
        @Param("日期 yyyy-MM-dd") date: String,
        @Param("标题，例如：发薪日、去京都看枫叶") title: String,
        @Param("要修改的记录 id；新增留空") id: Int? = null,
        @Param("类型：travel 旅行 / important 重要，默认 important") type: String? = null,
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
            "title、type(travel/important)、location、note。适合发薪日、假期等一次写多条的场景。",
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
        "location" to location,
        "note" to note,
    )

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

    private fun normalizeCalendarType(type: String?): String {
        val value = type?.trim()?.lowercase().orEmpty()
        return if (value.contains("travel") || value.contains("旅行") || value.contains("旅游")) {
            CALENDAR_TRAVEL
        } else {
            CALENDAR_IMPORTANT
        }
    }

    private fun isDate(value: String): Boolean =
        runCatching { LocalDate.parse(value) }.isSuccess

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
