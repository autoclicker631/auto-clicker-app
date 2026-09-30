package com.akaa.autoclicker.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.akaa.autoclicker.R
import com.akaa.autoclicker.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val dashboardFragment = DashboardFragment()
    private val rulesFragment = RulesFragment()
    private val profilesFragment = ProfilesFragment()
    private val settingsFragment = SettingsFragment()

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            Log.d("AutoClicker", "Notification permission granted: $isGranted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupImmersiveAndInsets()
        checkNotificationPermission()

        if (savedInstanceState == null) {
            com.akaa.autoclicker.data.PreferencesManager(this).resetCurrentScriptToEmpty()
            loadFragment(dashboardFragment)
        }

        setupBottomNavigation()

        // Clean up old updates and check for updates on startup
        com.akaa.autoclicker.update.AppUpdateManager.cleanupOldUpdates(this)
        com.akaa.autoclicker.update.AppUpdateManager.checkAndPromptOnLaunch(this)
    }

    override fun onResume() {
        super.onResume()
        // If an update was just installed, clean up leftover update files
        com.akaa.autoclicker.update.AppUpdateManager.cleanupOldUpdates(this)

        // If a force update is required and user returned without updating, re-enforce update dialog
        if (com.akaa.autoclicker.update.AppUpdateManager.isForceUpdateActive) {
            val forceInfo = com.akaa.autoclicker.update.AppUpdateManager.activeForceUpdateInfo
            if (forceInfo != null) {
                // Ensure floating service is stopped
                if (com.akaa.autoclicker.service.FloatingOverlayService.isRunning) {
                    val serviceIntent = android.content.Intent(this, com.akaa.autoclicker.service.FloatingOverlayService::class.java)
                    stopService(serviceIntent)
                }
                com.akaa.autoclicker.update.AppUpdateManager.showUpdateDialog(this, forceInfo, isForce = true)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemNavigationBars()
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupImmersiveAndInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemNavigationBars()

        // Apply status and navigation bar insets to ensure bottom tabs are 100% visible
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                0 // Bottom is handled by bottom navigation margin
            )
            binding.bottomNavigation.setPadding(0, 0, 0, systemBars.bottom)
            insets
        }
    }

    private fun hideSystemNavigationBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.navigationBars())
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    loadFragment(dashboardFragment)
                    true
                }
                R.id.nav_rules -> {
                    loadFragment(rulesFragment)
                    true
                }
                R.id.nav_profiles -> {
                    loadFragment(profilesFragment)
                    true
                }
                R.id.nav_settings -> {
                    loadFragment(settingsFragment)
                    true
                }
                else -> false
            }
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
}
