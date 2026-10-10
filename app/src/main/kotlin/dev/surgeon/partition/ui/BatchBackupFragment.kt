package dev.surgeon.partition.ui

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.preference.CheckBoxPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.surgeon.partition.R
import dev.surgeon.partition.core.shell.RootShell
import dev.surgeon.partition.engine.DumpEngine
import dev.surgeon.partition.engine.OpLog
import dev.surgeon.partition.engine.RestorePointStore
import dev.surgeon.partition.model.OpLogEntry
import dev.surgeon.partition.model.PartitionGroup
import dev.surgeon.partition.model.PartitionInfo
import dev.surgeon.partition.model.RestoreEntry
import dev.surgeon.partition.model.RestorePoint
import dev.surgeon.partition.safety.SafetyLevel
import dev.surgeon.partition.safety.SafetyPolicy
import dev.surgeon.partition.util.Format
import dev.surgeon.partition.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 勾选多个分区，一次打包成一个还原点。 */
class BatchBackupFragment : PreferenceFragmentCompat(), TitledFragment {

    private val selected = LinkedHashSet<String>()
    private val boxes = LinkedHashMap<String, CheckBoxPreference>()
    private var partitions: List<PartitionInfo> = emptyList()

    override fun title(context: Context): String = context.getString(R.string.batch_title)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        load()
    }

    private fun load() {
        safeLaunch {
            partitions = SurgeonRepository.partitions()
                .filter { SafetyPolicy.canExtract(it) }
            build()
        }
    }

    private fun build() {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)
        boxes.clear()
        if (selected.isEmpty()) preselectBoot()

        screen.addPreference(
            Preference(context).apply {
                setTitle(R.string.batch_hint)
                isSelectable = false
            }
        )

        val presets = PreferenceCategory(context).apply { title = context.getString(R.string.batch_title) }
        screen.addPreference(presets)
        presets.addPreference(
            Preference(context).apply {
                setTitle(R.string.batch_preset_boot)
                setIcon(R.drawable.ic_restore)
                setOnPreferenceClickListener { applyPreset(bootOnly = true); true }
            }
        )
        presets.addPreference(
            Preference(context).apply {
                setTitle(R.string.batch_preset_all)
                setIcon(R.drawable.ic_restore)
                setOnPreferenceClickListener { applyPreset(bootOnly = false); true }
            }
        )
        presets.addPreference(
            Preference(context).apply {
                setTitle(R.string.batch_clear_all)
                setOnPreferenceClickListener { applyNone(); true }
            }
        )

        val groups = listOf(
            PartitionGroup.BOOT to Pair(R.string.group_boot_title, R.string.group_boot_summary),
            PartitionGroup.VENDOR to Pair(R.string.group_vendor_title, R.string.group_vendor_summary),
            PartitionGroup.SYSTEM to Pair(R.string.group_system_title, R.string.group_system_summary),
            PartitionGroup.OTHER to Pair(R.string.group_other_title, R.string.group_other_summary)
        )

        groups.forEach { (group, text) ->
            val list = partitions.filter {
                SafetyPolicy.groupOf(it.baseName, it.isLogical) == group
            }
            if (list.isEmpty()) return@forEach
            val cat = PreferenceCategory(context).apply {
                setTitle(text.first)
                summary = getString(text.second)
            }
            screen.addPreference(cat)
            list.forEach { partition ->
                val box = CheckBoxPreference(context).apply {
                    key = partition.name
                    title = partition.name
                    summary = "${Format.size(partition.sizeBytes)} · ${safetyText(partition)}"
                    isChecked = selected.contains(partition.name)
                    setOnPreferenceChangeListener { _, value ->
                        val on = value as? Boolean ?: false
                        if (on) selected.add(partition.name) else selected.remove(partition.name)
                        refreshActionItem()
                        true
                    }
                }
                boxes[partition.name] = box
                cat.addPreference(box)
            }
        }

        val action = PreferenceCategory(context).apply { title = "" }
        screen.addPreference(action)
        val go = Preference(context).apply {
            key = "batch_go"
            setIcon(R.drawable.ic_extract)
            setOnPreferenceClickListener { askLabel(); true }
        }
        action.addPreference(go)
        refreshActionItem()
        preferenceScreen = screen
    }

    private fun safetyText(partition: PartitionInfo): String =
        when (SafetyPolicy.levelOf(partition.baseName, partition.isLogical)) {
            SafetyLevel.SAFE -> getString(R.string.safety_safe)
            SafetyLevel.CAUTION -> getString(R.string.safety_caution)
            SafetyLevel.DANGEROUS -> getString(R.string.safety_dangerous)
            SafetyLevel.FORBIDDEN -> getString(R.string.safety_forbidden)
        }

    private fun preselectBoot() {
        selected.clear()
        partitions.filter { SafetyPolicy.levelOf(it.baseName, it.isLogical) == SafetyLevel.SAFE }
            .forEach { selected.add(it.name) }
    }

    private fun applyPreset(bootOnly: Boolean) {
        selected.clear()
        partitions.forEach {
            if (!bootOnly || SafetyPolicy.levelOf(it.baseName, it.isLogical) == SafetyLevel.SAFE) {
                selected.add(it.name)
            }
        }
        syncBoxes()
    }

    private fun applyNone() {
        selected.clear()
        syncBoxes()
    }

    private fun syncBoxes() {
        boxes.forEach { (name, box) -> box.isChecked = selected.contains(name) }
        refreshActionItem()
    }

    private fun refreshActionItem() {
        val screen = preferenceScreen ?: return
        val go = screen.findPreference<Preference>("batch_go") ?: return
        val chosen = partitions.filter { selected.contains(it.name) }
        val total = chosen.sumOf { it.sizeBytes }
        go.title = getString(R.string.batch_start, chosen.size, Format.size(total))
        go.isEnabled = chosen.isNotEmpty()
        if (chosen.isEmpty()) {
            go.summary = getString(R.string.batch_none_selected)
        } else {
            go.summary = null
        }
    }

    private fun askLabel() {
        val context = requireContext()
        val chosen = partitions.filter { selected.contains(it.name) }
        if (chosen.isEmpty()) {
            toast(R.string.batch_none_selected)
            return
        }
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.batch_label_hint)
            setSingleLine(true)
        }
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.rp_create_title)
            .setView(wrapper)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                val label = input.text?.toString()?.trim().orEmpty()
                runBackup(chosen, label.ifBlank { getString(R.string.batch_label_default) })
            }
            .show()
    }

    private fun runBackup(chosen: List<PartitionInfo>, label: String) {
        val context = requireContext()
        val id = RestorePointStore.newId()
        val dir = "${Prefs.outputDir(context)}/restore-points/$id"
        val totalBytes = chosen.sumOf { it.sizeBytes }

        OperationRunner.start(
            context,
            viewLifecycleOwner,
            getString(R.string.op_snapshots),
            getString(R.string.batch_start, chosen.size, Format.size(totalBytes))
        ) { reporter ->
            withContext(Dispatchers.IO) {
                val session = RootShell.shell()
                val entries = ArrayList<RestoreEntry>()
                var doneBytes = 0L
                chosen.forEach { partition ->
                    val file = "$dir/${partition.name}.img"
                    reporter.log("${partition.name} …")
                    val base = doneBytes
                    val sha = DumpEngine.dump(partition, file, session) { done, _ ->
                        reporter.progress(base + done, totalBytes)
                    }
                    doneBytes = base + partition.sizeBytes
                    entries.add(
                        RestoreEntry(partition.name, partition.path, partition.sizeBytes, file, sha)
                    )
                }
                val slot = SurgeonRepository.slot()
                val point = RestorePoint(
                    id = id,
                    label = label,
                    createdAt = System.currentTimeMillis(),
                    device = slot.device,
                    fingerprint = slot.fingerprint,
                    activeSlot = slot.activeSuffix,
                    rootDir = Prefs.outputDir(context),
                    entries = entries
                )
                RestorePointStore.add(context, point)
                OpLog.add(
                    context,
                    OpLogEntry(
                        System.currentTimeMillis(), "批量备份", label, true,
                        "${entries.size} 个分区 · ${Format.size(totalBytes)}"
                    )
                )
                getString(
                    R.string.batch_done, id, entries.size,
                    Format.size(entries.sumOf { it.sizeBytes })
                )
            }
        }
    }
}
