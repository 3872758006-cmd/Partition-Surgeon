package dev.surgeon.partition.ui

import android.content.Context
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import dev.surgeon.partition.R
import dev.surgeon.partition.core.shell.RootShell
import dev.surgeon.partition.engine.OpLog
import dev.surgeon.partition.engine.PowerEngine
import dev.surgeon.partition.model.OpLogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PowerFragment : PreferenceFragmentCompat(), TitledFragment {

    override fun title(context: Context): String = context.getString(R.string.power_title)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)

        val reboot = PreferenceCategory(context).apply { setTitle(R.string.power_cat_reboot) }
        screen.addPreference(reboot)
        reboot.addPreference(item(context, PowerEngine.Action.REBOOT,
            R.string.power_reboot, R.string.power_reboot_summary))
        reboot.addPreference(item(context, PowerEngine.Action.SOFT,
            R.string.power_soft, R.string.power_soft_summary))
        reboot.addPreference(item(context, PowerEngine.Action.RECOVERY,
            R.string.power_recovery, R.string.power_recovery_summary))
        reboot.addPreference(item(context, PowerEngine.Action.BOOTLOADER,
            R.string.power_bootloader, R.string.power_bootloader_summary))
        reboot.addPreference(item(context, PowerEngine.Action.FASTBOOTD,
            R.string.power_fastbootd, R.string.power_fastbootd_summary))

        val other = PreferenceCategory(context).apply { setTitle(R.string.power_cat_other) }
        screen.addPreference(other)
        other.addPreference(item(context, PowerEngine.Action.POWEROFF,
            R.string.power_poweroff, R.string.power_poweroff_summary))

        preferenceScreen = screen
    }

    private fun item(
        context: Context,
        action: PowerEngine.Action,
        titleRes: Int,
        summaryRes: Int
    ): Preference = Preference(context).apply {
        setTitle(titleRes)
        setSummary(summaryRes)
        setIcon(R.drawable.ic_power)
        setOnPreferenceClickListener { runAction(action, getString(titleRes)); true }
    }

    private fun runAction(action: PowerEngine.Action, label: String) {
        val context = requireContext()
        confirm(
            getString(R.string.power_confirm_title),
            getString(R.string.power_confirm_body, label),
            getString(R.string.confirm)
        ) {
            safeLaunch {
                val ok = withContext(Dispatchers.IO) {
                    val session = RootShell.shell()
                    if (action == PowerEngine.Action.SOFT &&
                        !PowerEngine.softRebootSupported(session)
                    ) {
                        throw IllegalStateException(getString(R.string.power_soft_unsupported))
                    }
                    PowerEngine.run(session, action)
                }
                OpLog.add(
                    context,
                    OpLogEntry(
                        System.currentTimeMillis(), "电源", label, ok,
                        if (ok) "已下发指令" else "执行失败"
                    )
                )
                toast(if (ok) R.string.power_done else R.string.op_failed)
            }
        }
    }
}
