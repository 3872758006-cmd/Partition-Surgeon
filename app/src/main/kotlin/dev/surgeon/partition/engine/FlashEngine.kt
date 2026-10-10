package dev.surgeon.partition.engine

import dev.surgeon.partition.core.shell.RootShell
import dev.surgeon.partition.core.shell.ShellException
import dev.surgeon.partition.core.shell.ShellSession
import dev.surgeon.partition.model.PartitionInfo
import kotlinx.coroutines.delay

/**
 * 文件 → 分区的写入。
 *
 * 按 4MiB 分块 dd（skip/seek 推进），每块写完后把已写字节数写入进度文件，
 * 主会话轮询该文件得到进度。分块写入让进度可控，也避免一次性 dd 卡住无法观测。
 */
object FlashEngine {

    private const val PROG = "/data/local/tmp/.surgeon_flash_prog"
    private const val DONE = "/data/local/tmp/.surgeon_flash_done"

    suspend fun flash(
        partition: PartitionInfo,
        image: String,
        probe: ShellSession = RootShell.shell(),
        onProgress: suspend (Long, Long) -> Unit
    ): Long {
        val sizeLine = probe.exec("stat -c %s \"$image\" 2>/dev/null || echo -1").firstLine.trim()
        val imageSize = sizeLine.toLongOrNull() ?: -1L
        if (imageSize <= 0) throw ShellException("镜像文件不存在或为空：$image")
        if (partition.sizeBytes > 0 && imageSize > partition.sizeBytes) {
            throw ShellException(
                "镜像（$imageSize 字节）大于目标分区（${partition.sizeBytes} 字节），已中止"
            )
        }

        probe.exec("rm -f \"$PROG\" \"$DONE\"")
        val script = listOf(
            "total=\$(stat -c %s \"$image\" 2>/dev/null || echo 0)",
            "bs=4194304",
            "i=0",
            "n=\$(( (total + bs - 1) / bs ))",
            "while [ \"\$i\" -lt \"\$n\" ]; do",
            "  dd if=\"$image\" of=\"${partition.path}\" bs=\$bs skip=\$i seek=\$i count=1 2>/dev/null || { echo FAIL > \"$PROG\"; break; }",
            "  i=\$((i + 1))",
            "  echo \$((i * bs)) > \"$PROG\"",
            "done",
            "sync",
            "if grep -q FAIL \"$PROG\" 2>/dev/null; then echo BAD > \"$DONE\"; else echo GOOD > \"$DONE\"; fi"
        ).joinToString("\n")

        val work = RootShell.newSession()
        work.execNoWait(script)
        try {
            while (true) {
                delay(400)
                val line = probe.exec(
                    "if [ -f \"$DONE\" ]; then echo \"DONE \$(cat \"$DONE\" 2>/dev/null)\"; " +
                        "else echo \"SIZE \$(cat \"$PROG\" 2>/dev/null || echo 0)\"; fi"
                ).firstLine
                when {
                    line.startsWith("DONE") -> {
                        val state = line.removePrefix("DONE").trim()
                        if (state != "GOOD") throw ShellException("写入过程中 dd 失败，分区可能已损坏")
                        break
                    }
                    line.startsWith("SIZE") -> {
                        val done = line.removePrefix("SIZE").trim().toLongOrNull() ?: 0L
                        onProgress(done.coerceAtMost(imageSize), imageSize)
                    }
                }
            }
        } finally {
            work.close()
            probe.exec("rm -f \"$PROG\" \"$DONE\"")
        }
        onProgress(imageSize, imageSize)
        return imageSize
    }

    /**
     * 写入后回读校验：只比对镜像长度的前 N 字节，避免分区尾部残留数据造成误判。
     */
    fun verify(probe: ShellSession, partition: PartitionInfo, image: String, imageSize: Long): Boolean {
        val fromPartition = ImageProbe.sha256(probe, partition.path, imageSize)
        val fromImage = ImageProbe.sha256(probe, image, imageSize)
        return fromPartition.isNotBlank() && fromPartition == fromImage
    }
}
