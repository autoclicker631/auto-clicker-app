package com.akaa.autoclicker.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.akaa.autoclicker.data.PreferencesManager
import com.akaa.autoclicker.databinding.DialogImportJsonBinding
import com.akaa.autoclicker.databinding.DialogSaveProfileBinding
import com.akaa.autoclicker.databinding.FragmentProfilesBinding
import com.akaa.autoclicker.model.ScriptConfig
import java.util.UUID

class ProfilesFragment : Fragment() {

    private var _binding: FragmentProfilesBinding? = null
    private val binding get() = _binding!!
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var profileAdapter: ProfileAdapter
    private var profilesList: MutableList<ScriptConfig> = mutableListOf()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfilesBinding.inflate(inflater, container, false)
        preferencesManager = PreferencesManager(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadProfiles()
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        loadProfiles()
    }

    private fun loadProfiles() {
        profilesList = preferencesManager.getAllSavedProfiles()
        val currentActiveScript = preferencesManager.loadScriptConfig()

        profileAdapter = ProfileAdapter(
            profiles = profilesList,
            activeProfileId = currentActiveScript.id,
            onLoadClicked = { selectedProfile ->
                preferencesManager.saveScriptConfig(selectedProfile)
                Toast.makeText(requireContext(), "تم تحميل '${selectedProfile.name}' بنجاح", Toast.LENGTH_SHORT).show()
            },
            onExportClicked = { profile ->
                val json = preferencesManager.exportScriptToJson(profile)
                copyToClipboard(json)
                shareJson(json, profile.name)
            },
            onDeleteClicked = { profile, position ->
                AlertDialog.Builder(requireContext())
                    .setTitle("حذف السيناريو")
                    .setMessage("هل أنت متأكد من رغبتك في حذف '${profile.name}'؟")
                    .setPositiveButton("حذف") { _, _ ->
                        preferencesManager.deleteProfile(profile.id)
                        profilesList.removeAt(position)
                        profileAdapter.notifyItemRemoved(position)
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
        )

        binding.rvSavedProfiles.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSavedProfiles.adapter = profileAdapter
    }

    private fun setupButtons() {
        binding.cardSaveNewProfile.setOnClickListener {
            showSaveProfileDialog()
        }

        binding.cardImportJsonProfile.setOnClickListener {
            showImportJsonDialog()
        }
    }

    private fun showSaveProfileDialog() {
        val dialog = Dialog(requireContext(), android.R.style.Theme_Material_Dialog_NoActionBar)
        val dialogBinding = DialogSaveProfileBinding.inflate(LayoutInflater.from(requireContext()))
        dialog.setContentView(dialogBinding.root)

        val currentScript = preferencesManager.loadScriptConfig()
        dialogBinding.etProfileNameInput.setText("${currentScript.name} (نسخة جديدة)")

        dialogBinding.btnCancelSaveProfile.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnConfirmSaveProfile.setOnClickListener {
            val name = dialogBinding.etProfileNameInput.text.toString().trim()
            if (name.isNotEmpty()) {
                val newProfile = currentScript.copy(
                    id = UUID.randomUUID().toString(),
                    name = name
                )
                preferencesManager.saveProfile(newProfile)
                preferencesManager.saveScriptConfig(newProfile)
                loadProfiles()
                Toast.makeText(requireContext(), "تم حفظ السيناريو بنجاح ✓", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun showImportJsonDialog() {
        val dialog = Dialog(requireContext(), android.R.style.Theme_Material_Dialog_NoActionBar)
        val dialogBinding = DialogImportJsonBinding.inflate(LayoutInflater.from(requireContext()))
        dialog.setContentView(dialogBinding.root)

        dialogBinding.btnCancelImportJson.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnConfirmImportJson.setOnClickListener {
            val json = dialogBinding.etImportJsonInput.text.toString().trim()
            if (json.isNotEmpty()) {
                val imported = preferencesManager.importScriptFromJson(json)
                if (imported != null) {
                    val profileToSave = imported.copy(id = UUID.randomUUID().toString())
                    preferencesManager.saveProfile(profileToSave)
                    preferencesManager.saveScriptConfig(profileToSave)
                    loadProfiles()
                    Toast.makeText(requireContext(), "تم استيراد السيناريو '${profileToSave.name}' بنجاح ✓", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } else {
                    Toast.makeText(requireContext(), "كود JSON غير صالح، يرجى التأكد من نسخه بدقة", Toast.LENGTH_LONG).show()
                }
            }
        }

        dialog.show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("AutoClicker Script JSON", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "تم نسخ كود السيناريو للحافظة ✓", Toast.LENGTH_SHORT).show()
    }

    private fun shareJson(json: String, scriptName: String) {
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, json)
            putExtra(Intent.EXTRA_TITLE, "سيناريو $scriptName")
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "مشاركة سيناريو $scriptName")
        startActivity(shareIntent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
