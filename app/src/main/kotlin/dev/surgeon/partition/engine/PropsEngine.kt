package dev.surgeon.partition.engine

import dev.surgeon.partition.core.shell.RootShell
import dev.surgeon.partition.core.shell.ShellSession

/**
 * 系统属性（getprop / setprop / resetprop）与 SELinux 模式。
 *
 * getprop 的输出形如 "[ro.build.id]: [ABC]"，解析时按这个格式拆。
 */
object PropsEngine {

    /** 常用属性分组，默认展示这些，避免一次塞上千条卡顿。 */
    val COMMON_KEYS = listOf(
        "ro.build.id", "ro.build.display.id", "ro.build.version.incremental",
        "ro.build.version.sdk", "ro.build.version.release", "ro.build.type",
        "ro.build.tags", "ro.build.fingerprint", "ro.product.device",
        "ro.product.model", "ro.product.manufacturer", "ro.product.board",
        "ro.product.cpu.abi", "ro.hardware", "ro.boot.hardware",
        "ro.boot.slot_suffix", "ro.build.ab_update", "ro.virtual_ab.enabled",
        "ro.boot.dynamic_partitions", "ro.boot.verifiedbootstate",
        "ro.boot.vbmeta.device_state", "ro.boot.flash.locked",
        "ro.boot.veritymode", "ro.secure", "ro.debuggable",
        "ro.system.build.fingerprint", "ro.vendor.build.fingerprint",
        "ro.odm.build.fingerprint", "ro.product.system.device",
        "persist.sys.locale", "sys.boot_completed", "init.svc.zygote",
        "ro.treble.enabled", "ro.crypto.state", "ro.zygote"
    )

    data class Prop(val key: String, val value: String)

    fun get(session: ShellSession = RootShell.shell(), key: String): String =
        session.exec("getprop \"$key\" 2>/dev/null").firstLine.trim()

    /** 读取指定属性列表。一次 shell 调用批量取，比逐个 getprop 快得多。 */
    fun getMany(session: ShellSession = RootShell.shell(), keys: List<String>): List<Prop> {
        if (keys.isEmpty()) return emptyList()
        val script = buildString {
            keys.forEach { key ->
                append("echo \"$key=\$(getprop \"$key\" 2>/dev/null)\"\n")
            }
        }
        return session.exec(script).output.mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            Prop(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
        }
    }

    /** 全量属性。返回条数可能上千，UI 侧要限制展示。 */
    fun allProps(session: ShellSession = RootShell.shell()): List<Prop> {
        return session.exec("getprop 2>/dev/null").output.mapNotNull { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return@mapNotNull null
            val body = trimmed.removePrefix("[").removeSuffix("]")
            val sep = body.indexOf("]: [")
            if (sep < 0) return@mapNotNull null
            Prop(body.substring(0, sep).trim(), body.substring(sep + 4).trim())
        }
    }

    fun search(session: ShellSession = RootShell.shell(), query: String): List<Prop> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return allProps(session).filter {
            it.key.lowercase().contains(q) || it.value.lowercase().contains(q)
        }
    }

    /** setprop：常规写入，ro. 只读属性写不进去。 */
    fun setProp(session: ShellSession = RootShell.shell(), key: String, value: String) {
        val result = session.exec("setprop \"$key\" \"$value\" 2>&1; echo rc=\$?")
        require(result.output.any { it.contains("rc=0") }) {
            result.output.filter { !it.contains("rc=") }.joinToString("\n").ifBlank { "setprop 失败" }
        }
    }

    /** resetprop：Magisk / KernelSU / APatch 提供，可改写只读属性（内存中生效）。 */
    fun resetProp(session: ShellSession = RootShell.shell(), key: String, value: String) {
        val tool = listOf("resetprop", "/data/adb/magisk/resetprop", "/data/adb/ksud")
            .firstOrNull { session.exec("command -v $it 2>/dev/null").firstLine.isNotBlank() }
        if (tool.isNullOrBlank()) throw IllegalStateException("未找到 resetprop")
        val result = session.exec("$tool \"$key\" \"$value\" 2>&1; echo rc=\$?")
        require(result.output.any { it.contains("rc=0") }) {
            result.output.filter { !it.contains("rc=") }.joinToString("\n").ifBlank { "resetprop 失败" }
        }
    }

    fun resetPropAvailable(session: ShellSession = RootShell.shell()): Boolean {
        val out = session.exec(
            "command -v resetprop 2>/dev/null; " +
                "command -v /data/adb/magisk/resetprop 2>/dev/null; " +
                "command -v /data/adb/ksud 2>/dev/null"
        ).output.joinToString("").trim()
        return out.isNotBlank()
    }

    // ---- SELinux ----

    fun selinuxState(session: ShellSession = RootShell.shell()): String =
        session.exec("getenforce 2>/dev/null").firstLine.trim()

    fun isEnforcing(session: ShellSession = RootShell.shell()): Boolean =
        selinuxState(session).equals("Enforcing", ignoreCase = true)

    fun setSelinux(session: ShellSession = RootShell.shell(), enforcing: Boolean): Boolean {
        val value = if (enforcing) "1" else "0"
        val result = session.exec("setenforce $value 2>&1; echo rc=\$?")
        return result.output.any { it.contains("rc=0") }
    }
}
