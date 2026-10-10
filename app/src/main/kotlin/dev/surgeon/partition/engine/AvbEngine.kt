package dev.surgeon.partition.engine

import dev.surgeon.partition.core.shell.RootShell
import dev.surgeon.partition.core.shell.ShellSession

/**
 * AVB / dm-verity 状态与开关。
 *
 * avbctl 在部分 ROM 上被裁剪，此时只能给出 fastbootd 下的替代做法。
 */
object AvbEngine {

    data class State(
        val vbState: String,
        val deviceState: String,
        val locked: Boolean?,
        val verityMode: String,
        val avbctlAvailable: Boolean
    )

    private val PROBE = listOf(
        "echo \"vbstate=$(getprop ro.boot.verifiedbootstate)\"",
        "echo \"devstate=$(getprop ro.boot.vbmeta.device_state)\"",
        "echo \"locked=$(getprop ro.boot.flash.locked)\"",
        "echo \"verity=$(getprop ro.boot.veritymode)$(getprop ro.boot.dm_verity_mode)\"",
        "echo \"avbctl=$(command -v avbctl 2>/dev/null)\""
    ).joinToString("\n")

    fun read(session: ShellSession = RootShell.shell()): State {
        val map = HashMap<String, String>()
        for (line in session.exec(PROBE).output) {
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        return State(
            vbState = map["vbstate"].orEmpty(),
            deviceState = map["devstate"].orEmpty(),
            locked = when (map["locked"]) {
                "0" -> false
                "1" -> true
                else -> null
            },
            verityMode = map["verity"].orEmpty(),
            avbctlAvailable = (map["avbctl"] ?: "").isNotBlank()
        )
    }

    fun setVerification(session: ShellSession = RootShell.shell(), disabled: Boolean): String {
        if (!read(session).avbctlAvailable) {
            throw IllegalStateException("avbctl 不可用")
        }
        val cmd = if (disabled) "avbctl disable-verification" else "avbctl enable-verification"
        val result = session.exec("$cmd 2>&1; echo rc=\$?")
        val rc = result.output.firstOrNull { it.contains("rc=") }
            ?.substringAfter("rc=")?.trim()?.toIntOrNull() ?: -1
        if (rc != 0) {
            throw IllegalStateException(result.output.filter { !it.contains("rc=") }
                .joinToString("\n").ifBlank { "退出码 $rc" })
        }
        return result.output.filter { !it.contains("rc=") }
            .joinToString("\n").ifBlank { "ok" }
    }
}
