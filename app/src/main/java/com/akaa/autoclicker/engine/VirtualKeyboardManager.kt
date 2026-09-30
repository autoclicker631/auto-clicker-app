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
     * Sends a virtual key event that games recognize as a HARDWARE KEYBOARD input.
     * Uses `input keyevent` shell command which injects at the Linux input layer,
     * making games like Fortnite detect it as a physical keyboard connected via USB/Bluetooth.
     *
     * Priority order:
     * 1. Shell `input keyevent` (hardware-level, best for games)
     * 2. Shell `input keyboard keyevent` with source flag (explicit keyboard source)
     * 3. IME key event (for standard apps)
     * 4. Accessibility global actions (Back, Home, Recents fallback)
     */
    fun sendKeyCode(keyCode: Int): Boolean {
        // 1. Try shell input keyevent with explicit keyboard source
        //    This makes the event appear as if from a physical keyboard (SOURCE_KEYBOARD)
        if (sendShellKeyEvent(keyCode)) return true

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

    /**
     * Sends a hardware-level key event via shell.
     * Games detect this as a physical keyboard connected to the device.
     */
    private fun sendShellKeyEvent(keyCode: Int): Boolean {
        // Method 1: Standard input keyevent (goes through InputManager, includes SOURCE_KEYBOARD)
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent $keyCode"))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                android.util.Log.d("VirtualKeyboard", "Shell keyevent sent successfully: keyCode=$keyCode")
                return true
            }
        } catch (e: Exception) {
            android.util.Log.w("VirtualKeyboard", "Shell keyevent failed for keyCode=$keyCode", e)
        }

        // Method 2: Try sendevent directly on keyboard input device (requires root)
        // This is the most authentic hardware keyboard simulation
        try {
            // Linux input event: type=1 (EV_KEY), code=keyCode, value=1 (press) then 0 (release)
            val linuxKeyCode = androidKeyCodeToLinuxKeyCode(keyCode)
            if (linuxKeyCode > 0) {
                val cmds = """
                    sendevent /dev/input/event0 1 $linuxKeyCode 1
                    sendevent /dev/input/event0 0 0 0
                    sendevent /dev/input/event0 1 $linuxKeyCode 0
                    sendevent /dev/input/event0 0 0 0
                """.trimIndent()
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmds))
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    android.util.Log.d("VirtualKeyboard", "sendevent succeeded for keyCode=$keyCode (linux=$linuxKeyCode)")
                    return true
                }
            }
        } catch (e: Exception) {
            // Root not available - that's OK
        }

        return false
    }

    /**
     * Maps common Android KeyEvent codes to Linux input event codes.
     * Linux KEY_* codes differ from Android KEYCODE_* codes.
     */
    private fun androidKeyCodeToLinuxKeyCode(androidKeyCode: Int): Int {
        return when (androidKeyCode) {
            KeyEvent.KEYCODE_A -> 30
            KeyEvent.KEYCODE_B -> 48
            KeyEvent.KEYCODE_C -> 46
            KeyEvent.KEYCODE_D -> 32
            KeyEvent.KEYCODE_E -> 18
            KeyEvent.KEYCODE_F -> 33
            KeyEvent.KEYCODE_G -> 34
            KeyEvent.KEYCODE_H -> 35
            KeyEvent.KEYCODE_I -> 23
            KeyEvent.KEYCODE_J -> 36
            KeyEvent.KEYCODE_K -> 37
            KeyEvent.KEYCODE_L -> 38
            KeyEvent.KEYCODE_M -> 39
            KeyEvent.KEYCODE_N -> 49
            KeyEvent.KEYCODE_O -> 24
            KeyEvent.KEYCODE_P -> 25
            KeyEvent.KEYCODE_Q -> 16
            KeyEvent.KEYCODE_R -> 19
            KeyEvent.KEYCODE_S -> 31
            KeyEvent.KEYCODE_T -> 20
            KeyEvent.KEYCODE_U -> 22
            KeyEvent.KEYCODE_V -> 47
            KeyEvent.KEYCODE_W -> 17
            KeyEvent.KEYCODE_X -> 45
            KeyEvent.KEYCODE_Y -> 21
            KeyEvent.KEYCODE_Z -> 44
            KeyEvent.KEYCODE_0 -> 11
            KeyEvent.KEYCODE_1 -> 2
            KeyEvent.KEYCODE_2 -> 3
            KeyEvent.KEYCODE_3 -> 4
            KeyEvent.KEYCODE_4 -> 5
            KeyEvent.KEYCODE_5 -> 6
            KeyEvent.KEYCODE_6 -> 7
            KeyEvent.KEYCODE_7 -> 8
            KeyEvent.KEYCODE_8 -> 9
            KeyEvent.KEYCODE_9 -> 10
            KeyEvent.KEYCODE_SPACE -> 57
            KeyEvent.KEYCODE_ENTER -> 28
            KeyEvent.KEYCODE_TAB -> 15
            KeyEvent.KEYCODE_ESCAPE -> 1
            KeyEvent.KEYCODE_DEL -> 14  // Backspace
            KeyEvent.KEYCODE_SHIFT_LEFT -> 42
            KeyEvent.KEYCODE_SHIFT_RIGHT -> 54
            KeyEvent.KEYCODE_CTRL_LEFT -> 29
            KeyEvent.KEYCODE_CTRL_RIGHT -> 97
            KeyEvent.KEYCODE_ALT_LEFT -> 56
            KeyEvent.KEYCODE_ALT_RIGHT -> 100
            KeyEvent.KEYCODE_DPAD_UP -> 103
            KeyEvent.KEYCODE_DPAD_DOWN -> 108
            KeyEvent.KEYCODE_DPAD_LEFT -> 105
            KeyEvent.KEYCODE_DPAD_RIGHT -> 106
            KeyEvent.KEYCODE_F1 -> 59
            KeyEvent.KEYCODE_F2 -> 60
            KeyEvent.KEYCODE_F3 -> 61
            KeyEvent.KEYCODE_F4 -> 62
            KeyEvent.KEYCODE_F5 -> 63
            KeyEvent.KEYCODE_F6 -> 64
            KeyEvent.KEYCODE_F7 -> 65
            KeyEvent.KEYCODE_F8 -> 66
            KeyEvent.KEYCODE_F9 -> 67
            KeyEvent.KEYCODE_F10 -> 68
            KeyEvent.KEYCODE_F11 -> 87
            KeyEvent.KEYCODE_F12 -> 88
            else -> -1
        }
    }

    /**
     * Sends a key combination (e.g., Ctrl+A, Shift+W) via shell.
     * Games recognize this as hardware keyboard combo.
     */
    fun sendKeyCombination(vararg keyCodes: Int): Boolean {
        if (keyCodes.isEmpty()) return false
        try {
            // Build input command for key combo
            val sb = StringBuilder()
            // Press all keys down
            for (code in keyCodes) {
                sb.append("input keyevent --down $code; ")
            }
            // Release all keys in reverse order
            for (code in keyCodes.reversed()) {
                sb.append("input keyevent --up $code; ")
            }
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", sb.toString()))
            val exitCode = process.waitFor()
            return exitCode == 0
        } catch (e: Exception) {
            android.util.Log.w("VirtualKeyboard", "Key combination failed", e)
            return false
        }
    }
}
