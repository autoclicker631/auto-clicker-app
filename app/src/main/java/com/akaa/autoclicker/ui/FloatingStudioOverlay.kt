package com.akaa.autoclicker.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import com.akaa.autoclicker.R
import com.akaa.autoclicker.data.PreferencesManager
import com.akaa.autoclicker.databinding.DialogTargetConfigBinding
import com.akaa.autoclicker.databinding.ViewFloatingStudioBinding
import com.akaa.autoclicker.databinding.ViewStudioTargetPointBinding
import com.akaa.autoclicker.model.ActionType
import com.akaa.autoclicker.model.ConditionRule
import com.akaa.autoclicker.model.ConditionType
import com.akaa.autoclicker.model.RuleAction
import com.akaa.autoclicker.service.AutoClickerAccessibilityService
import com.akaa.autoclicker.utils.ImageMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

class FloatingStudioOverlay(
    private val context: Context,
    private val onRuleAdded: (ConditionRule) -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val preferencesManager = PreferencesManager(context)
    private var studioView: View? = null
    private var binding: ViewFloatingStudioBinding? = null

    // Inspection Region Coordinates
    private var regionLeft = 100
    private var regionTop = 300
    private var regionWidth = 240
    private var regionHeight = 140

    // Multi-Target Data structure
    private data class TargetItem(
        var action: RuleAction,
        val view: View,
        val itemBinding: ViewStudioTargetPointBinding
    )

    private val targetItems: MutableList<TargetItem> = mutableListOf()
    private var isBottomPanelExpanded = true
    private var currentlyEditingTarget: TargetItem? = null
    private var capturedImageBase64: String? = null
    private var isImageConditionMode = false

    private fun updateRegionBounds() {
        val regionBox = binding?.boxInspectionRegion ?: return
        val location = IntArray(2)
        regionBox.getLocationOnScreen(location)
        if (regionBox.width > 0 && regionBox.height > 0) {
            regionLeft = location[0].coerceAtLeast(0)
            regionTop = location[1].coerceAtLeast(0)
            regionWidth = regionBox.width
            regionHeight = regionBox.height
        }
    }

    fun show() {
        hide()
        try {
            val themedContext = ContextThemeWrapper(context, R.style.Theme_AutoClicker)
            binding = ViewFloatingStudioBinding.inflate(LayoutInflater.from(themedContext))
            studioView = binding?.root

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            targetItems.clear()
            setupRegionBox()
            setupBottomPanel()
            setupTopToolbar()
            setupConfigPopup()

            val currentScript = preferencesManager.loadScriptConfig()
            binding?.etStudioScriptName?.setText(currentScript.name)

            // Add default first target point near center-screen
            val displayMetrics = context.resources.displayMetrics
            val defaultX = displayMetrics.widthPixels / 2
            val defaultY = (displayMetrics.heightPixels * 0.6).toInt()
            addNewTargetPoint(defaultX, defaultY)

            windowManager.addView(studioView, params)

            binding?.boxInspectionRegion?.post {
                binding?.boxInspectionRegion?.x = regionLeft.toFloat()
                binding?.boxInspectionRegion?.y = regionTop.toFloat()
                updateRegionBounds()
            }

            Log.d("AutoClicker", "FloatingStudioOverlay displayed with multi-target & resizable region")
        } catch (e: Exception) {
            Log.e("AutoClicker", "Error displaying FloatingStudioOverlay", e)
        }
    }

    // ==========================================
    // 1. Resizable & Draggable Inspection Region
    // ==========================================
    private fun setupRegionBox() {
        val regionBox = binding?.boxInspectionRegion ?: return
        val resizeHandle = binding?.ivRegionResizeHandle ?: return

        // Drag Entire Region Box
        regionBox.setOnTouchListener(object : View.OnTouchListener {
            private var dX = 0f
            private var dY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dX = v.x - event.rawX
                        dY = v.y - event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val displayMetrics = context.resources.displayMetrics
                        val newX = (event.rawX + dX).coerceIn(0f, (displayMetrics.widthPixels - v.width).toFloat())
                        val newY = (event.rawY + dY).coerceIn(0f, (displayMetrics.heightPixels - v.height).toFloat())
                        v.x = newX
                        v.y = newY
                        updateRegionBounds()
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        updateRegionBounds()
                        return true
                    }
                }
                return false
            }
        })

        // Drag Bottom-End Handle to Resize Box
        resizeHandle.setOnTouchListener(object : View.OnTouchListener {
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var initialWidth = 0
            private var initialHeight = 0

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        initialWidth = regionBox.width
                        initialHeight = regionBox.height
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = (event.rawX - initialTouchX).toInt()
                        val deltaY = (event.rawY - initialTouchY).toInt()

                        val newW = max(100, initialWidth + deltaX)
                        val newH = max(60, initialHeight + deltaY)

                        val lp = regionBox.layoutParams
                        lp.width = newW
                        lp.height = newH
                        regionBox.layoutParams = lp

                        updateRegionBounds()
                        binding?.tvRegionDimensions?.text = "${regionWidth}x${regionHeight}"
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        updateRegionBounds()
                        return true
                    }
                }
                return false
            }
        })
    }

    // ==========================================
    // 2. Collapsible / Expandable Bottom Panel
    // ==========================================
    private fun setupBottomPanel() {
        binding?.layoutToggleBottomPanel?.setOnClickListener {
            toggleBottomPanel()
        }

        binding?.btnPeekFullScreen?.setOnClickListener {
            enterPeekMode()
        }

        binding?.layoutPeekRestorePill?.setOnClickListener {
            exitPeekMode()
        }

        binding?.rgStudioConditionMode?.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbModeImage) {
                isImageConditionMode = true
                binding?.rbModeImage?.setBackgroundResource(R.drawable.bg_pill_selected)
                binding?.rbModeImage?.setTextColor(context.getColor(R.color.white))
                binding?.rbModeAlwaysOrText?.setBackgroundResource(android.R.color.transparent)
                binding?.rbModeAlwaysOrText?.setTextColor(context.getColor(R.color.text_secondary))
                binding?.layoutStudioTextInputs?.visibility = View.GONE
                binding?.layoutStudioImageCapture?.visibility = View.VISIBLE
            } else {
                isImageConditionMode = false
                binding?.rbModeAlwaysOrText?.setBackgroundResource(R.drawable.bg_pill_selected)
                binding?.rbModeAlwaysOrText?.setTextColor(context.getColor(R.color.white))
                binding?.rbModeImage?.setBackgroundResource(android.R.color.transparent)
                binding?.rbModeImage?.setTextColor(context.getColor(R.color.text_secondary))
                binding?.layoutStudioTextInputs?.visibility = View.VISIBLE
                binding?.layoutStudioImageCapture?.visibility = View.GONE
            }
        }

        binding?.btnInspectVisibleTexts?.setOnClickListener {
            inspectVisibleTexts()
        }

        binding?.btnCaptureInspectionRegion?.setOnClickListener {
            captureConditionRegion()
        }

        binding?.btnTestCondition?.setOnClickListener {
            testCurrentCondition()
        }

        binding?.btnSaveStudioRule?.setOnClickListener {
            saveRule()
        }
    }

    private fun enterPeekMode() {
        binding?.layoutTopToolbar?.visibility = View.GONE
        binding?.layoutTopMinimizedPill?.visibility = View.GONE
        binding?.layoutBottomContainer?.visibility = View.GONE
        binding?.layoutPeekRestorePill?.visibility = View.VISIBLE
        Toast.makeText(context, "تم إخفاء القوائم للمعاينة. اضغط الزر السفلي للعودة", Toast.LENGTH_SHORT).show()
    }

    private fun exitPeekMode() {
        binding?.layoutTopToolbar?.visibility = View.VISIBLE
        binding?.layoutBottomContainer?.visibility = View.VISIBLE
        binding?.layoutPeekRestorePill?.visibility = View.GONE
    }

    private fun captureConditionRegion() {
        val accService = AutoClickerAccessibilityService.instance
        if (accService == null) {
            Toast.makeText(context, "يرجى تفعيل خدمة إمكانية الوصول أولاً لالتقاط الشاشة!", Toast.LENGTH_LONG).show()
            return
        }

        updateRegionBounds()
        val currentLeft = regionLeft
        val currentTop = regionTop
        val currentWidth = regionWidth
        val currentHeight = regionHeight

        CoroutineScope(Dispatchers.Main).launch {
            // Hide overlay briefly to take screenshot of the underlying app
            studioView?.visibility = View.INVISIBLE
            delay(160)

            val screenBitmap = accService.captureScreen()
            studioView?.visibility = View.VISIBLE

            if (screenBitmap != null) {
                val displayMetrics = context.resources.displayMetrics
                val scaleX = screenBitmap.width.toFloat() / displayMetrics.widthPixels.toFloat()
                val scaleY = screenBitmap.height.toFloat() / displayMetrics.heightPixels.toFloat()

                val safeLeft = (currentLeft * scaleX).toInt().coerceIn(0, screenBitmap.width - 1)
                val safeTop = (currentTop * scaleY).toInt().coerceIn(0, screenBitmap.height - 1)
                val safeWidth = (currentWidth * scaleX).toInt().coerceIn(10, screenBitmap.width - safeLeft)
                val safeHeight = (currentHeight * scaleY).toInt().coerceIn(10, screenBitmap.height - safeTop)

                val cropped = android.graphics.Bitmap.createBitmap(
                    screenBitmap,
                    safeLeft,
                    safeTop,
                    safeWidth,
                    safeHeight
                )

                capturedImageBase64 = ImageMatcher.bitmapToBase64(cropped)
                binding?.ivCapturedConditionPreview?.setImageBitmap(cropped)
                binding?.ivCapturedConditionPreview?.visibility = View.VISIBLE
                binding?.btnCaptureInspectionRegion?.text = "✓ تم التقاط صورة الشرط (إعادة التقاط)"
                Toast.makeText(context, "تم حفظ صورة منطقة الفحص كشرط بنجاح ✓", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "تعذر التقاط لقطة الشاشة، يرجى المحاولة ثانية", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleBottomPanel() {
        isBottomPanelExpanded = !isBottomPanelExpanded
        if (isBottomPanelExpanded) {
            binding?.layoutCollapsibleContent?.visibility = View.VISIBLE
            binding?.ivToggleChevron?.setImageResource(R.drawable.ic_chevron_down)
            binding?.tvToggleTitle?.text = "▼ إخفاء لوحة الإعدادات"
        } else {
            binding?.layoutCollapsibleContent?.visibility = View.GONE
            binding?.ivToggleChevron?.setImageResource(R.drawable.ic_chevron_up)
            binding?.tvToggleTitle?.text = "▲ إظهار الإعدادات وحفظ الشرط"
        }
    }

    // ==========================================
    // 3. Top Quick Toolbar & Actions
    // ==========================================
    private fun setupTopToolbar() {
        binding?.btnCloseStudio?.setOnClickListener {
            hide()
        }

        binding?.btnMinimizeTopBar?.setOnClickListener {
            binding?.layoutTopToolbar?.visibility = View.GONE
            binding?.layoutTopMinimizedPill?.visibility = View.VISIBLE
        }

        binding?.layoutTopMinimizedPill?.setOnClickListener {
            binding?.layoutTopToolbar?.visibility = View.VISIBLE
            binding?.layoutTopMinimizedPill?.visibility = View.GONE
        }

        binding?.btnQuickAddTarget?.setOnClickListener {
            val displayMetrics = context.resources.displayMetrics
            val x = (displayMetrics.widthPixels * 0.3 + (targetItems.size * 50) % 400).toInt()
            val y = (displayMetrics.heightPixels * 0.5 + (targetItems.size * 60) % 500).toInt()
            addNewTargetPoint(x, y)
            Toast.makeText(context, "تمت إضافة نقطة #${targetItems.size} (اضغط عليها لتعديلها)", Toast.LENGTH_SHORT).show()
        }

        binding?.btnQuickRemoveTarget?.setOnClickListener {
            if (targetItems.isNotEmpty()) {
                val lastItem = targetItems.removeAt(targetItems.size - 1)
                binding?.flTargetPointsContainer?.removeView(lastItem.view)
                updateTargetsUi()
                Toast.makeText(context, "تم حذف آخر نقطة", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==========================================
    // 4. Dynamic Draggable & Configurable Target Points
    // ==========================================
    private fun addNewTargetPoint(initialX: Int, initialY: Int) {
        val themedContext = ContextThemeWrapper(context, R.style.Theme_AutoClicker)
        val itemBinding = ViewStudioTargetPointBinding.inflate(LayoutInflater.from(themedContext))
        val targetView = itemBinding.root

        val action = RuleAction(
            actionType = ActionType.CLICK_COORDINATE,
            clickX = initialX,
            clickY = initialY,
            delayAfterMs = 300
        )

        val targetItem = TargetItem(action, targetView, itemBinding)
        targetItems.add(targetItem)

        val lp = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        targetView.layoutParams = lp
        targetView.layoutDirection = View.LAYOUT_DIRECTION_LTR

        // Set initial coordinates
        targetView.x = (initialX - 60).coerceAtLeast(0).toFloat()
        targetView.y = (initialY - 60).coerceAtLeast(0).toFloat()

        setupTargetPointTouch(targetItem)
        binding?.flTargetPointsContainer?.addView(targetView)
        updateTargetsUi()
    }

    private fun setupTargetPointTouch(item: TargetItem) {
        val targetView = item.view
        targetView.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0f
            private var startY = 0f
            private var dX = 0f
            private var dY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        dX = v.x - event.rawX
                        dY = v.y - event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val moveDist = abs(event.rawX - startX) + abs(event.rawY - startY)
                        if (moveDist > 12) {
                            isDragging = true
                            val displayMetrics = context.resources.displayMetrics
                            val maxX = (displayMetrics.widthPixels - v.width).toFloat()
                            val maxY = (displayMetrics.heightPixels - v.height).toFloat()
                            val newX = (event.rawX + dX).coerceIn(0f, maxX)
                            val newY = (event.rawY + dY).coerceIn(0f, maxY)
                            v.x = newX
                            v.y = newY

                            val centerX = (newX + v.width / 2f).toInt()
                            val centerY = (newY + v.height / 2f).toInt()
                            item.action.clickX = centerX
                            item.action.clickY = centerY
                            item.itemBinding.tvTargetCoords.text = "$centerX, $centerY"
                            updateSummaryText()
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            // Single tap detected -> Open configuration dialog for this target
                            openTargetConfigDialog(item)
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun updateTargetsUi() {
        for ((index, item) in targetItems.withIndex()) {
            val num = index + 1
            item.itemBinding.tvTargetNumber.text = "$num"
            item.itemBinding.tvTargetCoords.text = "${item.action.clickX}, ${item.action.clickY}"

            val actionDesc = when (item.action.actionType) {
                ActionType.CLICK_COORDINATE -> "👆 نقر (${item.action.delayAfterMs}ms)"
                ActionType.SWIPE -> {
                    if (item.action.clickX == item.action.swipeEndX && item.action.clickY == item.action.swipeEndY) {
                        "⏱️ ضغط مطول"
                    } else {
                        "👉 سحب (${item.action.swipeDurationMs}ms)"
                    }
                }
                ActionType.TYPE_TEXT -> "⌨️ '${item.action.textToType}'"
                ActionType.SEND_KEY_CODE -> "↵ زر Enter"
                ActionType.DELAY_ONLY -> "⏳ انتظار ${item.action.delayAfterMs}ms"
                else -> "👆 نقر"
            }
            item.itemBinding.tvTargetActionType.text = actionDesc
        }

        binding?.tvTargetPointsCount?.text = "${targetItems.size} نقاط"
        updateSummaryText()
    }

    private fun updateSummaryText() {
        val sb = StringBuilder("قائمة الإجراءات (${targetItems.size}): ")
        for ((idx, item) in targetItems.withIndex()) {
            val action = item.action
            val desc = when (action.actionType) {
                ActionType.CLICK_COORDINATE -> "نقر (${action.clickX}, ${action.clickY})"
                ActionType.SWIPE -> {
                    if (action.clickX == action.swipeEndX && action.clickY == action.swipeEndY) {
                        "ضغط مطول (${action.clickX}, ${action.clickY})"
                    } else {
                        "سحب (${action.clickX}, ${action.clickY} ➔ ${action.swipeEndX}, ${action.swipeEndY})"
                    }
                }
                ActionType.TYPE_TEXT -> "كتابة '${action.textToType}'"
                ActionType.SEND_KEY_CODE -> "زر كيبورد [Enter]"
                ActionType.DELAY_ONLY -> "تأخير ${action.delayAfterMs}ms"
                else -> "إجراء"
            }
            sb.append("${idx + 1}. $desc  ")
        }
        binding?.tvStudioActionsSummary?.text = sb.toString()
    }

    // ==========================================
    // 5. Target Point Configuration Modal
    // ==========================================
    private fun setupConfigPopup() {
        val popupBinding = DialogTargetConfigBinding.bind(binding!!.includeTargetConfig.root)

        popupBinding.rgActionType.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbActionTypeText -> {
                    popupBinding.etConfigTextToType.visibility = View.VISIBLE
                    popupBinding.layoutSwipeConfig.visibility = View.GONE
                }
                R.id.rbActionSwipe -> {
                    popupBinding.etConfigTextToType.visibility = View.GONE
                    popupBinding.layoutSwipeConfig.visibility = View.VISIBLE
                }
                else -> {
                    popupBinding.etConfigTextToType.visibility = View.GONE
                    popupBinding.layoutSwipeConfig.visibility = View.GONE
                }
            }
        }

        popupBinding.btnConfigConfirm.setOnClickListener {
            currentlyEditingTarget?.let { item ->
                when (popupBinding.rgActionType.checkedRadioButtonId) {
                    R.id.rbActionClick -> {
                        item.action.actionType = ActionType.CLICK_COORDINATE
                    }
                    R.id.rbActionSwipe -> {
                        item.action.actionType = ActionType.SWIPE
                        val distance = popupBinding.etSwipeDistance.text.toString().toIntOrNull() ?: 400
                        val duration = popupBinding.etSwipeDuration.text.toString().toLongOrNull() ?: 300L
                        item.action.swipeDurationMs = duration.coerceAtLeast(50L)

                        val startX = item.action.clickX
                        val startY = item.action.clickY
                        when (popupBinding.rgSwipeDirection.checkedRadioButtonId) {
                            R.id.rbSwipeUp -> {
                                item.action.swipeEndX = startX
                                item.action.swipeEndY = (startY - distance).coerceAtLeast(0)
                            }
                            R.id.rbSwipeDown -> {
                                item.action.swipeEndX = startX
                                item.action.swipeEndY = startY + distance
                            }
                            R.id.rbSwipeLeft -> {
                                item.action.swipeEndX = (startX - distance).coerceAtLeast(0)
                                item.action.swipeEndY = startY
                            }
                            R.id.rbSwipeRight -> {
                                item.action.swipeEndX = startX + distance
                                item.action.swipeEndY = startY
                            }
                            else -> {
                                item.action.swipeEndX = startX
                                item.action.swipeEndY = (startY - distance).coerceAtLeast(0)
                            }
                        }
                    }
                    R.id.rbActionLongPress -> {
                        item.action.actionType = ActionType.SWIPE
                        item.action.swipeEndX = item.action.clickX
                        item.action.swipeEndY = item.action.clickY
                        item.action.swipeDurationMs = 1000L
                    }
                    R.id.rbActionTypeText -> {
                        item.action.actionType = ActionType.TYPE_TEXT
                        item.action.textToType = popupBinding.etConfigTextToType.text.toString().trim()
                    }
                    R.id.rbActionDelay -> {
                        item.action.actionType = ActionType.DELAY_ONLY
                    }
                    else -> item.action.actionType = ActionType.CLICK_COORDINATE
                }

                val delayVal = popupBinding.etConfigDelayMs.text.toString().toLongOrNull() ?: 300L
                item.action.delayAfterMs = delayVal

                updateTargetsUi()
                binding?.flConfigPopupContainer?.visibility = View.GONE
                Toast.makeText(context, "تم حفظ إعداد النقطة ✓", Toast.LENGTH_SHORT).show()
            }
        }

        popupBinding.btnConfigDeleteTarget.setOnClickListener {
            currentlyEditingTarget?.let { item ->
                targetItems.remove(item)
                binding?.flTargetPointsContainer?.removeView(item.view)
                updateTargetsUi()
                binding?.flConfigPopupContainer?.visibility = View.GONE
                Toast.makeText(context, "تم حذف النقطة", Toast.LENGTH_SHORT).show()
            }
        }

        popupBinding.btnDelay100.setOnClickListener { popupBinding.etConfigDelayMs.setText("100") }
        popupBinding.btnDelay300.setOnClickListener { popupBinding.etConfigDelayMs.setText("300") }
        popupBinding.btnDelay500.setOnClickListener { popupBinding.etConfigDelayMs.setText("500") }
        popupBinding.btnDelay1000.setOnClickListener { popupBinding.etConfigDelayMs.setText("1000") }
        popupBinding.btnDelay2000.setOnClickListener { popupBinding.etConfigDelayMs.setText("2000") }

        popupBinding.btnConfigClose.setOnClickListener {
            binding?.flConfigPopupContainer?.visibility = View.GONE
        }

        binding?.flConfigPopupContainer?.setOnClickListener {
            binding?.flConfigPopupContainer?.visibility = View.GONE
        }
    }

    private fun openTargetConfigDialog(item: TargetItem) {
        currentlyEditingTarget = item
        val popupBinding = DialogTargetConfigBinding.bind(binding!!.includeTargetConfig.root)

        val index = targetItems.indexOf(item) + 1
        popupBinding.tvConfigTitle.text = "إعدادات النقطة #$index"
        popupBinding.tvConfigCoords.text = "(${item.action.clickX}, ${item.action.clickY})"
        popupBinding.etConfigDelayMs.setText(item.action.delayAfterMs.toString())
        popupBinding.etConfigTextToType.setText(item.action.textToType)

        when (item.action.actionType) {
            ActionType.CLICK_COORDINATE -> {
                popupBinding.rbActionClick.isChecked = true
                popupBinding.etConfigTextToType.visibility = View.GONE
                popupBinding.layoutSwipeConfig.visibility = View.GONE
            }
            ActionType.SWIPE -> {
                val isLongPress = (item.action.clickX == item.action.swipeEndX && item.action.clickY == item.action.swipeEndY)
                if (isLongPress) {
                    popupBinding.rbActionLongPress.isChecked = true
                    popupBinding.etConfigTextToType.visibility = View.GONE
                    popupBinding.layoutSwipeConfig.visibility = View.GONE
                } else {
                    popupBinding.rbActionSwipe.isChecked = true
                    popupBinding.etConfigTextToType.visibility = View.GONE
                    popupBinding.layoutSwipeConfig.visibility = View.VISIBLE

                    val dx = item.action.swipeEndX - item.action.clickX
                    val dy = item.action.swipeEndY - item.action.clickY
                    val distance: Int
                    if (Math.abs(dy) >= Math.abs(dx)) {
                        if (dy < 0) {
                            popupBinding.rbSwipeUp.isChecked = true
                            distance = -dy
                        } else {
                            popupBinding.rbSwipeDown.isChecked = true
                            distance = dy
                        }
                    } else {
                        if (dx < 0) {
                            popupBinding.rbSwipeLeft.isChecked = true
                            distance = -dx
                        } else {
                            popupBinding.rbSwipeRight.isChecked = true
                            distance = dx
                        }
                    }
                    popupBinding.etSwipeDistance.setText(distance.coerceAtLeast(50).toString())
                    popupBinding.etSwipeDuration.setText(item.action.swipeDurationMs.coerceAtLeast(100L).toString())
                }
            }
            ActionType.TYPE_TEXT -> {
                popupBinding.rbActionTypeText.isChecked = true
                popupBinding.etConfigTextToType.visibility = View.VISIBLE
                popupBinding.layoutSwipeConfig.visibility = View.GONE
            }
            ActionType.DELAY_ONLY -> {
                popupBinding.rbActionDelay.isChecked = true
                popupBinding.etConfigTextToType.visibility = View.GONE
                popupBinding.layoutSwipeConfig.visibility = View.GONE
            }
            else -> {
                popupBinding.rbActionClick.isChecked = true
                popupBinding.etConfigTextToType.visibility = View.GONE
                popupBinding.layoutSwipeConfig.visibility = View.GONE
            }
        }

        binding?.flConfigPopupContainer?.visibility = View.VISIBLE
    }

    // ==========================================
    // 6. Test Condition Right from Overlay
    // ==========================================
    private fun showInspectResultModal(icon: String, title: String, body: CharSequence, copyText: String? = null) {
        binding?.let { b ->
            b.tvInspectModalIcon.text = icon
            b.tvInspectModalTitle.text = title
            b.tvInspectModalBody.text = body

            if (!copyText.isNullOrBlank()) {
                b.btnInspectModalCopyFirst.visibility = View.VISIBLE
                b.btnInspectModalCopyFirst.text = "📋 استخدام '$copyText' كشرط"
                b.btnInspectModalCopyFirst.setOnClickListener {
                    b.etStudioConditionText.setText(copyText)
                    b.flInspectResultModalContainer.visibility = View.GONE
                    Toast.makeText(context, "تم وضع '$copyText' في حقل نص الشرط ✓", Toast.LENGTH_SHORT).show()
                }
            } else {
                b.btnInspectModalCopyFirst.visibility = View.GONE
            }

            b.btnInspectModalOk.setOnClickListener {
                b.flInspectResultModalContainer.visibility = View.GONE
            }
            b.btnInspectModalClose.setOnClickListener {
                b.flInspectResultModalContainer.visibility = View.GONE
            }
            b.flInspectResultModalContainer.setOnClickListener {
                b.flInspectResultModalContainer.visibility = View.GONE
            }

            b.flInspectResultModalContainer.visibility = View.VISIBLE
        }
    }

    private fun testCurrentCondition() {
        val accService = AutoClickerAccessibilityService.instance
        if (accService == null) {
            Toast.makeText(context, "يرجى تفعيل خدمة إمكانية الوصول (Accessibility) أولاً لتجربة الشرط!", Toast.LENGTH_LONG).show()
            return
        }

        updateRegionBounds()

        if (isImageConditionMode) {
            if (capturedImageBase64.isNullOrBlank()) {
                showInspectResultModal(
                    "⚠️",
                    "تنبيه فحص الصورة",
                    "يرجى التقاط صورة منطقة الفحص أولاً بالضغط على زر 📸 التقاط صورة قبل إجراء الاختبار!"
                )
                return
            }

            Toast.makeText(context, "جاري فحص وتجربة تطابق الصورة على الشاشة...", Toast.LENGTH_SHORT).show()

            val currentLeft = regionLeft
            val currentTop = regionTop
            val currentWidth = regionWidth
            val currentHeight = regionHeight

            CoroutineScope(Dispatchers.Main).launch {
                studioView?.visibility = View.INVISIBLE
                delay(180)

                val screenBitmap = accService.captureScreen()
                studioView?.visibility = View.VISIBLE

                if (screenBitmap != null) {
                    val displayMetrics = context.resources.displayMetrics
                    val scaleX = screenBitmap.width.toFloat() / displayMetrics.widthPixels.toFloat()
                    val scaleY = screenBitmap.height.toFloat() / displayMetrics.heightPixels.toFloat()

                    val safeLeft = (currentLeft * scaleX).toInt().coerceIn(0, screenBitmap.width - 1)
                    val safeTop = (currentTop * scaleY).toInt().coerceIn(0, screenBitmap.height - 1)
                    val safeWidth = (currentWidth * scaleX).toInt().coerceIn(10, screenBitmap.width - safeLeft)
                    val safeHeight = (currentHeight * scaleY).toInt().coerceIn(10, screenBitmap.height - safeTop)

                    val searchRegion = android.graphics.Rect(safeLeft, safeTop, safeLeft + safeWidth, safeTop + safeHeight)
                    val templateBitmap = ImageMatcher.base64ToBitmap(capturedImageBase64!!)
                    if (templateBitmap != null) {
                        val (isMatched, score) = ImageMatcher.computeSimilarityScore(screenBitmap, templateBitmap, searchRegion)
                        val simPercent = (score * 100).toInt()
                        if (isMatched) {
                            showInspectResultModal(
                                "✅",
                                "نجح اختبار الصورة",
                                "✅ تم العثور على صورة الشرط بنجاح!\n\n• نسبة التطابق: $simPercent%\n• الحد الأدنى المطلوب: 70%\n• الحالة: الصورة متطابقة وموجودة داخل منطقة الفحص."
                            )
                        } else {
                            showInspectResultModal(
                                "❌",
                                "فشل اختبار الصورة",
                                "❌ لم يتم العثور على الصورة المطلوبة!\n\n• نسبة التطابق الحالية: $simPercent%\n• الحد الأدنى المطلوب: 70%\n• الحالة: الصورة غير متطابقة في منطقة الفحص الحالية."
                            )
                        }
                    } else {
                        Toast.makeText(context, "تعذر قراءة صورة القالب الملتقطة", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "تعذر التقاط لقطة الشاشة للاختبار", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            val targetText = binding?.etStudioConditionText?.text?.toString()?.trim() ?: ""
            val exactMatch = binding?.cbExactMatch?.isChecked == true
            val matchTypeLabel = if (exactMatch) "تطابق تام (Exact)" else "تطابق جزئي (Contains)"

            val inspectionRect = android.graphics.Rect(regionLeft, regionTop, regionLeft + regionWidth, regionTop + regionHeight)

            if (targetText.isEmpty()) {
                val scanDetails = accService.findTextWithDetails("", false, inspectionRect)
                val detected = scanDetails.allDetectedTextsInRegion
                val info = if (detected.isNotEmpty()) {
                    val detectedListStr = detected.mapIndexed { i, t -> "${i + 1}. \"$t\"" }.joinToString("\n")
                    "\n\n👀 النصوص المرئية في هذا المربع (${detected.size}):\n$detectedListStr"
                } else ""
                showInspectResultModal(
                    "⚡",
                    "الشرط دائم (Always)",
                    "هذا الشرط بدون نص، لذلك سيعمل دائماً بدون شروط وينفذ جميع النقرات المحددة.$info",
                    copyText = detected.firstOrNull()
                )
            } else {
                val scanDetails = accService.findTextWithDetails(targetText, exactMatch, inspectionRect)

                if (scanDetails.isFound) {
                    val coords = scanDetails.coordinates!!
                    showInspectResultModal(
                        "✅",
                        "نجح اختبار النص ($matchTypeLabel)",
                        "✅ تم العثور على النص المطلوب بنجاح!\n\n• الكلمة المستهدفة: '$targetText'\n• النص المكتشف على الشاشة: \"${scanDetails.matchedFullText}\"\n• المكان: X: ${coords.centerX()} | Y: ${coords.centerY()}\n• نوع المطابقة: $matchTypeLabel"
                    )
                } else {
                    val detected = scanDetails.allDetectedTextsInRegion
                    if (detected.isNotEmpty()) {
                        val detectedListStr = detected.mapIndexed { i, t -> "${i + 1}. \"$t\"" }.joinToString("\n")
                        showInspectResultModal(
                            "❌",
                            "لم يتم العثور على '$targetText'",
                            "❌ لم يتم العثور على '$targetText' ($matchTypeLabel)!\n\n👀 النصوص التي يراها التطبيق حالياً داخل هذا المربع (${detected.size}):\n$detectedListStr\n\n💡 يمكنك نسخ أي نص منها أو الضغط بالأسفل لاستخدامه مباشرة.",
                            copyText = detected.firstOrNull()
                        )
                    } else {
                        // No Accessibility texts found - try OCR
                        CoroutineScope(Dispatchers.Main).launch {
                            studioView?.visibility = View.INVISIBLE
                            delay(160)
                            val ocrResult = accService.findTextWithOCR(targetText, exactMatch, inspectionRect)
                            studioView?.visibility = View.VISIBLE
                            if (ocrResult.isFound) {
                                val coords = ocrResult.coordinates!!
                                showInspectResultModal(
                                    "✅",
                                    "نجح اختبار النص عبر OCR ($matchTypeLabel)",
                                    "✅ تم العثور على النص عبر تحليل الصورة (OCR)!\n\n• الكلمة: '$targetText'\n• النص المكتشف: \"${ocrResult.matchedFullText}\"\n• المكان: X: ${coords.centerX()} | Y: ${coords.centerY()}\n• طريقة الاكتشاف: 🎮 OCR (تحليل صورة الشاشة)"
                                )
                            } else {
                                val ocrTexts = ocrResult.allDetectedTextsInRegion
                                if (ocrTexts.isNotEmpty()) {
                                    val listStr = ocrTexts.mapIndexed { i, t -> "${i + 1}. \"$t\"" }.joinToString("\n")
                                    showInspectResultModal(
                                        "❌",
                                        "لم يتم العثور على '$targetText'",
                                        "❌ لم يتم العثور على '$targetText' ($matchTypeLabel)!\n\n🎮 النصوص المكتشفة عبر OCR (تحليل الصورة):\n$listStr\n\n💡 يمكنك نسخ نص منها.",
                                        copyText = ocrTexts.firstOrNull()
                                    )
                                } else {
                                    showInspectResultModal(
                                        "⚠️",
                                        "لم يتم اكتشاف نصوص",
                                        "❌ لم يتم العثور على أي نصوص (لا عادية ولا OCR).\n\n💡 استخدم '📸 فحص صورة' أو '🎮 نقر مباشر'."
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun inspectVisibleTexts() {
        val accService = AutoClickerAccessibilityService.instance
        if (accService == null) {
            Toast.makeText(context, "يرجى تفعيل خدمة إمكانية الوصول أولاً!", Toast.LENGTH_SHORT).show()
            return
        }

        updateRegionBounds()
        val inspectionRect = android.graphics.Rect(regionLeft, regionTop, regionLeft + regionWidth, regionTop + regionHeight)
        val scanDetails = accService.findTextWithDetails("", false, inspectionRect)
        val texts = scanDetails.allDetectedTextsInRegion

        if (texts.isNotEmpty()) {
            val listStr = texts.mapIndexed { i, t -> "${i + 1}. \"$t\"" }.joinToString("\n")
            showInspectResultModal(
                "👀",
                "النصوص المكتشفة في منطقة الفحص (${texts.size})",
                "النصوص التي يراها التطبيق حالياً داخل هذا المربع:\n\n$listStr\n\n💡 يمكنك الضغط بالأسفل لنسخ النص واستخدامه كشرط فوراً.",
                copyText = texts.firstOrNull()
            )
        } else {
            // Try OCR fallback for games
            Toast.makeText(context, "لم يتم العثور على نصوص عادية. جاري فحص OCR للألعاب...", Toast.LENGTH_SHORT).show()
            CoroutineScope(Dispatchers.Main).launch {
                studioView?.visibility = View.INVISIBLE
                delay(160)
                val ocrResult = accService.findTextWithOCR("", false, inspectionRect)
                studioView?.visibility = View.VISIBLE
                val ocrTexts = ocrResult.allDetectedTextsInRegion
                if (ocrTexts.isNotEmpty()) {
                    val listStr = ocrTexts.mapIndexed { i, t -> "${i + 1}. \"$t\"" }.joinToString("\n")
                    showInspectResultModal(
                        "🎮",
                        "نصوص مكتشفة بـ OCR (تعرف الصورة) (${ocrTexts.size})",
                        "🎮 تم اكتشاف النصوص التالية عبر تحليل الصورة (OCR):\n\n$listStr\n\n💡 هذه النصوص مرسومة كصورة (مثل ألعاب فورتنايت). يمكنك استخدامها كشرط.",
                        copyText = ocrTexts.firstOrNull()
                    )
                } else {
                    showInspectResultModal(
                        "⚠️",
                        "ماذا يرى التطبيق؟",
                        "⚠️ لا توجد أي نصوص في هذا المربع (لا نصوص عادية ولا OCR).\n\n💡 استخدم '📸 فحص صورة' أو '🎮 نقر مباشر' للألعاب."
                    )
                }
            }
        }
    }

    // ==========================================
    // 7. Save Complete Rule & Actions
    // ==========================================
    private fun saveRule() {
        if (targetItems.isEmpty()) {
            Toast.makeText(context, "يرجى إضافة نقطة نقر واحدة على الأقل!", Toast.LENGTH_SHORT).show()
            return
        }

        updateRegionBounds()

        val scriptNameInput = binding?.etStudioScriptName?.text?.toString()?.trim() ?: ""
        val ruleNameInput = binding?.etStudioRuleName?.text?.toString()?.trim() ?: ""
        val condTextInput = binding?.etStudioConditionText?.text?.toString()?.trim() ?: ""
        val exactMatch = binding?.cbExactMatch?.isChecked == true

        val conditionType = if (isImageConditionMode && !capturedImageBase64.isNullOrBlank()) {
            ConditionType.IMAGE_EXISTS
        } else if (condTextInput.isNotEmpty()) {
            ConditionType.TEXT_EXISTS
        } else {
            ConditionType.ALWAYS
        }

        val firstTarget = targetItems.first().action
        val ruleName = if (ruleNameInput.isNotEmpty()) {
            ruleNameInput
        } else if (conditionType == ConditionType.IMAGE_EXISTS) {
            "فحص تطابق صورة (${targetItems.size} إجراءات)"
        } else if (condTextInput.isNotEmpty()) {
            "فحص '$condTextInput' بالمنطقة (${targetItems.size} إجراءات)"
        } else {
            "تسلسل نقر (${targetItems.size} نقاط)"
        }

        val newRule = ConditionRule(
            name = ruleName,
            conditionType = conditionType,
            targetText = condTextInput,
            matchExact = exactMatch,
            targetImageBase64 = if (conditionType == ConditionType.IMAGE_EXISTS) capturedImageBase64 else null,
            regionLeft = regionLeft,
            regionTop = regionTop,
            regionRight = regionLeft + regionWidth,
            regionBottom = regionTop + regionHeight,
            clickX = firstTarget.clickX,
            clickY = firstTarget.clickY
        )

        for (item in targetItems) {
            newRule.actions.add(item.action.copy())
        }

        val currentScript = preferencesManager.loadScriptConfig()
        if (scriptNameInput.isNotEmpty()) {
            currentScript.name = scriptNameInput
        }
        currentScript.rules.add(newRule)
        preferencesManager.saveScriptConfig(currentScript)
        
        // Also save to profiles library so user can restore it anytime
        preferencesManager.saveProfile(currentScript)

        onRuleAdded(newRule)
        Toast.makeText(context, "تم حفظ '${currentScript.name}' مع الشرط بنجاح! ✓ (يمكنك استعادته دائماً من الملفات المحفوظة)", Toast.LENGTH_LONG).show()
        hide()
    }

    fun hide() {
        studioView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e("AutoClicker", "Error removing FloatingStudioOverlay", e)
            }
            studioView = null
            binding = null
            targetItems.clear()
        }
    }
}
