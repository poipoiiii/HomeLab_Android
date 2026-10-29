package com.poi.homelab.feature.files

import com.poi.homelab.core.util.fileSize
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val MB = 1024L * 1024L
private const val MIN_DUPLICATE_BYTES = 1L * MB
private const val MIN_CANDIDATE_BYTES = 1L * MB
private const val OLD_LARGE_BYTES = 100L * MB
private const val MAX_BRIEF_CHARS = 6000

private val TEMP_SEGMENTS = setOf(
    ".thumbnails",
    "cache",
    "caches",
    "tmp",
    "temp",
    "lost.dir",
)

private val SENSITIVE_KEYWORDS = listOf(
    "身份证", "证件", "护照", "银行卡", "密码", "密钥", "合同", "简历", "体检", "社保", "户口",
)

private val timeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

object FileInsights {

    fun build(
        snapshot: FileIndexSnapshot,
        freeBytes: Long,
        storageTotalBytes: Long,
    ): FileInsight {
        val entries = snapshot.entries
        val now = System.currentTimeMillis()

        val byCategory = entries.groupBy { it.category }
        val categories = FileCategory.entries
            .map { category ->
                val group = byCategory[category].orEmpty()
                CategoryStat(category, group.size, group.sumOf { it.size })
            }
            .filter { it.count > 0 }
            .sortedByDescending { it.bytes }

        val dirBytes = HashMap<String, Long>()
        val dirCounts = HashMap<String, Int>()
        entries.forEach { entry ->
            val display = entry.displayPath
            val head = if (display.contains('/')) display.substringBefore('/') else "(根目录)"
            dirBytes[head] = (dirBytes[head] ?: 0L) + entry.size
            dirCounts[head] = (dirCounts[head] ?: 0) + 1
        }
        val topDirs = dirBytes.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { (path, bytes) -> DirStat(path, dirCounts[path] ?: 0, bytes) }

        val largest = entries.sortedByDescending { it.size }.take(50)

        val allDuplicates = entries.asSequence()
            .filter { it.size >= MIN_DUPLICATE_BYTES }
            .groupBy { it.name.lowercase(Locale.ROOT) to it.size }
            .values
            .filter { it.size >= 2 }
            .map { group -> DuplicateGroup(group.first().name, group.first().size, group) }
            .sortedByDescending { it.reclaimable }
            .toList()
        val duplicates = allDuplicates.take(50)

        val allTemp = entries
            .filter { isTempPath(it) && it.size >= MIN_CANDIDATE_BYTES }
            .sortedByDescending { it.size }
        val tempFiles = allTemp.take(50)

        val oldThreshold = now - TimeUnit.DAYS.toMillis(365)
        val allOldLarge = entries
            .filter { it.size >= OLD_LARGE_BYTES && it.modified in 1..oldThreshold }
            .sortedByDescending { it.size }
        val oldLarge = allOldLarge.take(50)

        val allSensitive = entries
            .filter { entry -> SENSITIVE_KEYWORDS.any { entry.name.contains(it) } }
            .sortedByDescending { it.size }
        val sensitive = allSensitive.take(50)

        val rootLabel = snapshot.roots
            .ifEmpty { listOf(snapshot.rootName) }
            .filter { it.isNotBlank() }
            .joinToString("、")
            .ifBlank { "已选目录" }

        return FileInsight(
            rootName = rootLabel,
            deviceName = snapshot.deviceName,
            scannedAt = snapshot.scannedAt,
            durationMs = snapshot.durationMs,
            totalFiles = snapshot.totalFiles,
            totalDirs = snapshot.totalDirs,
            totalBytes = snapshot.totalBytes,
            freeBytes = freeBytes,
            storageTotalBytes = storageTotalBytes,
            categories = categories,
            topDirs = topDirs,
            largest = largest,
            duplicates = duplicates,
            duplicateCount = allDuplicates.size,
            duplicateBytes = allDuplicates.sumOf { it.reclaimable },
            tempFiles = tempFiles,
            tempCount = allTemp.size,
            tempBytes = allTemp.sumOf { it.size },
            oldLarge = oldLarge,
            oldLargeCount = allOldLarge.size,
            oldLargeBytes = allOldLarge.sumOf { it.size },
            sensitive = sensitive,
            sensitiveCount = allSensitive.size,
            truncated = snapshot.truncated,
            skippedDirs = snapshot.skippedDirs,
        )
    }

