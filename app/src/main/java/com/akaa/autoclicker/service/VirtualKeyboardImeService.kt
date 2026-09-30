package com.akaa.autoclicker.service

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout

/**
 * Built-in Virtual Keyboard Input Method Service
 * Can be selected as an active IME to send authentic keyboard signals directly to apps
 */
class VirtualKeyboardImeService : InputMethodService() {

    companion object {
        var activeInstance: VirtualKeyboardImeService? = null
            private set

        fun typeText(text: String): Boolean {
            val ic = activeInstance?.currentInputConnection ?: return false
            return ic.commitText(text, 1)
        }

        fun sendKeyEvent(keyCode: Int): Boolean {
            val ic = activeInstance?.currentInputConnection ?: return false
            val downTime = android.os.SystemClock.uptimeMillis()
            val downEvent = KeyEvent(
                downTime, downTime,
                KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_FROM_SYSTEM,
                android.view.InputDevice.SOURCE_KEYBOARD
            )
            val upEvent = KeyEvent(
                downTime, android.os.SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP, keyCode, 0, 0,
                android.view.KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_FROM_SYSTEM,
                android.view.InputDevice.SOURCE_KEYBOARD
            )
            ic.sendKeyEvent(downEvent)
            ic.sendKeyEvent(upEvent)
            return true
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
    }

    override fun onCreateInputView(): View {
        return FrameLayout(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        activeInstance = null
    }
}
