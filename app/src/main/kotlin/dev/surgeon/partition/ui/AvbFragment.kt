package dev.surgeon.partition.ui

import android.content.Context
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.surgeon.partition.R
import dev.surgeon.partition.core.shell.RootShell
import dev.surgeon.partition.engine.AvbEngine
import dev.surgeon.partition.engine.OpLog
import dev.surgeon.partition.model.OpLogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AvbFragment : PreferenceFragmentCompat(), TitledFragment {

    override fun title(context: Context): String = context.getString(R.string.avb_title)

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
            val state = withContext(Dispatchers.IO) { AvbEngine.read(RootShell.shell()) }
            build(state)
        }
    }

    private fun build(state: AvbEngine.State) {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)

        val info = PreferenceCategory(context).apply { setTitle(R.string.avb_cat_state) }
        screen.addPreference(info)
        info.addPreference(
            readonly(context, R.string.avb_vbstate, state.vbState.ifBlank { getString(R.string.unknown) })
        )
        info.addPreference(
            readonly(context, R.string.avb_device_state, state.deviceState.ifBlank { getString(R.string.unknown) })
        )
        info.addPreference(
            readonly(
                context,
                R.string.avb_locked,
                when (state.locked) {
                    true -> getString(R.string.state_locked)
                    false -> getString(R.string.state_unlocked)
                    null -> getString(R.string.unknown)
                }
            )
        )
        info.addPreference(
            readonly(context, R.string.avb_dmverity, state.verityMode.ifBlank { getString(R.string.unknown) })
        )
        info.addPreference(
            readonly(
                context,
                R.string.avb_tool_available,
                if (state.avbctlAvailable) getString(R.string.yes) else getString(R.string.no)
            )
        )

        val ops = PreferenceCategory(context).apply { setTitle(R.string.avb_cat_ops) }
        screen.addPreference(ops)
        ops.addPreference(
            Preference(context).apply {
                setTitle(R.string.avb_disable)
                setSummary(R.string.avb_disable_summary)
                setIcon(R.drawable.ic_shield)
                setOnPreferenceClickListener { runAvb(state, true); true }
            }
        )
        ops.addPreference(
            Preference(context).apply {
                setTitle(R.string.avb_enable)
                setSummary(R.string.avb_enable_summary)
                setIcon(R.drawable.ic_shield)
                setOnPreferenceClickListener { runAvb(state, false); true }
            }
        )

        preferenceScreen = screen
    }

    private fun runAvb(state: AvbEngine.State, disable: Boolean) {
        val context = requireContext()
        if (!state.avbctlAvailable) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.avb_title)
                .setMessage(R.string.avb_not_available)
                .setPositiveButton(getString(R.string.ok), null)
                .show()
            return
        }
        confirm(
            getString(R.string.avb_confirm_title),
            getString(if (disable) R.string.avb_disable_body else R.string.avb_enable_body),
            getString(R.string.confirm)
        ) {
            safeLaunch {
                val context2 = requireContext()
                val result = withContext(Dispatchers.IO) {
                    AvbEngine.setVerification(RootShell.shell(), disable)
                }
                OpLog.add(
                    context2,
                    OpLogEntry(
                        System.currentTimeMillis(),
                        if (disable) "禁用校验" else "恢复校验",
                        "avbctl",
                        true,
                        result.take(120)
                    )
                )
                MaterialAlertDialogBuilder(context2)
                    .setTitle(R.string.avb_done)
                    .setMessage(getString(R.string.avb_result, result))
                    .setPositiveButton(getString(R.string.ok), null)
                    .show()
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
