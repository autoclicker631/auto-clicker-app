package com.akaa.autoclicker.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.akaa.autoclicker.R
import com.akaa.autoclicker.databinding.ItemConditionRuleBinding
import com.akaa.autoclicker.model.ActionType
import com.akaa.autoclicker.model.ConditionRule
import com.akaa.autoclicker.model.ConditionType

class RuleAdapter(
    private val rules: MutableList<ConditionRule>,
    private val onEditClicked: (ConditionRule, Int) -> Unit,
    private val onDeleteClicked: (Int) -> Unit,
    private val onRuleToggle: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<RuleAdapter.RuleViewHolder>() {

    inner class RuleViewHolder(val binding: ItemConditionRuleBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuleViewHolder {
        val binding = ItemConditionRuleBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RuleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RuleViewHolder, position: Int) {
        val rule = rules[position]
        with(holder.binding) {
            tvRuleIndex.text = (position + 1).toString()
            tvRuleName.text = if (rule.name.isNotBlank()) rule.name else "الشرط #${position + 1}"
            switchRuleEnabled.isChecked = rule.isEnabled

            // Condition badge text
            tvConditionBadge.text = when (rule.conditionType) {
                ConditionType.ALWAYS -> "شرط: دائماً (مباشر)"
                ConditionType.TEXT_EXISTS -> "شرط: ظهور '${rule.targetText}'"
                ConditionType.TEXT_NOT_EXISTS -> "شرط: غياب '${rule.targetText}'"
                ConditionType.IMAGE_EXISTS -> "شرط: تطابق صورة"
            }

            // Action badge text
            val actions = rule.getEffectiveActions()
            if (actions.size > 1) {
                tvActionBadge.text = "⚡ تنفيذ ${actions.size} إجراءات متتالية"
                val totalDelay = actions.sumOf { it.delayAfterMs }
                tvDelayInfo.text = "مجموع التأخير: ${totalDelay}ms"
            } else {
                val action = actions.firstOrNull() ?: rule.getEffectiveActions().first()
                tvActionBadge.text = when (action.actionType) {
                    ActionType.CLICK_COORDINATE -> "نقر (${action.clickX}, ${action.clickY})"
                    ActionType.CLICK_DETECTED_TEXT -> "نقر على النص"
                    ActionType.TYPE_TEXT -> "كتابة: '${action.textToType}'"
                    ActionType.SEND_KEY_CODE -> "زر كيبورد [${action.keyCode}]"
                    ActionType.GAME_KEY_OR_TOUCH -> "🎮 ألعاب: مفتاح/نقر (${action.clickX}, ${action.clickY})"
                    ActionType.SWIPE -> "سحب شاشة"
                    ActionType.DELAY_ONLY -> "تأخير زمني"
                    ActionType.CLOSE_RECENT_APPS -> "إغلاق التطبيقات"
                }
                tvDelayInfo.text = "تأخير: ${action.delayAfterMs}ms"
            }

            switchRuleEnabled.setOnCheckedChangeListener { _, isChecked ->
                rule.isEnabled = isChecked
                onRuleToggle(position, isChecked)
            }

            btnEditRule.setOnClickListener {
                onEditClicked(rule, position)
            }

            btnDeleteRule.setOnClickListener {
                onDeleteClicked(position)
            }
        }
    }

    override fun getItemCount(): Int = rules.size
}
