package dev.surgeon.partition.engine

import dev.surgeon.partition.core.shell.RootShell
import dev.surgeon.partition.core.shell.ShellSession
import dev.surgeon.partition.model.SlotInfo
import dev.surgeon.partition.model.SlotState

/**
 * 读取当前启动槽位与 A/B 状态。
 *
 * 数据来源优先级：ro.boot.slot_suffix → /proc/cmdline → bootctl → 块设备是否存在 boot_a/boot_b。
 */
object SlotReader {

    private val SCRIPT = listOf(
        "echo \"slot=$(getprop ro.boot.slot_suffix)\"",
        "echo \"ab=$(getprop ro.build.ab_update)\"",
        "echo \"vab=$(getprop ro.virtual_ab.enabled)\"",
        "echo \"dyn=$(getprop ro.boot.dynamic_partitions)\"",
        "echo \"vbstate=$(getprop ro.boot.verifiedbootstate)\"",
        "echo \"locked=$(getprop ro.boot.flash.locked)\"",
        "echo \"vbmeta=$(getprop ro.boot.vbmeta.device_state)\"",
        "echo \"fp=$(getprop ro.build.fingerprint)\"",
        "echo \"kernel=$(uname -r)\"",
        "echo \"sdk=$(getprop ro.build.version.sdk)\"",
        "echo \"device=$(getprop ro.product.device)\"",
        "echo \"cmdline=$(tr ' ' '\\n' < /proc/cmdline 2>/dev/null | grep -m1 androidboot.slot_suffix)\"",
        "echo \"bootctl=$(command -v bootctl 2>/dev/null)\"",
        "echo \"cur=$(bootctl get-current-slot 2>/dev/null | tail -1)\"",
        "echo \"nslots=$(bootctl get-number-slots 2>/dev/null | tail -1)\"",
        "echo \"a_bootable=$(bootctl is-slot-bootable 0 2>/dev/null | tail -1)\"",
        "echo \"a_succ=$(bootctl is-slot-marked-successful 0 2>/dev/null | tail -1)\"",
        "echo \"b_bootable=$(bootctl is-slot-bootable 1 2>/dev/null | tail -1)\"",
        "echo \"b_succ=$(bootctl is-slot-marked-successful 1 2>/dev/null | tail -1)\"",
        "echo \"has_a=$([ -e /dev/block/by-name/boot_a ] && echo 1 || echo 0)\"",
        "echo \"has_b=$([ -e /dev/block/by-name/boot_b ] && echo 1 || echo 0)\""
    ).joinToString("\n")

    fun read(session: ShellSession = RootShell.shell()): SlotInfo {
        val map = HashMap<String, String>()
        for (line in session.exec(SCRIPT).output) {
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }

        var slot = map["slot"].orEmpty().trim('_', '"').let { if (it.isEmpty()) "" else "_$it" }
        if (slot.isEmpty()) {
            val cmd = map["cmdline"].orEmpty()
            val at = cmd.indexOf('=')
            if (at >= 0) {
                val v = cmd.substring(at + 1).trim()
                if (v.isNotEmpty()) slot = if (v.startsWith("_")) v else "_$v"
            }
        }

        val hasA = map["has_a"] == "1"
        val hasB = map["has_b"] == "1"
        val isAb = slot.isNotEmpty() || (hasA && hasB)
        val active = if (slot.isNotEmpty()) slot else if (hasA) "_a" else ""

        val states = LinkedHashMap<String, SlotState>()
        val nslots = map["nslots"]?.toIntOrNull() ?: if (isAb) 2 else 1
        for (i in 0 until nslots) {
            val suffix = "_${('a'.code + i).toChar()}"
            val bootable = flagOf(map["${suffix.trimStart('_')}_bootable"])
            val successful = flagOf(map["${suffix.trimStart('_')}_succ"])
            if (bootable != null || successful != null) {
                states[suffix] = SlotState(suffix, bootable, successful)
            }
        }

        return SlotInfo(
            isAbDevice = isAb,
            activeSuffix = active,
            slotStates = states,
            abUpdate = map["ab"] == "true",
            virtualAb = map["vab"] == "true",
            dynamicPartitions = map["dyn"] == "true",
            vbState = map["vbstate"].orEmpty(),
            locked = when (map["locked"]) {
                "0" -> false
                "1" -> true
                else -> null
            },
            vbmetaState = map["vbmeta"].orEmpty(),
            fingerprint = map["fp"].orEmpty(),
            kernel = map["kernel"].orEmpty(),
            sdk = map["sdk"].orEmpty(),
            device = map["device"].orEmpty()
        )
    }

    /** 把另一个槽位设为活动槽位（下次开机生效）。 */
    fun switchSlot(session: ShellSession, target: String): Boolean {
        val index = when (target) {
            "_a" -> 0
            "_b" -> 1
            else -> return false
        }
        val result = session.exec("bootctl set-active-boot-slot $index 2>&1; echo rc=\$?")
        return result.output.any { it.contains("rc=0") }
    }

    private fun flagOf(raw: String?): Boolean? {
        val v = raw?.trim()?.lowercase() ?: return null
        if (v.isEmpty()) return null
        return when {
            v.contains("yes") || v.contains("true") || v.endsWith("1") -> true
            v.contains("no") || v.contains("false") || v.endsWith("0") -> false
            else -> null
        }
    }
}
