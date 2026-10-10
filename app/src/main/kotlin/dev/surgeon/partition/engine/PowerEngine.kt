package dev.surgeon.partition.engine

import dev.surgeon.partition.core.shell.RootShell
import dev.surgeon.partition.core.shell.ShellSession

/**
 * 电源与重启指令。
 *
 * 所有命令都通过 root shell 下发，等价于在终端里手动执行。
 */
object PowerEngine {

    enum class Action(val command: String) {
        REBOOT("reboot"),
        SOFT("setprop ctl.restart zygote"),
        RECOVERY("reboot recovery"),
        BOOTLOADER("reboot bootloader"),
        FASTBOOTD("reboot fastboot"),
        POWEROFF("reboot -p")
    }

    /** 下发指令。注意：成功后设备会立刻断电/重启，返回的 output 可能读不全。 */
    fun run(session: ShellSession = RootShell.shell(), action: Action): Boolean {
        val result = session.exec("${action.command} 2>&1; echo rc=\$?")
        return result.output.any { it.contains("rc=0") }
    }

    /** 软重启依赖 ctl.restart 属性，部分内核裁掉了这个通道。 */
    fun softRebootSupported(session: ShellSession = RootShell.shell()): Boolean {
        val out = session.exec("getprop init.svc.zygote 2>/dev/null").firstLine.trim()
        return out.isNotBlank()
    }
}
