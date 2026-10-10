package dev.surgeon.partition.engine

import dev.surgeon.partition.core.shell.RootShell
import dev.surgeon.partition.core.shell.ShellSession

/**
 * 输出目录的空间占用统计与清理。
 *
 * 统计用一个 shell 脚本一次算完：dumps 目录、还原点目录、总占用、剩余空间。
 */
object StorageEngine {

    data class Usage(
        val dumpsBytes: Long,
        val restorePointsBytes: Long,
        val totalBytes: Long,
        val freeBytes: Long,
        val imageCount: Int
    )

    fun usage(session: ShellSession = RootShell.shell(), rootDir: String): Usage {
        val dumps = "$rootDir/dumps"
        val points = "$rootDir/restore-points"
        val script = listOf(
            "echo \"dumps=\$(du -sb \"$dumps\" 2>/dev/null | awk '{print \$1}' || echo 0)\"",
            "echo \"points=\$(du -sb \"$points\" 2>/dev/null | awk '{print \$1}' || echo 0)\"",
            "echo \"total=\$(du -sb \"$rootDir\" 2>/dev/null | awk '{print \$1}' || echo 0)\"",
            "echo \"free=\$(df -P \"$rootDir\" 2>/dev/null | tail -1 | awk '{print \$4}' || echo 0)\"",
            "echo \"count=\$(find \"$rootDir\" -type f -name '*.img' 2>/dev/null | wc -l)\""
        ).joinToString("\n")

        val map = HashMap<String, String>()
        for (line in session.exec(script).output) {
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        return Usage(
            dumpsBytes = map["dumps"]?.toLongOrNull() ?: 0L,
            restorePointsBytes = map["points"]?.toLongOrNull() ?: 0L,
            totalBytes = map["total"]?.toLongOrNull() ?: 0L,
            // df 输出的是 1K 块
            freeBytes = (map["free"]?.toLongOrNull() ?: 0L) * 1024L,
            imageCount = map["count"]?.toIntOrNull() ?: 0
        )
    }

    /** 删除目录并返回释放的字节数（按删除前统计）。 */
    fun deleteDir(session: ShellSession = RootShell.shell(), dir: String): Long {
        val before = session.exec("du -sb \"$dir\" 2>/dev/null | awk '{print $1}'")
            .firstLine.trim().toLongOrNull() ?: 0L
        session.exec("rm -rf \"$dir\"")
        return before
    }

    fun clearDumps(session: ShellSession = RootShell.shell(), rootDir: String): Long =
        deleteDir(session, "$rootDir/dumps")

    fun clearAll(session: ShellSession = RootShell.shell(), rootDir: String): Long =
        deleteDir(session, rootDir)
}
