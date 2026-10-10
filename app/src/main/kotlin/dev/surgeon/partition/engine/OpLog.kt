package dev.surgeon.partition.engine

import android.content.Context
import dev.surgeon.partition.model.OpLogEntry
import org.json.JSONObject
import java.io.File

/** 追加写的操作留痕，只保留最近 300 条。 */
object OpLog {

    private const val FILE = "op-log.jsonl"
    private const val MAX = 300

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun add(context: Context, entry: OpLogEntry) {
        runCatching {
            val line = JSONObject().apply {
                put("time", entry.time)
                put("op", entry.op)
                put("target", entry.target)
                put("success", entry.success)
                put("detail", entry.detail)
            }.toString()
            file(context).appendText(line + "\n")
            trim(context)
        }
    }

    fun load(context: Context): List<OpLogEntry> {
        val file = file(context)
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines()
                .asReversed()
                .take(MAX)
                .mapNotNull { line ->
                    runCatching {
                        val o = JSONObject(line)
                        OpLogEntry(
                            time = o.optLong("time"),
                            op = o.optString("op"),
                            target = o.optString("target"),
                            success = o.optBoolean("success"),
                            detail = o.optString("detail")
                        )
                    }.getOrNull()
                }
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun trim(context: Context) {
        val file = file(context)
        val lines = runCatching { file.readLines() }.getOrElse { return }
        if (lines.size > MAX * 2) {
            runCatching { file.writeText(lines.takeLast(MAX).joinToString("\n", postfix = "\n")) }
        }
    }
}
