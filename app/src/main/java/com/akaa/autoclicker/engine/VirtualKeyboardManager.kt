package com.akaa.autoclicker.engine

import android.view.KeyEvent
import com.akaa.autoclicker.service.AutoClickerAccessibilityService
import com.akaa.autoclicker.service.VirtualKeyboardImeService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object VirtualKeyboardManager {

    /**
     * Tracks whether shell-based key injection is available on this device.
     * Once detected as unavailable, we skip it to avoid delays.
     */
    @Volatile
    private var shellKeyEventAvailable: Boolean? = null // null = not yet tested

    /**
     * Maps a single character to its corresponding Android KeyEvent keyCode.
     * Returns -1 if the character cannot be mapped to a key code.
     */
    fun charToKeyCode(char: Char): Int {
        return when (char) {
            ' ' -> KeyEvent.KEYCODE_SPACE
            '\n', '\r' -> KeyEvent.KEYCODE_ENTER
            '\t' -> KeyEvent.KEYCODE_TAB
            'a', 'A' -> KeyEvent.KEYCODE_A
            'b', 'B' -> KeyEvent.KEYCODE_B
            'c', 'C' -> KeyEvent.KEYCODE_C
            'd', 'D' -> KeyEvent.KEYCODE_D
            'e', 'E' -> KeyEvent.KEYCODE_E
            'f', 'F' -> KeyEvent.KEYCODE_F
            'g', 'G' -> KeyEvent.KEYCODE_G
            'h', 'H' -> KeyEvent.KEYCODE_H
            'i', 'I' -> KeyEvent.KEYCODE_I
            'j', 'J' -> KeyEvent.KEYCODE_J
            'k', 'K' -> KeyEvent.KEYCODE_K
            'l', 'L' -> KeyEvent.KEYCODE_L
            'm', 'M' -> KeyEvent.KEYCODE_M
            'n', 'N' -> KeyEvent.KEYCODE_N
            'o', 'O' -> KeyEvent.KEYCODE_O
            'p', 'P' -> KeyEvent.KEYCODE_P
            'q', 'Q' -> KeyEvent.KEYCODE_Q
            'r', 'R' -> KeyEvent.KEYCODE_R
            's', 'S' -> KeyEvent.KEYCODE_S
            't', 'T' -> KeyEvent.KEYCODE_T
            'u', 'U' -> KeyEvent.KEYCODE_U
            'v', 'V' -> KeyEvent.KEYCODE_V
            'w', 'W' -> KeyEvent.KEYCODE_W
            'x', 'X' -> KeyEvent.KEYCODE_X
            'y', 'Y' -> KeyEvent.KEYCODE_Y
            'z', 'Z' -> KeyEvent.KEYCODE_Z
            '0' -> KeyEvent.KEYCODE_0
            '1' -> KeyEvent.KEYCODE_1
            '2' -> KeyEvent.KEYCODE_2
            '3' -> KeyEvent.KEYCODE_3
            '4' -> KeyEvent.KEYCODE_4
            '5' -> KeyEvent.KEYCODE_5
            '6' -> KeyEvent.KEYCODE_6
            '7' -> KeyEvent.KEYCODE_7
            '8' -> KeyEvent.KEYCODE_8
            '9' -> KeyEvent.KEYCODE_9
            ',' -> KeyEvent.KEYCODE_COMMA
            '.' -> KeyEvent.KEYCODE_PERIOD
            '-' -> KeyEvent.KEYCODE_MINUS
            '=' -> KeyEvent.KEYCODE_EQUALS
            '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
            ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
            '\\' -> KeyEvent.KEYCODE_BACKSLASH
            ';' -> KeyEvent.KEYCODE_SEMICOLON
            '\'' -> KeyEvent.KEYCODE_APOSTROPHE
            '/' -> KeyEvent.KEYCODE_SLASH
            '`' -> KeyEvent.KEYCODE_GRAVE
            else -> -1
        }
    }

    /**
     * Checks if the text can be fully converted to key events
     * (all characters have a valid keyCode mapping).
     */
    fun canSendAsKeyEvents(text: String): Boolean {
        if (text.isEmpty()) return false
        return text.all { charToKeyCode(it) != -1 }
    }

    /**
     * Sends text as a sequence of individual key press/release events.
     * This is what games like Fortnite recognize - real KeyDown/KeyUp events,
     * NOT text input via commitText().
     *
     * Returns false if key injection is not available on this device.
     */
    fun sendTextAsKeyEvents(text: String): Boolean {
        if (text.isEmpty()) return false

        var allSuccess = true
        for (char in text) {
            val keyCode = charToKeyCode(char)
            if (keyCode != -1) {
                val sent = sendKeyCode(keyCode)
                if (!sent) allSuccess = false
            } else {
                android.util.Log.w("VirtualKeyboard", "Cannot map character '${char}' to keyCode, skipping")
                allSuccess = false
            }
            // Small delay between keys to simulate real typing
            if (text.length > 1) {
                try { Thread.sleep(30) } catch (_: Exception) {}
            }
        }
        return allSuccess
    }

    /**
     * Sends virtual text typing signal to the active application.
     *
     * SMART MODE: If the text consists of characters that map to key codes
     * (letters, numbers, space, etc.), it will FIRST try to send them as
     * real key events (which games recognize). If that fails or the text
     * contains unmappable characters, falls back to text input methods.
     *
     * @return true ONLY if injection was truly successful
     */
    fun typeText(text: String): Boolean {
        // SMART: If text can be sent as key events, try that FIRST.
        // This is critical for games like Fortnite where " " should trigger Jump,
        // "w" should trigger Walk Forward, etc.
        if (canSendAsKeyEvents(text)) {
            android.util.Log.d("VirtualKeyboard", "typeText: text '$text' can be sent as key events, trying key injection first")
            if (sendTextAsKeyEvents(text)) {
                android.util.Log.d("VirtualKeyboard", "typeText: key events sent successfully for '$text'")
                return true
            }
            android.util.Log.d("VirtualKeyboard", "typeText: key events failed, falling back to text input methods")
        }

        // 1. Try Virtual Keyboard IME first if enabled
        if (VirtualKeyboardImeService.typeText(text)) {
            return true
        }

        // 2. Try shell input text injection (for Shizuku / Root / ADB environments)
        if (shellKeyEventAvailable != false) {
            try {
                val escapedText = text.replace(" ", "%s").replace("\"", "\\\"")
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input text \"$escapedText\""))
                val finished = process.waitFor(3, TimeUnit.SECONDS)
                if (finished && process.exitValue() == 0) return true
                if (!finished) process.destroyForcibly()
            } catch (e: Exception) {
                // Shell not available or restricted
            }
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
     *
     * IMPORTANT: This method returns FALSE if key injection is not available,
     * so the caller can fall back to touch simulation (which works without root).
     *
     * Priority order:
     * 1. Shell `input keyevent` (hardware-level, best for games) - needs ADB/Shizuku/Root
     * 2. IME key event (for standard apps only, games ignore this)
     * 3. Accessibility global actions (Back, Home, Recents only)
     * 4. Returns FALSE → caller should use touch fallback for games
     */
    fun sendKeyCode(keyCode: Int): Boolean {
        // 1. Try shell input keyevent (only if not already known to be unavailable)
        if (shellKeyEventAvailable != false) {
            val shellResult = sendShellKeyEvent(keyCode)
            if (shellResult) return true
        }

        // 2. Try IME if active (only useful for apps with InputConnection, NOT games)
        if (VirtualKeyboardImeService.sendKeyEvent(keyCode)) {
            return true
        }

        // 3. Accessibility service global actions fallback (limited to system keys)
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

        // 4. Return false - caller should use touch simulation as fallback
        android.util.Log.w("VirtualKeyboard", "All key injection methods failed for keyCode=$keyCode. " +
                "Touch simulation at configured coordinates will be used as fallback.")
        return false
    }

    /**
     * Sends a hardware-level key event via shell.
     * Games detect this as a physical keyboard connected to the device.
     *
     * REQUIRES: ADB shell access (via Shizuku, ADB over WiFi, or Root).
     * On devices without these, this will FAIL and return false properly.
     */
    private fun sendShellKeyEvent(keyCode: Int): Boolean {
        // Method 1: Standard input keyevent (goes through InputManager)
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent $keyCode"))
            // Use timeout to prevent hanging on restricted devices
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                android.util.Log.w("VirtualKeyboard", "Shell keyevent timed out for keyCode=$keyCode")
                shellKeyEventAvailable = false
                return false
            }
            val exitCode = process.exitValue()

            // On non-rooted devices without INJECT_EVENTS permission,
            // `input keyevent` may exit with code 0 but actually do nothing.
            // We verify by checking stderr for permission errors.
            val errorOutput = process.errorStream.bufferedReader().readText().trim()
            if (errorOutput.contains("Permission", ignoreCase = true) ||
                errorOutput.contains("SecurityException", ignoreCase = true) ||
                errorOutput.contains("Injecting", ignoreCase = true)) {
                android.util.Log.w("VirtualKeyboard", "Shell keyevent permission denied: $errorOutput")
                shellKeyEventAvailable = false
                return false
            }

            if (exitCode == 0) {
                android.util.Log.d("VirtualKeyboard", "Shell keyevent sent successfully: keyCode=$keyCode")
                shellKeyEventAvailable = true
                return true
            }
        } catch (e: Exception) {
            android.util.Log.w("VirtualKeyboard", "Shell keyevent failed for keyCode=$keyCode", e)
        }

        // Method 2: Try with explicit keyboard source flag
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyboard keyevent $keyCode"))
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return false
            }
            if (process.exitValue() == 0) {
                val errorOutput = process.errorStream.bufferedReader().readText().trim()
                if (errorOutput.isEmpty() || !errorOutput.contains("Permission", ignoreCase = true)) {
                    android.util.Log.d("VirtualKeyboard", "Shell keyboard keyevent sent: keyCode=$keyCode")
                    shellKeyEventAvailable = true
                    return true
                }
            }
        } catch (e: Exception) {
            // Not supported on this device
        }

        // Method 3: Try sendevent directly (requires root)
        try {
            val linuxKeyCode = androidKeyCodeToLinuxKeyCode(keyCode)
            if (linuxKeyCode > 0) {
                val cmds = """
                    sendevent /dev/input/event0 1 $linuxKeyCode 1
                    sendevent /dev/input/event0 0 0 0
                    sendevent /dev/input/event0 1 $linuxKeyCode 0
                    sendevent /dev/input/event0 0 0 0
                """.trimIndent()
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmds))
                val finished = process.waitFor(3, TimeUnit.SECONDS)
                if (finished && process.exitValue() == 0) {
                    android.util.Log.d("VirtualKeyboard", "sendevent succeeded for keyCode=$keyCode (linux=$linuxKeyCode)")
                    shellKeyEventAvailable = true
                    return true
                }
                if (!finished) process.destroyForcibly()
            }
        } catch (e: Exception) {
            // Root not available
        }

        shellKeyEventAvailable = false
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
            KeyEvent.KEYCODE_COMMA -> 51
            KeyEvent.KEYCODE_PERIOD -> 52
            KeyEvent.KEYCODE_MINUS -> 12
            KeyEvent.KEYCODE_EQUALS -> 13
            KeyEvent.KEYCODE_LEFT_BRACKET -> 26
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 27
            KeyEvent.KEYCODE_BACKSLASH -> 43
            KeyEvent.KEYCODE_SEMICOLON -> 39
            KeyEvent.KEYCODE_APOSTROPHE -> 40
            KeyEvent.KEYCODE_SLASH -> 53
            KeyEvent.KEYCODE_GRAVE -> 41
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
     * REQUIRES: ADB/Shizuku/Root
     */
    fun sendKeyCombination(vararg keyCodes: Int): Boolean {
        if (keyCodes.isEmpty()) return false
        if (shellKeyEventAvailable == false) return false
        try {
            val sb = StringBuilder()
            for (code in keyCodes) {
                sb.append("input keyevent --down $code; ")
            }
            for (code in keyCodes.reversed()) {
                sb.append("input keyevent --up $code; ")
            }
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", sb.toString()))
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return false
            }
            return process.exitValue() == 0
        } catch (e: Exception) {
            android.util.Log.w("VirtualKeyboard", "Key combination failed", e)
            return false
        }
    }

    /**
     * Returns true if shell key injection has been tested and found available.
     * Returns null if not yet tested.
     */
    fun isShellKeyEventAvailable(): Boolean? = shellKeyEventAvailable

    /**
     * Resets the cached shell availability state (useful when user enables ADB/Shizuku).
     */
    fun resetShellAvailabilityCache() {
        shellKeyEventAvailable = null
    }
}