    fun brief(insight: FileInsight): String {
        val builder = StringBuilder()
        builder.appendLine("设备：${insight.deviceName}")
        builder.appendLine("扫描范围：${insight.rootName} · ${formatTime(insight.scannedAt)}")
        builder.appendLine("扫描耗时：${insight.durationMs / 1000} 秒")
        if (insight.truncated) {
            builder.appendLine("注意：已达到 5 万个文件上限，结果不完整。")
        }
        builder.appendLine(
            "总量：${insight.totalFiles} 个文件，${insight.totalDirs} 个目录，" +
                "${fileSize(insight.totalBytes)}；剩余 ${fileSize(insight.freeBytes)} / " +
                "总容量 ${fileSize(insight.storageTotalBytes)}。",
        )
        builder.appendLine()

        builder.appendLine("类型构成（按大小）：")
        insight.categories.forEach {
            builder.appendLine("- ${it.category.label}：${it.count} 个，${fileSize(it.bytes)}")
        }
        builder.appendLine()

        if (insight.topDirs.isNotEmpty()) {
            builder.appendLine("占空间目录 Top 10：")
            insight.topDirs.forEach {
                builder.appendLine("- ${it.path}：${it.count} 个文件，${fileSize(it.bytes)}")
            }
            builder.appendLine()
        }

        if (insight.largest.isNotEmpty()) {
            builder.appendLine("最大文件 Top 15：")
            insight.largest.take(15).forEach {
                builder.appendLine("- ${it.name}（${fileSize(it.size)}，${it.displayPath}）")
            }
            builder.appendLine()
        }

        if (insight.duplicateCount > 0) {
            builder.appendLine(
                "重复文件（同名同大小）：${insight.duplicateCount} 组，" +
                    "可回收 ${fileSize(insight.duplicateBytes)}。Top 10：",
            )
            insight.duplicates.take(10).forEach {
                builder.appendLine(
                    "- ${it.name}：${it.count} 份，每份 ${fileSize(it.size)}，" +
                        "可回收 ${fileSize(it.reclaimable)}",
                )
            }
            builder.appendLine()
        }

        if (insight.tempCount > 0) {
            builder.appendLine(
                "缓存/临时文件：${insight.tempCount} 个，共 ${fileSize(insight.tempBytes)}。Top 10：",
            )
            insight.tempFiles.take(10).forEach {
                builder.appendLine("- ${it.displayPath}（${fileSize(it.size)}）")
            }
            builder.appendLine()
        }

        if (insight.oldLargeCount > 0) {
            builder.appendLine(
                "超过 100MB 且一年未修改：${insight.oldLargeCount} 个，" +
                    "共 ${fileSize(insight.oldLargeBytes)}。",
            )
            builder.appendLine()
        }

        if (insight.sensitiveCount > 0) {
            builder.appendLine(
                "文件名疑似敏感（仅按文件名推测）：${insight.sensitiveCount} 个",
            )
            insight.sensitive.take(10).forEach {
                builder.appendLine("- ${it.displayPath}")
            }
            builder.appendLine()
        }

        val text = builder.toString()
        return if (text.length > MAX_BRIEF_CHARS) {
            text.take(MAX_BRIEF_CHARS) + "\n…（摘要已截断）"
        } else {
            text
        }
    }

    /** 给 AI 工具用的结构化摘要，不再拼长文本。 */
    fun summaryMap(insight: FileInsight): Map<String, Any?> = mapOf(
        "device" to insight.deviceName,
        "root" to insight.rootName,
        "scanned_at" to formatTime(insight.scannedAt),
        "duration_seconds" to insight.durationMs / 1000,
        "truncated" to insight.truncated,
        "total_files" to insight.totalFiles,
        "total_dirs" to insight.totalDirs,
        "total_size" to fileSize(insight.totalBytes),
        "free_size" to fileSize(insight.freeBytes),
        "storage_size" to fileSize(insight.storageTotalBytes),
        "categories" to insight.categories.map {
            mapOf(
                "type" to it.category.label,
                "count" to it.count,
                "size" to fileSize(it.bytes),
            )
        },
        "top_dirs" to insight.topDirs.map {
            mapOf("path" to it.path, "count" to it.count, "size" to fileSize(it.bytes))
        },
        "largest" to insight.largest.take(15).map { it.toBriefMap() },
        "duplicate_groups" to insight.duplicateCount,
        "duplicate_reclaimable" to fileSize(insight.duplicateBytes),
        "duplicates" to insight.duplicates.take(10).map {
            mapOf(
                "name" to it.name,
                "count" to it.count,
                "size_each" to fileSize(it.size),
                "reclaimable" to fileSize(it.reclaimable),
            )
        },
        "temp_count" to insight.tempCount,
        "temp_size" to fileSize(insight.tempBytes),
        "temp" to insight.tempFiles.take(10).map { it.toBriefMap() },
        "old_large_count" to insight.oldLargeCount,
        "old_large_size" to fileSize(insight.oldLargeBytes),
        "sensitive_count" to insight.sensitiveCount,
        "sensitive_names" to insight.sensitive.take(10).map { it.displayPath },
        "note" to "只读元数据，不包含文件内容；没有索引时让用户先在文件洞察页扫描。",
    )

    fun FileEntry.toBriefMap(): Map<String, Any?> = mapOf(
        "name" to name,
        "path" to displayPath,
        "size" to fileSize(size),
        "modified" to if (modified > 0) formatTime(modified) else "",
        "mime" to mime,
    )

    private fun isTempPath(entry: FileEntry): Boolean {
        val segments = entry.path.lowercase(Locale.ROOT).split('/')
        if (segments.any { it in TEMP_SEGMENTS }) return true
        return entry.extension == "tmp" || entry.extension == "temp" || entry.extension == "log"
    }

    private fun formatTime(millis: Long): String =
        if (millis <= 0) "" else timeFormat.format(Instant.ofEpochMilli(millis))
}
