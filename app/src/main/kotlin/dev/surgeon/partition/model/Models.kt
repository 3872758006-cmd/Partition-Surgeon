package dev.surgeon.partition.model

enum class PartitionGroup { BOOT, SYSTEM, VENDOR, OTHER }

/** 一个块设备分区的静态描述。 */
data class PartitionInfo(
    val name: String,
    val baseName: String,
    val path: String,
    val realPath: String,
    val sizeBytes: Long,
    val isLogical: Boolean,
    val slotSuffix: String
)

data class SlotState(
    val suffix: String,
    val bootable: Boolean?,
    val successful: Boolean?
)

data class SlotInfo(
    val isAbDevice: Boolean,
    val activeSuffix: String,
    val slotStates: Map<String, SlotState>,
    val abUpdate: Boolean,
    val virtualAb: Boolean,
    val dynamicPartitions: Boolean,
    val vbState: String,
    val locked: Boolean?,
    val vbmetaState: String,
    val fingerprint: String,
    val kernel: String,
    val sdk: String,
    val device: String
) {
    companion object {
        /** 读取失败时的占位值，避免 UI 空着。 */
        fun empty(): SlotInfo = SlotInfo(
            isAbDevice = false,
            activeSuffix = "",
            slotStates = emptyMap(),
            abUpdate = false,
            virtualAb = false,
            dynamicPartitions = false,
            vbState = "",
            locked = null,
            vbmetaState = "",
            fingerprint = "",
            kernel = "",
            sdk = "",
            device = ""
        )
    }
}

data class RestoreEntry(
    val partition: String,
    val path: String,
    val sizeBytes: Long,
    val file: String,
    val sha256: String
)

data class RestorePoint(
    val id: String,
    val label: String,
    val createdAt: Long,
    val device: String,
    val fingerprint: String,
    val activeSlot: String,
    val rootDir: String,
    val entries: List<RestoreEntry>
) {
    val totalBytes: Long get() = entries.sumOf { it.sizeBytes }
}

data class OpLogEntry(
    val time: Long,
    val op: String,
    val target: String,
    val success: Boolean,
    val detail: String
)
