package dev.surgeon.partition.core

import dev.surgeon.partition.core.shell.RootShell
import dev.surgeon.partition.core.shell.ShellSession

object RootManager {

    enum class Impl { MAGISK, KERNELSU, APATCH, UNKNOWN }

    data class RootInfo(
        val granted: Boolean,
        val uid: Int,
        val impl: Impl,
        val version: String,
        val selinux: String,
        val moduleCount: Int
    ) {
        val implName: String
            get() = when (impl) {
                Impl.MAGISK -> "Magisk"
                Impl.KERNELSU -> "KernelSU"
                Impl.APATCH -> "APatch"
                Impl.UNKNOWN -> if (granted) "未知实现" else "不可用"
            }

        val display: String
            get() = if (!granted) implName else buildString {
                append(implName)
                if (version.isNotBlank()) append(" $version")
                if (moduleCount > 0) append(" · 模块 $moduleCount")
            }
    }

    private val PROBE = listOf(
        "echo \"uid=\$(id -u 2>/dev/null)\"",
        "echo \"magisk=\$(command -v magisk 2>/dev/null)\"",
        "echo \"magisk_v=\$(magisk -V 2>/dev/null | head -1)\"",
        "echo \"ksu=\$([ -f /data/adb/ksud ] && echo 1 || echo 0)\"",
        "echo \"ksu_v=\$(/data/adb/ksud --version 2>/dev/null | head -1)\"",
        "echo \"apatch=\$([ -f /data/adb/apd ] && echo 1 || echo 0)\"",
        "echo \"apatch_v=\$(/data/adb/apd -V 2>/dev/null | head -1)\"",
        "echo \"selinux=\$(getenforce 2>/dev/null)\"",
        "echo \"modules=\$([ -d /data/adb/modules ] && ls /data/adb/modules 2>/dev/null | grep -c . || echo 0)\""
    ).joinToString("\n")

    private var cached: RootInfo? = null

    /** 探测 root 可用性。每次都新开一个 su 进程，避免被主会话状态影响。 */
    fun probe(force: Boolean = false): RootInfo {
        cached?.takeIf { !force }?.let { return it }
        val info = runCatching {
            val session: ShellSession = RootShell.newSession()
            try {
                val result = session.exec(PROBE)
                parse(result.output)
            } finally {
                session.close()
            }
        }.getOrElse {
            RootInfo(false, -1, Impl.UNKNOWN, "", "", 0)
        }
        if (info.granted) cached = info
        return info
    }

    /** 返回上一次探测到的结果，没探测过就是 null。不触发任何 shell。 */
    fun lastKnown(): RootInfo? = cached

    private fun parse(lines: List<String>): RootInfo {
        val map = HashMap<String, String>()
        for (line in lines) {
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        val uid = map["uid"]?.toIntOrNull() ?: -1
        val granted = uid == 0
        val impl = when {
            map["ksu"] == "1" -> Impl.KERNELSU
            map["apatch"] == "1" -> Impl.APATCH
            (map["magisk"] ?: "").isNotBlank() -> Impl.MAGISK
            else -> Impl.UNKNOWN
        }
        val version = when (impl) {
            Impl.KERNELSU -> map["ksu_v"].orEmpty()
            Impl.APATCH -> map["apatch_v"].orEmpty()
            Impl.MAGISK -> map["magisk_v"].orEmpty()
            Impl.UNKNOWN -> ""
        }.trim()
        val modules = map["modules"]?.toIntOrNull() ?: 0
        return RootInfo(granted, uid, impl, version, map["selinux"].orEmpty(), modules)
    }
}
