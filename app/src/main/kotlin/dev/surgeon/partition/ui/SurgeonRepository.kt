package dev.surgeon.partition.ui

import dev.surgeon.partition.core.RootManager
import dev.surgeon.partition.engine.PartitionScanner
import dev.surgeon.partition.engine.SlotReader
import dev.surgeon.partition.model.PartitionInfo
import dev.surgeon.partition.model.SlotInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 分区表与槽位信息在一次会话内缓存，写入/切换槽位后调用 invalidate()。 */
object SurgeonRepository {

    @Volatile
    private var partitionsCache: List<PartitionInfo>? = null

    @Volatile
    private var slotCache: SlotInfo? = null

    suspend fun partitions(force: Boolean = false): List<PartitionInfo> =
        withContext(Dispatchers.IO) {
            val cached = partitionsCache
            if (force || cached == null) {
                PartitionScanner.scan().also { partitionsCache = it }
            } else cached
        }

    suspend fun slot(force: Boolean = false): SlotInfo = withContext(Dispatchers.IO) {
        val cached = slotCache
        if (force || cached == null) {
            SlotReader.read().also { slotCache = it }
        } else cached
    }

    suspend fun rootInfo(): RootManager.RootInfo = withContext(Dispatchers.IO) {
        RootManager.probe()
    }

    /** 同步取已探测到的 root 信息，没探测过返回 null（不触发 shell）。 */
    fun cachedRoot(): RootManager.RootInfo? = RootManager.lastKnown()

    fun invalidate() {
        partitionsCache = null
        slotCache = null
    }
}
