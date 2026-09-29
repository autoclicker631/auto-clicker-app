package com.akaa.autoclicker.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.akaa.autoclicker.data.PreferencesManager
import com.akaa.autoclicker.databinding.FragmentRulesBinding
import com.akaa.autoclicker.model.ScriptConfig

class RulesFragment : Fragment() {

    private var _binding: FragmentRulesBinding? = null
    private val binding get() = _binding!!
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var currentScript: ScriptConfig
    private lateinit var ruleAdapter: RuleAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRulesBinding.inflate(inflater, container, false)
        preferencesManager = PreferencesManager(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadRules()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        loadRules()
    }

    private fun loadRules() {
        currentScript = preferencesManager.loadScriptConfig()

        ruleAdapter = RuleAdapter(
            rules = currentScript.rules,
            onEditClicked = { rule, position ->
                EditRuleDialog(requireContext(), rule) { updatedRule ->
                    currentScript.rules[position] = updatedRule
                    ruleAdapter.notifyItemChanged(position)
                    preferencesManager.saveScriptConfig(currentScript)
                    checkEmptyState()
                }.show()
            },
            onDeleteClicked = { position ->
                currentScript.rules.removeAt(position)
                ruleAdapter.notifyItemRemoved(position)
                ruleAdapter.notifyItemRangeChanged(position, currentScript.rules.size)
                preferencesManager.saveScriptConfig(currentScript)
                checkEmptyState()
            },
            onRuleToggle = { _, _ ->
                preferencesManager.saveScriptConfig(currentScript)
            }
        )

        binding.rvRulesFlow.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRulesFlow.adapter = ruleAdapter
        checkEmptyState()
    }

    private fun checkEmptyState() {
        val isEmpty = currentScript.rules.isEmpty()
        binding.layoutRulesEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvRulesFlow.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun setupListeners() {
        binding.btnRulesAddStep.setOnClickListener {
            EditRuleDialog(requireContext(), null) { newRule ->
                currentScript.rules.add(newRule)
                ruleAdapter.notifyItemInserted(currentScript.rules.size - 1)
                preferencesManager.saveScriptConfig(currentScript)
                checkEmptyState()
            }.show()
        }

        binding.btnRulesPresets.setOnClickListener {
            showPresetsDialog()
        }
    }

    private fun showPresetsDialog() {
        val presets = preferencesManager.getAllSavedProfiles()
        val names = presets.map { it.name }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("اختر قالباً جاهزاً للسيناريو")
            .setItems(names) { _, which ->
                val selected = presets[which]
                preferencesManager.saveScriptConfig(selected)
                loadRules()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
