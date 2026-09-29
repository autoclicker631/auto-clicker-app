package com.akaa.autoclicker.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.akaa.autoclicker.databinding.ViewTargetCrosshairBinding

class OverlayTouchPointer(
    private val context: Context,
    private val onCoordinateSelected: (Int, Int) -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var crosshairView: View? = null
    private var currentX = 500
    private var currentY = 1000

    fun show() {
        hide()
        val themedContext = androidx.appcompat.view.ContextThemeWrapper(context, com.akaa.autoclicker.R.style.Theme_AutoClicker)
        val binding = ViewTargetCrosshairBinding.inflate(LayoutInflater.from(themedContext))
        crosshairView = binding.root

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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        binding.ivCrosshairPointer.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_DOWN -> {
                    currentX = event.rawX.toInt()
                    currentY = event.rawY.toInt()
                    v.x = event.rawX - (v.width / 2f)
                    v.y = event.rawY - (v.height / 2f)
                    binding.tvCapturedCoordinates.text = "X: $currentX | Y: $currentY"
                    true
                }
                else -> false
            }
        }

        binding.btnConfirmCrosshair.setOnClickListener {
            onCoordinateSelected(currentX, currentY)
            hide()
        }

        binding.btnCancelCrosshair.setOnClickListener {
            hide()
        }

        windowManager.addView(crosshairView, params)
    }

    fun hide() {
        crosshairView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            crosshairView = null
        }
    }
}
