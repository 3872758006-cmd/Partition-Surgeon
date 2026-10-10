package dev.surgeon.partition.ui

import android.content.Context
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import dev.surgeon.partition.R
import dev.surgeon.partition.core.shell.RootShell
import dev.surgeon.partition.engine.OpLog
import dev.surgeon.partition.engine.SlotReader
import dev.surgeon.partition.model.OpLogEntry
import dev.surgeon.partition.model.SlotInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SlotFragment : PreferenceFragmentCompat(), TitledFragment {

    override fun title(context: Context): String = context.getString(R.string.pref_slot_title)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        load()
    }

    private fun load() {
        safeLaunch {
            val slot = SurgeonRepository.slot(force = true)
            build(slot)
        }
    }

    private fun build(slot: SlotInfo) {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)

        val current = PreferenceCategory(context).apply { setTitle(R.string.slot_cat_current) }
        screen.addPreference(current)
        current.addPreference(
            readonly(context, R.string.slot_active, slot.activeSuffix.ifBlank { "无后缀（单槽位）" })
        )
        current.addPreference(
            readonly(context, R.string.slot_ab_update, bool(slot.isAbDevice))
        )
        current.addPreference(readonly(context, R.string.slot_ab_update_flag, bool(slot.abUpdate)))
        current.addPreference(readonly(context, R.string.slot_vab, bool(slot.virtualAb)))
        current.addPreference(readonly(context, R.string.slot_dynamic, bool(slot.dynamicPartitions)))

        val states = slot.slotStates
        if (states.isNotEmpty()) {
            states.values.forEach { state ->
                current.addPreference(
                    readonly(
                        context,
                        R.string.slot_bootable_slot,
                        "${state.suffix} → " + when {
                            state.bootable == false -> getString(R.string.slot_unbootable)
                            state.successful == true -> getString(R.string.slot_successful)
                            state.bootable == true -> getString(R.string.slot_bootable)
                            else -> getString(R.string.unknown)
                        }
                    )
                )
            }
        }

        val verify = PreferenceCategory(context).apply { setTitle(R.string.slot_cat_verify) }
        screen.addPreference(verify)
        verify.addPreference(
            readonly(context, R.string.slot_vbstate, slot.vbState.ifBlank { getString(R.string.unknown) })
        )
        verify.addPreference(
            readonly(
                context,
                R.string.slot_locked,
                when (slot.locked) {
                    true -> getString(R.string.state_locked)
                    false -> getString(R.string.state_unlocked)
                    null -> getString(R.string.unknown)
                }
            )
        )
        verify.addPreference(
            readonly(context, R.string.slot_vbmeta_state, slot.vbmetaState.ifBlank { getString(R.string.unknown) })
        )

        val device = PreferenceCategory(context).apply { setTitle(R.string.slot_cat_device) }
        screen.addPreference(device)
        device.addPreference(readonly(context, R.string.slot_device, slot.device))
        device.addPreference(readonly(context, R.string.slot_sdk, slot.sdk))
        device.addPreference(readonly(context, R.string.slot_kernel, slot.kernel))
        device.addPreference(readonly(context, R.string.slot_fingerprint, slot.fingerprint))

        if (slot.isAbDevice) {
            val ops = PreferenceCategory(context).apply { setTitle(R.string.pd_cat_ops) }
            screen.addPreference(ops)
            val target = if (slot.activeSuffix == "_a") "_b" else "_a"
            ops.addPreference(
                Preference(context).apply {
                    setTitle(R.string.slot_action_switch)
                    setSummary(R.string.slot_action_switch_summary)
                    setIcon(R.drawable.ic_slot)
                    setOnPreferenceClickListener { confirmSwitch(target); true }
                }
            )
        }

        preferenceScreen = screen
    }

    private fun confirmSwitch(target: String) {
        confirm(
            getString(R.string.slot_switch_title, target),
            getString(R.string.slot_switch_body, target),
            getString(R.string.confirm)
        ) {
            val context = requireContext()
            safeLaunch {
                val ok = withContext(Dispatchers.IO) {
                    SlotReader.switchSlot(RootShell.shell(), target)
                }
                toast(if (ok) R.string.slot_switch_ok else R.string.op_failed)
                OpLog.add(
                    context,
                    OpLogEntry(
                        System.currentTimeMillis(),
                        "切换槽位",
                        target,
                        ok,
                        if (ok) "下次开机生效" else "bootctl 调用失败"
                    )
                )
                SurgeonRepository.invalidate()
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

    private fun bool(value: Boolean): String = getString(if (value) R.string.yes else R.string.no)
}
