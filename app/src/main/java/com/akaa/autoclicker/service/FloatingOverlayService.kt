package com.akaa.autoclicker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.akaa.autoclicker.R
import com.akaa.autoclicker.data.PreferencesManager
import com.akaa.autoclicker.databinding.ViewFloatingBubbleBinding
import com.akaa.autoclicker.engine.BatteryGuardManager
import com.akaa.autoclicker.engine.ScriptExecutorEngine
import com.akaa.autoclicker.ui.FloatingStudioOverlay
import com.akaa.autoclicker.ui.MainActivity
import com.akaa.autoclicker.ui.OverlayTouchPointer

class FloatingOverlayService : Service() {

    companion object {
        const val TAG = "AutoClicker"
        const val CHANNEL_ID = "channel_autoclicker_overlay"
        const val NOTIFICATION_ID = 1001

        var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var preferencesManager: PreferencesManager
    private var floatingView: View? = null
    private var binding: ViewFloatingBubbleBinding? = null

    private lateinit var scriptExecutor: ScriptExecutorEngine
    private lateinit var batteryGuard: BatteryGuardManager
    private var touchPointer: OverlayTouchPointer? = null
    private var floatingStudio: FloatingStudioOverlay? = null

    private var prefChangeListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "FloatingOverlayService onCreate() started")
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        preferencesManager = PreferencesManager(this)

        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildForegroundNotification())
            Log.i(TAG, "Foreground notification started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground notification", e)
        }

        initEngines()
        initFloatingOverlay()
        setupLiveConfigObserver()
    }

    private fun setupLiveConfigObserver() {
        val sharedPrefs = getSharedPreferences("autoclicker_prefs_v2", Context.MODE_PRIVATE)
        prefChangeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "key_current_script") {
                val updatedScript = preferencesManager.loadScriptConfig()
                Log.d(TAG, "Live reload triggered: ${updatedScript.rules.size} rules (${updatedScript.rules.count { it.isEnabled }} active)")
                scriptExecutor.updateScriptConfig(updatedScript)
            } else if (key == "key_battery_threshold" || key == "key_close_apps_low_battery") {
                val threshold = preferencesManager.getBatteryThreshold()
                val closeApps = preferencesManager.shouldCloseAppsOnLowBattery()
                batteryGuard.updateSettings(threshold, closeApps)
            }
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
    }

    private fun initEngines() {
        scriptExecutor = ScriptExecutorEngine(
            isHumanTouchEnabled = { preferencesManager.isHumanTouchEnabled() },
            onStatusUpdate = { statusMessage ->
                Log.d(TAG, "Engine Status: $statusMessage")
                binding?.tvFloatingStatus?.text = statusMessage
            }
        )

        batteryGuard = BatteryGuardManager(this) { batteryPct ->
            Log.w(TAG, "Battery guard triggered: $batteryPct%")
            scriptExecutor.stop()
            binding?.tvFloatingStatus?.text = "بطارية منخفضة ($batteryPct%)!"
            binding?.btnFloatingPlayPause?.setImageResource(R.drawable.ic_play)
            Toast.makeText(this, "حماية البطارية: تم إيقاف النقرات وإغلاق التطبيق ($batteryPct%)", Toast.LENGTH_LONG).show()
            
            // Close floating service
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                stopSelf()
            }, 500)
        }

        val threshold = preferencesManager.getBatteryThreshold()
        val closeApps = preferencesManager.shouldCloseAppsOnLowBattery()
        batteryGuard.startMonitoring(threshold, closeApps)

        val themedContext = androidx.appcompat.view.ContextThemeWrapper(this, R.style.Theme_AutoClicker)

        touchPointer = OverlayTouchPointer(themedContext) { x, y ->
            val script = preferencesManager.loadScriptConfig()
            if (script.rules.isNotEmpty()) {
                script.rules.last().clickX = x
                script.rules.last().clickY = y
                preferencesManager.saveScriptConfig(script)
                binding?.tvFloatingStatus?.text = "تم ضبط ($x, $y)"
                Log.d(TAG, "Coordinate saved from touch pointer: ($x, $y)")
            }
        }

        floatingStudio = FloatingStudioOverlay(themedContext) { newRule ->
            binding?.tvFloatingStatus?.text = "تمت إضافة '${newRule.name}' ✓"
            Log.i(TAG, "New rule created via FloatingStudio: ${newRule.name}")
        }
    }

    private fun initFloatingOverlay() {
        try {
            val themedContext = androidx.appcompat.view.ContextThemeWrapper(this, R.style.Theme_AutoClicker)
            binding = ViewFloatingBubbleBinding.inflate(LayoutInflater.from(themedContext))
            floatingView = binding?.root

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 400
            }

            setupDragListener(params)
            setupButtonListeners()

            windowManager.addView(floatingView, params)
            Log.i(TAG, "Floating control bar attached to WindowManager at (100, 400)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach floating overlay to window manager", e)
        }
    }

    private fun setupDragListener(params: WindowManager.LayoutParams) {
        binding?.ivFloatingDragHandle?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                when (event?.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        floatingView?.let { windowManager.updateViewLayout(it, params) }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun setupButtonListeners() {
        binding?.btnFloatingPlayPause?.setOnClickListener {
            if (!AutoClickerAccessibilityService.isServiceRunning) {
                Toast.makeText(this, "يرجى تفعيل خدمة إمكانية الوصول (Accessibility) من إعدادات الهاتف لتنفيذ النقرات!", Toast.LENGTH_LONG).show()
                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
                return@setOnClickListener
            }

            if (scriptExecutor.isRunning) {
                if (scriptExecutor.isPaused) {
                    Log.d(TAG, "Resuming script executor with latest rules")
                    val latestScript = preferencesManager.loadScriptConfig()
                    scriptExecutor.updateScriptConfig(latestScript)
                    scriptExecutor.resume()
                    batteryGuard.setScriptActive(true)
                    binding?.btnFloatingPlayPause?.setImageResource(R.drawable.ic_pause)
                } else {
                    Log.d(TAG, "Pausing script executor")
                    scriptExecutor.pause()
                    batteryGuard.setScriptActive(false)
                    binding?.btnFloatingPlayPause?.setImageResource(R.drawable.ic_play)
                }
            } else {
                val script = preferencesManager.loadScriptConfig()
                if (script.rules.isEmpty()) {
                    Toast.makeText(this, "لا توجد نقاط نقر! افتح الأداة 🎯 لإضافة نقاط ونقر على الشاشة", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                Log.d(TAG, "Starting script executor with ${script.rules.size} rules")
                batteryGuard.setScriptActive(true)
                scriptExecutor.start(script)
                binding?.btnFloatingPlayPause?.setImageResource(R.drawable.ic_pause)
            }
        }

        binding?.btnFloatingTargetPicker?.setOnClickListener {
            Log.d(TAG, "Opening FloatingStudioOverlay")
            floatingStudio?.show()
        }

        binding?.btnFloatingSettings?.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }

        binding?.btnFloatingClose?.setOnClickListener {
            Log.d(TAG, "Closing FloatingOverlayService")
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_running_desc)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_touch)
            .setContentTitle(getString(R.string.notification_running_title))
            .setContentText(getString(R.string.notification_running_desc))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "FloatingOverlayService onDestroy()")
        isRunning = false

        prefChangeListener?.let { listener ->
            val sharedPrefs = getSharedPreferences("autoclicker_prefs_v2", Context.MODE_PRIVATE)
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }

        scriptExecutor.stop()
        batteryGuard.stopMonitoring()
        touchPointer?.hide()
        floatingStudio?.hide()

        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing floatingView in onDestroy()", e)
            }
            floatingView = null
        }
    }
}
