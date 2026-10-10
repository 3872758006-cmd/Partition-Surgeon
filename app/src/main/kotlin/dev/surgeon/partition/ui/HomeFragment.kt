package dev.surgeon.partition.ui

import android.content.Context
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import dev.surgeon.partition.R
import dev.surgeon.partition.model.PartitionGroup
import dev.surgeon.partition.model.SlotInfo

class HomeFragment : PreferenceFragmentCompat(), TitledFragment {

    private var slotCard: SlotCardPreference? = null

    override fun title(context: Context): String = context.getString(R.string.app_name)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)

        val card = SlotCardPreference(context).apply {
            key = KEY_SLOT_CARD
            setOnPreferenceClickListener {
                navigate(SlotFragment(), getString(R.string.pref_slot_title))
                true
            }
        }
        slotCard = card
        screen.addPreference(card)

        val ops = PreferenceCategory(context).apply {
            setTitle(R.string.home_sec_ops)
            key = "cat_ops"
        }
        screen.addPreference(ops)
        ops.addPreference(
            entry(
                context, "partitions",
                R.string.pref_partitions_title, R.string.pref_partitions_summary, R.drawable.ic_partition
            ) { PartitionGroupsFragment() to R.string.pref_partitions_title }
        )
        ops.addPreference(
            entry(
                context, "restore_points",
                R.string.pref_restore_points_title, R.string.pref_restore_points_summary, R.drawable.ic_restore
            ) { RestorePointsFragment() to R.string.rp_title }
        )
        ops.addPreference(
            entry(
                context, "batch",
                R.string.pref_batch_title, R.string.pref_batch_summary, R.drawable.ic_batch
            ) { BatchBackupFragment() to R.string.batch_title }
        )
        ops.addPreference(
            entry(
                context, "images",
                R.string.pref_images_title, R.string.pref_images_summary, R.drawable.ic_image
            ) { ImageLibraryFragment() to R.string.img_lib_title }
        )

        val tools = PreferenceCategory(context).apply {
            setTitle(R.string.home_sec_tools)
            key = "cat_tools"
        }
        screen.addPreference(tools)
        tools.addPreference(
            entry(
                context, "slot",
                R.string.pref_slot_title, R.string.pref_slot_summary, R.drawable.ic_slot
            ) { SlotFragment() to R.string.pref_slot_title }
        )
        tools.addPreference(
            entry(
                context, "props",
                R.string.pref_props_title, R.string.pref_props_summary, R.drawable.ic_props
            ) { PropsFragment() to R.string.props_title }
        )
        tools.addPreference(
            entry(
                context, "power",
                R.string.pref_power_title, R.string.pref_power_summary, R.drawable.ic_power
            ) { PowerFragment() to R.string.power_title }
        )
        tools.addPreference(
            entry(
                context, "avb",
                R.string.pref_avb_title, R.string.pref_avb_summary, R.drawable.ic_shield
            ) { AvbFragment() to R.string.avb_title }
        )
        tools.addPreference(
            entry(
                context, "storage",
                R.string.pref_storage_title, R.string.pref_storage_summary, R.drawable.ic_storage
            ) { StorageFragment() to R.string.storage_title }
        )
        tools.addPreference(
            entry(
                context, "logs",
                R.string.pref_logs_title, R.string.pref_logs_summary, R.drawable.ic_log
            ) { LogsFragment() to R.string.pref_logs_title }
        )

        val about = PreferenceCategory(context).apply {
            setTitle(R.string.home_sec_about)
            key = "cat_about"
        }
        screen.addPreference(about)
        about.addPreference(
            entry(
                context, "settings",
                R.string.pref_settings_title, R.string.pref_settings_summary, R.drawable.ic_settings
            ) { SettingsFragment() to R.string.pref_settings_title }
        )

        preferenceScreen = screen
        refresh()
    }

    private fun entry(
        context: Context,
        key: String,
        titleRes: Int,
        summaryRes: Int,
        iconRes: Int,
        target: () -> Pair<androidx.fragment.app.Fragment, Int>
    ): Preference = Preference(context).apply {
        this.key = key
        setTitle(titleRes)
        setSummary(summaryRes)
        setIcon(iconRes)
        setOnPreferenceClickListener {
            val (fragment, titleId) = target()
            navigate(fragment, getString(titleId))
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        safeLaunch(
            onError = { t ->
                runCatching {
                    slotCard?.update(SlotInfo.empty(), SurgeonRepository.cachedRoot())
                    toast(getString(R.string.err_slot_read, t.message ?: ""))
                }
            }
        ) {
            val slot = SurgeonRepository.slot()
            val root = SurgeonRepository.rootInfo()
            slotCard?.update(slot, root)
        }
    }

    companion object {
        private const val KEY_SLOT_CARD = "slot_card"
    }
}

/** 分区分组入口页。 */
class PartitionGroupsFragment : PreferenceFragmentCompat(), TitledFragment {

    override fun title(context: Context): String = context.getString(R.string.pref_partitions_title)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)

        listOf(
            PartitionGroup.BOOT to Pair(R.string.group_boot_title, R.string.group_boot_summary),
            PartitionGroup.SYSTEM to Pair(R.string.group_system_title, R.string.group_system_summary),
            PartitionGroup.VENDOR to Pair(R.string.group_vendor_title, R.string.group_vendor_summary),
            PartitionGroup.OTHER to Pair(R.string.group_other_title, R.string.group_other_summary)
        ).forEach { (group, text) ->
            screen.addPreference(
                Preference(context).apply {
                    key = "group_${group.name}"
                    setTitle(text.first)
                    setSummary(text.second)
                    setIcon(R.drawable.ic_partition)
                    setOnPreferenceClickListener {
                        navigate(
                            PartitionListFragment.newInstance(group),
                            getString(text.first)
                        )
                        true
                    }
                }
            )
        }
        preferenceScreen = screen
    }
}
