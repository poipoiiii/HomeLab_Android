package com.poi.homelab.feature.ai

import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import com.poi.homelab.core.util.hasPhotoAccess
import com.poi.homelab.core.util.hasUsageAccess
import com.poi.homelab.core.util.photoPermission
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/** 相册里的一张原始照片，上传时直接读 [uri] 的字节。 */
data class PhotoFile(
    val uri: String,
    val name: String,
    val album: String,
    val addedAt: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val mime: String,
)

class DeviceTools(private val context: Context) {

    suspend fun systemResources(): JsonObject = withContext(Dispatchers.IO) {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memory)
        val internal = StatFs(Environment.getDataDirectory().absolutePath)
        val externalDir = context.getExternalFilesDir(null)
        val external = externalDir?.let { StatFs(it.absolutePath) }

        buildJsonObject {
            put("device", buildJsonObject {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("android", Build.VERSION.RELEASE)
                put("sdk", Build.VERSION.SDK_INT)
            })
            put("memory", buildJsonObject {
                put("total_mb", mb(memory.totalMem))
                put("available_mb", mb(memory.availMem))
                put("used_mb", mb(memory.totalMem - memory.availMem))
                put("low_memory", memory.lowMemory)
                put("threshold_mb", mb(memory.threshold))
            })
            put("storage", buildJsonObject {
                put("internal_total_mb", mb(internal.totalBytes))
                put("internal_free_mb", mb(internal.availableBytes))
                if (external != null) {
                    put("external_total_mb", mb(external.totalBytes))
                    put("external_free_mb", mb(external.availableBytes))
                }
            })
            put("battery", batteryInfo())
            put("network", networkInfo())
            put("cpu_cores", Runtime.getRuntime().availableProcessors())
            put("uptime_minutes", SystemClock.elapsedRealtime() / 60000)
        }
    }

    suspend fun topUsageAndStorage(limit: Int): JsonObject = withContext(Dispatchers.IO) {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memory)
        val own = activityManager
            ?.getProcessMemoryInfo(intArrayOf(Process.myPid()))
            ?.firstOrNull()

        val processes = buildJsonArray {
            @Suppress("DEPRECATION")
            activityManager?.runningAppProcesses.orEmpty().forEach { processInfo ->
                val pss = runCatching {
                    activityManager?.getProcessMemoryInfo(intArrayOf(processInfo.pid))?.firstOrNull()
                }.getOrNull()
                if (pss != null && pss.totalPss > 0) {
                    add(buildJsonObject {
                        put("process", processInfo.processName)
                        put("package", processInfo.pkgList?.firstOrNull() ?: processInfo.processName)
                        put("pss_mb", pss.totalPss / 1024)
                    })
                }
            }
        }

        val usageAccess = hasUsageAccess(context)
        val usageTop = if (usageAccess) usageTop(limit) else emptyList()
        val storageTop = if (usageAccess) storageTop(limit) else emptyList()

        buildJsonObject {
            put("usage_access", usageAccess)
            put("memory", buildJsonObject {
                put("total_mb", mb(memory.totalMem))
                put("available_mb", mb(memory.availMem))
                put("used_mb", mb(memory.totalMem - memory.availMem))
                put("own_pss_mb", (own?.totalPss ?: 0) / 1024)
            })
            put("accessible_processes", processes)
            put("usage_top", JsonArray(usageTop))
            put("storage_top", JsonArray(storageTop))
            put(
                "note",
                "Android 普通应用无法读取其他应用的内存占用，也没有可申请的权限；" +
                    "usage_top 是最近 24 小时前台使用时长（需要“使用情况访问”特殊权限），" +
                    "storage_top 是应用存储占用，可作为优化参考。",
            )
        }
    }

    suspend fun listInstalledApps(limit: Int, includeSystem: Boolean): JsonArray =
        withContext(Dispatchers.IO) {
            val packageManager = context.packageManager
            val packages = runCatching { packageManager.getInstalledPackages(0) }.getOrNull().orEmpty()
            val rows = packages.asSequence()
                .filter { info ->
                    val app = info.applicationInfo ?: return@filter false
                    includeSystem || (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                }
                .mapNotNull { info ->
                    val app = info.applicationInfo ?: return@mapNotNull null
                    buildJsonObject {
                        put("package", info.packageName)
                        put(
                            "label",
                            runCatching { packageManager.getApplicationLabel(app).toString() }
                                .getOrDefault(info.packageName),
                        )
                        put("version", info.versionName.orEmpty())
                        put("system", (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                        put("install_time", formatTime(info.firstInstallTime))
                        put(
                            "apk_mb",
                            mb(runCatching { File(app.sourceDir).length() }.getOrDefault(0L)),
                        )
                    }
                }
                .sortedBy { (it["label"] as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase() }
                .take(limit)
                .toList()
            JsonArray(rows)
        }

    suspend fun findFiles(
        treeUri: String,
        query: String,
        extensions: List<String>,
        limit: Int,
    ): JsonObject = withContext(Dispatchers.IO) {
        if (treeUri.isBlank()) {
            return@withContext buildJsonObject {
                put("error", "还没有选择可搜索目录，请到设置里选择")
                put("read_only", true)
            }
        }
        val root = runCatching {
            DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
        }.getOrNull()
        if (root == null) {
            return@withContext buildJsonObject {
                put("error", "目录授权已失效，请重新选择")
                put("read_only", true)
            }
        }

        val keyword = query.trim()
        val exts = extensions
            .map { it.trim().removePrefix(".").lowercase() }
            .filter { it.isNotBlank() }
        val maxVisited = 5000
        val matches = mutableListOf<JsonObject>()
        var scanned = 0
        var truncated = false
        val stack = ArrayDeque<DocumentFile>()
        stack.add(root)

        while (stack.isNotEmpty()) {
            val directory = stack.removeLast()
            val children = runCatching { directory.listFiles() }.getOrNull().orEmpty()
            for (child in children) {
                scanned++
                if (scanned > maxVisited) {
                    truncated = true
                    break
                }
                if (child.isDirectory) {
                    stack.add(child)
                    continue
                }
                val name = child.name ?: continue
                if (keyword.isNotEmpty() && !name.contains(keyword, ignoreCase = true)) continue
                if (exts.isNotEmpty() && exts.none { name.lowercase().endsWith(".$it") }) continue
                matches += buildJsonObject {
                    put("name", name)
                    put("uri", child.uri.toString())
                    put("size_mb", mb(child.length()))
                    put("modified", formatTime(child.lastModified()))
                }
                if (matches.size >= limit) {
                    truncated = true
                    break
                }
            }
            if (truncated) break
            if (scanned % 100 == 0) yield()
        }

        buildJsonObject {
            put("query", keyword)
            put("extensions", JsonArray(exts.map { JsonPrimitive(it) }))
            put("scanned", scanned)
            put("matches", JsonArray(matches))
            put("truncated", truncated)
            put("read_only", true)
        }
    }

    suspend fun findPhotos(query: String, sinceDays: Int, limit: Int): JsonObject =
        withContext(Dispatchers.IO) {
            if (!hasPhotoAccess(context)) {
                return@withContext buildJsonObject {
                    put("error", "没有相册读取权限，请到 设置 → 权限 里授权相册")
                    put("need_permission", photoPermission)
                    put("read_only", true)
                }
            }
            val keyword = query.trim()
            val photos = runCatching { queryPhotos(keyword, sinceDays, limit) }
                .getOrElse { failure ->
                    return@withContext buildJsonObject {
                        put("error", "读取相册失败：${failure.message ?: failure.javaClass.simpleName}")
                    }
                }

            buildJsonObject {
                put("query", keyword)
                put("since_days", sinceDays)
                put("count", photos.size)
                put("photos", JsonArray(photos.map { it.toPhotoJson() }))
                put("read_only", true)
            }
        }

    /** 上传工具使用的原始照片列表，按加入相册时间倒序；offset 用于分批上传。 */
    suspend fun recentPhotos(
        query: String,
        sinceDays: Int,
        limit: Int,
        offset: Int = 0,
    ): List<PhotoFile> = withContext(Dispatchers.IO) {
        queryPhotos(query.trim(), sinceDays, limit + offset).drop(offset)
    }

    /** 读取原始照片字节，用于上传到云盘。 */
    suspend fun photoBytes(uriString: String): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(Uri.parse(uriString))?.use { it.readBytes() }
            ?: throw IllegalStateException("打不开这张照片：$uriString")
    }

    private fun queryPhotos(keyword: String, sinceDays: Int, limit: Int): List<PhotoFile> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE,
        )
        val since = if (sinceDays > 0) {
            System.currentTimeMillis() / 1000 - sinceDays * 86400L
        } else {
            0L
        }
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (since > 0) {
            clauses += "${MediaStore.Images.Media.DATE_ADDED} >= ?"
            args += since.toString()
        }
        if (keyword.isNotEmpty()) {
            clauses += "(${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? OR " +
                "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?)"
            args += "%$keyword%"
            args += "%$keyword%"
        }
        val selection = clauses.joinToString(" AND ").ifBlank { null }
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val photos = mutableListOf<PhotoFile>()

        context.contentResolver
            .query(collection, projection, selection, args.toTypedArray(), sort)
            ?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val albumIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val widthIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                while (cursor.moveToNext() && photos.size < limit) {
                    photos += PhotoFile(
                        uri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex)).toString(),
                        name = cursor.getString(nameIndex).orEmpty(),
                        album = cursor.getString(albumIndex).orEmpty(),
                        addedAt = cursor.getLong(dateIndex) * 1000,
                        sizeBytes = cursor.getLong(sizeIndex),
                        width = cursor.getInt(widthIndex),
                        height = cursor.getInt(heightIndex),
                        mime = cursor.getString(mimeIndex).orEmpty(),
                    )
                }
            }
        return photos
    }

    private fun PhotoFile.toPhotoJson(): JsonObject = buildJsonObject {
        put("uri", uri)
        put("name", name)
        put("album", album)
        put("date", formatTime(addedAt))
        put("size_mb", mb(sizeBytes))
        put("width", width)
        put("height", height)
        put("mime", mime)
    }

    /** 读取图片并压到 [maxSide] 以内，返回 JPEG 的 base64，用于多模态识图。 */
    suspend fun imageBase64(uriString: String, maxSide: Int = 1024, nameHint: String? = null): String =
        withContext(Dispatchers.IO) {
            if (!hasPhotoAccess(context)) {
                throw IllegalStateException("没有相册读取权限，请到 设置 → 权限 里授权相册")
            }
            val candidates = photoUris(uriString, nameHint)
            if (candidates.isEmpty()) throw IllegalStateException("打不开这张图片：$uriString")
            var lastError: Exception? = null
            candidates.forEach { uri ->
                try {
                    return@withContext encodePhoto(uri, maxSide)
                } catch (failure: Exception) {
                    lastError = failure
                }
            }
            throw IllegalStateException(
                "打不开这张图片：$uriString（先用 find_photos 重新拿 uri，或把文件名传给 name）",
                lastError,
            )
        }

    /**
     * 模型回传的 uri 可能带引号、转义斜杠，或只剩一个 id/文件名。
     * 这里把它还原成若干可尝试的相册 uri，按顺序逐个打开。
     */
    private fun photoUris(raw: String, nameHint: String?): List<Uri> {
        val text = raw.trim().trim('"', '\'', '`', '“', '”').replace("\\/", "/")
        val result = LinkedHashSet<Uri>()
        val parsed = runCatching { Uri.parse(text) }.getOrNull()
        if (parsed?.scheme != null) result += parsed

        val id = Regex("\\d{3,}").findAll(text).lastOrNull()?.value?.toLongOrNull()
        if (id != null && (text.isNotBlank() && text.all { it.isDigit() } ||
                text.contains("images/media"))
        ) {
            result += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        }

        val hints = buildList {
            nameHint?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (parsed?.scheme == null && text.isNotBlank()) add(text)
        }
        hints.forEach { hint -> findPhotoByName(hint)?.let { result += it } }
        return result.toList()
    }

    private fun findPhotoByName(name: String): Uri? = runCatching {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf(name),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(0),
                )
            } else {
                null
            }
        }
    }.getOrNull()

    private fun encodePhoto(uri: Uri, maxSide: Int): String {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IllegalStateException("打不开这张图片：$uri")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("无法识别图片尺寸，可能不是图片：$uri")
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        }
        val decoded = resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: throw IllegalStateException("解码图片失败：$uri")
        val scaled = scaleDown(decoded, maxSide)
        return try {
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    /** 保存生成的图片到应用目录，Android 10+ 同时写入相册。 */
    suspend fun saveImage(bytes: ByteArray, extension: String): JsonObject =
        withContext(Dispatchers.IO) {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "ai")
            if (!dir.exists() && !dir.mkdirs()) {
                throw IllegalStateException("无法创建图片目录：${dir.absolutePath}")
            }
            val name = "ai_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date()) + "." + extension
            val file = File(dir, name)
            file.writeBytes(bytes)
            val gallery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                insertToGallery(name, bytes, extension)
            } else {
                null
            }
            buildJsonObject {
                put("path", file.absolutePath)
                put("size_kb", file.length() / 1024)
                if (gallery != null) put("gallery_uri", gallery) else put("gallery_uri", "")
            }
        }

    private fun insertToGallery(name: String, bytes: ByteArray, extension: String): String? {
        val mime = if (extension.equals("jpg", ignoreCase = true) ||
            extension.equals("jpeg", ignoreCase = true)
        ) {
            "image/jpeg"
        } else {
            "image/$extension"
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HomelabAI")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = runCatching {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null
        runCatching {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
        }.onFailure {
            resolver.delete(uri, null, null)
            return null
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri.toString()
    }

    private fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / sample > maxSide && sample < 16) sample *= 2
        return sample
    }

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun usageTop(limit: Int): List<JsonObject> {
        val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
            ?: return emptyList()
        val end = System.currentTimeMillis()
        val start = end - TimeUnit.DAYS.toMillis(1)
        val stats = runCatching {
            usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
        }.getOrNull().orEmpty()

        val foreground = mutableMapOf<String, Long>()
        val lastUsed = mutableMapOf<String, Long>()
        stats.forEach { stat ->
            if (stat.totalTimeInForeground > 0) {
                foreground[stat.packageName] =
                    (foreground[stat.packageName] ?: 0L) + stat.totalTimeInForeground
                lastUsed[stat.packageName] = maxOf(
                    lastUsed[stat.packageName] ?: 0L,
                    stat.lastTimeUsed,
                )
            }
        }

        return foreground.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { (pkg, millis) ->
                buildJsonObject {
                    put("package", pkg)
                    put("label", label(pkg))
                    put("foreground_minutes", millis / 60000)
                    put("last_used", formatTime(lastUsed[pkg] ?: 0L))
                }
            }
    }

    private fun storageTop(limit: Int): List<JsonObject> {
        val packageManager = context.packageManager
        val storageStatsManager = context.getSystemService(StorageStatsManager::class.java)
            ?: return emptyList()
        val rows = mutableListOf<JsonObject>()
        val packages = runCatching { packageManager.getInstalledPackages(0) }.getOrNull().orEmpty()

        packages.take(400).forEach { info ->
            val stats = runCatching {
                storageStatsManager.queryStatsForPackage(
                    StorageManager.UUID_DEFAULT,
                    info.packageName,
                    Process.myUserHandle(),
                )
            }.getOrNull() ?: return@forEach
            val total = stats.appBytes + stats.dataBytes + stats.cacheBytes
            if (total <= 0) return@forEach
            rows += buildJsonObject {
                put("package", info.packageName)
                put("label", label(info.packageName))
                put("total_mb", mb(total))
                put("app_mb", mb(stats.appBytes))
                put("data_mb", mb(stats.dataBytes))
                put("cache_mb", mb(stats.cacheBytes))
            }
        }

        return rows
            .sortedByDescending { (it["total_mb"] as? JsonPrimitive)?.doubleOrNull ?: 0.0 }
            .take(limit)
    }

    private fun batteryInfo(): JsonObject {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val temperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1

        return buildJsonObject {
            put("percent", percent)
            put(
                "status",
                when (status) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                    BatteryManager.BATTERY_STATUS_FULL -> "full"
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                    else -> "unknown"
                },
            )
            put(
                "health",
                when (health) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
                    else -> "unknown"
                },
            )
            if (temperature > 0) put("temperature_c", temperature / 10.0) else put("temperature_c", JsonNull)
            if (voltage > 0) put("voltage_mv", voltage) else put("voltage_mv", JsonNull)
            put("plugged", plugged != 0)
        }
    }

    private fun networkInfo(): JsonObject {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivityManager?.activeNetwork
        val capabilities = network?.let { connectivityManager?.getNetworkCapabilities(it) }
        val link = network?.let { connectivityManager?.getLinkProperties(it) }
        val transports = buildList {
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("wifi")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("cellular")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ethernet")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("vpn")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) add("bluetooth")
        }
        val ipv4 = link?.linkAddresses
            ?.mapNotNull { it.address as? Inet4Address }
            ?.mapNotNull { it.hostAddress }
            .orEmpty()

        return buildJsonObject {
            put("connected", capabilities != null)
            put("transports", JsonArray(transports.map { JsonPrimitive(it) }))
            put(
                "metered",
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
            )
            put("down_kbps", capabilities?.linkDownstreamBandwidthKbps ?: 0)
            put("up_kbps", capabilities?.linkUpstreamBandwidthKbps ?: 0)
            put("ipv4", JsonArray(ipv4.map { JsonPrimitive(it) }))
        }
    }

    private fun label(packageName: String): String = runCatching {
        val packageManager = context.packageManager
        val app = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(app).toString()
    }.getOrDefault(packageName)

    private fun mb(bytes: Long): Double =
        kotlin.math.round(bytes / (1024.0 * 1024.0) * 10.0) / 10.0

    private fun formatTime(millis: Long): String {
        if (millis <= 0) return ""
        return SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(millis))
    }
}
