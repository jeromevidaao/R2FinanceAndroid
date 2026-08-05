package com.cleaningbutton.r2finance.update

import kotlinx.serialization.Serializable

/** Published by CI to S3 next to the APK (`r2finance-builds/version.json`). */
@Serializable
data class RemoteAppVersion(
    val versionCode: Int,
    val versionName: String = "",
    val apkUrl: String,
    val releaseNotes: String = "",
    val minVersionCode: Int = 1,
)
