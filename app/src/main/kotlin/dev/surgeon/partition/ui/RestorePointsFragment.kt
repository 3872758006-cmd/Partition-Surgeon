package dev.surgeon.partition.ui

import android.content.Context
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import dev.surgeon.partition.R
import dev.surgeon.partition.core.shell.RootShell
import dev.surgeon.partition.engine.FlashEngine
import dev.surgeon.partition.engine.ImageProbe
import dev.surgeon.partition.engine.OpLog
import dev.surgeon.partition.engine.RestorePointStore
import dev.surgeon.partition.model.OpLogEntry
import dev.surgeon.partition.model.RestorePoint
import dev.surgeon.partition.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RestorePointsFragment : PreferenceFragmentCompat(), TitledFragment {

    override fun title(context: Context): String = context.getString(R.string.rp_title)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        safeLaunch {
            val points = RestorePointStore.load(requireContext())
            val screen = preferenceScreen ?: return@safeLaunch
            screen.removeAll()
            if (points.isEmpty()) {
                screen.addPreference(
                    Preference(requireContext()).apply {
                        setTitle(R.string.rp_none)
                        isSelectable = false
                    }
                )
                return@safeLaunch
            }
            points.forEach { point ->
                screen.addPreference(
                    Preference(requireContext()).apply {
                        key = point.id
                        title = point.label.ifBlank { point.id }
                        summary = buildString {
                            append(getString(R.string.rp_created, Format.time(point.createdAt)))
                            append("\n")
                            append(
                                getString(
                                    R.string.rp_entries,
                                    point.entries.size,
                                    Format.size(point.totalBytes)
                                )
                            )
                            if (point.activeSlot.isNotBlank()) append(" · ").append(point.activeSlot)
                        }
                        setIcon(R.drawable.ic_restore)
                        setOnPreferenceClickListener {
                            navigate(
                                RestorePointDetailFragment.newInstance(point.id),
                                point.label.ifBlank { point.id }
                            )
                            true
                        }
                    }
                )
            }
        }
    }
}

class RestorePointDetailFragment : PreferenceFragmentCompat(), TitledFragment {

