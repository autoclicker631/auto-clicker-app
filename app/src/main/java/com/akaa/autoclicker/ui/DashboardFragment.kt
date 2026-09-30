package com.akaa.autoclicker.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.akaa.autoclicker.R
import com.akaa.autoclicker.data.PreferencesManager
import com.akaa.autoclicker.databinding.FragmentDashboardBinding
import com.akaa.autoclicker.engine.BatteryGuardManager
import com.akaa.autoclicker.model.AutomationMode
import com.akaa.autoclicker.service.FloatingOverlayService
import com.akaa.autoclicker.utils.PermissionUtils

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private lateinit var preferencesManager: PreferencesManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        preferencesManager = PreferencesManager(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupPermissionsActions()
        setupModeSelection()
        setupFloatingServiceButton()
    }

    override fun onResume() {
        super.onResume()
        refreshDashboardState()
    }

    fun refreshDashboardState() {
        if (_binding == null) return
        val script = preferencesManager.loadScriptConfig()
        binding.tvHeroProfileName.text = "السيناريو النشط: ${script.name}"
        binding.tvHeroRulesCount.text = "${script.rules.size} شروط وخطوات مبرمجة"

        val batteryPct = BatteryGuardManager.getCurrentBatteryPercentage(requireContext())
        binding.tvHeroBattery.text = "$batteryPct%"

        // Permissions check
        val hasAcc = PermissionUtils.isAccessibilityServiceEnabled(requireContext())
        val hasOverlay = PermissionUtils.canDrawOverlays(requireContext())
        val hasBattery = PermissionUtils.isIgnoringBatteryOptimizations(requireContext())

        binding.btnDashPermAccessibility.text = if (hasAcc) "مفعل ✓" else "تفعيل"
        binding.btnDashPermAccessibility.isEnabled = !hasAcc

        binding.btnDashPermOverlay.text = if (hasOverlay) "مفعل ✓" else "تفعيل"
        binding.btnDashPermOverlay.isEnabled = !hasOverlay

        binding.btnDashPermBattery.text = if (hasBattery) "مفعل ✓" else "تفعيل"
        binding.btnDashPermBattery.isEnabled = !hasBattery

        // Mode Radio
        when (script.mode) {
            AutomationMode.AUTO_CLICKER -> binding.rbModeClicker.isChecked = true
            AutomationMode.VIRTUAL_KEYBOARD -> binding.rbModeKeyboard.isChecked = true
            AutomationMode.HYBRID -> binding.rbModeHybrid.isChecked = true
        }

        // Service Start/Stop Button state
        if (FloatingOverlayService.isRunning) {
            binding.btnHeroToggleService.text = getString(R.string.dashboard_btn_stop)
            binding.btnHeroToggleService.setIconResource(R.drawable.ic_close)
            binding.btnHeroToggleService.setBackgroundColor(requireContext().getColor(R.color.rose_red))
        } else {
            binding.btnHeroToggleService.text = getString(R.string.dashboard_btn_start)
            binding.btnHeroToggleService.setIconResource(R.drawable.ic_play)
            binding.btnHeroToggleService.setBackgroundColor(requireContext().getColor(R.color.primary))
        }
    }

    private fun setupPermissionsActions() {
        binding.btnDashPermAccessibility.setOnClickListener {
            PermissionUtils.openAccessibilitySettings(requireContext())
        }
        binding.btnDashPermOverlay.setOnClickListener {
            PermissionUtils.openOverlaySettings(requireContext())
        }
        binding.btnDashPermBattery.setOnClickListener {
            PermissionUtils.requestIgnoreBatteryOptimizations(requireContext())
        }
    }

    private fun setupModeSelection() {
        binding.rgDashboardMode.setOnCheckedChangeListener { _, checkedId ->
            val script = preferencesManager.loadScriptConfig()
            script.mode = when (checkedId) {
                R.id.rbModeClicker -> AutomationMode.AUTO_CLICKER
                R.id.rbModeKeyboard -> AutomationMode.VIRTUAL_KEYBOARD
                R.id.rbModeHybrid -> AutomationMode.HYBRID
                else -> AutomationMode.AUTO_CLICKER
            }
            preferencesManager.saveScriptConfig(script)
        }
    }

    private fun setupFloatingServiceButton() {
        binding.btnHeroToggleService.setOnClickListener {
            // Check for force update block
            if (com.akaa.autoclicker.update.AppUpdateManager.isForceUpdateActive) {
                val forceInfo = com.akaa.autoclicker.update.AppUpdateManager.activeForceUpdateInfo
                if (forceInfo != null) {
                    Toast.makeText(requireContext(), "لا يمكن تشغيل الأداة، يجب تثبيت التحديث الإجباري أولاً!", Toast.LENGTH_LONG).show()
                    com.akaa.autoclicker.update.AppUpdateManager.showUpdateDialog(requireActivity(), forceInfo, isForce = true)
                }
                return@setOnClickListener
            }

            if (FloatingOverlayService.isRunning) {
                val serviceIntent = Intent(requireContext(), FloatingOverlayService::class.java)
                requireContext().stopService(serviceIntent)
                refreshDashboardState()
            } else {
                if (!PermissionUtils.canDrawOverlays(requireContext())) {
                    Toast.makeText(requireContext(), "يرجى منح إذن الظهور فوق التطبيقات أولاً", Toast.LENGTH_SHORT).show()
                    PermissionUtils.openOverlaySettings(requireContext())
                    return@setOnClickListener
                }
                if (!PermissionUtils.isAccessibilityServiceEnabled(requireContext())) {
                    Toast.makeText(requireContext(), "يرجى تفعيل خدمة إمكانية الوصول للتطبيق", Toast.LENGTH_SHORT).show()
                    PermissionUtils.openAccessibilitySettings(requireContext())
                    return@setOnClickListener
                }

                val serviceIntent = Intent(requireContext(), FloatingOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    requireContext().startForegroundService(serviceIntent)
                } else {
                    requireContext().startService(serviceIntent)
                }
                refreshDashboardState()
                Toast.makeText(requireContext(), "تم تشغيل الأداة العائمة بنجاح", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
