package dev.surgeon.partition.engine

import dev.surgeon.partition.core.shell.RootShell
import dev.surgeon.partition.core.shell.ShellSession

object ImageProbe {

    data class ImageInfo(
        val path: String,
        val sizeBytes: Long,
        val magic: String,
        val kind: String
    )

    /** 扫描输出目录下的所有 .img，返回 "大小|路径" 列表。 */
    fun listImages(session: ShellSession = RootShell.shell(), rootDir: String): List<Pair<Long, String>> {
        val script = listOf(
            "find \"$rootDir\" -type f -name '*.img' 2>/dev/null | while read -r f; do",
            "  echo \"\$(stat -c %s \"\$f\" 2>/dev/null || echo 0)|\$f\"",
            "done"
        ).joinToString("\n")
        return session.exec(script).output.mapNotNull { line ->
            val idx = line.indexOf('|')
            if (idx <= 0) return@mapNotNull null
            val size = line.substring(0, idx).trim().toLongOrNull() ?: 0L
            val path = line.substring(idx + 1).trim()
            if (path.isEmpty()) null else size to path
        }.sortedByDescending { it.second }
    }

    fun inspect(session: ShellSession = RootShell.shell(), path: String): ImageInfo {
        val size = session.exec("stat -c %s \"$path\" 2>/dev/null || echo -1")
            .firstLine.trim().toLongOrNull() ?: -1L
        val hex = session.exec("head -c 8 \"$path\" 2>/dev/null | od -An -tx1 | tr -d '[:space:]'")
            .firstLine.trim()
        val magic = hexToAscii(hex)
        return ImageInfo(path, size, magic, kindOf(magic))
    }

    fun sha256(session: ShellSession, path: String, limitBytes: Long = -1L): String {
        val cmd = if (limitBytes > 0) {
            "head -c $limitBytes \"$path\" 2>/dev/null | sha256sum | cut -d' ' -f1"
        } else {
            "sha256sum \"$path\" 2>/dev/null | cut -d' ' -f1"
        }
        return session.exec(cmd).firstLine.trim()
    }

    private fun kindOf(magic: String): String = when {
        magic.startsWith("ANDROID!") -> "boot / init_boot 镜像"
        magic.startsWith("VNDRBOOT") -> "vendor_boot 镜像"
        magic.startsWith("AVB0") -> "vbmeta 镜像"
        magic.startsWith("CrAU") -> "payload.bin（需解包后使用）"
        magic.startsWith("PK") -> "ZIP 压缩包（不是可直接刷入的镜像）"
        magic.isBlank() -> "无法识别"
        else -> "未知格式"
    }

    private fun hexToAscii(hex: String): String {
        if (hex.length < 2) return ""
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < hex.length) {
            val value = hex.substring(i, i + 2).toIntOrNull(16) ?: break
            sb.append(if (value in 32..126) value.toChar() else '.')
            i += 2
        }
        return sb.toString()
    }
}
