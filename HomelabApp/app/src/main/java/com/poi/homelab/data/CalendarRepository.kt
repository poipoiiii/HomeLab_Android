package com.poi.homelab.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 日历记录的唯一读写入口：界面和 Agent 都通过它修改，
 * StateFlow 会让日历页实时刷新。
 */
class CalendarRepository(private val store: CalendarStore) {

    private val _entries = MutableStateFlow<List<CalendarEntry>>(emptyList())
    val entries: StateFlow<List<CalendarEntry>> = _entries.asStateFlow()
    private val mutex = Mutex()

    suspend fun load() {
        mutex.withLock {
            _entries.value = sorted(store.load())
        }
    }

    fun current(): List<CalendarEntry> = _entries.value

    fun find(id: Long): CalendarEntry? = _entries.value.firstOrNull { it.id == id }

    suspend fun upsert(entry: CalendarEntry): CalendarEntry = mutex.withLock {
        val current = _entries.value
        val id = if (entry.id == 0L) {
            (current.maxOfOrNull { it.id } ?: 0L) + 1
        } else {
            entry.id
        }
        val saved = entry.copy(
            id = id,
            createdAt = entry.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
        val updated = sorted(current.filterNot { it.id == id } + saved)
        _entries.value = updated
        store.save(updated)
        saved
    }

    suspend fun addAll(inputs: List<CalendarEntry>): List<CalendarEntry> = mutex.withLock {
        if (inputs.isEmpty()) return@withLock emptyList()
        var nextId = (_entries.value.maxOfOrNull { it.id } ?: 0L) + 1
        val now = System.currentTimeMillis()
        val saved = inputs.map { entry ->
            entry.copy(
                id = nextId++,
                createdAt = entry.createdAt.takeIf { it > 0 } ?: now,
            )
        }
        val updated = sorted(_entries.value + saved)
        _entries.value = updated
        store.save(updated)
        saved
    }

    suspend fun delete(ids: Collection<Long>): Int = mutex.withLock {
        val remove = ids.toSet()
        if (remove.isEmpty()) return@withLock 0
        val before = _entries.value
        val updated = before.filterNot { it.id in remove }
        if (updated.size == before.size) return@withLock 0
        _entries.value = updated
        store.save(updated)
        before.size - updated.size
    }

    private fun sorted(list: List<CalendarEntry>): List<CalendarEntry> =
        list.sortedWith(compareBy({ it.date }, { it.id }))
}
