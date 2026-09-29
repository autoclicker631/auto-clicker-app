package com.akaa.autoclicker.data

import android.content.Context
import android.content.SharedPreferences
import com.akaa.autoclicker.model.ActionType
import com.akaa.autoclicker.model.AutomationMode
import com.akaa.autoclicker.model.ConditionRule
import com.akaa.autoclicker.model.ConditionType
import com.akaa.autoclicker.model.ScriptConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class PreferencesManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("autoclicker_prefs_v2", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_CURRENT_SCRIPT = "key_current_script"
        private const val KEY_SAVED_PROFILES = "key_saved_profiles"
        private const val KEY_BATTERY_THRESHOLD = "key_battery_threshold"
        private const val KEY_CLOSE_APPS_ON_LOW_BATTERY = "key_close_apps"
        private const val KEY_HUMAN_TOUCH = "key_human_touch"
        private const val KEY_HAPTIC = "key_haptic"
        private const val KEY_OVERLAY_OPACITY = "key_overlay_opacity"
    }

    fun saveScriptConfig(config: ScriptConfig) {
        val json = gson.toJson(config)
        prefs.edit().putString(KEY_CURRENT_SCRIPT, json).apply()
    }

    fun loadScriptConfig(): ScriptConfig {
        val json = prefs.getString(KEY_CURRENT_SCRIPT, null)
        if (json != null) {
            try {
                return gson.fromJson(json, ScriptConfig::class.java)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return createDefaultScript()
    }

    fun getAllSavedProfiles(): MutableList<ScriptConfig> {
        val json = prefs.getString(KEY_SAVED_PROFILES, null)
        if (json != null) {
            try {
                val type = object : TypeToken<MutableList<ScriptConfig>>() {}.type
                return gson.fromJson(json, type)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        val defaultList = mutableListOf(createDefaultScript(), createAdSkipPreset(), createAutoAcceptPreset())
        saveAllSavedProfiles(defaultList)
        return defaultList
    }

    fun saveAllSavedProfiles(profiles: List<ScriptConfig>) {
        val json = gson.toJson(profiles)
        prefs.edit().putString(KEY_SAVED_PROFILES, json).apply()
    }

    fun saveProfile(profile: ScriptConfig) {
        val list = getAllSavedProfiles()
        val existingIndex = list.indexOfFirst { it.id == profile.id || it.name == profile.name }
        if (existingIndex >= 0) {
            list[existingIndex] = profile
        } else {
            list.add(0, profile)
        }
        saveAllSavedProfiles(list)
    }

    fun deleteProfile(profileId: String) {
        val list = getAllSavedProfiles()
        list.removeAll { it.id == profileId }
        saveAllSavedProfiles(list)
    }

    fun exportScriptToJson(script: ScriptConfig): String {
        return gson.toJson(script)
    }

    fun importScriptFromJson(json: String): ScriptConfig? {
        return try {
            gson.fromJson(json, ScriptConfig::class.java)
        } catch (e: Exception) {
            null
        }
    }

    // Battery Settings
    fun getBatteryThreshold(): Int = prefs.getInt(KEY_BATTERY_THRESHOLD, 20)
    fun setBatteryThreshold(threshold: Int) = prefs.edit().putInt(KEY_BATTERY_THRESHOLD, threshold).apply()

    fun shouldCloseAppsOnLowBattery(): Boolean = prefs.getBoolean(KEY_CLOSE_APPS_ON_LOW_BATTERY, true)
    fun setCloseAppsOnLowBattery(close: Boolean) = prefs.edit().putBoolean(KEY_CLOSE_APPS_ON_LOW_BATTERY, close).apply()

    // Advanced UX Settings
    fun isHumanTouchEnabled(): Boolean = prefs.getBoolean(KEY_HUMAN_TOUCH, true)
    fun setHumanTouchEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_HUMAN_TOUCH, enabled).apply()

    fun isHapticEnabled(): Boolean = prefs.getBoolean(KEY_HAPTIC, true)
    fun setHapticEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_HAPTIC, enabled).apply()

    fun getOverlayOpacity(): Float = prefs.getFloat(KEY_OVERLAY_OPACITY, 0.95f)
    fun setOverlayOpacity(opacity: Float) = prefs.edit().putFloat(KEY_OVERLAY_OPACITY, opacity).apply()

    fun resetCurrentScriptToEmpty() {
        val emptyScript = ScriptConfig(
            name = "سيناريو جديد",
            mode = AutomationMode.AUTO_CLICKER,
            batteryThresholdStopPercent = 20,
            closeAppsOnLowBattery = true,
            rules = mutableListOf()
        )
        saveScriptConfig(emptyScript)
    }

    private fun createDefaultScript(): ScriptConfig {
        return ScriptConfig(
            name = "سيناريو جديد",
            mode = AutomationMode.AUTO_CLICKER,
            batteryThresholdStopPercent = 20,
            closeAppsOnLowBattery = true,
            rules = mutableListOf()
        )
    }

    private fun createAdSkipPreset(): ScriptConfig {
        val script = ScriptConfig(
            name = "⚡ تخطي الإعلانات تلقائياً",
            mode = AutomationMode.AUTO_CLICKER,
            batteryThresholdStopPercent = 15,
            closeAppsOnLowBattery = true
        )
        script.rules.add(
            ConditionRule(
                name = "البحث عن كلمة 'تخطي' أو 'Skip'",
                conditionType = ConditionType.TEXT_EXISTS,
                targetText = "تخطي",
                actionType = ActionType.CLICK_DETECTED_TEXT,
                delayAfterMs = 500
            )
        )
        script.rules.add(
            ConditionRule(
                name = "البحث عن كلمة 'Skip'",
                conditionType = ConditionType.TEXT_EXISTS,
                targetText = "Skip",
                actionType = ActionType.CLICK_DETECTED_TEXT,
                delayAfterMs = 500
            )
        )
        return script
    }

    private fun createAutoAcceptPreset(): ScriptConfig {
        val script = ScriptConfig(
            name = "⚡ قبول الطلبات الفوري",
            mode = AutomationMode.AUTO_CLICKER,
            batteryThresholdStopPercent = 20,
            closeAppsOnLowBattery = false
        )
        script.rules.add(
            ConditionRule(
                name = "فحص كلمة 'قبول' والضغط فوراً",
                conditionType = ConditionType.TEXT_EXISTS,
                targetText = "قبول",
                actionType = ActionType.CLICK_DETECTED_TEXT,
                delayAfterMs = 200
            )
        )
        return script
    }
}
