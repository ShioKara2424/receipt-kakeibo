package com.shiokara.receiptkakeibo.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

object Permissions {
    /** 写真へのアクセスに必要な権限 */
    val photoPermission: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    /** 起動時にまとめて求める権限 */
    val requested: Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(photoPermission, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(photoPermission)

    fun hasPhotoAccess(context: Context): Boolean = granted(context, photoPermission)

    /**
     * Android 14 以降で「選択した写真のみ許可」にされた状態。
     * この状態では新しく撮った写真を見られないので、「すべて許可」に変えてもらう必要がある。
     */
    fun hasOnlyPartialAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 34 && !hasPhotoAccess(context) &&
            granted(context, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
