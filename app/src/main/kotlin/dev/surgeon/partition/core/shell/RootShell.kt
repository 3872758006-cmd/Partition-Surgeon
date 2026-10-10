package dev.surgeon.partition.core.shell

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/** 一次命令执行的结果。stdout 与 stderr 已合并，顺序即为终端上看到的顺序。 */
class ShellResult(val exitCode: Int, val output: List<String>) {
    val isSuccess: Boolean get() = exitCode == 0
    val firstLine: String get() = output.firstOrNull()?.trim().orEmpty()
    val asText: String get() = output.joinToString("\n")
}

class ShellException(message: String, val result: ShellResult? = null) : RuntimeException(message)

/**
 * 一个常驻的 root shell 会话。
 *
 * 每条命令后面追加一个唯一 token 行（携带上一条命令的退出码），
 * 读到 token 即认为本次执行结束。这样即使命令产生大量输出也不会误判边界。
 */
class ShellSession internal constructor(
    private val process: Process,
    private val writer: PrintWriter,
    private val reader: BufferedReader
) : Closeable {

    private val tokenSeq = AtomicInteger(0)

    @Volatile
    var closed: Boolean = false
        private set

    @Synchronized
    fun exec(command: String): ShellResult {
        check(!closed) { "shell session already closed" }
        val token = "__SURGEON_${tokenSeq.incrementAndGet()}__"
        writer.print(command)
        if (!command.endsWith("\n")) writer.print("\n")
        writer.print("printf '%s:%s\\n' \"$token\" \"\$?\"\n")
        writer.flush()

        val lines = ArrayList<String>()
        var code = -1
        while (true) {
            val line = reader.readLine() ?: break
            val at = line.indexOf(token)
            if (at >= 0) {
                val tail = line.substring(at + token.length)
                    .trim()
                    .trimStart(':')
                    .trim()
                code = tail.toIntOrNull() ?: 0
                break
            }
            lines.add(line)
        }
        if (code < 0) {
            throw ShellException("shell 会话意外终止（su 被回收或进程退出）")
        }
        return ShellResult(code, lines)
    }

    /** 只下发命令、不读输出，用于后台长任务（配合轮询进度文件）。 */
    @Synchronized
    fun execNoWait(command: String) {
        check(!closed) { "shell session already closed" }
        writer.print(command)
        if (!command.endsWith("\n")) writer.print("\n")
        writer.flush()
    }

    @Synchronized
    fun execOrThrow(command: String): ShellResult {
        val result = exec(command)
        if (!result.isSuccess) {
            throw ShellException("命令执行失败（exit=${result.exitCode}）：$command", result)
        }
        return result
    }

    fun isAlive(): Boolean = runCatching { process.exitValue(); false }.getOrDefault(true)

    override fun close() {
        closed = true
        runCatching {
            writer.print("exit\n")
            writer.flush()
        }
        runCatching { process.destroy() }
    }
}

object RootShell {

    private const val SU = "su"

    @Volatile
    private var cached: ShellSession? = null

    /** 新开一个 su 进程。长任务建议单独开，避免污染主会话的输出流。 */
    fun newSession(): ShellSession {
        val builder = ProcessBuilder(SU)
        builder.redirectErrorStream(true)
        builder.environment()["LC_ALL"] = "C"
        val process = builder.start()
        val writer = PrintWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8), true)
        val reader = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
        return ShellSession(process, writer, reader)
    }

    /** 全局主会话，用于短命令（getprop、stat、ls 等）。 */
    fun shell(): ShellSession {
        cached?.let { if (it.isAlive()) return it }
        return synchronized(this) {
            cached?.takeIf { it.isAlive() } ?: newSession().also { cached = it }
        }
    }

    fun reset() {
        synchronized(this) {
            cached?.close()
            cached = null
        }
    }
}
