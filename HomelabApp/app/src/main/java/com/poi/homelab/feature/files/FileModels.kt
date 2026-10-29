package com.poi.homelab.feature.files

import java.util.Locale
import kotlinx.serialization.Serializable

/** 文件类型，只用于展示与统计，索引里不落盘。 */
enum class FileCategory(val label: String) {
    Image("图片"),
    Video("视频"),
    Audio("音频"),
    Document("文档"),
    Archive("压缩包"),
    Apk("安装包"),
    Other("其他"),
}

@Serializable
data class FileEntry(
    /** 相对扫描根目录的路径，如 Camera/IMG_0001.jpg */
    val path: String = "",
    val name: String = "",
    val size: Long = 0L,
    val modified: Long = 0L,
    val mime: String = "",
    /** 所属授权文件夹的名称，用于多范围展示与去重。 */
    val root: String = "",
) {
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    val category: FileCategory
        get() = categorize(mime, name)

    /** 展示用完整路径；单范围旧索引 root 为空时退回 path。 */
    val displayPath: String
        get() = if (root.isBlank()) path else "$root/$path"

    /** 列表 key，避免多个范围里同名文件冲突。 */
    val key: String
        get() = displayPath
}

@Serializable
data class FileIndexSnapshot(
    val version: Int = 1,
    /** 旧字段，保留兼容；新索引使用 roots。 */
    val rootUri: String = "",
    /** 展示用范围名称，多个用「、」连接。 */
    val rootName: String = "",
    val roots: List<String> = emptyList(),
    val deviceName: String = "",
    val scannedAt: Long = 0L,
    val durationMs: Long = 0L,
    val totalFiles: Int = 0,
    val totalDirs: Int = 0,
    val totalBytes: Long = 0L,
    val truncated: Boolean = false,
    val skippedDirs: List<String> = emptyList(),
    val entries: List<FileEntry> = emptyList(),
)

data class ScanProgress(
    val files: Int = 0,
    val dirs: Int = 0,
    val currentDir: String = "",
)

data class ScanResult(
    val roots: List<String>,
    val entries: List<FileEntry>,
    val totalDirs: Int,
    val totalBytes: Long,
    val truncated: Boolean,
    val skippedDirs: List<String>,
)

data class CategoryStat(
    val category: FileCategory,
    val count: Int,
    val bytes: Long,
)

data class DirStat(
    val path: String,
    val count: Int,
    val bytes: Long,
)

data class DuplicateGroup(
    val name: String,
    val size: Long,
    val files: List<FileEntry>,
) {
    val count: Int get() = files.size
    val reclaimable: Long get() = size * (files.size - 1).coerceAtLeast(0)
}

data class FileInsight(
    val rootName: String,
    val deviceName: String,
    val scannedAt: Long,
    val durationMs: Long,
    val totalFiles: Int,
    val totalDirs: Int,
    val totalBytes: Long,
    val freeBytes: Long,
    val storageTotalBytes: Long,
    val categories: List<CategoryStat>,
    val topDirs: List<DirStat>,
    val largest: List<FileEntry>,
    val duplicates: List<DuplicateGroup>,
    val duplicateCount: Int,
    val duplicateBytes: Long,
    val tempFiles: List<FileEntry>,
    val tempCount: Int,
    val tempBytes: Long,
    val oldLarge: List<FileEntry>,
    val oldLargeCount: Int,
    val oldLargeBytes: Long,
    val sensitive: List<FileEntry>,
    val sensitiveCount: Int,
    val truncated: Boolean,
    val skippedDirs: List<String>,
)

enum class FileView(val label: String) {
    All("全部"),
    Largest("大文件"),
    Duplicates("重复"),
    Temp("缓存"),
    OldLarge("旧文件"),
}

enum class FileSort(val label: String) {
    SizeDesc("大小"),
    ModifiedDesc("时间"),
    NameAsc("名称"),
}

data class FileFilter(
    val query: String = "",
    val category: FileCategory? = null,
    val sort: FileSort = FileSort.SizeDesc,
)

data class FileQueryResult(
    val rows: List<FileEntry>,
    val total: Int,
)

private val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "dng", "raw",
)

private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "m4v", "ts", "rmvb",
)

private val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "opus", "ape", "amr",
)

private val DOCUMENT_EXTENSIONS = setOf(
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv",
    "json", "xml", "html", "htm", "epub", "rtf", "log", "yaml", "yml",
)

private val ARCHIVE_EXTENSIONS = setOf(
    "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "zst", "tgz",
)

fun categorize(mime: String, name: String): FileCategory {
    val type = mime.lowercase(Locale.ROOT)
    if (type.startsWith("image/")) return FileCategory.Image
    if (type.startsWith("video/")) return FileCategory.Video
    if (type.startsWith("audio/")) return FileCategory.Audio
    if (type == "application/vnd.android.package-archive") return FileCategory.Apk

    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        ext in IMAGE_EXTENSIONS -> FileCategory.Image
        ext in VIDEO_EXTENSIONS -> FileCategory.Video
        ext in AUDIO_EXTENSIONS -> FileCategory.Audio
        ext == "apk" -> FileCategory.Apk
        ext in ARCHIVE_EXTENSIONS -> FileCategory.Archive
        ext in DOCUMENT_EXTENSIONS -> FileCategory.Document
        type.startsWith("text/") -> FileCategory.Document
        type.startsWith("application/zip") ||
            type.startsWith("application/x-rar") ||
            type.startsWith("application/x-7z") ||
            type.startsWith("application/gzip") ||
            type.startsWith("application/x-tar") -> FileCategory.Archive
        type == "application/pdf" -> FileCategory.Document
        else -> FileCategory.Other
    }
}
