package com.akaa.autoclicker.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
import com.akaa.autoclicker.databinding.DialogAppUpdateBinding
import com.akaa.autoclicker.model.UpdateInfo
import com.akaa.autoclicker.service.FloatingOverlayService
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"

    // Default remote configuration URL (Raw GitHub JSON from repository)
    var updateConfigUrl: String = "https://raw.githubusercontent.com/autoclicker631/auto-clicker-app/main/version.json"

    // Track active force update state
    var isForceUpdateActive: Boolean = false
        private set
    var activeForceUpdateInfo: UpdateInfo? = null
        private set

    private var currentDialog: AlertDialog? = null

    sealed class UpdateResult {
        data class Available(val updateInfo: UpdateInfo, val isForce: Boolean) : UpdateResult()
        object UpToDate : UpdateResult()
        data class Error(val message: String) : UpdateResult()
    }

    /**
     * Returns file pointer to the update APK for a specific version
     */
    fun getUpdateApkFile(context: Context, versionCode: Int): File {
        val updatesDir = File(context.getExternalFilesDir(null), "updates")
        if (!updatesDir.exists()) updatesDir.mkdirs()
        return File(updatesDir, "AutoClicker-v$versionCode.apk")
    }

    /**
     * Checks if the APK for the specified version code is already downloaded and is a valid package
     */
    fun isApkDownloadedAndValid(context: Context, versionCode: Int): Boolean {
        val apkFile = getUpdateApkFile(context, versionCode)
        if (!apkFile.exists() || apkFile.length() <= 0) return false
        return try {
            val archiveInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            archiveInfo != null && archiveInfo.packageName == context.packageName
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Cleans up all old downloaded APK files to free up storage space
     */
    fun cleanupOldUpdates(context: Context) {
        try {
            val updatesDir = File(context.getExternalFilesDir(null), "updates")
            if (updatesDir.exists() && updatesDir.isDirectory) {
                val currentVersionCode = getAppVersionCode(context)
                updatesDir.listFiles()?.forEach { file ->
                    if (file.name.endsWith(".apk", ignoreCase = true) || file.name.endsWith(".tmp", ignoreCase = true)) {
                        try {
                            if (file.name.endsWith(".tmp", ignoreCase = true)) {
                                file.delete()
                                Log.d(TAG, "Deleted incomplete tmp file: ${file.name}")
                            } else {
                                val archiveInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                                val apkVersionCode = if (archiveInfo != null) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                        archiveInfo.longVersionCode.toInt()
                                    } else {
                                        @Suppress("DEPRECATION")
                                        archiveInfo.versionCode
                                    }
                                } else 0

                                // If user is already on or past this version, or file is invalid, delete it
                                if (currentVersionCode >= apkVersionCode || archiveInfo == null) {
                                    file.delete()
                                    Log.d(TAG, "Cleaned up update file: ${file.name}")
                                }
                            }
                        } catch (e: Exception) {
                            file.delete()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old updates", e)
        }
    }

    /**
     * Checks remote server for available updates asynchronously
     */
    fun checkForUpdates(context: Context, customUrl: String? = null, onResult: (UpdateResult) -> Unit) {
        val targetUrl = customUrl ?: updateConfigUrl

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentVersionCode = getAppVersionCode(context)
                val updateInfo = fetchRemoteUpdateInfo(targetUrl)

                withContext(Dispatchers.Main) {
                    if (updateInfo != null) {
                        if (updateInfo.latestVersionCode > currentVersionCode) {
                            val isForce = updateInfo.isForceUpdate ||
                                    (currentVersionCode < updateInfo.minSupportedVersionCode)

                            if (isForce) {
                                isForceUpdateActive = true
                                activeForceUpdateInfo = updateInfo

                                // Stop floating service immediately if running
                                if (FloatingOverlayService.isRunning) {
                                    try {
                                        val serviceIntent = Intent(context, FloatingOverlayService::class.java)
                                        context.stopService(serviceIntent)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error stopping floating service during force update", e)
                                    }
                                }
                            } else {
                                isForceUpdateActive = false
                                activeForceUpdateInfo = null
                            }

                            onResult(UpdateResult.Available(updateInfo, isForce))
                        } else {
                            isForceUpdateActive = false
                            activeForceUpdateInfo = null
                            cleanupOldUpdates(context)
                            onResult(UpdateResult.UpToDate)
                        }
                    } else {
                        onResult(UpdateResult.Error("تعذر قراءة بيانات التحديث من الخادم"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for updates", e)
                withContext(Dispatchers.Main) {
                    onResult(UpdateResult.Error("خطأ في الاتصال: ${e.localizedMessage}"))
                }
            }
        }
    }

    /**
     * Helper to display update dialog automatically on launch if an update exists
     */
    fun checkAndPromptOnLaunch(activity: Activity) {
        cleanupOldUpdates(activity)
        checkForUpdates(activity) { result ->
            if (result is UpdateResult.Available) {
                showUpdateDialog(activity, result.updateInfo, result.isForce)
            }
        }
    }

    /**
     * Displays modern update dialog with support for resume/cached APK installation
     */
    fun showUpdateDialog(
        activity: Activity,
        updateInfo: UpdateInfo,
        isForce: Boolean,
        onDismiss: (() -> Unit)? = null
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        if (isForce) {
            isForceUpdateActive = true
            activeForceUpdateInfo = updateInfo

            // Ensure floating service is stopped
            if (FloatingOverlayService.isRunning) {
                try {
                    val serviceIntent = Intent(activity, FloatingOverlayService::class.java)
                    activity.stopService(serviceIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping floating service", e)
                }
            }
        }

        // If current dialog is already showing for the same version and force mode, don't recreate
        if (currentDialog?.isShowing == true) {
            return
        }

        val binding = DialogAppUpdateBinding.inflate(LayoutInflater.from(activity))
        val dialog = AlertDialog.Builder(activity)
            .setView(binding.root)
            .setCancelable(!isForce)
            .create()

        currentDialog = dialog
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(!isForce)

        if (isForce) {
            dialog.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    activity.finishAffinity()
                    true
                } else {
                    false
                }
            }
        }

        val currentVersionName = getAppVersionName(activity)
        binding.tvUpdateDialogTitle.text = updateInfo.title
        binding.tvUpdateVersionComparison.text = "الإصدار الحالي: v$currentVersionName  ➔  الجديد: v${updateInfo.latestVersionName}"
        binding.tvReleaseNotes.text = updateInfo.releaseNotes

        if (isForce) {
            binding.tvUpdateBadge.text = "🔴 تحديث إجباري"
            binding.tvUpdateBadge.setBackgroundColor(Color.parseColor("#B91C1C"))
            binding.btnUpdateLater.visibility = View.GONE
        } else {
            binding.tvUpdateBadge.text = "🟢 تحديث اختياري"
            binding.tvUpdateBadge.setBackgroundColor(Color.parseColor("#047857"))
            binding.btnUpdateLater.visibility = View.VISIBLE
        }

        binding.btnUpdateLater.setOnClickListener {
            dialog.dismiss()
            currentDialog = null
            onDismiss?.invoke()
        }

        // Check if APK is already downloaded
        val apkFile = getUpdateApkFile(activity, updateInfo.latestVersionCode)
        val isAlreadyDownloaded = isApkDownloadedAndValid(activity, updateInfo.latestVersionCode)

        if (isAlreadyDownloaded) {
            binding.btnUpdateNow.text = "تثبيت التحديث الآن 🚀"
            binding.layoutDownloadProgress.visibility = View.GONE
            binding.layoutUpdateActionButtons.visibility = View.VISIBLE
        } else {
            binding.btnUpdateNow.text = "تحديث وتثبيت الآن 🚀"
            binding.layoutDownloadProgress.visibility = View.GONE
            binding.layoutUpdateActionButtons.visibility = View.VISIBLE
        }

        binding.btnUpdateNow.setOnClickListener {
            val downloadUrl = updateInfo.downloadUrl.trim()

            // If APK already downloaded and valid, install immediately without re-downloading!
            if (isApkDownloadedAndValid(activity, updateInfo.latestVersionCode)) {
                installDownloadedApk(activity, apkFile)
                if (!isForce) {
                    dialog.dismiss()
                    currentDialog = null
                }
                return@setOnClickListener
            }

            if (downloadUrl.endsWith(".apk", ignoreCase = true) || downloadUrl.contains("storage", ignoreCase = true)) {
                // Direct APK Download and in-app install
                startInAppDownload(activity, updateInfo, binding, dialog, isForce)
            } else if (downloadUrl.isNotBlank()) {
                // Open external download page / GitHub release in browser
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                    activity.startActivity(browserIntent)
                } catch (e: Exception) {
                    Toast.makeText(activity, "تعذر فتح الرابط: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(activity, "رابط التحميل غير متوفر حالياً!", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.setOnDismissListener {
            if (currentDialog == dialog) {
                currentDialog = null
            }
        }

        dialog.show()
    }

    private fun startInAppDownload(
        activity: Activity,
        updateInfo: UpdateInfo,
        binding: DialogAppUpdateBinding,
        dialog: AlertDialog,
        isForce: Boolean
    ) {
        binding.layoutDownloadProgress.visibility = View.VISIBLE
        binding.layoutUpdateActionButtons.visibility = View.GONE
        binding.pbDownloadProgress.progress = 0
        binding.tvDownloadPercent.text = "0%"
        binding.tvDownloadStatus.text = "جاري الاتصال بالخادم..."

        CoroutineScope(Dispatchers.IO).launch {
            val apkFile = getUpdateApkFile(activity, updateInfo.latestVersionCode)
            val tempFile = File(apkFile.parentFile, "${apkFile.name}.tmp")

            try {
                if (tempFile.exists()) tempFile.delete()
                if (apkFile.exists()) apkFile.delete()

                val url = URL(updateInfo.downloadUrl.trim())
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.connect()

                val fileLength = connection.contentLength
                val inputStream = connection.inputStream
                val outputStream = FileOutputStream(tempFile)

                val data = ByteArray(8192)
                var total: Long = 0
                var count: Int

                while (inputStream.read(data).also { count = it } != -1) {
                    total += count
                    outputStream.write(data, 0, count)

                    if (fileLength > 0) {
                        val progress = ((total * 100) / fileLength).toInt().coerceIn(0, 100)
                        withContext(Dispatchers.Main) {
                            binding.pbDownloadProgress.progress = progress
                            binding.tvDownloadPercent.text = "$progress%"
                            val totalMb = String.format("%.1f", total.toDouble() / (1024 * 1024))
                            val lengthMb = String.format("%.1f", fileLength.toDouble() / (1024 * 1024))
                            binding.tvDownloadStatus.text = "جاري التحميل ($totalMb MB / $lengthMb MB)..."
                        }
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                // Rename temp file to final APK name atomically
                tempFile.renameTo(apkFile)

                withContext(Dispatchers.Main) {
                    binding.tvDownloadStatus.text = "اكتمل التحميل ✓ جاري بدء التثبيت..."
                    binding.btnUpdateNow.text = "تثبيت التحديث الآن 🚀"
                    binding.layoutDownloadProgress.visibility = View.GONE
                    binding.layoutUpdateActionButtons.visibility = View.VISIBLE

                    if (!isForce) {
                        dialog.dismiss()
                        currentDialog = null
                    }

                    installDownloadedApk(activity, apkFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                if (tempFile.exists()) tempFile.delete()
                withContext(Dispatchers.Main) {
                    binding.layoutDownloadProgress.visibility = View.GONE
                    binding.layoutUpdateActionButtons.visibility = View.VISIBLE
                    binding.btnUpdateNow.text = "إعادة المحاولة 🔄"
                    Toast.makeText(activity, "فشل التحميل: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Triggers Android package installer for downloaded APK file
     */
    fun installDownloadedApk(context: Context, apkFile: File) {
        try {
            if (!apkFile.exists() || apkFile.length() == 0L) {
                Toast.makeText(context, "ملف التحديث غير موجود أو تالف!", Toast.LENGTH_SHORT).show()
                return
            }

            // Check Unknown Sources permission on Android 8.0+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(context, "يرجى السماح بتثبيت التطبيقات من هذا المصدر للمتابعة", Toast.LENGTH_LONG).show()
                    val permissionIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(permissionIntent)
                    return
                }
            }

            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }

            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            Toast.makeText(context, "تعذر فتح مثبت التطبيقات: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun fetchRemoteUpdateInfo(urlString: String): UpdateInfo? {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.connect()

        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
            return Gson().fromJson(jsonString, UpdateInfo::class.java)
        }
        return null
    }

    fun getAppVersionCode(context: Context): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: PackageManager.NameNotFoundException) {
            1
        }
    }

    fun getAppVersionName(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "1.0"
        }
    }
}

