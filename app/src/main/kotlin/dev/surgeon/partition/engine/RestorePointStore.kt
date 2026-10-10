package dev.surgeon.partition.engine

import android.content.Context
import dev.surgeon.partition.model.RestoreEntry
import dev.surgeon.partition.model.RestorePoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 还原点索引。镜像文件本体存在 /sdcard 上的输出目录里（卸载 App 也不丢），
 * 这里只保存元数据，放在 App 私有目录，避免被其他应用窥探或误删。
 */
object RestorePointStore {

    private const val FILE = "restore-points.json"

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun load(context: Context): List<RestorePoint> {
        val file = file(context)
        if (!file.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("points") ?: return emptyList()
            val list = ArrayList<RestorePoint>()
            for (i in 0 until array.length()) {
                parsePoint(array.getJSONObject(i))?.let { list.add(it) }
            }
            list.sortedByDescending { it.createdAt }
        }.getOrDefault(emptyList())
    }

    private fun parsePoint(obj: JSONObject): RestorePoint? {
        val id = obj.optString("id")
        if (id.isBlank()) return null
        val entries = ArrayList<RestoreEntry>()
        val array = obj.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until array.length()) {
            val e = array.getJSONObject(i)
            entries.add(
                RestoreEntry(
                    partition = e.optString("partition"),
                    path = e.optString("path"),
                    sizeBytes = e.optLong("size"),
                    file = e.optString("file"),
                    sha256 = e.optString("sha256")
                )
            )
        }
        return RestorePoint(
            id = id,
            label = obj.optString("label"),
            createdAt = obj.optLong("createdAt"),
            device = obj.optString("device"),
            fingerprint = obj.optString("fingerprint"),
            activeSlot = obj.optString("activeSlot"),
            rootDir = obj.optString("rootDir"),
            entries = entries
        )
    }

    fun save(context: Context, points: List<RestorePoint>) {
        val array = JSONArray()
        points.forEach { p ->
            val entries = JSONArray()
            p.entries.forEach { e ->
                entries.put(
                    JSONObject().apply {
                        put("partition", e.partition)
                        put("path", e.path)
                        put("size", e.sizeBytes)
                        put("file", e.file)
                        put("sha256", e.sha256)
                    }
                )
            }
            array.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("label", p.label)
                    put("createdAt", p.createdAt)
                    put("device", p.device)
                    put("fingerprint", p.fingerprint)
                    put("activeSlot", p.activeSlot)
                    put("rootDir", p.rootDir)
                    put("entries", entries)
                }
            )
        }
        val root = JSONObject().apply { put("version", 1); put("points", array) }
        runCatching { file(context).writeText(root.toString()) }
    }

    fun add(context: Context, point: RestorePoint) {
        val list = load(context).toMutableList()
        list.add(0, point)
        save(context, list)
    }

    fun remove(context: Context, id: String): RestorePoint? {
        val list = load(context)
        val target = list.firstOrNull { it.id == id } ?: return null
        save(context, list.filterNot { it.id == id })
        return target
    }

    fun newId(): String = System.currentTimeMillis().toString(36) +
        (1000..9999).random().toString(36)
}
