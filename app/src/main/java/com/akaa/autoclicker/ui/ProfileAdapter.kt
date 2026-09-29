package com.akaa.autoclicker.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.akaa.autoclicker.databinding.ItemSavedProfileBinding
import com.akaa.autoclicker.model.AutomationMode
import com.akaa.autoclicker.model.ScriptConfig

class ProfileAdapter(
    private val profiles: MutableList<ScriptConfig>,
    private var activeProfileId: String,
    private val onLoadClicked: (ScriptConfig) -> Unit,
    private val onExportClicked: (ScriptConfig) -> Unit,
    private val onDeleteClicked: (ScriptConfig, Int) -> Unit
) : RecyclerView.Adapter<ProfileAdapter.ProfileViewHolder>() {

    inner class ProfileViewHolder(val binding: ItemSavedProfileBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val binding = ItemSavedProfileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ProfileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        val profile = profiles[position]
        with(holder.binding) {
            tvProfileItemName.text = profile.name

            val modeName = when (profile.mode) {
                AutomationMode.AUTO_CLICKER -> "نقر تلقائي"
                AutomationMode.VIRTUAL_KEYBOARD -> "كيبورد وهمي"
                AutomationMode.HYBRID -> "هجين"
            }
            tvProfileItemDetails.text = "الوضع: $modeName • ${profile.rules.size} خطوات مبرمجة"

            val isActive = profile.id == activeProfileId
            tvProfileActiveBadge.visibility = if (isActive) View.VISIBLE else View.GONE

            btnProfileLoad.setOnClickListener {
                activeProfileId = profile.id
                notifyDataSetChanged()
                onLoadClicked(profile)
            }

            btnProfileExport.setOnClickListener {
                onExportClicked(profile)
            }

            btnProfileDelete.setOnClickListener {
                onDeleteClicked(profile, position)
            }
        }
    }

    override fun getItemCount(): Int = profiles.size

    fun setActiveId(id: String) {
        activeProfileId = id
        notifyDataSetChanged()
    }
}
