package dev.surgeon.partition.ui

import android.content.Context
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.surgeon.partition.R
import dev.surgeon.partition.util.Prefs
import kotlinx.coroutines.launch

class SettingsFragment : PreferenceFragmentCompat(), TitledFragment {

    override fun title(context: Context): String = context.getString(R.string.pref_settings_title)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)

        val storage = PreferenceCategory(context).apply { setTitle(R.string.set_cat_storage) }
        screen.addPreference(storage)
        storage.addPreference(
            EditTextPreference(context).apply {
                key = "output_dir"
                setTitle(R.string.set_output_dir)
                dialogTitle = getString(R.string.set_output_dir)
                text = Prefs.outputDir(context)
                summary = getString(R.string.set_output_dir_summary, Prefs.outputDir(context))
                setOnPreferenceChangeListener { _, newValue ->
                    val value = (newValue as? String)?.trim().orEmpty()
                    Prefs.setOutputDir(context, value)
                    this.text = Prefs.outputDir(context)
                    this.summary = getString(R.string.set_output_dir_summary, Prefs.outputDir(context))
                    false
                }
            }
        )

        val safety = PreferenceCategory(context).apply { setTitle(R.string.set_cat_safety) }
        screen.addPreference(safety)
        safety.addPreference(
            SwitchPreferenceCompat(context).apply {
                key = "force_backup"
                setTitle(R.string.set_force_backup)
                setSummary(R.string.set_force_backup_summary)
                isChecked = Prefs.forceBackup(context)
                setOnPreferenceChangeListener { _, newValue ->
                    Prefs.setForceBackup(context, newValue as? Boolean ?: false)
                    false
                }
            }
        )
        safety.addPreference(
            SwitchPreferenceCompat(context).apply {
                key = "verify_after_flash"
                setTitle(R.string.set_verify_after_flash)
                setSummary(R.string.set_verify_after_flash_summary)
                isChecked = Prefs.verifyAfterFlash(context)
                setOnPreferenceChangeListener { _, newValue ->
                    Prefs.setVerifyAfterFlash(context, newValue as? Boolean ?: false)
                    false
                }
            }
        )
        safety.addPreference(
            SwitchPreferenceCompat(context).apply {
                key = "expert_mode"
                setTitle(R.string.set_expert_mode)
                setSummary(R.string.set_expert_mode_summary)
                isChecked = Prefs.expertMode(context)
                setOnPreferenceChangeListener { _, newValue ->
                    Prefs.setExpertMode(context, newValue as? Boolean ?: false)
                    false
                }
            }
        )

        val about = PreferenceCategory(context).apply { setTitle(R.string.set_cat_about) }
        screen.addPreference(about)
        about.addPreference(
            Preference(context).apply {
                setTitle(R.string.set_author)
                summary = getString(R.string.author_name)
                setIcon(R.drawable.ic_info)
                isSelectable = false
            }
        )
        about.addPreference(
            Preference(context).apply {
                setTitle(R.string.set_disclaimer_title)
                summary = if (Prefs.disclaimerAgreed(context)) {
                    getString(R.string.set_disclaimer_agreed)
                } else {
                    getString(R.string.set_disclaimer_not_agreed)
                }
                setIcon(R.drawable.ic_warning)
                setOnPreferenceClickListener {
                    showLongTextDialog(
                        context,
                        getString(R.string.set_disclaimer_title),
                        getText(R.string.disclaimer_body)
                    )
                    true
                }
            }
        )
        val rootPref = Preference(context).apply {
            setTitle(R.string.set_root_impl)
            summary = getString(R.string.loading)
            isSelectable = false
        }
        about.addPreference(rootPref)
        about.addPreference(
            Preference(context).apply {
                setTitle(R.string.set_version)
                summary = (requireActivity() as MainActivity).versionName()
                isSelectable = false
            }
        )
        preferenceScreen = screen

        safeLaunch {
            val info = SurgeonRepository.rootInfo()
            rootPref.summary = info.display
        }
    }
}
