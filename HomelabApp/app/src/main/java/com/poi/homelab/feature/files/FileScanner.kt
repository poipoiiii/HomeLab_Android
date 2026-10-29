package com.poi.homelab.feature.files

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

private const val MAX_FILES = 50_000
private const val MAX_DIRS = 20_000
private const val MAX_DEPTH = 32
private const val MAX_SKIPPED_DIRS = 50
private const val PROGRESS_INTERVAL_MS = 140L

private val SKIPPED_DIRECTORIES = setOf(
    ".trash",
    ".globaltrash",
    "lost.dir",
    "\$RECYCLE.BIN".lowercase(Locale.ROOT),
)

/**
 * 通过 SAF 目录树遍历文件元数据。
 *
 * Android 11+ 不允许把内部存储根目录授权给普通应用，所以支持一次扫描多个授权文件夹。
 * 不用 DocumentFile.listFiles()：每个文件一次 IPC，几万个文件会非常慢；
 * 这里按目录用 DocumentsContract 一次查出全部子项。
 */
class FileScanner(private val context: Context) {

    suspend fun scan(
        treeUris: List<String>,
        onProgress: suspend (ScanProgress) -> Unit = {},
    ): ScanResult = withContext(Dispatchers.IO) {
        val entries = ArrayList<FileEntry>()
        val roots = ArrayList<String>()
        val skippedDirs = ArrayList<String>()
        var dirs = 0
        var files = 0
        var bytes = 0L
        var truncated = false
        var lastProgress = 0L

        for ((rootIndex, treeUri) in treeUris.withIndex()) {
            coroutineContext.ensureActive()
            if (files >= MAX_FILES) {
                truncated = true
                break
            }
            val uri = runCatching { Uri.parse(treeUri) }.getOrNull()
            val rootDocumentId = runCatching {
                uri?.let { DocumentsContract.getTreeDocumentId(it) }
            }.getOrNull()
            if (uri == null || rootDocumentId.isNullOrBlank()) {
                if (skippedDirs.size < MAX_SKIPPED_DIRS) skippedDirs += treeUri
                continue
            }
            val rootName = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "文件夹 ${rootIndex + 1}"
            roots += rootName

            val stack = ArrayDeque<DirNode>()
            stack.addLast(DirNode(rootDocumentId, "", 0))
            while (stack.isNotEmpty() && files < MAX_FILES) {
                coroutineContext.ensureActive()
                val node = stack.removeLast()
                val children = runCatching { queryChildren(context.contentResolver, uri, node.documentId) }
                    .getOrElse {
                        if (skippedDirs.size < MAX_SKIPPED_DIRS) {
                            skippedDirs += joinPath(rootName, node.path)
                        }
                        emptyList()
                    }

                for (child in children) {
                    coroutineContext.ensureActive()
                    if (child.isDirectory) {
                        if (shouldSkipDirectory(node.path, child.name)) continue
                        dirs++
                        if (dirs > MAX_DIRS || node.depth >= MAX_DEPTH) {
                            truncated = true
                        } else {
                            stack.addLast(
                                DirNode(
                                    documentId = child.documentId,
                                    path = childPath(node.path, child.name),
                                    depth = node.depth + 1,
                                ),
                            )
                        }
                        continue
                    }

                    entries += FileEntry(
                        path = childPath(node.path, child.name),
                        name = child.name,
                        size = child.size,
                        modified = child.modified,
                        mime = child.mime,
                        root = rootName,
                    )
                    files++
                    bytes += child.size
                    if (files >= MAX_FILES) {
                        truncated = true
                        break
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastProgress >= PROGRESS_INTERVAL_MS) {
                        lastProgress = now
                        onProgress(
                            ScanProgress(
                                files = files,
                                dirs = dirs,
                                currentDir = joinPath(rootName, node.path),
                            ),
                        )
                    }
                    if (files % 200 == 0) yield()
                }
            }
        }

        onProgress(ScanProgress(files, dirs, ""))
        ScanResult(
            roots = roots,
            entries = entries,
            totalDirs = dirs,
            totalBytes = bytes,
            truncated = truncated,
            skippedDirs = skippedDirs,
        )
    }

    private fun queryChildren(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocumentId: String,
    ): List<ChildNode> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val nodes = ArrayList<ChildNode>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndex(projection[0])
            val nameIndex = cursor.getColumnIndex(projection[1])
            val mimeIndex = cursor.getColumnIndex(projection[2])
            val sizeIndex = cursor.getColumnIndex(projection[3])
            val modifiedIndex = cursor.getColumnIndex(projection[4])
            if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0) return nodes

            while (cursor.moveToNext()) {
                val documentId = cursor.getString(idIndex)
                val name = cursor.getString(nameIndex)
                if (documentId.isNullOrBlank() || name.isNullOrBlank()) continue
                nodes += ChildNode(
                    documentId = documentId,
                    name = name,
                    mime = cursor.getString(mimeIndex).orEmpty(),
                    size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        cursor.getLong(sizeIndex)
                    } else {
                        0L
                    },
                    modified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                        cursor.getLong(modifiedIndex)
                    } else {
                        0L
                    },
                )
            }
        }
        return nodes
    }

    private fun shouldSkipDirectory(parentPath: String, name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        if (lower in SKIPPED_DIRECTORIES) return true
        // Android 11+ 不允许普通应用读取 Android/data 与 Android/obb
        return parentPath.equals("Android", ignoreCase = true) &&
            (lower == "data" || lower == "obb")
    }

    private fun joinPath(root: String, path: String): String =
        if (path.isEmpty()) root else "$root/$path"

    private fun childPath(parent: String, name: String): String =
        if (parent.isEmpty()) name else "$parent/$name"

    private data class DirNode(
        val documentId: String,
        val path: String,
        val depth: Int,
    )

    private data class ChildNode(
        val documentId: String,
        val name: String,
        val mime: String,
        val size: Long,
        val modified: Long,
    ) {
        val isDirectory: Boolean
            get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }
}
