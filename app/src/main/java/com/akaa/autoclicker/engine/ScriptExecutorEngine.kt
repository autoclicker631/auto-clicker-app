package com.akaa.autoclicker.engine

import android.graphics.Rect
import com.akaa.autoclicker.model.ActionType
import com.akaa.autoclicker.model.ConditionRule
import com.akaa.autoclicker.model.ConditionType
import com.akaa.autoclicker.model.RuleAction
import com.akaa.autoclicker.model.ScriptConfig
import com.akaa.autoclicker.service.AutoClickerAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

class ScriptExecutorEngine(
    private val isHumanTouchEnabled: () -> Boolean = { true },
    private val onActionExecuted: (() -> Unit)? = null,
    private val onStatusUpdate: (String) -> Unit
) {
    private var executionJob: Job? = null
    var isPaused: Boolean = false
        private set

    @Volatile
    private var activeScriptConfig: ScriptConfig? = null

    val isRunning: Boolean
        get() = executionJob?.isActive == true

    fun start(script: ScriptConfig) {
        stop()
        isPaused = false
        activeScriptConfig = script
        executionJob = CoroutineScope(Dispatchers.Default).launch {
            runScript(script)
        }
    }

    fun updateScriptConfig(newScript: ScriptConfig) {
        activeScriptConfig = newScript
        onStatusUpdate("تم تحديث الشروط (${newScript.rules.count { it.isEnabled }} مفعلة) 🔄")
    }

    fun pause() {
        isPaused = true
        onStatusUpdate("موقوف مؤقتاً ⏸️")
    }

    fun resume() {
        isPaused = false
        onStatusUpdate("جاري الاستئناف... ▶️")
    }

    fun stop() {
        executionJob?.cancel()
        executionJob = null
        activeScriptConfig = null
        isPaused = false
        onStatusUpdate("متوقف ⏹️")
    }

    private suspend fun runScript(initialScript: ScriptConfig) {
        activeScriptConfig = initialScript
        var currentLoop = 0

        while (CoroutineScope(Dispatchers.Default).isActive) {
            val currentScript = activeScriptConfig ?: initialScript
            val maxLoops = currentScript.loopCount
            if (maxLoops > 0 && currentLoop >= maxLoops) {
                break
            }
            currentLoop++

            val rules = currentScript.rules.filter { it.isEnabled }
            if (rules.isEmpty()) {
                withContext(Dispatchers.Main) {
                    onStatusUpdate("لا توجد شروط مفعلة!")
                }
                delay(500)
                continue
            }

            for ((index, rule) in rules.withIndex()) {
                while (isPaused && CoroutineScope(Dispatchers.Default).isActive) {
                    delay(200)
                }

                if (!CoroutineScope(Dispatchers.Default).isActive) break

                withContext(Dispatchers.Main) {
                    onStatusUpdate("فحص [${index + 1}/${rules.size}]: ${rule.name}")
                }

                val evaluationResult = evaluateCondition(rule)
                if (evaluationResult.isMatched) {
                    val actions = rule.getEffectiveActions()
                    withContext(Dispatchers.Main) {
                        onStatusUpdate("تحقق [${index + 1}] ✓ تنفيذ ${actions.size} إجراءات")
                        onActionExecuted?.invoke()
                    }

                    // Execute all actions chained for this condition
                    for (action in actions) {
                        if (!CoroutineScope(Dispatchers.Default).isActive) break
                        executeSingleAction(action, evaluationResult.detectedRect, rule.targetText, rule.matchExact)

                        var delayTime = action.delayAfterMs
                        if (isHumanTouchEnabled() && delayTime > 100) {
                            val jitter = Random.nextLong(-25, 25)
                            delayTime = (delayTime + jitter).coerceAtLeast(40)
                        }

                        if (delayTime > 0) {
                            delay(delayTime)
                        }
                    }

                    // Restart rule evaluation chain
                    break
                } else {
                    // Condition not met -> move directly to the next condition in chain
                    delay(30)
                }
            }

            if (currentScript.cycleDelayMs > 0) {
                delay(currentScript.cycleDelayMs)
            }
        }

        withContext(Dispatchers.Main) {
            onStatusUpdate("اكتمل التشغيل ✓")
        }
    }

    private data class ConditionResult(val isMatched: Boolean, val detectedRect: Rect? = null)

    private suspend fun evaluateCondition(rule: ConditionRule): ConditionResult {
        val accService = AutoClickerAccessibilityService.instance

        val searchRegion = if (rule.regionRight > rule.regionLeft && rule.regionBottom > rule.regionTop) {
            Rect(rule.regionLeft, rule.regionTop, rule.regionRight, rule.regionBottom)
        } else null

        return when (rule.conditionType) {
            ConditionType.ALWAYS -> ConditionResult(true)

            ConditionType.TEXT_EXISTS -> {
                if (accService == null) return ConditionResult(false)
                // Try Accessibility text scan first
                val rect = accService.findTextCoordinates(rule.targetText, rule.matchExact, searchRegion)
                if (rect != null) {
                    ConditionResult(true, rect)
                } else {
                    // Fallback to OCR for games (GPU-rendered text)
                    val ocrRect = accService.findTextCoordsWithOCR(rule.targetText, rule.matchExact, searchRegion)
                    ConditionResult(ocrRect != null, ocrRect)
                }
            }

            ConditionType.TEXT_NOT_EXISTS -> {
                if (accService == null) return ConditionResult(false)
                val rect = accService.findTextCoordinates(rule.targetText, rule.matchExact, searchRegion)
                if (rect != null) {
                    ConditionResult(false)
                } else {
                    // Also check with OCR
                    val ocrRect = accService.findTextCoordsWithOCR(rule.targetText, rule.matchExact, searchRegion)
                    ConditionResult(ocrRect == null)
                }
            }

            ConditionType.IMAGE_EXISTS -> {
                if (accService == null || rule.targetImageBase64.isNullOrBlank()) {
                    ConditionResult(false)
                } else {
                    val template = com.akaa.autoclicker.utils.ImageMatcher.base64ToBitmap(rule.targetImageBase64)
                    if (template == null) {
                        ConditionResult(false)
                    } else {
                        val screen = accService.captureScreen()
                        if (screen == null) {
                            ConditionResult(false)
                        } else {
                            val isMatched = com.akaa.autoclicker.utils.ImageMatcher.matchTemplate(
                                screenBitmap = screen,
                                templateBitmap = template,
                                searchRegion = searchRegion,
                                threshold = rule.imageThreshold
                            )
                            ConditionResult(isMatched, searchRegion)
                        }
                    }
                }
            }
        }
    }

    private suspend fun executeSingleAction(
        action: RuleAction,
        detectedRect: Rect?,
        ruleTargetText: String,
        matchExact: Boolean
    ) {
        val accService = AutoClickerAccessibilityService.instance
        val useHumanTouch = isHumanTouchEnabled()

        when (action.actionType) {
            ActionType.CLICK_COORDINATE -> {
                var targetX = action.clickX
                var targetY = action.clickY
                if (useHumanTouch) {
                    targetX += Random.nextInt(-4, 5)
                    targetY += Random.nextInt(-4, 5)
                }
                accService?.performClick(targetX, targetY)
            }

            ActionType.CLICK_DETECTED_TEXT -> {
                val rect = detectedRect ?: accService?.findTextCoordinates(ruleTargetText, matchExact)
                if (rect != null) {
                    var targetX = rect.centerX()
                    var targetY = rect.centerY()
                    if (useHumanTouch) {
                        targetX += Random.nextInt(-3, 4)
                        targetY += Random.nextInt(-3, 4)
                    }
                    accService?.performClick(targetX, targetY)
                }
            }

            ActionType.TYPE_TEXT -> {
                val textSuccess = VirtualKeyboardManager.typeText(action.textToType)
                if (!textSuccess && action.clickX > 0 && action.clickY > 0) {
                    var targetX = action.clickX
                    var targetY = action.clickY
                    if (useHumanTouch) {
                        targetX += Random.nextInt(-3, 4)
                        targetY += Random.nextInt(-3, 4)
                    }
                    accService?.performClick(targetX, targetY)
                }
            }

            ActionType.SEND_KEY_CODE -> {
                val keySuccess = VirtualKeyboardManager.sendKeyCode(action.keyCode)
                // If direct key event is restricted by Android OS (common in 3D games),
                // automatically perform touch gesture at the point's screen coordinates!
                if (!keySuccess && action.clickX > 0 && action.clickY > 0) {
                    var targetX = action.clickX
                    var targetY = action.clickY
                    if (useHumanTouch) {
                        targetX += Random.nextInt(-3, 4)
                        targetY += Random.nextInt(-3, 4)
                    }
                    accService?.performClick(targetX, targetY)
                }
            }

            ActionType.SWIPE -> {
                accService?.performSwipe(
                    action.clickX,
                    action.clickY,
                    action.swipeEndX,
                    action.swipeEndY,
                    action.swipeDurationMs
                )
            }

            ActionType.DELAY_ONLY -> {}

            ActionType.CLOSE_RECENT_APPS -> {
                accService?.closeOpenAppsAndReturnHome()
            }
        }
    }
}
