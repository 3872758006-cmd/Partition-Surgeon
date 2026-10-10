package dev.surgeon.partition.ui

import android.content.Context
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import dev.surgeon.partition.R
import dev.surgeon.partition.core.RootManager
import dev.surgeon.partition.model.SlotInfo

/** 首页顶部的槽位概览卡片。 */
class SlotCardPreference(context: Context) : Preference(context) {

    private var slot: SlotInfo? = null
    private var root: RootManager.RootInfo? = null

    init {
        layoutResource = R.layout.card_slot
        isSelectable = true
        isPersistent = false
    }

    fun update(slot: SlotInfo, root: RootManager.RootInfo?) {
        this.slot = slot
        this.root = root
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val s = slot ?: run {
            holder.itemView.findViewById<TextView>(R.id.tv_slot_value).text = "…"
            return
        }
        val view = holder.itemView

        view.findViewById<TextView>(R.id.tv_slot_value).text =
            if (s.isAbDevice) s.activeSuffix.ifEmpty { "?" } else "单槽位"

        view.findViewById<TextView>(R.id.tv_ab_value).text =
            if (s.isAbDevice) "A/B" else "仅单槽位"

        val state = s.slotStates[s.activeSuffix]
        view.findViewById<TextView>(R.id.tv_state_value).text = when {
            state == null -> "—"
            state.bootable == false -> "不可启动"
            state.successful == true -> "可启动 · 已成功"
            state.bootable == true -> "可启动"
            else -> "—"
        }

        val boot = buildString {
            append(s.vbState.ifBlank { "vbstate 未知" })
            append(" · ")
            append(
                when (s.locked) {
                    true -> "BL 已上锁"
                    false -> "BL 已解锁"
                    null -> "BL 状态未知"
                }
            )
            if (s.vbmetaState.isNotBlank()) append(" · ").append(s.vbmetaState)
            if (s.virtualAb) append(" · VAB")
        }
        view.findViewById<TextView>(R.id.tv_boot_state).text = boot
        view.findViewById<TextView>(R.id.tv_root_impl).text =
            "Root：${root?.display ?: "—"}"
        view.findViewById<TextView>(R.id.tv_device).text =
            listOf(s.device, "Android ${s.sdk}".takeIf { s.sdk.isNotBlank() }, s.kernel)
                .filterNotNull()
                .filter { it.isNotBlank() }
                .joinToString(" · ")
    }
}
