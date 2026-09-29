package com.akaa.autoclicker.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.akaa.autoclicker.data.PreferencesManager
import com.akaa.autoclicker.databinding.FragmentSettingsBinding
import com.akaa.autoclicker.utils.PermissionUtils

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var preferencesManager: PreferencesManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        preferencesManager = PreferencesManager(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupBatterySettings()
        setupHumanTouchAndHaptics()
        setupSamsungOptimization()
        setupAppUpdateSystem()
    }

    private fun setupAppUpdateSystem() {
        val packageInfo = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
        } catch (e: Exception) {
            null
        }

        val versionName = packageInfo?.versionName ?: "1.0"
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode ?: 1L
        } else {
            @Suppress("DEPRECATION")
            packageInfo?.versionCode?.toLong() ?: 1L
        }

        binding.tvSettingsAppVersion.text = "v$versionName (Build $versionCode)"

        binding.btnCheckForUpdatesManual.setOnClickListener {
            binding.btnCheckForUpdatesManual.isEnabled = false
            binding.btnCheckForUpdatesManual.text = "⏳ جاري التحقق..."

            com.akaa.autoclicker.update.AppUpdateManager.checkForUpdates(requireContext()) { result ->
                binding.btnCheckForUpdatesManual.isEnabled = true
                binding.btnCheckForUpdatesManual.text = "🔍 التحقق من وجود تحديثات الآن"

                when (result) {
                    is com.akaa.autoclicker.update.AppUpdateManager.UpdateResult.Available -> {
                        com.akaa.autoclicker.update.AppUpdateManager.showUpdateDialog(
                            requireActivity(),
                            result.updateInfo,
                            result.isForce
                        )
                    }
                    is com.akaa.autoclicker.update.AppUpdateManager.UpdateResult.UpToDate -> {
                        android.widget.Toast.makeText(
                            requireContext(),
                            "أنت تستخدم أحدث إصدار بالفعل ✓ (v$versionName)",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    is com.akaa.autoclicker.update.AppUpdateManager.UpdateResult.Error -> {
                        android.widget.Toast.makeText(
                            requireContext(),
                            result.message,
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun setupBatterySettings() {
        val savedThreshold = preferencesManager.getBatteryThreshold()
        binding.sliderSettingsBattery.value = savedThreshold.toFloat().coerceIn(5f, 50f)
        binding.tvSettingsBatteryThresholdLabel.text = "إيقاف التشغيل عند وصول البطارية إلى: $savedThreshold%"

        binding.sliderSettingsBattery.addOnChangeListener { _, value, _ ->
            val threshold = value.toInt()
            binding.tvSettingsBatteryThresholdLabel.text = "إيقاف التشغيل عند وصول البطارية إلى: $threshold%"
            preferencesManager.setBatteryThreshold(threshold)

            val currentScript = preferencesManager.loadScriptConfig()
            currentScript.batteryThresholdStopPercent = threshold
            preferencesManager.saveScriptConfig(currentScript)
        }

        binding.switchSettingsCloseApps.isChecked = preferencesManager.shouldCloseAppsOnLowBattery()
        binding.switchSettingsCloseApps.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.setCloseAppsOnLowBattery(isChecked)
            val currentScript = preferencesManager.loadScriptConfig()
            currentScript.closeAppsOnLowBattery = isChecked
            preferencesManager.saveScriptConfig(currentScript)
        }

        binding.btnTestEmergencyClose.setOnClickListener {
            if (!com.akaa.autoclicker.service.AutoClickerAccessibilityService.isServiceRunning) {
                android.widget.Toast.makeText(
                    requireContext(),
                    "يرجى تفعيل خدمة إمكانية الوصول (Accessibility) أولاً لتجربة إغلاق التطبيقات!",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
                return@setOnClickListener
            }

            android.widget.Toast.makeText(
                requireContext(),
                "جاري تجربة إغلاق التطبيقات والرجوع للشاشة الرئيسية...",
                android.widget.Toast.LENGTH_SHORT
            ).show()

            com.akaa.autoclicker.service.AutoClickerAccessibilityService.instance?.closeOpenAppsAndReturnHome()
        }
    }

    private fun setupHumanTouchAndHaptics() {
        binding.switchHumanTouch.isChecked = preferencesManager.isHumanTouchEnabled()
        binding.switchHumanTouch.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.setHumanTouchEnabled(isChecked)
        }

        binding.switchHaptic.isChecked = preferencesManager.isHapticEnabled()
        binding.switchHaptic.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.setHapticEnabled(isChecked)
        }
    }

    private fun setupSamsungOptimization() {
        binding.btnOpenSamsungSettings.setOnClickListener {
            PermissionUtils.openSamsungBatterySettings(requireContext())
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
