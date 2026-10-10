package dev.surgeon.partition.ui

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import dev.surgeon.partition.R
import dev.surgeon.partition.core.shell.RootShell
import dev.surgeon.partition.engine.OpLog
import dev.surgeon.partition.engine.PropsEngine
import dev.surgeon.partition.model.OpLogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 系统属性浏览与编辑，外加 SELinux 模式开关。 */
class PropsFragment : PreferenceFragmentCompat(), TitledFragment {

    private companion object {
        const val MAX_VISIBLE = 200
    }

    override fun title(context: Context): String = context.getString(R.string.props_title)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        load()
    }

    private fun load() {
        safeLaunch {
            val props = withContext(Dispatchers.IO) {
                PropsEngine.getMany(RootShell.shell(), PropsEngine.COMMON_KEYS)
            }
            val enforcing = withContext(Dispatchers.IO) { PropsEngine.isEnforcing(RootShell.shell()) }
            buildCommon(props, enforcing)
        }
    }

    private fun buildCommon(props: List<PropsEngine.Prop>, enforcing: Boolean) {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)

        val common = PreferenceCategory(context).apply { setTitle(R.string.props_cat_common) }
        screen.addPreference(common)
        props.forEach { prop ->
            common.addPreference(propItem(context, prop))
        }

        val search = PreferenceCategory(context).apply { setTitle(R.string.props_cat_search) }
        screen.addPreference(search)
        search.addPreference(
            Preference(context).apply {
                setTitle(R.string.props_search)
                setSummary(R.string.props_search_summary)
                setIcon(R.drawable.ic_search)
                setOnPreferenceClickListener { showSearch(); true }
            }
        )

        val selinux = PreferenceCategory(context).apply { setTitle(R.string.selinux_title) }
        screen.addPreference(selinux)
        selinux.addPreference(
            Preference(context).apply {
                setTitle(R.string.selinux_state)
                summary = if (enforcing) {
                    getString(R.string.selinux_enforcing)
                } else {
                    getString(R.string.selinux_permissive)
                }
                isSelectable = false
            }
        )
        selinux.addPreference(
            Preference(context).apply {
                val target = if (enforcing) {
                    getString(R.string.selinux_permissive)
                } else {
                    getString(R.string.selinux_enforcing)
                }
                setTitle(getString(R.string.selinux_switch_to, target))
                setIcon(R.drawable.ic_shield)
                setOnPreferenceClickListener { switchSelinux(!enforcing); true }
            }
        )

        preferenceScreen = screen
    }

    private fun propItem(context: Context, prop: PropsEngine.Prop): Preference =
        Preference(context).apply {
            title = prop.key
            summary = prop.value.ifBlank { getString(R.string.props_empty_value) }
            isPersistent = false
            setOnPreferenceClickListener { showEdit(prop); true }
        }

    private fun showEdit(prop: PropsEngine.Prop) {
        val context = requireContext()
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_edit_prop, null)
        val keyView = view.findViewById<android.widget.TextView>(R.id.tv_prop_key)
        val currentView = view.findViewById<android.widget.TextView>(R.id.tv_prop_current)
        val edit = view.findViewById<TextInputEditText>(R.id.et_prop_value)

        keyView.text = prop.key
        currentView.text = getString(
            R.string.props_current,
            prop.value.ifBlank { getString(R.string.props_empty_value) }
        )
        edit.setText(prop.value)

        val resetpropAvailable = runCatching {
            PropsEngine.resetPropAvailable(RootShell.shell())
        }.getOrDefault(false)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.props_edit_title)
            .setView(view)
            .setNegativeButton(getString(R.string.cancel), null)
            .setNeutralButton(getString(R.string.props_set)) { _, _ ->
                writeProp(prop.key, edit.text?.toString()?.trim().orEmpty(), false)
            }
            .setPositiveButton(getString(R.string.props_resetprop)) { _, _ ->
                writeProp(prop.key, edit.text?.toString()?.trim().orEmpty(), true)
            }
            .show()

        if (!resetpropAvailable) {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.isEnabled = false
            view.findViewById<android.widget.TextView>(R.id.tv_prop_hint).text =
                getString(R.string.props_resetprop_missing)
        }
    }

    private fun writeProp(key: String, value: String, useResetProp: Boolean) {
        val context = requireContext()
        safeLaunch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val session = RootShell.shell()
                    if (useResetProp) PropsEngine.resetProp(session, key, value)
                    else PropsEngine.setProp(session, key, value)
                    PropsEngine.get(session, key)
                }
            }
            val ok = result.isSuccess
            OpLog.add(
                context,
                OpLogEntry(
                    System.currentTimeMillis(),
                    if (useResetProp) "resetprop" else "setprop",
                    key,
                    ok,
                    value.take(80)
                )
            )
            val message = result.getOrNull()?.let {
                getString(R.string.props_done, key, it.ifBlank { getString(R.string.props_empty_value) })
            } ?: getString(R.string.err_generic, result.exceptionOrNull()?.message ?: "")
            MaterialAlertDialogBuilder(context)
                .setTitle(if (ok) R.string.op_succeeded else R.string.op_failed)
                .setMessage(message)
                .setPositiveButton(getString(R.string.ok), null)
                .show()
            load()
        }
    }

    private fun switchSelinux(enforcing: Boolean) {
        val context = requireContext()
        val target = if (enforcing) {
            getString(R.string.selinux_enforcing)
        } else {
            getString(R.string.selinux_permissive)
        }
        confirm(
            getString(R.string.selinux_confirm_title),
            getString(R.string.selinux_confirm_body),
            getString(R.string.confirm)
        ) {
            safeLaunch {
                val ok = withContext(Dispatchers.IO) {
                    PropsEngine.setSelinux(RootShell.shell(), enforcing)
                }
                OpLog.add(
                    context,
                    OpLogEntry(System.currentTimeMillis(), "SELinux", target, ok, "")
                )
                toast(if (ok) R.string.op_done else R.string.op_failed)
                load()
            }
        }
    }

    private fun showSearch() {
        val context = requireContext()
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.props_search_hint)
            setSingleLine(true)
        }
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.props_search)
            .setView(wrapper)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.action_search)) { _, _ ->
                val query = input.text?.toString()?.trim().orEmpty()
                if (query.isNotBlank()) runSearch(query)
            }
            .show()
    }

    private fun runSearch(query: String) {
        safeLaunch {
            val matched = withContext(Dispatchers.IO) {
                PropsEngine.search(RootShell.shell(), query)
            }
            val context = requireContext()
            val screen = preferenceScreen ?: return@safeLaunch
            screen.removeAll()

            val cat = PreferenceCategory(context).apply {
                title = getString(
                    R.string.props_match_count,
                    matched.size,
                    if (matched.size > MAX_VISIBLE) {
                        getString(R.string.props_more_truncated, MAX_VISIBLE)
                    } else {
                        ""
                    }
                )
            }
            screen.addPreference(cat)
            if (matched.isEmpty()) {
                cat.addPreference(
                    Preference(context).apply {
                        setTitle(R.string.props_no_match)
                        isSelectable = false
                    }
                )
            } else {
                matched.take(MAX_VISIBLE).forEach { cat.addPreference(propItem(context, it)) }
            }

            screen.addPreference(
                Preference(context).apply {
                    setTitle(R.string.action_refresh)
                    setOnPreferenceClickListener { load(); true }
                }
            )
        }
    }
}