    private var pointId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pointId = arguments?.getString(ARG_ID).orEmpty()
    }

    override fun title(context: Context): String = context.getString(R.string.rp_title)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        load()
    }

    private fun load() {
        val context = requireContext()
        val point = RestorePointStore.load(context).firstOrNull { it.id == pointId }
        if (point == null) {
            preferenceScreen?.addPreference(
                Preference(context).apply {
                    setTitle(R.string.empty_hint)
                    isSelectable = false
                }
            )
            return
        }
        val screen = preferenceManager.createPreferenceScreen(context)

        val info = PreferenceCategory(context).apply {
            setTitle(R.string.pd_cat_info)
        }
        screen.addPreference(info)
        info.addPreference(
            Preference(context).apply {
                setTitle(R.string.rp_created)
                summary = Format.time(point.createdAt)
                isSelectable = false
            }
        )
        info.addPreference(
            Preference(context).apply {
                setTitle(R.string.slot_device)
                summary = point.device.ifBlank { "—" }
                isSelectable = false
            }
        )
        info.addPreference(
            Preference(context).apply {
                setTitle(R.string.slot_fingerprint)
                summary = point.fingerprint.ifBlank { "—" }
                isSelectable = false
            }
        )
        info.addPreference(
            Preference(context).apply {
                setTitle(R.string.slot_active)
                summary = point.activeSlot.ifBlank { "—" }
                isSelectable = false
            }
        )

        val entries = PreferenceCategory(context).apply {
            setTitle(context.getString(R.string.rp_entries, point.entries.size, Format.size(point.totalBytes)))
        }
        screen.addPreference(entries)
        point.entries.forEach { entry ->
            entries.addPreference(
                Preference(context).apply {
                    title = entry.partition
                    summary = buildString {
                        append(Format.size(entry.sizeBytes))
                        append("\n")
                        append(entry.file)
                        if (entry.sha256.isNotBlank()) {
                            append("\nSHA-256: ").append(Format.shortHash(entry.sha256))
                        }
                    }
                    isSelectable = false
                }
            )
        }

        val ops = PreferenceCategory(context).apply {
            setTitle(R.string.pd_cat_ops)
        }
        screen.addPreference(ops)
        ops.addPreference(
            Preference(context).apply {
                setTitle(R.string.rp_action_restore)
                setIcon(R.drawable.ic_flash)
                setOnPreferenceClickListener { confirmRestore(point); true }
            }
        )
        ops.addPreference(
            Preference(context).apply {
                setTitle(R.string.rp_action_verify)
                setIcon(R.drawable.ic_shield)
                setOnPreferenceClickListener { verify(point); true }
            }
        )
        ops.addPreference(
            Preference(context).apply {
                setTitle(R.string.rp_action_delete)
                setIcon(R.drawable.ic_warning)
                setOnPreferenceClickListener { confirmDelete(point); true }
            }
        )

        preferenceScreen = screen
    }

    private fun confirmRestore(point: RestorePoint) {
        val context = requireContext()
        safeLaunch {
            val slot = SurgeonRepository.slot()
            val names = point.entries.joinToString("\n") { "· ${it.partition}" }
            val body = buildString {
                append(getString(R.string.rp_restore_body, names, slot.activeSuffix))
                if (point.fingerprint.isNotBlank() && point.fingerprint != slot.fingerprint) {
                    append("\n\n").append(getString(R.string.rp_device_mismatch, point.device))
                }
            }
            confirm(
                getString(R.string.rp_restore_title),
                body,
                getString(R.string.rp_action_restore)
            ) { runRestore(point) }
        }
    }

    private fun runRestore(point: RestorePoint) {
        val context = requireContext()
        OperationRunner.start(
            context,
            viewLifecycleOwner,
            getString(R.string.op_restoring),
            point.label.ifBlank { point.id }
        ) { reporter ->
            withContext(Dispatchers.IO) {
                var done = 0
                for (entry in point.entries) {
                    val partition = SurgeonRepository.partitions()
                        .firstOrNull { it.name == entry.partition }
                    if (partition == null) {
                        reporter.log("跳过 ${entry.partition}：当前设备没有这个分区")
                        continue
                    }
                    reporter.log("写入 ${entry.partition} ← ${entry.file.substringAfterLast('/')}")
                    FlashEngine.flash(partition, entry.file, RootShell.shell()) { d, t ->
                        reporter.progress(d, t)
                    }
                    done++
                }
                OpLog.add(
                    context,
                    OpLogEntry(
                        System.currentTimeMillis(), "回滚", point.id, true,
                        "$done / ${point.entries.size} 个分区"
                    )
                )
                getString(R.string.result_restore_ok, done, point.entries.size)
            }
        }
    }

    private fun verify(point: RestorePoint) {
        val context = requireContext()
        OperationRunner.start(
            context,
            viewLifecycleOwner,
            getString(R.string.op_verifying),
            point.label.ifBlank { point.id }
        ) { reporter ->
            withContext(Dispatchers.IO) {
                var bad = 0
                point.entries.forEach { entry ->
                    reporter.log("校验 ${entry.file.substringAfterLast('/')} …")
                    val sha = ImageProbe.sha256(RootShell.shell(), entry.file)
                    if (entry.sha256.isBlank() || sha == entry.sha256) {
                        reporter.log("  一致")
                    } else {
                        bad++
                        reporter.log("  不一致：记录 ${entry.sha256.take(12)}… 实际 ${sha.take(12)}…")
                    }
                }
                if (bad == 0) getString(R.string.rp_verify_ok, point.entries.size)
                else getString(R.string.rp_verify_fail, bad)
            }
        }
    }

    private fun confirmDelete(point: RestorePoint) {
        confirm(
            getString(R.string.rp_delete_title),
            getString(R.string.rp_delete_body),
            getString(R.string.rp_action_delete)
        ) {
            val context = requireContext()
            safeLaunch {
                withContext(Dispatchers.IO) {
                    val dir = point.rootDir.trimEnd('/') + "/restore-points/${point.id}"
                    RootShell.shell().exec("rm -rf \"$dir\"")
                }
                RestorePointStore.remove(context, point.id)
                toast(R.string.rp_delete_title)
                parentFragmentManager.popBackStack()
            }
        }
    }

    companion object {
        private const val ARG_ID = "id"

        fun newInstance(id: String): RestorePointDetailFragment =
            RestorePointDetailFragment().apply { arguments = bundleOf(ARG_ID to id) }
    }
}
