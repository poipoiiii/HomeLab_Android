package com.poi.homelab.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Cloudreve WebDAV 客户端，只覆盖照片上传需要的最小动作：
 * PROPFIND 测连接、MKCOL 建目录、HEAD 查重、PUT 上传。
 *
 * WebDAV 入口固定是站点根下的 `/dav/`，用户名和密码就是 Cloudreve 登录账号。
 */
class CloudreveRepository(http: OkHttpClient) {

    private val client = http.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    data class UploadResult(
        val name: String,
        val status: String,
        val message: String? = null,
    )

    suspend fun ping(baseUrl: String, user: String, password: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(webdavRoot(baseUrl))
            .method("PROPFIND", null)
            .header("Depth", "0")
            .header("Authorization", credentials(user, password))
            .build()
        client.newCall(request).execute().use { response ->
            ensureAuthorized(response.code)
            if (!response.isSuccessful && response.code != 207) {
                throw IOException("Cloudreve WebDAV 返回 HTTP ${response.code}")
            }
        }
    }

    suspend fun uploadPhoto(
        baseUrl: String,
        user: String,
        password: String,
        remoteDir: String,
        name: String,
        mime: String,
        bytes: ByteArray,
    ): UploadResult = withContext(Dispatchers.IO) {
        val auth = credentials(user, password)
        val folder = ensureFolders(webdavRoot(baseUrl), user, password, remoteDir)
        val fileName = safeFileName(name)
        val target = folder.newBuilder().addPathSegment(fileName).build()

        val head = Request.Builder()
            .url(target)
            .head()
            .header("Authorization", auth)
            .build()
        client.newCall(head).execute().use { response ->
            ensureAuthorized(response.code)
            // HEAD 不被支持时返回 405，继续尝试上传；200 说明云端已有同名文件。
            if (response.isSuccessful) {
                return@withContext UploadResult(fileName, "skipped", "云端已有同名文件")
            }
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
        UploadResult(fileName, "uploaded")
    }

    private fun ensureFolders(
        root: HttpUrl,
        user: String,
        password: String,
        remoteDir: String,
    ): HttpUrl {
        val segments = remoteDir
            .split('/', '\\')
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "." && it != ".." }
        var current = root
        val auth = credentials(user, password)
        segments.forEach { segment ->
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

    private fun credentials(user: String, password: String): String =
        Credentials.basic(user.trim(), password, Charsets.UTF_8)

    private fun ensureAuthorized(code: Int) {
        if (code == 401 || code == 403) {
            throw IOException("Cloudreve 账号或密码不对（HTTP $code）")
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
