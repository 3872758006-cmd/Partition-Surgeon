package dev.surgeon.partition.ui

import android.content.Context
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import dev.surgeon.partition.R
import dev.surgeon.partition.core.shell.RootShell
import dev.surgeon.partition.engine.OpLog
import dev.surgeon.partition.engine.RestorePointStore
import dev.surgeon.partition.engine.StorageEngine
import dev.surgeon.partition.model.OpLogEntry
import dev.surgeon.partition.util.Format
import dev.surgeon.partition.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StorageFragment : PreferenceFragmentCompat(), TitledFragment {

    private var usage: StorageEngine.Usage? = null

    override fun title(context: Context): String = context.getString(R.string.storage_title)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        load()
    }

    override fun onResume() {
        super.onResume()
        if (isAdded && view != null) load()
    }

    private fun load() {
        safeLaunch {
            val context = requireContext()
            val dir = Prefs.outputDir(context)
            val u = withContext(Dispatchers.IO) { StorageEngine.usage(RootShell.shell(), dir) }
            usage = u
            build(u, dir)
        }
    }

    private fun build(u: StorageEngine.Usage, dir: String) {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)

        val info = PreferenceCategory(context).apply { setTitle(R.string.storage_cat_usage) }
        screen.addPreference(info)
        info.addPreference(readonly(context, R.string.storage_output_dir, dir))
        info.addPreference(readonly(context, R.string.storage_dumps, Format.size(u.dumpsBytes)))
        info.addPreference(
            readonly(context, R.string.storage_restore_points, Format.size(u.restorePointsBytes))
        )
        info.addPreference(
            readonly(
                context,
                R.string.storage_total,
                "${Format.size(u.totalBytes)} · ${u.imageCount} 个镜像"
            )
        )
        info.addPreference(readonly(context, R.string.storage_free, Format.size(u.freeBytes)))

        val ops = PreferenceCategory(context).apply { setTitle(R.string.storage_cat_ops) }
        screen.addPreference(ops)
        ops.addPreference(
            Preference(context).apply {
                setTitle(R.string.storage_clear_dumps)
                setSummary(R.string.storage_clear_dumps_summary)
                setIcon(R.drawable.ic_warning)
                setOnPreferenceClickListener { clearDumps(u.dumpsBytes); true }
            }
        )
        ops.addPreference(
            Preference(context).apply {
                setTitle(R.string.storage_clear_all)
                setSummary(R.string.storage_clear_all_summary)
                setIcon(R.drawable.ic_warning)
                setOnPreferenceClickListener { clearAll(u.totalBytes); true }
            }
        )

        preferenceScreen = screen
    }

    private fun clearDumps(bytes: Long) {
        val context = requireContext()
        confirm(
            getString(R.string.storage_confirm_title),
            getString(R.string.storage_clear_dumps_body, Format.size(bytes)),
            getString(R.string.confirm)
        ) {
            safeLaunch {
                val freed = withContext(Dispatchers.IO) {
                    StorageEngine.clearDumps(RootShell.shell(), Prefs.outputDir(context))
                }
                OpLog.add(
                    context,
                    OpLogEntry(System.currentTimeMillis(), "清理", "dumps", true, Format.size(freed))
                )
                toast(getString(R.string.storage_cleared, Format.size(freed)))
                load()
            }
        }
    }

    private fun clearAll(bytes: Long) {
        val context = requireContext()
        confirm(
            getString(R.string.storage_confirm_title),
            getString(R.string.storage_clear_all_body, Format.size(bytes)),
            getString(R.string.confirm)
        ) {
            safeLaunch {
                val freed = withContext(Dispatchers.IO) {
                    StorageEngine.clearAll(RootShell.shell(), Prefs.outputDir(context))
                }
                RestorePointStore.save(context, emptyList())
                OpLog.add(
                    context,
                    OpLogEntry(System.currentTimeMillis(), "清理", "全部", true, Format.size(freed))
                )
                toast(getString(R.string.storage_cleared, Format.size(freed)))
                load()
            }
        }
    }

    private fun readonly(context: Context, titleRes: Int, value: String): Preference =
        Preference(context).apply {
            setTitle(titleRes)
            summary = value
            isSelectable = false
            isPersistent = false
        }
}
