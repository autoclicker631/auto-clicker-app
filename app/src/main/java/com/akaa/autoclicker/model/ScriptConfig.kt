package com.akaa.autoclicker.model

import java.util.UUID

data class ScriptConfig(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "My Script",
    var mode: AutomationMode = AutomationMode.AUTO_CLICKER,
    var loopCount: Int = 0, // 0 = infinite
    var cycleDelayMs: Long = 300,
    var batteryThresholdStopPercent: Int = 20,
    var closeAppsOnLowBattery: Boolean = true,
    val rules: MutableList<ConditionRule> = mutableListOf()
)
