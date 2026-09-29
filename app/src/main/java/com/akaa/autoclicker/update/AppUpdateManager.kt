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
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
import com.akaa.autoclicker.databinding.DialogAppUpdateBinding
import com.akaa.autoclicker.model.UpdateInfo
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

    sealed class UpdateResult {
        data class Available(val updateInfo: UpdateInfo, val isForce: Boolean) : UpdateResult()
        object UpToDate : UpdateResult()
        data class Error(val message: String) : UpdateResult()
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
                            onResult(UpdateResult.Available(updateInfo, isForce))
                        } else {
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
        checkForUpdates(activity) { result ->
            if (result is UpdateResult.Available) {
                showUpdateDialog(activity, result.updateInfo, result.isForce)
            }
        }
    }

    /**
     * Displays modern update dialog
     */
    fun showUpdateDialog(
        activity: Activity,
        updateInfo: UpdateInfo,
        isForce: Boolean,
        onDismiss: (() -> Unit)? = null
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val binding = DialogAppUpdateBinding.inflate(LayoutInflater.from(activity))
        val dialog = AlertDialog.Builder(activity)
            .setView(binding.root)
            .setCancelable(!isForce)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

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
            onDismiss?.invoke()
        }

        binding.btnUpdateNow.setOnClickListener {
            val downloadUrl = updateInfo.downloadUrl.trim()
            if (downloadUrl.endsWith(".apk", ignoreCase = true) || downloadUrl.contains("storage", ignoreCase = true)) {
                // Direct APK Download and in-app install
                startInAppDownload(activity, downloadUrl, binding, dialog)
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

        dialog.show()
    }

    private fun startInAppDownload(
        activity: Activity,
        downloadUrl: String,
        binding: DialogAppUpdateBinding,
        dialog: AlertDialog
    ) {
        binding.layoutDownloadProgress.visibility = View.VISIBLE
        binding.layoutUpdateActionButtons.visibility = View.GONE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val updatesDir = File(activity.getExternalFilesDir(null), "updates")
                if (!updatesDir.exists()) updatesDir.mkdirs()
                val apkFile = File(updatesDir, "app-update.apk")
                if (apkFile.exists()) apkFile.delete()

                val url = URL(downloadUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.connect()

                val fileLength = connection.contentLength
                val inputStream = connection.inputStream
                val outputStream = FileOutputStream(apkFile)

                val data = ByteArray(4096)
                var total: Long = 0
                var count: Int

                while (inputStream.read(data).also { count = it } != -1) {
                    total += count
                    outputStream.write(data, 0, count)

                    if (fileLength > 0) {
                        val progress = ((total * 100) / fileLength).toInt()
                        withContext(Dispatchers.Main) {
                            binding.pbDownloadProgress.progress = progress
                            binding.tvDownloadPercent.text = "$progress%"
                            binding.tvDownloadStatus.text = "جاري التحميل (${total / 1024 / 1024}MB / ${fileLength / 1024 / 1024}MB)..."
                        }
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                withContext(Dispatchers.Main) {
                    binding.tvDownloadStatus.text = "اكتمل التحميل ✓ جاري بدء التثبيت..."
                    dialog.dismiss()
                    installDownloadedApk(activity, apkFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                withContext(Dispatchers.Main) {
                    binding.layoutDownloadProgress.visibility = View.GONE
                    binding.layoutUpdateActionButtons.visibility = View.VISIBLE
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
            if (!apkFile.exists()) {
                Toast.makeText(context, "ملف التحديث غير موجود!", Toast.LENGTH_SHORT).show()
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

    private fun getAppVersionCode(context: Context): Int {
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

    private fun getAppVersionName(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "1.0"
        }
    }
}
