package com.akaa.autoclicker.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import com.akaa.autoclicker.R
import com.akaa.autoclicker.databinding.DialogEditRuleBinding
import com.akaa.autoclicker.databinding.ItemDialogRuleActionBinding
import com.akaa.autoclicker.model.ActionType
import com.akaa.autoclicker.model.ConditionRule
import com.akaa.autoclicker.model.ConditionType
import com.akaa.autoclicker.model.RuleAction
import com.akaa.autoclicker.utils.ImageMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditRuleDialog(
    private val context: Context,
    private val existingRule: ConditionRule?,
    private val onSave: (ConditionRule) -> Unit
) {

    private val actionsList: MutableList<RuleAction> = mutableListOf()

    fun show() {
        val dialog = Dialog(context, android.R.style.Theme_Material_Dialog_NoActionBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val binding = DialogEditRuleBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)

        // Make dialog full-width and modern
        dialog.window?.let { win ->
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.setGravity(Gravity.BOTTOM)
        }

        val rule = existingRule?.copy() ?: ConditionRule()

        // Populate initial actions list from existing rule
        actionsList.clear()
        val initialActions = rule.getEffectiveActions()
        for (action in initialActions) {
            actionsList.add(action.copy())
        }
        if (actionsList.isEmpty()) {
            actionsList.add(RuleAction())
        }

        binding.tvDialogTitle.text = if (existingRule == null) "إضافة خطوة جديدة" else "تعديل الخطوة"
        binding.etRuleName.setText(rule.name)
        binding.etTargetText.setText(rule.targetText)

        // Image Condition Preview (loaded asynchronously without blocking UI)
        if (!rule.targetImageBase64.isNullOrBlank()) {
            CoroutineScope(Dispatchers.IO).launch {
                val bm = ImageMatcher.base64ToBitmap(rule.targetImageBase64)
                if (bm != null) {
                    withContext(Dispatchers.Main) {
                        binding.ivDialogConditionImagePreview.setImageBitmap(bm)
                    }
                }
            }
        }

        // Condition options
        val conditionOptions = listOf(
            "1. دائماً (تنفيذ مباشر بدون شرط)",
            "2. إذا وجد نص محدد على الشاشة",
            "3. إذا لم يظهر النص على الشاشة",
            "4. إذا تطابقت صورة محددة"
        )
        val conditionAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, conditionOptions)
        binding.spinnerConditionType.adapter = conditionAdapter
        binding.spinnerConditionType.setSelection(rule.conditionType.ordinal)

        binding.spinnerConditionType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedCondition = ConditionType.entries[position]
                binding.layoutTargetText.visibility = if (
                    selectedCondition == ConditionType.TEXT_EXISTS ||
                    selectedCondition == ConditionType.TEXT_NOT_EXISTS
                ) View.VISIBLE else View.GONE

                binding.layoutImageConditionSection.visibility = if (
                    selectedCondition == ConditionType.IMAGE_EXISTS
                ) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Render dynamic actions list
        fun renderActions() {
            binding.llDialogActionsContainer.removeAllViews()
            for ((index, action) in actionsList.withIndex()) {
                val itemBinding = ItemDialogRuleActionBinding.inflate(
                    LayoutInflater.from(context),
                    binding.llDialogActionsContainer,
                    false
                )

                itemBinding.tvActionIndex.text = "${index + 1}"
                itemBinding.etItemCoordX.setText(action.clickX.toString())
                itemBinding.etItemCoordY.setText(action.clickY.toString())
                itemBinding.etItemTextToType.setText(action.textToType)
                itemBinding.etItemDelayAfterMs.setText(action.delayAfterMs.toString())

                // Action types spinner for this item
                val actionTypeOptions = listOf(
                    "👆 1. نقر على إحداثيات (X, Y)",
                    "🔤 2. نقر مباشر على النص المكتشف",
                    "⌨️ 3. كتابة نص (كيبورد وهمي)",
                    "↵ 4. إرسال زر Enter",
                    "⏱️ 5. ضغط مطول / سحب (Swipe)",
                    "⏳ 6. تأخير زمني فقط",
                    "❌ 7. إغلاق البرامج المفتوحة والرجوع للرئيسية"
                )
                val itemActionAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, actionTypeOptions)
                itemBinding.spinnerItemActionType.adapter = itemActionAdapter
                itemBinding.spinnerItemActionType.setSelection(action.actionType.ordinal)

                itemBinding.spinnerItemActionType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        val selected = ActionType.entries[pos]
                        action.actionType = selected
                        itemBinding.layoutItemCoordinates.visibility = if (
                            selected == ActionType.CLICK_COORDINATE || selected == ActionType.SWIPE
                        ) View.VISIBLE else View.GONE

                        itemBinding.layoutItemTextToType.visibility = if (
                            selected == ActionType.TYPE_TEXT
                        ) View.VISIBLE else View.GONE

                        val summary = when (selected) {
                            ActionType.CLICK_COORDINATE -> "نقر على إحداثيات (${action.clickX}, ${action.clickY})"
                            ActionType.CLICK_DETECTED_TEXT -> "نقر على النص المكتشف"
                            ActionType.TYPE_TEXT -> "كتابة نص بالكيبورد"
                            ActionType.SEND_KEY_CODE -> "إرسال زر كيبورد [Enter]"
                            ActionType.SWIPE -> "ضغط مطول / سحب شاشة"
                            ActionType.DELAY_ONLY -> "تأخير زمني"
                            ActionType.CLOSE_RECENT_APPS -> "إغلاق التطبيقات"
                        }
                        itemBinding.tvActionSummaryTitle.text = summary
                    }

                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }

                // Delete this action button
                itemBinding.btnDeleteAction.setOnClickListener {
                    if (actionsList.size > 1) {
                        actionsList.removeAt(index)
                        renderActions()
                    } else {
                        Toast.makeText(context, "يجب وجود إجراء واحد على الأقل في الخطوة", Toast.LENGTH_SHORT).show()
                    }
                }

                binding.llDialogActionsContainer.addView(itemBinding.root)
            }
        }

        renderActions()

        // Add new action button
        binding.btnAddActionItem.setOnClickListener {
            val newAction = RuleAction(
                actionType = ActionType.CLICK_COORDINATE,
                clickX = 540,
                clickY = 1200 + (actionsList.size * 100) % 600,
                delayAfterMs = 300
            )
            actionsList.add(newAction)
            renderActions()
        }

        binding.btnDialogCancel.setOnClickListener {
            dialog.dismiss()
        }

        binding.btnDialogSave.setOnClickListener {
            // Collect user inputs from dynamic action items
            for (i in 0 until binding.llDialogActionsContainer.childCount) {
                val childView = binding.llDialogActionsContainer.getChildAt(i)
                val itemBinding = ItemDialogRuleActionBinding.bind(childView)
                if (i < actionsList.size) {
                    val action = actionsList[i]
                    action.actionType = ActionType.entries[itemBinding.spinnerItemActionType.selectedItemPosition]
                    action.clickX = itemBinding.etItemCoordX.text.toString().toIntOrNull() ?: 540
                    action.clickY = itemBinding.etItemCoordY.text.toString().toIntOrNull() ?: 1200
                    action.textToType = itemBinding.etItemTextToType.text.toString()
                    action.delayAfterMs = itemBinding.etItemDelayAfterMs.text.toString().toLongOrNull() ?: 300
                }
            }

            val conditionType = ConditionType.entries[binding.spinnerConditionType.selectedItemPosition]
            rule.name = binding.etRuleName.text.toString().trim()
            rule.conditionType = conditionType
            rule.targetText = binding.etTargetText.text.toString().trim()

            // Update primary fields from first action for backwards compatibility
            val firstAction = actionsList.firstOrNull() ?: RuleAction()
            rule.actionType = firstAction.actionType
            rule.clickX = firstAction.clickX
            rule.clickY = firstAction.clickY
            rule.textToType = firstAction.textToType
            rule.delayAfterMs = firstAction.delayAfterMs

            // Save complete list of actions
            rule.actions.clear()
            for (action in actionsList) {
                rule.actions.add(action.copy())
            }

            onSave(rule)
            dialog.dismiss()
        }

        dialog.show()
    }
}
