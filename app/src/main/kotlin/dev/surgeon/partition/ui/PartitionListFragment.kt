package dev.surgeon.partition.ui

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.os.bundleOf
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.surgeon.partition.R
import dev.surgeon.partition.model.PartitionGroup
import dev.surgeon.partition.model.PartitionInfo
import dev.surgeon.partition.safety.SafetyLevel
import dev.surgeon.partition.safety.SafetyPolicy
import dev.surgeon.partition.util.Format

class PartitionListFragment : PreferenceFragmentCompat(), TitledFragment {

    private var group: PartitionGroup = PartitionGroup.BOOT
    private var query: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        group = PartitionGroup.valueOf(
            arguments?.getString(ARG_GROUP) ?: PartitionGroup.BOOT.name
        )
    }

    override fun title(context: Context): String = when (group) {
        PartitionGroup.BOOT -> context.getString(R.string.group_boot_title)
        PartitionGroup.SYSTEM -> context.getString(R.string.group_system_title)
        PartitionGroup.VENDOR -> context.getString(R.string.group_vendor_title)
        PartitionGroup.OTHER -> context.getString(R.string.group_other_title)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        load()
    }

    private fun load() {
        safeLaunch {
            val all = SurgeonRepository.partitions()
            val byGroup = all.filter {
                SafetyPolicy.groupOf(it.baseName, it.isLogical) == group
            }
            val list = if (query.isBlank()) byGroup else byGroup.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.baseName.contains(query, ignoreCase = true)
            }
            val screen = preferenceScreen ?: return@safeLaunch
            screen.removeAll()

            screen.addPreference(
                Preference(requireContext()).apply {
                    key = "search"
                    setTitle(
                        if (query.isBlank()) R.string.search_title
                        else R.string.search_clear
                    )
                    summary = if (query.isBlank()) {
                        getString(R.string.search_hint)
                    } else {
                        getString(R.string.search_result_count, list.size)
                    }
                    setIcon(R.drawable.ic_search)
                    setOnPreferenceClickListener {
                        if (query.isBlank()) showSearch() else { query = ""; load() }
                        true
                    }
                }
            )

            if (list.isEmpty()) {
                val cat = PreferenceCategory(requireContext()).apply {
                    setTitle(R.string.pd_cat_info)
                }
                screen.addPreference(cat)
                cat.addPreference(
                    Preference(requireContext()).apply {
                        title = if (query.isBlank()) {
                            getString(R.string.empty_hint)
                        } else {
                            getString(R.string.search_no_result, query)
                        }
                        isSelectable = false
                    }
                )
                return@safeLaunch
            }

            val cat = PreferenceCategory(requireContext()).apply {
                title = "${title(requireContext())} · ${list.size}"
            }
            screen.addPreference(cat)
            list.forEach { partition ->
                cat.addPreference(
                    Preference(requireContext()).apply {
                        key = partition.name
                        title = partition.name
                        summary = describe(partition)
                        setIcon(R.drawable.ic_partition)
                        setOnPreferenceClickListener {
                            navigate(
                                PartitionDetailFragment.newInstance(partition.name),
                                partition.name
                            )
                            true
                        }
                    }
                )
            }
        }
    }

    private fun showSearch() {
        val context = requireContext()
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.search_hint)
            setSingleLine(true)
        }
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.search_title)
            .setView(wrapper)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.action_search)) { _, _ ->
                query = input.text?.toString()?.trim().orEmpty()
                load()
            }
            .show()
    }

    private fun describe(partition: PartitionInfo): String {
        val type = getString(
            if (partition.isLogical) R.string.type_logical else R.string.type_physical
        )
        val safety = when (SafetyPolicy.levelOf(partition.baseName, partition.isLogical)) {
            SafetyLevel.SAFE -> getString(R.string.safety_safe)
            SafetyLevel.CAUTION -> getString(R.string.safety_caution)
            SafetyLevel.DANGEROUS -> getString(R.string.safety_dangerous)
            SafetyLevel.FORBIDDEN -> getString(R.string.safety_forbidden)
        }
        return "${Format.size(partition.sizeBytes)} · $type · $safety"
    }

    companion object {
        private const val ARG_GROUP = "group"

        fun newInstance(group: PartitionGroup): PartitionListFragment =
            PartitionListFragment().apply { arguments = bundleOf(ARG_GROUP to group.name) }
    }
}
