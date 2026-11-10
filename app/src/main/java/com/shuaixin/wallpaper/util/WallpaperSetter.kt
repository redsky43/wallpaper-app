package com.shuaixin.wallpaper.util

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class WallpaperTarget {
    HOME,
    LOCK,
    BOTH,
}

object WallpaperSetter {

    suspend fun apply(context: Context, bitmap: Bitmap, target: WallpaperTarget): Boolean =
        withContext(Dispatchers.IO) {
            val manager = WallpaperManager.getInstance(context)
            try {
                when (target) {
                    WallpaperTarget.HOME -> {
                        manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                    }
                    WallpaperTarget.LOCK -> {
                        manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
                    }
                    WallpaperTarget.BOTH -> {
                        manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                        manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
                    }
                }
                true
            } catch (_: Exception) {
                false
            }
        }
}
