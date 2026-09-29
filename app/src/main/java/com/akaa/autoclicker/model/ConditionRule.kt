package com.akaa.autoclicker.model

import java.util.UUID

data class RuleAction(
    val id: String = UUID.randomUUID().toString(),
    var actionType: ActionType = ActionType.CLICK_COORDINATE,
    var clickX: Int = 500,
    var clickY: Int = 1000,
    var swipeEndX: Int = 500,
    var swipeEndY: Int = 500,
    var swipeDurationMs: Long = 300,
    var textToType: String = "",
    var keyCode: Int = 66, // Default KEYCODE_ENTER
    var delayAfterMs: Long = 300
)

data class ConditionRule(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var isEnabled: Boolean = true,
    
    // Condition Parameters
    var conditionType: ConditionType = ConditionType.ALWAYS,
    var targetText: String = "",
    var matchExact: Boolean = false,
    var targetImageBase64: String? = null,
    var imageThreshold: Float = 0.8f,
    
    // Region bounding box on screen (0,0,0,0 means whole screen)
    var regionLeft: Int = 0,
    var regionTop: Int = 0,
    var regionRight: Int = 0,
    var regionBottom: Int = 0,
    
    // Primary Action Type
    var actionType: ActionType = ActionType.CLICK_COORDINATE,
    var clickX: Int = 540,
    var clickY: Int = 1200,
    var textToType: String = "",
    var keyCode: Int = 66,
    var delayAfterMs: Long = 400,

    // Chained Multiple Actions for the same condition
    val actions: MutableList<RuleAction> = mutableListOf()
) {
    fun getEffectiveActions(): List<RuleAction> {
        if (actions.isNotEmpty()) {
            return actions
        }
        return listOf(
            RuleAction(
                actionType = actionType,
                clickX = clickX,
                clickY = clickY,
                textToType = textToType,
                keyCode = keyCode,
                delayAfterMs = delayAfterMs
            )
        )
    }
}
