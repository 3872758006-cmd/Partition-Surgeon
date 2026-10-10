package dev.surgeon.partition.ui

import android.content.Context
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.surgeon.partition.R
import dev.surgeon.partition.core.shell.RootShell
import dev.surgeon.partition.engine.DumpEngine
import dev.surgeon.partition.engine.FlashEngine
import dev.surgeon.partition.engine.ImageProbe
import dev.surgeon.partition.engine.OpLog
import dev.surgeon.partition.engine.RestorePointStore
import dev.surgeon.partition.model.OpLogEntry
import dev.surgeon.partition.model.PartitionInfo
import dev.surgeon.partition.model.RestoreEntry
import dev.surgeon.partition.model.RestorePoint
import dev.surgeon.partition.safety.SafetyLevel
import dev.surgeon.partition.safety.SafetyPolicy
import dev.surgeon.partition.util.Format
import dev.surgeon.partition.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PartitionDetailFragment : PreferenceFragmentCompat(), TitledFragment {

    private var partitionName: String = ""
    private var partition: PartitionInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        partitionName = arguments?.getString(ARG_NAME).orEmpty()
    }

    override fun title(context: Context): String = partitionName

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        load()
    }

    private fun load() {
        safeLaunch {
            val found = SurgeonRepository.partitions()
                .firstOrNull { it.name == partitionName }
            if (found == null) {
                toast(R.string.err_no_partition)
                return@safeLaunch
            }
            partition = found
            build(found)
        }
    }

    private fun build(partition: PartitionInfo) {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)

        val info = PreferenceCategory(context).apply {
            setTitle(R.string.pd_cat_info)
            key = "cat_info"
        }
        screen.addPreference(info)
        info.addPreference(readonly(context, R.string.pd_path, partition.path))
        info.addPreference(readonly(context, R.string.pd_real, partition.realPath.ifBlank { "—" }))
        info.addPreference(readonly(context, R.string.pd_size, Format.size(partition.sizeBytes)))
        info.addPreference(
            readonly(
                context,
                R.string.pd_type,
                getString(if (partition.isLogical) R.string.type_logical else R.string.type_physical)
            )
        )
        info.addPreference(
            readonly(context, R.string.pd_slot, partition.slotSuffix.ifBlank { "无" })
        )
        val level = SafetyPolicy.levelOf(partition.baseName, partition.isLogical)
        info.addPreference(readonly(context, R.string.pd_safety, safetyLabel(level)))

        val ops = PreferenceCategory(context).apply {
            setTitle(R.string.pd_cat_ops)
            key = "cat_ops"
        }
        screen.addPreference(ops)

        if (SafetyPolicy.canExtract(partition)) {
            ops.addPreference(
                Preference(context).apply {
                    key = "op_extract"
                    setTitle(R.string.pd_action_extract)
                    setSummary(R.string.pd_action_extract_summary)
                    setIcon(R.drawable.ic_extract)
                    setOnPreferenceClickListener { extract(partition); true }
                }
            )
            ops.addPreference(
                Preference(context).apply {
                    key = "op_snapshot"
                    setTitle(R.string.pd_action_snapshot)
                    setSummary(R.string.pd_action_snapshot_summary)
                    setIcon(R.drawable.ic_restore)
                    setOnPreferenceClickListener { snapshot(partition); true }
                }
            )
        }
        if (SafetyPolicy.canFlash(partition, Prefs.expertMode(context))) {
            ops.addPreference(
                Preference(context).apply {
                    key = "op_flash"
                    setTitle(R.string.pd_action_flash)
                    setSummary(R.string.pd_action_flash_summary)
                    setIcon(R.drawable.ic_flash)
                    setOnPreferenceClickListener { pickImage(partition); true }
                }
            )
        }
        ops.addPreference(
            Preference(context).apply {
                key = "op_hash"
                setTitle(R.string.pd_action_hash)
                setSummary(R.string.pd_action_hash_summary)
                setIcon(R.drawable.ic_shield)
                setOnPreferenceClickListener { hash(partition); true }
            }
        )

        if (SafetyPolicy.canExtract(partition)) {
            ops.addPreference(
                Preference(context).apply {
                    key = "op_compare"
                    setTitle(R.string.pd_action_compare)
                    setSummary(R.string.pd_action_compare_summary)
                    setIcon(R.drawable.ic_compare)
                    setOnPreferenceClickListener { compare(partition); true }
                }
            )
        }

        preferenceScreen = screen
    }

    private fun readonly(context: Context, titleRes: Int, value: String): Preference =
        Preference(context).apply {
            setTitle(titleRes)
            summary = value
            isSelectable = false
            isPersistent = false
        }

    private fun safetyLabel(level: SafetyLevel): String = when (level) {
        SafetyLevel.SAFE -> getString(R.string.safety_safe)
        SafetyLevel.CAUTION -> getString(R.string.safety_caution)
        SafetyLevel.DANGEROUS -> getString(R.string.safety_dangerous)
        SafetyLevel.FORBIDDEN -> getString(R.string.safety_forbidden)
    }

    // ---- 提取 ----

    private fun extract(partition: PartitionInfo) {
        val context = requireContext()
        val file = "${Prefs.outputDir(context)}/dumps/" +
            "${partition.name}_${Format.stamp(System.currentTimeMillis())}.img"
        OperationRunner.start(
            context,
            viewLifecycleOwner,
            getString(R.string.op_extracting),
            partition.name
        ) { reporter ->
            withContext(Dispatchers.IO) {
                reporter.log("源：${partition.path}")
                reporter.log("目标：$file")
                val sha = DumpEngine.dump(partition, file, RootShell.shell()) { done, total ->
                    reporter.progress(done, total)
                }
                reporter.log("SHA-256：$sha")
                OpLog.add(
                    context,
                    OpLogEntry(
                        System.currentTimeMillis(), "提取", partition.name, true,
                        "${file.substringAfterLast('/')} · ${Format.size(partition.sizeBytes)}"
                    )
                )
                getString(R.string.result_extract_ok, file)
            }
        }
    }

    // ---- 刷入 ----

    private fun pickImage(partition: PartitionInfo) {
        val context = requireContext()
        safeLaunch {
            val images = withContext(Dispatchers.IO) {
                ImageProbe.listImages(RootShell.shell(), Prefs.outputDir(context))
            }
            if (images.isEmpty()) {
                toast(R.string.flash_no_image)
                return@safeLaunch
            }
            val labels = images.map { (size, path) ->
                "${path.substringAfterLast('/')} · ${Format.size(size)}"
            }.toTypedArray()
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.flash_pick_image)
                .setItems(labels) { _, which ->
                    val (size, path) = images[which]
                    confirmFlash(partition, path, size)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    private fun confirmFlash(partition: PartitionInfo, image: String, imageSize: Long) {
        val context = requireContext()
        safeLaunch {
            val info = withContext(Dispatchers.IO) {
                ImageProbe.inspect(RootShell.shell(), image)
            }
            val slot = SurgeonRepository.slot()
            val message = buildString {
                append(
                    getString(
                        R.string.flash_warn_body,
                        image,
                        Format.size(imageSize),
                        partition.name
                    )
                )
                append("\n\n类型：").append(info.kind)
                if (Prefs.forceBackup(context)) {
                    append("\n\n").append(getString(R.string.flash_force_backup_on))
                }
                if (partition.slotSuffix.isNotBlank() && partition.slotSuffix != slot.activeSuffix) {
                    append("\n\n").append(getString(R.string.flash_cross_slot, slot.activeSuffix))
                }
            }
            confirm(
                getString(R.string.flash_warn_title),
                message,
                getString(R.string.flash_check_backup)
            ) { runFlash(partition, image) }
        }
    }

    private fun runFlash(partition: PartitionInfo, image: String) {
        val context = requireContext()
        val forceBackup = Prefs.forceBackup(context)
        val verify = Prefs.verifyAfterFlash(context)
        OperationRunner.start(
            context,
            viewLifecycleOwner,
            getString(R.string.op_flashing),
            partition.name
        ) { reporter ->
            withContext(Dispatchers.IO) {
                if (forceBackup) {
                    reporter.log(getString(R.string.op_backing_up))
                    val dir = "${Prefs.outputDir(context)}/restore-points/" +
                        "auto-${Format.stamp(System.currentTimeMillis())}"
                    DumpEngine.dump(partition, "$dir/${partition.name}.img", RootShell.shell()) { done, total ->
                        reporter.progress(done, total)
                    }
                    reporter.log("备份完成：$dir")
                }
                reporter.log(getString(R.string.op_writing))
                val size = FlashEngine.flash(partition, image, RootShell.shell()) { done, total ->
                    reporter.progress(done, total)
                }
                if (verify) {
                    reporter.log(getString(R.string.op_verifying))
                    val pass = FlashEngine.verify(RootShell.shell(), partition, image, size)
                    if (!pass) {
                        throw IllegalStateException("回读校验不一致，分区内容可能与镜像不同")
                    }
                    reporter.log("校验通过")
                }
                OpLog.add(
                    context,
                    OpLogEntry(
                        System.currentTimeMillis(), "刷入", partition.name, true,
                        "${image.substringAfterLast('/')} · ${Format.size(size)}"
                    )
                )
                getString(R.string.result_flash_ok, partition.name, Format.size(size))
            }
        }
    }

    // ---- 还原点 ----

    private fun snapshot(partition: PartitionInfo) {
        val context = requireContext()
        val id = RestorePointStore.newId()
        val file = "${Prefs.outputDir(context)}/restore-points/$id/${partition.name}.img"
        OperationRunner.start(
            context,
            viewLifecycleOwner,
            getString(R.string.op_snapshots),
            partition.name
        ) { reporter ->
            withContext(Dispatchers.IO) {
                val sha = DumpEngine.dump(partition, file, RootShell.shell()) { done, total ->
                    reporter.progress(done, total)
                }
                val slot = SurgeonRepository.slot()
                val point = RestorePoint(
                    id = id,
                    label = "手动 · ${partition.name}",
                    createdAt = System.currentTimeMillis(),
                    device = slot.device,
                    fingerprint = slot.fingerprint,
                    activeSlot = slot.activeSuffix,
                    rootDir = Prefs.outputDir(context),
                    entries = listOf(
                        RestoreEntry(partition.name, partition.path, partition.sizeBytes, file, sha)
                    )
                )
                RestorePointStore.add(context, point)
                OpLog.add(
                    context,
                    OpLogEntry(
                        System.currentTimeMillis(), "还原点", partition.name, true, id
                    )
                )
                getString(R.string.result_snapshot_ok, id)
            }
        }
    }

    // ---- 校验和 ----

    private fun hash(partition: PartitionInfo) {
        val context = requireContext()
        OperationRunner.start(
            context,
            viewLifecycleOwner,
            getString(R.string.op_hashing),
            partition.name
        ) { reporter ->
            withContext(Dispatchers.IO) {
                reporter.log("读取 ${partition.path} …")
                reporter.progress(0, 1)
                val sha = ImageProbe.sha256(RootShell.shell(), partition.path)
                reporter.progress(1, 1)
                getString(R.string.result_hash, sha)
            }
        }
    }

    // ---- 与镜像比对 ----

    private fun compare(partition: PartitionInfo) {
        val context = requireContext()
        safeLaunch {
            val images = withContext(Dispatchers.IO) {
                ImageProbe.listImages(RootShell.shell(), Prefs.outputDir(context))
            }
            if (images.isEmpty()) {
                toast(R.string.flash_no_image)
                return@safeLaunch
            }
            val labels = images.map { (size, path) ->
                "${path.substringAfterLast('/')} · ${Format.size(size)}"
            }.toTypedArray()
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.compare_pick)
                .setItems(labels) { _, which -> runCompare(partition, images[which]) }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    private fun runCompare(partition: PartitionInfo, image: Pair<Long, String>) {
        val context = requireContext()
        val limit = image.first
        OperationRunner.start(
            context,
            viewLifecycleOwner,
            getString(R.string.op_comparing),
            partition.name
        ) { reporter ->
            withContext(Dispatchers.IO) {
                reporter.log(getString(R.string.compare_range, Format.size(limit)))
                reporter.progress(0, 2)
                val partitionSha = ImageProbe.sha256(RootShell.shell(), partition.path, limit)
                reporter.progress(1, 2)
                val imageSha = ImageProbe.sha256(RootShell.shell(), image.second, limit)
                reporter.progress(2, 2)
                if (partitionSha.isNotBlank() && partitionSha == imageSha) {
                    getString(R.string.compare_equal, Format.size(limit))
                } else {
                    getString(
                        R.string.compare_diff,
                        partitionSha.ifBlank { "-" },
                        imageSha.ifBlank { "-" }
                    )
                }
            }
        }
    }

    companion object {
        private const val ARG_NAME = "name"

        fun newInstance(name: String): PartitionDetailFragment =
            PartitionDetailFragment().apply { arguments = bundleOf(ARG_NAME to name) }
    }
}
