package com.akaa.autoclicker.model

import com.google.gson.annotations.SerializedName

/**
 * Data model representing remote update configuration.
 * Can be fetched from Firebase Remote Config, Firebase Storage, GitHub Releases, or custom server.
 */
data class UpdateInfo(
    @SerializedName("latestVersionCode")
    val latestVersionCode: Int = 1,

    @SerializedName("latestVersionName")
    val latestVersionName: String = "1.0",

    @SerializedName("minSupportedVersionCode")
    val minSupportedVersionCode: Int = 1,

    @SerializedName("isForceUpdate")
    val isForceUpdate: Boolean = false,

    @SerializedName("title")
    val title: String = "تحديث جديد متوفر 🚀",

    @SerializedName("releaseNotes")
    val releaseNotes: String = "تحسينات عامة وإصلاحات في الأداء.",

    @SerializedName("downloadUrl")
    val downloadUrl: String = "",

    @SerializedName("publishDate")
    val publishDate: String = ""
)
