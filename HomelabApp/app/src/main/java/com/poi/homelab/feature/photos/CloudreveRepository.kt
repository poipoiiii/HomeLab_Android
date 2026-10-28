package com.poi.homelab.feature.photos

import com.poi.homelab.feature.ai.PhotoFile
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val responseBlock = Regex(
    "<[^>]*:?response[^>]*>(.*?)</[^>]*:?response>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val hrefTag = Regex("<[^>]*:?href[^>]*>([^<]*)</[^>]*:?href>", RegexOption.IGNORE_CASE)
private val collectionTag = Regex("<[^>]*:?collection\\b", RegexOption.IGNORE_CASE)

/**
 * Cloudreve WebDAV 客户端，只覆盖照片上传需要的最小动作：
 * PROPFIND 测连接/列目录、MKCOL 建目录、HEAD 查重、PUT 上传。
 *
 * WebDAV 入口固定是站点根下的 `/dav/`，用户名和密码就是 Cloudreve 登录账号。
 */
class CloudreveRepository(http: OkHttpClient) {

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

    /** 逐张上传，目录只创建一次；同名文件跳过，不删除任何云端文件。 */
    suspend fun uploadAll(
        baseUrl: String,
        user: String,
        password: String,
        remoteDir: String,
        photos: List<PhotoFile>,
        readBytes: suspend (PhotoFile) -> ByteArray,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): BatchUploadResult = withContext(Dispatchers.IO) {
        if (photos.isEmpty()) return@withContext BatchUploadResult(0, emptyList(), emptyList(), emptyList())
        val auth = credentials(user, password)
        val folder = ensureFolders(webdavRoot(baseUrl), auth, remoteDir)
        val uploaded = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<UploadFailure>()
        photos.forEachIndexed { index, photo ->
            try {
                val bytes = readBytes(photo)
                when (val result = uploadFile(folder, auth, photo.name, photo.mime, bytes)) {
                    "uploaded" -> uploaded += photo.name
                    else -> skipped += photo.name
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failed += UploadFailure(
                    photo.name,
                    failure.message ?: failure.javaClass.simpleName,
                )
            }
            onProgress(index + 1, photos.size)
        }
        BatchUploadResult(photos.size, uploaded, skipped, failed)
    }

    private fun uploadFile(
        folder: HttpUrl,
        auth: String,
        name: String,
        mime: String,
        bytes: ByteArray,
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
            .put(bytes.toRequestBody(mime.ifBlank { "application/octet-stream" }.toMediaTypeOrNull()))
            .header("Authorization", auth)
            .build()
        client.newCall(put).execute().use { response ->
            ensureAuthorized(response.code)
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
                    ensureAuthorized(response.code)
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
