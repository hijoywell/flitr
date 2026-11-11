package com.aios.flitr.data.logs

import android.content.Context
import com.aios.flitr.data.logs.ActionLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 触发日志仓库。
 *
 * 存储选型说明: 这里刻意没有用 Room。审计日志是**只追加、按时间倒序读取、有上限**的数据,
 * 用 JSONL 追加写 + 内存缓存反而更简单可靠, 也避免了注解处理器带来的构建复杂度。
 * 若后续需要按维度聚合查询, 再迁移到 Room 不迟。
 */
class LogRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(ActionLog.serializer())

    private val _logs = MutableStateFlow<List<ActionLog>>(emptyList())
    val logs: StateFlow<List<ActionLog>> = _logs.asStateFlow()

    private val file: File
        get() = File(context.filesDir, "logs/actions.jsonl").apply { parentFile?.mkdirs() }

    suspend fun load() = withContext(Dispatchers.IO) {
        val f = file
        if (!f.exists()) return@withContext
        val list = runCatching {
            f.readLines()
                .asSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    runCatching { json.decodeFromString(ActionLog.serializer(), line) }.getOrNull()
                }
                .toList()
        }.getOrDefault(emptyList())
        _logs.value = list.sortedByDescending { it.timestampMs }.take(MAX_ENTRIES)
    }

    suspend fun record(entry: ActionLog) = withContext(Dispatchers.IO) {
        val current = _logs.value
        _logs.value = (listOf(entry) + current).take(MAX_ENTRIES)
        runCatching {
            file.appendText(json.encodeToString(ActionLog.serializer(), entry) + "\n")
        }
        compactIfNeeded()
    }

    suspend fun markFalsePositive(id: String) = withContext(Dispatchers.IO) {
        val updated = _logs.value.map { if (it.id == id) it.copy(falsePositive = true) else it }
        _logs.value = updated
        rewrite(updated)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        _logs.value = emptyList()
        runCatching { file.delete() }
    }

    private fun compactIfNeeded() {
        val f = file
        if (!f.exists()) return
        val lines = runCatching { f.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
        if (lines.size <= MAX_ENTRIES * 2) return
        runCatching {
            f.writeText(lines.takeLast(MAX_ENTRIES).joinToString("\n", postfix = "\n"))
        }
    }

    private fun rewrite(list: List<ActionLog>) {
        runCatching {
            file.writeText(
                list.joinToString("\n") { json.encodeToString(ActionLog.serializer(), it) } + "\n"
            )
        }
    }

    companion object {
        const val MAX_ENTRIES = 2000
    }
}
