package dev.surgeon.partition.engine

import dev.surgeon.partition.core.shell.RootShell
import dev.surgeon.partition.core.shell.ShellSession
import dev.surgeon.partition.model.PartitionInfo

/**
 * 枚举 /dev/block/by-name 下的所有分区。
 *
 * 大小优先用 blockdev --getsize64，缺失时退回 sysfs 的扇区数 * 512。
 */
object PartitionScanner {

    private val SCRIPT = listOf(
        "for p in /dev/block/by-name/*; do",
        "  [ -e \"\$p\" ] || continue",
        "  n=\$(basename \"\$p\")",
        "  rp=\$(readlink -f \"\$p\" 2>/dev/null || echo \"\")",
        "  sz=\$(blockdev --getsize64 \"\$p\" 2>/dev/null || echo \"\")",
        "  if [ -z \"\$sz\" ] || [ \"\$sz\" = \"0\" ]; then",
        "    rb=\$(basename \"\$rp\" 2>/dev/null || echo \"\")",
        "    s=\$(cat /sys/class/block/\$rb/size 2>/dev/null || echo \"\")",
        "    if [ -n \"\$s\" ]; then sz=\$((s * 512)); else sz=0; fi",
        "  fi",
        "  echo \"\$n|\$rp|\$sz\"",
        "done"
    ).joinToString("\n")

    fun scan(session: ShellSession = RootShell.shell()): List<PartitionInfo> {
        val result = session.exec(SCRIPT)
        return result.output
            .mapNotNull { parse(it) }
            .sortedWith(compareBy({ it.baseName }, { it.slotSuffix }, { it.name }))
    }

    fun find(session: ShellSession, name: String): PartitionInfo? =
        scan(session).firstOrNull { it.name == name }

    fun baseNameOf(name: String): Pair<String, String> {
        for (suffix in listOf("_a", "_b", "_c", "_d")) {
            if (name.length > suffix.length && name.endsWith(suffix)) {
                return name.substring(0, name.length - suffix.length) to suffix
            }
        }
        return name to ""
    }

    private fun parse(raw: String): PartitionInfo? {
        val parts = raw.split("|")
        if (parts.size < 3) return null
        val name = parts[0].trim()
        if (name.isEmpty()) return null
        val realPath = parts[1].trim()
        val size = parts[2].trim().toLongOrNull() ?: 0L
        val (base, slot) = baseNameOf(name)
        val isLogical = realPath.contains("/dev/block/dm-") ||
            realPath.contains("/dev/block/mapper/") ||
            realPath.startsWith("/dev/mapper/")
        return PartitionInfo(
            name = name,
            baseName = base,
            path = "/dev/block/by-name/$name",
            realPath = realPath,
            sizeBytes = size,
            isLogical = isLogical,
            slotSuffix = slot
        )
    }
}
