package dev.surgeon.partition.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {

    fun size(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return if (index == 0) "$bytes B" else String.format(Locale.US, "%.2f %s", value, units[index])
    }

    fun time(millis: Long): String {
        val sdf = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(millis))
    }

    fun stamp(millis: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(millis))

    fun shortHash(hash: String): String =
        if (hash.length > 16) hash.substring(0, 16) + "…" else hash
}
