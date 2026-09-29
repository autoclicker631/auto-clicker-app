package com.akaa.autoclicker.model

enum class ActionType {
    CLICK_COORDINATE,       // Click at (clickX, clickY)
    CLICK_DETECTED_TEXT,    // Click directly on the detected text position
    TYPE_TEXT,              // Type string via virtual keyboard
    SEND_KEY_CODE,          // Send key code (Enter, Space, Back, etc.)
    SWIPE,                  // Swipe from (clickX, clickY) to (swipeEndX, swipeEndY)
    DELAY_ONLY,             // Just wait delayAfterMs
    CLOSE_RECENT_APPS       // Trigger Recents / Close all apps and go to Home
}
