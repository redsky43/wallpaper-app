package com.shuaixin.wallpaper.data

data class WallpaperItem(
    val imageUrl: String,
    val artistName: String? = null,
    val artistUrl: String? = null,
    val sourceUrl: String? = null,
    val title: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val source: String = "",
) {
    val isPortrait: Boolean get() = height >= width
    val aspectRatio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 0.75f
}
