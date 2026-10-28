package com.poi.homelab.feature.notify

import android.content.Context
import java.io.File
import org.json.JSONObject

/** 发送失败的原文暂存在这里，下次采集或定时任务时补发。 */
object Outbox {
    private const val FILE = "capture_outbox.jsonl"
    private val lock = Any()

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun readAll(context: Context): List<JSONObject> {
        val target = file(context)
        if (!target.exists()) return emptyList()
        return target.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }
    }

    private fun writeAll(context: Context, items: List<JSONObject>) {
        val target = file(context)
        if (items.isEmpty()) {
            target.delete()
            return
        }
        target.writeText(items.joinToString("\n") { it.toString() } + "\n")
    }

    fun add(context: Context, payload: JSONObject): Int = synchronized(lock) {
        file(context).appendText(payload.toString() + "\n")
        readAll(context).size
    }

    fun pending(context: Context): Int = synchronized(lock) { readAll(context).size }

    fun takeAll(context: Context): List<JSONObject> = synchronized(lock) { readAll(context) }

    /**
     * 丢弃已经成功上报的前 [count] 条。
     *
     * 发送期间通知/短信可能继续 append，这里按当前文件重新读取后只删前缀，
     * 不会像「整表覆盖」那样把新进来的记录一起抹掉。
     */
    fun removeFirst(context: Context, count: Int) = synchronized(lock) {
        if (count <= 0) return@synchronized
        val items = readAll(context)
        if (items.isEmpty()) return@synchronized
        writeAll(context, items.drop(count))
    }
}
