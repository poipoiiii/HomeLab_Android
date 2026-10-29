package com.poi.homelab.feature.files

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 文件洞察的对外入口：扫描、加载索引、筛选、AI 摘要。 */
class FileInsightRepository(private val context: Context) {

    private val scanner = FileScanner(context)
    private val store = FileIndexStore(context)
    private val mutex = Mutex()

    @Volatile
    private var snapshot: FileIndexSnapshot? = null

    @Volatile
    private var cachedInsight: FileInsight? = null

    suspend fun load(): FileIndexSnapshot? = mutex.withLock {
        snapshot ?: store.load()?.also { snapshot = it }
    }

    suspend fun insight(): FileInsight? {
        val index = load() ?: return null
        cachedInsight?.let { return it }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                cachedInsight ?: buildInsight(index).also { cachedInsight = it }
            }
        }
    }

    suspend fun scan(
        treeUris: List<String>,
        deviceName: String,
        onProgress: suspend (ScanProgress) -> Unit = {},
    ): FileIndexSnapshot {
        val startedAt = System.currentTimeMillis()
        val result = scanner.scan(treeUris, onProgress)
        val finishedAt = System.currentTimeMillis()
        val index = FileIndexSnapshot(
            version = 2,
            rootUri = treeUris.firstOrNull().orEmpty(),
            rootName = result.roots.joinToString("、").ifBlank { "已选目录" },
            roots = result.roots,
            deviceName = deviceName,
            scannedAt = finishedAt,
            durationMs = finishedAt - startedAt,
            totalFiles = result.entries.size,
            totalDirs = result.totalDirs,
            totalBytes = result.totalBytes,
            truncated = result.truncated,
            skippedDirs = result.skippedDirs,
            entries = result.entries,
        )
        store.save(index)
        mutex.withLock {
            snapshot = index
            cachedInsight = null
        }
        return index
    }

    suspend fun list(view: FileView, filter: FileFilter, limit: Int): FileQueryResult =
        withContext(Dispatchers.IO) {
            val insight = if (view == FileView.All) null else insight()
            val base = when (view) {
                FileView.All -> load()?.entries.orEmpty()
                FileView.Largest -> insight?.largest.orEmpty()
                FileView.Duplicates -> insight?.duplicates.orEmpty().flatMap { it.files }
                FileView.Temp -> insight?.tempFiles.orEmpty()
                FileView.OldLarge -> insight?.oldLarge.orEmpty()
            }
            val keyword = filter.query.trim()
            val matched = base.asSequence()
                .filter { filter.category == null || it.category == filter.category }
                .filter {
                    keyword.isEmpty() ||
                        it.name.contains(keyword, ignoreCase = true) ||
                        it.path.contains(keyword, ignoreCase = true)
                }
                .toList()
            val sorted = when (filter.sort) {
                FileSort.SizeDesc -> matched.sortedByDescending { it.size }
                FileSort.ModifiedDesc -> matched.sortedByDescending { it.modified }
                FileSort.NameAsc -> matched.sortedBy { it.name.lowercase(Locale.ROOT) }
            }
            FileQueryResult(sorted.take(limit), sorted.size)
        }

    suspend fun brief(): String? = insight()?.let { FileInsights.brief(it) }

    suspend fun clear() {
        mutex.withLock {
            snapshot = null
            cachedInsight = null
            store.clear()
        }
    }

    private fun buildInsight(index: FileIndexSnapshot): FileInsight {
        val stat = runCatching { StatFs(Environment.getDataDirectory().absolutePath) }.getOrNull()
        return FileInsights.build(
            snapshot = index,
            freeBytes = stat?.availableBytes ?: 0L,
            storageTotalBytes = stat?.totalBytes ?: 0L,
        )
    }
}
