package com.poi.homelab.core.util

import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import androidx.core.content.ContextCompat

/** Android 13 起用 READ_MEDIA_IMAGES，更早版本用 READ_EXTERNAL_STORAGE。 */
val photoPermission: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

fun hasPhotoAccess(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, photoPermission) ==
        PackageManager.PERMISSION_GRANTED
