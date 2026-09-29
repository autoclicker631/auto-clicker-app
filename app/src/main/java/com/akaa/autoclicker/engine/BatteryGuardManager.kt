package com.akaa.autoclicker.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.akaa.autoclicker.service.AutoClickerAccessibilityService

class BatteryGuardManager(
    private val context: Context,
    private val onLowBatteryTriggered: (Int) -> Unit
) {
    private var isRegistered = false
    private var targetThresholdPercent: Int = 20
    private var shouldCloseApps: Boolean = false
    private var isScriptActive: Boolean = false
    private var hasTriggered: Boolean = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    val batteryPct = (level * 100) / scale
                    Log.d("AutoClicker", "Battery level received from broadcast: $batteryPct%, threshold: $targetThresholdPercent%")
                    checkBatteryLevel(batteryPct)
                }
            }
        }
    }

    fun startMonitoring(thresholdPercent: Int, closeApps: Boolean) {
        this.targetThresholdPercent = thresholdPercent
        this.shouldCloseApps = closeApps
        this.hasTriggered = false

        if (!isRegistered) {
            try {
                val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                context.registerReceiver(batteryReceiver, filter)
                isRegistered = true
                Log.d("AutoClicker", "BatteryGuard monitoring started (threshold=$thresholdPercent%, closeApps=$closeApps)")
            } catch (e: Exception) {
                Log.e("AutoClicker", "Failed to register battery receiver", e)
            }
        }
    }

    fun updateSettings(thresholdPercent: Int, closeApps: Boolean) {
        this.targetThresholdPercent = thresholdPercent
        this.shouldCloseApps = closeApps
        Log.d("AutoClicker", "BatteryGuard settings updated: threshold=$thresholdPercent%, closeApps=$closeApps")
        if (isScriptActive) {
            val currentBattery = getCurrentBatteryPercentage(context)
            checkBatteryLevel(currentBattery)
        }
    }

    fun setScriptActive(active: Boolean) {
        this.isScriptActive = active
        if (active) {
            hasTriggered = false
            val currentBattery = getCurrentBatteryPercentage(context)
            Log.d("AutoClicker", "BatteryGuard: Script active, current battery=$currentBattery%, threshold=$targetThresholdPercent%")
            checkBatteryLevel(currentBattery)
        }
    }

    fun stopMonitoring() {
        if (isRegistered) {
            try {
                context.unregisterReceiver(batteryReceiver)
                Log.d("AutoClicker", "BatteryGuard monitoring stopped")
            } catch (e: Exception) {
                Log.e("AutoClicker", "Error unregistering battery receiver", e)
            }
            isRegistered = false
        }
    }

    private fun checkBatteryLevel(currentBatteryPct: Int) {
        // Only trigger emergency close if script is actively running and threshold is reached
        if (isScriptActive && !hasTriggered && currentBatteryPct <= targetThresholdPercent) {
            hasTriggered = true
            Log.w("AutoClicker", "Emergency Battery Guard TRIGGERED at $currentBatteryPct% (<= $targetThresholdPercent%)")
            
            if (shouldCloseApps) {
                AutoClickerAccessibilityService.instance?.closeOpenAppsAndReturnHome()
            }
            
            onLowBatteryTriggered(currentBatteryPct)
        }
    }

    companion object {
        fun getCurrentBatteryPercentage(context: Context): Int {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val cap = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (cap in 0..100) return cap

            try {
                val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                val batteryStatus = context.registerReceiver(null, ifilter)
                val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level >= 0 && scale > 0) {
                    return (level * 100) / scale
                }
            } catch (e: Exception) {
                Log.e("AutoClicker", "Error getting sticky battery intent", e)
            }
            return 100
        }
    }
}
