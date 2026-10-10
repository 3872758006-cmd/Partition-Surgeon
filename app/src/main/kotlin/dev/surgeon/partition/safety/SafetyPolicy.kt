package dev.surgeon.partition.safety

import dev.surgeon.partition.model.PartitionGroup
import dev.surgeon.partition.model.PartitionInfo

/**
 * 分区安全分级。
 *
 * 分级只决定"允不允许"，不决定"要不要提醒"：
 * 所有写操作无论哪个等级都要二次确认，FORBIDDEN 则直接拒绝。
 */
enum class SafetyLevel { SAFE, CAUTION, DANGEROUS, FORBIDDEN }

object SafetyPolicy {

    /** 写坏以后基本等于变砖或丢号，任何模式下都不允许读写。 */
    private val FORBIDDEN_NAMES = setOf(
        "userdata", "metadata", "misc", "frp", "persist", "persistblk",
        "nvram", "nvcfg", "nvdata", "efs", "efscs", "proinfo", "protect1", "protect2",
        "seccfg", "devinfo", "boot_para", "para", "expdb", "keystore", "super",
        "rescue", "opconfig", "oplusreserve", "logdump", "minidump", "rawdump"
    )

    /** 可自由提取、刷入失败可救回（fastboot 重刷原厂镜像即可）。 */
    private val SAFE_NAMES = setOf(
        "boot", "init_boot", "vendor_boot", "vendor_kernel_boot", "recovery",
        "dtbo", "vbmeta", "vbmeta_system", "vbmeta_vendor", "bootconfig", "pvmfw"
    )

    /** 基带、安全协处理器等，刷错会丢信号或丢指纹。 */
    private val CAUTION_NAMES = setOf(
        "modem", "md1img", "md1dsp", "md1arm7", "md3img", "bluetooth",
        "dsp", "dsp_a", "dsp_b", "tee1", "tee2", "tlc", "oem", "logo",
        "scp1", "scp2", "sspm_1", "sspm_2", "mcupm1", "mcupm2",
        "gz1", "gz2", "cam_vpu1", "cam_vpu2", "spmfw", "pi_img", "lk", "lk2",
        "preloader", "preloader_raw", "sboot", "up_param", "dtb", "dtb_a", "dtb_b"
    )

    private val VENDOR_HINTS = listOf(
        "modem", "md1", "md3", "dsp", "tee", "scp", "sspm", "mcupm",
        "gz", "cam_vpu", "spmfw", "lk", "preloader", "bluetooth", "oem"
    )

    private val BOOT_HINTS = listOf("boot", "vbmeta", "dtbo", "pvmfw", "recovery", "bootconfig")

    fun groupOf(baseName: String, isLogical: Boolean): PartitionGroup {
        val name = baseName.lowercase()
        if (name in SAFE_NAMES || BOOT_HINTS.any { name.contains(it) }) return PartitionGroup.BOOT
        if (isLogical) return PartitionGroup.SYSTEM
        if (name in CAUTION_NAMES || VENDOR_HINTS.any { name.contains(it) }) return PartitionGroup.VENDOR
        return PartitionGroup.OTHER
    }

    fun levelOf(baseName: String, isLogical: Boolean): SafetyLevel {
        val name = baseName.lowercase()
        if (name in FORBIDDEN_NAMES) return SafetyLevel.FORBIDDEN
        if (name in SAFE_NAMES) return SafetyLevel.SAFE
        // 动态逻辑分区挂在 dm 设备上，dd 写入会破坏 dm-verity / 叠加层
        if (isLogical) return SafetyLevel.DANGEROUS
        if (name in CAUTION_NAMES) return SafetyLevel.CAUTION
        return SafetyLevel.CAUTION
    }

    /** 提取（读）操作是否被允许。 */
    fun canExtract(partition: PartitionInfo): Boolean =
        levelOf(partition.baseName, partition.isLogical) != SafetyLevel.FORBIDDEN

    /** 刷入（写）操作是否被允许。expertMode 只放开 DANGEROUS，不放开 FORBIDDEN。 */
    fun canFlash(partition: PartitionInfo, expertMode: Boolean): Boolean {
        return when (levelOf(partition.baseName, partition.isLogical)) {
            SafetyLevel.FORBIDDEN -> false
            SafetyLevel.DANGEROUS -> expertMode
            SafetyLevel.CAUTION, SafetyLevel.SAFE -> true
        }
    }
}
