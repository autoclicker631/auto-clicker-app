package com.akaa.autoclicker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AutoClickerAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AutoClickerAccessibilityService? = null
            private set
        
        val isServiceRunning: Boolean
            get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * Dispatches a single click gesture at the specified screen coordinates
     */
    fun performClick(x: Int, y: Int, callback: ((Boolean) -> Unit)? = null) {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x.toFloat() + 1f, y.toFloat() + 1f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                android.util.Log.d("AutoClicker", "Click gesture successfully executed at ($x, $y)")
                callback?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                android.util.Log.w("AutoClicker", "Click gesture cancelled at ($x, $y)")
                callback?.invoke(false)
            }
        }, null)

        if (!dispatched) {
            android.util.Log.e("AutoClicker", "dispatchGesture returned FALSE for ($x, $y)")
            callback?.invoke(false)
        }
    }

    /**
     * Dispatches a swipe gesture from (x1, y1) to (x2, y2)
     */
    fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, callback: ((Boolean) -> Unit)? = null) {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(100))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                callback?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                callback?.invoke(false)
            }
        }, null)
    }

    data class TextScanDetails(
        val isFound: Boolean,
        val matchedFullText: String?,
        val coordinates: Rect?,
        val allDetectedTextsInRegion: List<String>
    )

    /**
     * Finds active root nodes belonging to the target app/game (ignoring AutoClicker's own overlay)
     */
    private fun getTargetAppRootNodes(): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        val myPkg = packageName ?: "com.akaa.autoclicker"

        // 1. Inspect non-autoclicker windows first
        try {
            windows?.forEach { win ->
                win.root?.let { r ->
                    if (r.packageName?.toString() != myPkg && !list.contains(r)) {
                        list.add(r)
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        // 2. Check rootInActiveWindow if not our own overlay
        rootInActiveWindow?.let { root ->
            if (root.packageName?.toString() != myPkg && !list.contains(root)) {
                list.add(0, root)
            }
        }

        // 3. Fallback to rootInActiveWindow if no other window found
        if (list.isEmpty()) {
            rootInActiveWindow?.let { list.add(it) }
        }
        return list
    }

    /**
     * Searches active window for matching text nodes and returns full inspection details
     */
    fun findTextWithDetails(targetText: String, exactMatch: Boolean, searchRegion: Rect? = null): TextScanDetails {
        val detectedList = LinkedHashSet<String>()
        var matchedCoords: Rect? = null
        var matchedString: String? = null
        val myPkg = packageName ?: "com.akaa.autoclicker"

        val rootNodes = getTargetAppRootNodes()
        if (rootNodes.isEmpty()) {
            return TextScanDetails(false, null, null, emptyList())
        }

        fun scanRecursively(node: AccessibilityNodeInfo) {
            // CRITICAL: Filter out our own AutoClicker overlay/studio package completely!
            if (node.packageName?.toString() == myPkg) {
                return
            }

            val nodeText = (node.text?.toString() ?: node.contentDescription?.toString())?.trim()
            if (!nodeText.isNullOrBlank() && node.isVisibleToUser) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    if (searchRegion == null || Rect.intersects(searchRegion, rect)) {
                        detectedList.add(nodeText)
                        if (matchedCoords == null && targetText.isNotBlank()) {
                            val isMatch = if (exactMatch) {
                                nodeText.trim().equals(targetText.trim(), ignoreCase = true)
                            } else {
                                nodeText.contains(targetText, ignoreCase = true)
                            }
                            if (isMatch) {
                                matchedCoords = rect
                                matchedString = nodeText
                            }
                        }
                    }
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                scanRecursively(child)
            }
        }

        try {
            for (root in rootNodes) {
                scanRecursively(root)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return TextScanDetails(
            isFound = matchedCoords != null,
            matchedFullText = matchedString,
            coordinates = matchedCoords,
            allDetectedTextsInRegion = detectedList.toList()
        )
    }

    /**
     * Searches active window for matching text nodes (optionally inside an inspection region)
     */
    fun findTextCoordinates(targetText: String, exactMatch: Boolean, searchRegion: Rect? = null): Rect? {
        if (targetText.isBlank()) return null
        val myPkg = packageName ?: "com.akaa.autoclicker"
        val rootNodes = getTargetAppRootNodes()
        if (rootNodes.isEmpty()) return null

        fun searchNodeRecursively(
            node: AccessibilityNodeInfo,
            targetText: String,
            exactMatch: Boolean,
            searchRegion: Rect?
        ): Rect? {
            // CRITICAL: Filter out our own AutoClicker overlay
            if (node.packageName?.toString() == myPkg) {
                return null
            }

            val nodeText = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
            val isMatch = if (exactMatch) {
                nodeText.trim().equals(targetText.trim(), ignoreCase = true)
            } else {
                nodeText.contains(targetText, ignoreCase = true)
            }

            if (isMatch && node.isVisibleToUser) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    if (searchRegion == null || Rect.intersects(searchRegion, rect)) {
                        return rect
                    }
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = searchNodeRecursively(child, targetText, exactMatch, searchRegion)
                if (found != null) {
                    return found
                }
            }
            return null
        }

        try {
            for (root in rootNodes) {
                val found = searchNodeRecursively(root, targetText, exactMatch, searchRegion)
                if (found != null) return found
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * Injects text directly into the currently focused or editable input field (appending by default)
     */
    fun typeTextIntoFocusedField(text: String, append: Boolean = true): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: rootNode.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: findFirstEditableNode(rootNode)

        if (focusedNode != null) {
            val currentText = if (append) (focusedNode.text?.toString() ?: "") else ""
            val fullText = currentText + text
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    fullText
                )
            }
            return focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
        return false
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val found = findFirstEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * Captures current screen bitmap using AccessibilityService API 30+
     */
    suspend fun captureScreen(): android.graphics.Bitmap? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hardwareBuffer = screenshot.hardwareBuffer
                            val colorSpace = screenshot.colorSpace
                            val hwBitmap = android.graphics.Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            val softBitmap = hwBitmap?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            hardwareBuffer.close()
                            executor.shutdown()
                            android.util.Log.d("AutoClicker", "Screenshot captured successfully: ${softBitmap?.width}x${softBitmap?.height}")
                            if (cont.isActive) cont.resumeWith(Result.success(softBitmap))
                        } catch (e: Exception) {
                            android.util.Log.e("AutoClicker", "Error processing screenshot buffer", e)
                            executor.shutdown()
                            if (cont.isActive) cont.resumeWith(Result.success(null))
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        android.util.Log.e("AutoClicker", "takeScreenshot failed with errorCode=$errorCode")
                        executor.shutdown()
                        if (cont.isActive) cont.resumeWith(Result.success(null))
                    }
                })
            } catch (e: Exception) {
                android.util.Log.e("AutoClicker", "Exception calling takeScreenshot", e)
                executor.shutdown()
                if (cont.isActive) cont.resumeWith(Result.success(null))
            }
        } else {
            if (cont.isActive) cont.resumeWith(Result.success(null))
        }
    }

    /**
     * Closes open apps and returns to the home screen (Emergency Kill Switch)
     */
     fun closeOpenAppsAndReturnHome() {
         android.util.Log.i("AutoClicker", "Triggering closeOpenAppsAndReturnHome()")
         performGlobalAction(GLOBAL_ACTION_RECENTS)
         
         val handler = android.os.Handler(android.os.Looper.getMainLooper())
         val clearAllIds = listOf(
             "com.sec.android.app.launcher:id/clear_all_button",
             "com.sec.android.app.launcher:id/button_clear_all",
             "com.sec.android.app.launcher:id/clear_all_text",
             "com.sec.android.app.launcher:id/clear_all",
             "com.android.systemui:id/clear_all",
             "com.android.systemui:id/button_clear_all",
             "com.android.systemui:id/clear_all_recents_image_button",
             "com.android.launcher3:id/clear_all_button",
             "com.google.android.apps.nexuslauncher:id/clear_all_button"
         )
         val clearAllTexts = listOf(
             "مسح الكل", "إغلاق الكل", "إغلاق جميع التطبيقات", "مسح الكل من التطبيقات",
             "Close all", "Clear all", "CLEAR ALL", "CLOSE ALL", "Clear All", "Close All"
         )

         fun tryFindAndClickClearAll(): Boolean {
             val roots = mutableListOf<AccessibilityNodeInfo>()
             rootInActiveWindow?.let { roots.add(it) }
             try {
                 windows?.forEach { win ->
                     win.root?.let { if (!roots.contains(it)) roots.add(it) }
                 }
             } catch (e: Exception) {
                 // ignore
             }

             for (root in roots) {
                 // 1. Check by resource ID
                 for (viewId in clearAllIds) {
                     try {
                         val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
                         if (!nodes.isNullOrEmpty()) {
                             for (node in nodes) {
                                 if (node.isVisibleToUser) {
                                     val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                                             (node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                                     val rect = Rect()
                                     node.getBoundsInScreen(rect)
                                     if (rect.width() > 0 && rect.height() > 0) {
                                         performClick(rect.centerX(), rect.centerY())
                                     }
                                     android.util.Log.i("AutoClicker", "Clear All clicked via viewId: $viewId")
                                     return true
                                 }
                             }
                         }
                     } catch (e: Exception) {
                         // continue
                     }
                 }

                 // 2. Check by text / description
                 for (text in clearAllTexts) {
                     try {
                         val nodes = root.findAccessibilityNodeInfosByText(text)
                         if (!nodes.isNullOrEmpty()) {
                             for (node in nodes) {
                                 if (node.isVisibleToUser) {
                                     val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                                             (node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                                     val rect = Rect()
                                     node.getBoundsInScreen(rect)
                                     if (rect.width() > 0 && rect.height() > 0) {
                                         performClick(rect.centerX(), rect.centerY())
                                     }
                                     android.util.Log.i("AutoClicker", "Clear All clicked via text: $text")
                                     return true
                                 }
                             }
                         }
                     } catch (e: Exception) {
                         // continue
                     }
                 }
             }
             return false
         }

         // Try after recents animation starts
         handler.postDelayed({
             val clicked = tryFindAndClickClearAll()
             if (!clicked) {
                 handler.postDelayed({
                     val retryClicked = tryFindAndClickClearAll()
                     if (!retryClicked) {
                         // Fallback return home after attempt
                         handler.postDelayed({
                             tryFindAndClickClearAll()
                             handler.postDelayed({
                                 performGlobalAction(GLOBAL_ACTION_HOME)
                             }, 400)
                         }, 400)
                     } else {
                         handler.postDelayed({
                             performGlobalAction(GLOBAL_ACTION_HOME)
                         }, 400)
                     }
                 }, 400)
             } else {
                 handler.postDelayed({
                     performGlobalAction(GLOBAL_ACTION_HOME)
                 }, 400)
             }
         }, 500)
     }
}
