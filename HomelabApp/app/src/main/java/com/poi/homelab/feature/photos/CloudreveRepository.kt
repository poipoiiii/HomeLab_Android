package com.poi.homelab.feature.photos

import com.poi.homelab.feature.ai.PhotoFile
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

private val responseBlock = Regex(
    "<[^>]*:?response[^>]*>(.*?)</[^>]*:?response>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val monthFormat = DateTimeFormatter.ofPattern("yyyy-MM")
private val hrefTag = Regex("<[^>]*:?href[^>]*>([^<]*)</[^>]*:?href>", RegexOption.IGNORE_CASE)
private val collectionTag = Regex("<[^>]*:?collection\\b", RegexOption.IGNORE_CASE)

/**
 * Cloudreve WebDAV 客户端，只覆盖照片上传需要的最小动作：
 * PROPFIND 测连接/列目录、MKCOL 建目录、HEAD 查重、PUT 上传。
 *
 * WebDAV 入口固定是站点根下的 `/dav/`。Basic Auth 的账号是 Cloudreve 注册邮箱，
 * 密码是在 Cloudreve「WebDAV」页面创建 WebDAV 账户时生成的密码，不是登录密码。
 */
class CloudreveRepository(http: OkHttpClient) {

    private companion object {
        /** 并发上传数：家用上行带宽有限，3 路足够摊掉往返延迟，又不至于压垮服务端。 */
        const val MAX_PARALLEL = 3
    }

    private val client = http.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    data class UploadFailure(val name: String, val error: String)

    data class BatchUploadResult(
        val total: Int,
        val uploaded: List<String>,
        val skipped: List<String>,
        val failed: List<UploadFailure>,
    )

    suspend fun ping(baseUrl: String, user: String, password: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(webdavRoot(baseUrl))
            .method("PROPFIND", null)
            .header("Depth", "0")
            .header("Authorization", credentials(user, password))
            .build()
        client.newCall(request).execute().use { response ->
            ensureAuthorized(response)
            if (!response.isSuccessful && response.code != 207) {
                throw IOException("Cloudreve WebDAV 返回 HTTP ${response.code}")
            }
        }
    }

    /** 列出 [path] 下的子文件夹名（不含文件），用于让用户选择上传目标目录。 */
    suspend fun listFolders(
        baseUrl: String,
        user: String,
        password: String,
        path: String = "",
    ): List<String> = withContext(Dispatchers.IO) {
        val target = resolvePath(webdavRoot(baseUrl), path)
        val body = """<?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>
        """.trimIndent()
        val request = Request.Builder()
            .url(target)
            .method(
                "PROPFIND",
                body.toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull()),
            )
            .header("Depth", "1")
            .header("Authorization", credentials(user, password))
            .build()
        val xml = client.newCall(request).execute().use { response ->
            ensureAuthorized(response)
            if (!response.isSuccessful && response.code != 207) {
                throw IOException("读取 Cloudreve 目录失败：HTTP ${response.code}")
            }
            response.body.string()
        }
        parseFolders(xml, target)
    }

    /**
     * 逐张上传，按拍摄日期分目录：根目录/设备名/年-月/年-月-日/，同一天的图落在同一层。
     * 同目录下同名文件跳过，不删除任何云端文件；最多 [MAX_PARALLEL] 张并发上传。
     */
    suspend fun uploadAll(
        baseUrl: String,
        user: String,
        password: String,
        remoteDir: String,
        photos: List<PhotoFile>,
        deviceName: String = "",
        readBytes: suspend (PhotoFile) -> ByteArray,
        onProgress: (done: Int, total: Int, written: Long, totalBytes: Long) -> Unit =
            { _, _, _, _ -> },
    ): BatchUploadResult = withContext(Dispatchers.IO) {
        if (photos.isEmpty()) return@withContext BatchUploadResult(0, emptyList(), emptyList(), emptyList())
        val auth = credentials(user, password)
        val root = webdavRoot(baseUrl)
        // 目录先串行建好，避免并发 MKCOL 撞车
        val folders = photos
            .map { datedDir(remoteDir, deviceName, it) }
            .distinct()
            .associateWith { ensureFolders(root, auth, it) }
        val totalBytes = photos.sumOf { it.sizeBytes }
        val written = AtomicLong(0)
        val done = AtomicInteger(0)
        val lock = Any()
        val uploaded = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<UploadFailure>()
        val gate = Semaphore(MAX_PARALLEL)
        coroutineScope {
            photos.forEach { photo ->
                launch(Dispatchers.IO) {
                    gate.withPermit {
                        val folder = folders.getValue(datedDir(remoteDir, deviceName, photo))
                        var localWritten = 0L
                        try {
                            val bytes = readBytes(photo)
                            val result = uploadFile(folder, auth, photo.name, photo.mime, bytes) { count ->
                                localWritten += count
                                val now = written.addAndGet(count.toLong())
                                onProgress(done.get(), photos.size, now, totalBytes)
                            }
                            synchronized(lock) {
                                if (result == "uploaded") uploaded += photo.name else skipped += photo.name
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            synchronized(lock) {
                                failed += UploadFailure(
                                    photo.name,
                                    failure.message ?: failure.javaClass.simpleName,
                                )
                            }
                        } finally {
                            // 跳过/失败的照片也要补上剩余字节，进度条才会走到头
                            val gap = (photo.sizeBytes - localWritten).coerceAtLeast(0)
                            if (gap > 0) written.addAndGet(gap)
                            onProgress(done.incrementAndGet(), photos.size, written.get(), totalBytes)
                        }
                    }
                }
            }
        }
        BatchUploadResult(photos.size, uploaded, skipped, failed)
    }

    /** 设备名/年-月/年-月-日，空段自动跳过；设备名用于隔离多台手机的路径和同名文件。 */
    private fun datedDir(root: String, deviceName: String, photo: PhotoFile): String {
        val date = Instant.ofEpochMilli(photo.capturedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        return listOf(
            root.trim().trim('/', '\\'),
            deviceName.trim().replace('/', '_').replace('\\', '_'),
            monthFormat.format(date),
            date.toString(),
        )
            .filter { it.isNotBlank() }
            .joinToString("/")
    }

    private fun uploadFile(
        folder: HttpUrl,
        auth: String,
        name: String,
        mime: String,
        bytes: ByteArray,
        onChunk: (Int) -> Unit = {},
    ): String {
        val fileName = safeFileName(name)
        val target = folder.newBuilder().addPathSegment(fileName).build()

        val head = Request.Builder()
            .url(target)
            .head()
            .header("Authorization", auth)
            .build()
        client.newCall(head).execute().use { response ->
            ensureAuthorized(response)
            // HEAD 不被支持时返回 405，继续尝试上传；200 说明云端已有同名文件。
            if (response.isSuccessful) return "skipped"
        }

        val put = Request.Builder()
            .url(target)
            .put(
                ProgressRequestBody(
                    bytes = bytes,
                    mime = mime.ifBlank { "application/octet-stream" }.toMediaTypeOrNull(),
                    onChunk = onChunk,
                ),
            )
            .header("Authorization", auth)
            .build()
        client.newCall(put).execute().use { response ->
            ensureAuthorized(response)
            if (!response.isSuccessful) {
                throw IOException("上传失败：HTTP ${response.code}")
            }
        }
        return "uploaded"
    }

    private fun ensureFolders(root: HttpUrl, auth: String, remoteDir: String): HttpUrl {
        var current = root
        remoteDir
            .split('/', '\\')
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .forEach { segment ->
                current = current.newBuilder().addPathSegment(segment).build()
                val mkcol = Request.Builder()
                    .url(current)
                    .method("MKCOL", null)
                    .header("Authorization", auth)
                    .build()
                client.newCall(mkcol).execute().use { response ->
                    ensureAuthorized(response)
                    // 405 表示目录已存在，是正常情况。
                    if (!response.isSuccessful && response.code != 405) {
                        throw IOException("创建目录「$segment」失败：HTTP ${response.code}")
                    }
                }
            }
        return current
    }

    private fun parseFolders(xml: String, target: HttpUrl): List<String> {
        val self = decodePath(target.encodedPath).trimEnd('/')
        return responseBlock
            .findAll(xml)
            .mapNotNull { block ->
                val body = block.groupValues[1]
                if (!collectionTag.containsMatchIn(body)) return@mapNotNull null
                val href = hrefTag.find(body)?.groupValues?.get(1)
                    ?.replace("&amp;", "&")
                    ?: return@mapNotNull null
                val path = decodePath(hrefPath(href)).trimEnd('/')
                if (path == self || path.isBlank()) return@mapNotNull null
                path.substringAfterLast('/').takeIf { it.isNotBlank() }
            }
            .distinct()
            .sorted()
            .toList()
    }

    private fun hrefPath(href: String): String =
        runCatching { if (href.startsWith("http", true)) URI(href).rawPath else href }
            .getOrDefault(href)

    private fun decodePath(path: String): String = runCatching {
        URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(path)

    private fun resolvePath(root: HttpUrl, path: String): HttpUrl {
        var current = root
        path.split('/', '\\')
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .forEach { current = current.newBuilder().addPathSegment(it).build() }
        return current
    }

    private fun credentials(user: String, password: String): String =
        Credentials.basic(user.trim(), password, Charsets.UTF_8)

    private fun ensureAuthorized(response: okhttp3.Response) {
        if (response.code == 401 || response.code == 403) {
            val realm = response.header("WWW-Authenticate")?.let { " [${it}]" }.orEmpty()
            throw IOException(
                "Cloudreve 认证失败（HTTP ${response.code}）$realm：密码要用 Cloudreve「WebDAV」里" +
                    "创建 WebDAV 账户时生成的密码，不是登录密码；账号仍填注册邮箱。",
            )
        }
    }

    private fun webdavRoot(baseUrl: String): HttpUrl {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isBlank()) throw IOException("还没有填写 Cloudreve 地址")
        val withDav = if (trimmed.endsWith("/dav", ignoreCase = true)) trimmed else "$trimmed/dav"
        return "$withDav/".toHttpUrlOrNull()
            ?: throw IOException("Cloudreve 地址格式不正确：$baseUrl")
    }

    private fun safeFileName(name: String): String =
        name.trim()
            .replace('/', '_')
            .replace('\\', '_')
            .ifBlank { "photo_${System.currentTimeMillis()}.jpg" }
}

/** PUT 时按 64 KB 回报写入量，用来算上传进度和实时速度。 */
private class ProgressRequestBody(
    private val bytes: ByteArray,
    private val mime: MediaType?,
    private val onChunk: (Int) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = mime

    override fun contentLength(): Long = bytes.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(64 * 1024, bytes.size - offset)
            sink.write(bytes, offset, count)
            offset += count
            onChunk(count)
        }
    }
}
