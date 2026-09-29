package com.akaa.autoclicker.engine

import android.view.KeyEvent
import com.akaa.autoclicker.service.AutoClickerAccessibilityService
import com.akaa.autoclicker.service.VirtualKeyboardImeService

object VirtualKeyboardManager {

    /**
     * Sends virtual text typing signal to the active application
     */
    fun typeText(text: String): Boolean {
        // 1. Try Virtual Keyboard IME first if enabled
        if (VirtualKeyboardImeService.typeText(text)) {
            return true
        }

        // 2. Try shell input text injection (for Shizuku / Root / ADB environments)
        try {
            val escapedText = text.replace(" ", "%s").replace("\"", "\\\"")
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input text \"$escapedText\""))
            val exitCode = process.waitFor()
            if (exitCode == 0) return true
        } catch (e: Exception) {
            // Shell not available or restricted
        }

        // 3. Fallback to Accessibility Service text injection
        val accService = AutoClickerAccessibilityService.instance
        if (accService != null) {
            return accService.typeTextIntoFocusedField(text)
        }

        return false
    }

    /**
     * Sends a virtual key event (e.g. Space, Enter, Back, Gaming Keys)
     */
    fun sendKeyCode(keyCode: Int): Boolean {
        // 1. Try shell input keyevent (Direct Linux kernel keycode injection for games & apps)
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent $keyCode"))
            val exitCode = process.waitFor()
            if (exitCode == 0) return true
        } catch (e: Exception) {
            // Shell not available
        }

        // 2. Try IME if active
        if (VirtualKeyboardImeService.sendKeyEvent(keyCode)) {
            return true
        }

        // 3. Accessibility service global actions fallback
        val accService = AutoClickerAccessibilityService.instance
        if (accService != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    return accService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                }
                KeyEvent.KEYCODE_HOME -> {
                    return accService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                }
                KeyEvent.KEYCODE_APP_SWITCH -> {
                    return accService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                }
            }
        }
        return false
    }
}
