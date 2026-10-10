package dev.surgeon.partition.ui

import android.content.Context
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import dev.surgeon.partition.R
import dev.surgeon.partition.engine.OpLog
import dev.surgeon.partition.model.OpLogEntry
import dev.surgeon.partition.util.Format

class LogsFragment : PreferenceFragmentCompat(), TitledFragment {

    override fun title(context: Context): String = context.getString(R.string.pref_logs_title)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        load()
    }

    private fun load() {
        val context = requireContext()
        val screen = preferenceScreen ?: return
        screen.removeAll()
        val entries: List<OpLogEntry> = OpLog.load(context)
        if (entries.isEmpty()) {
            screen.addPreference(
                Preference(context).apply {
                    setTitle(R.string.log_empty)
                    isSelectable = false
                }
            )
            return
        }
        entries.forEach { entry ->
            screen.addPreference(
                Preference(context).apply {
                    val flag = if (entry.success) "✓" else "✗"
                    title = "$flag ${entry.op} · ${entry.target}"
                    summary = buildString {
                        append(Format.time(entry.time))
                        if (entry.detail.isNotBlank()) append("\n").append(entry.detail)
                    }
                    isSelectable = false
                }
            )
        }
        screen.addPreference(
            Preference(context).apply {
                setTitle(R.string.log_clear)
                setIcon(R.drawable.ic_warning)
                setOnPreferenceClickListener {
                    confirm(
                        getString(R.string.log_clear),
                        getString(R.string.log_cleared),
                        getString(R.string.confirm)
                    ) {
                        OpLog.clear(context)
                        load()
                    }
                    true
                }
            }
        )
    }
}
