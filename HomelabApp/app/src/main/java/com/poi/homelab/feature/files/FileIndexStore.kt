package com.poi.homelab.feature.files

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 文件索引落盘：应用私有目录下的 gzip JSON，重启后可直接复用。 */
class FileIndexStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val folder = File(context.filesDir, "files")
    private val indexFile = File(folder, "index.json.gz")
    private val tempFile = File(folder, "index.json.gz.tmp")

    suspend fun load(): FileIndexSnapshot? = withContext(Dispatchers.IO) {
        if (!indexFile.exists()) return@withContext null
        runCatching {
            GZIPInputStream(indexFile.inputStream().buffered()).bufferedReader().use { reader ->
                json.decodeFromString<FileIndexSnapshot>(reader.readText())
            }
        }.getOrNull()
    }

    suspend fun save(snapshot: FileIndexSnapshot) = withContext(Dispatchers.IO) {
        folder.mkdirs()
        runCatching {
            GZIPOutputStream(tempFile.outputStream().buffered()).bufferedWriter().use { writer ->
                writer.write(json.encodeToString(snapshot))
            }
        }.getOrElse { error ->
            tempFile.delete()
            throw error
        }
        if (!tempFile.renameTo(indexFile)) {
            indexFile.delete()
            if (!tempFile.renameTo(indexFile)) {
                tempFile.delete()
                throw IOException("无法写入文件索引")
            }
        }
    }

    fun clear() {
        indexFile.delete()
        tempFile.delete()
    }
}
