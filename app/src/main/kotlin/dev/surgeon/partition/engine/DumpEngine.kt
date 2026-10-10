package dev.surgeon.partition.engine

import dev.surgeon.partition.core.shell.RootShell
import dev.surgeon.partition.core.shell.ShellException
import dev.surgeon.partition.core.shell.ShellSession
import dev.surgeon.partition.model.PartitionInfo
import kotlinx.coroutines.delay

/**
 * 分区 → 文件的提取。
 *
 * dd 在后台跑，主会话周期性 stat 输出文件大小来推进度，
 * 这样既不需要 GNU dd 的 status=progress，也不会阻塞读流。
 */
object DumpEngine {

    suspend fun dump(
        partition: PartitionInfo,
        outFile: String,
        probe: ShellSession = RootShell.shell(),
        onProgress: suspend (Long, Long) -> Unit
    ): String {
        val dir = outFile.substringBeforeLast('/')
        val doneFile = "$outFile.done"

        probe.exec("mkdir -p \"$dir\"")
        probe.exec("rm -f \"$outFile\" \"$doneFile\"")

        val work = RootShell.newSession()
        val job = "(dd if=\"${partition.path}\" of=\"$outFile\" bs=1048576 2>/dev/null); " +
            "echo \$? > \"$doneFile\""
        work.execNoWait(job)

        try {
            while (true) {
                delay(400)
                val line = probe.exec(
                    "if [ -f \"$doneFile\" ]; then echo \"DONE \$(cat \"$doneFile\" 2>/dev/null)\"; " +
                        "else echo \"SIZE \$(stat -c %s \"$outFile\" 2>/dev/null || echo 0)\"; fi"
                ).firstLine
                when {
                    line.startsWith("DONE") -> {
                        val code = line.removePrefix("DONE").trim().toIntOrNull() ?: -1
                        if (code != 0) throw ShellException("dd 以退出码 $code 结束，分区可能不可读")
                        break
                    }
                    line.startsWith("SIZE") -> {
                        val size = line.removePrefix("SIZE").trim().toLongOrNull() ?: 0L
                        onProgress(size.coerceAtMost(partition.sizeBytes), partition.sizeBytes)
                    }
                }
            }
        } finally {
            work.close()
            probe.exec("rm -f \"$doneFile\"")
        }

        onProgress(partition.sizeBytes, partition.sizeBytes)
        return ImageProbe.sha256(probe, outFile)
    }
}
