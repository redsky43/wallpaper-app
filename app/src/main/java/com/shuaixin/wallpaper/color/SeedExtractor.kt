package com.shuaixin.wallpaper.color

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

private class HueBucket {
    var weight = 0.0
    private var rSum = 0.0
    private var gSum = 0.0
    private var bSum = 0.0

    fun add(w: Double, r: Double, g: Double, b: Double) {
        weight += w
        rSum += r * w
        gSum += g * w
        bSum += b * w
    }

    fun average(): Color? {
        if (weight <= 0.0) return null
        return Color(
            red = (rSum / weight).toFloat().coerceIn(0f, 1f),
            green = (gSum / weight).toFloat().coerceIn(0f, 1f),
            blue = (bSum / weight).toFloat().coerceIn(0f, 1f),
        )
    }
}

/**
 * 从图片里找出「最有代表性的一种颜色」当作整套配色的种子。
 *
 * 做法：缩到 64×64 像素 -> 逐个转 OKLCH -> 丢掉太灰/太黑/太白的 -> 按色相分成 12 个桶
 *       -> 中间调加权最高 -> 取权重最大的那一桶的平均色。
 *
 * 比直接取「出现最多的颜色」更稳：纯白背景、大面积黑线稿都不会把结果带偏。
 */
suspend fun extractSeedColor(bitmap: Bitmap): Color = withContext(Dispatchers.Default) {
    val side = 64
    val small = Bitmap.createScaledBitmap(bitmap, side, side, true)
    val pixels = IntArray(side * side)
    small.getPixels(pixels, 0, side, 0, 0, side, side)
    if (small !== bitmap) small.recycle()

    val buckets = Array(12) { HueBucket() }
    var total = 0.0

    for (px in pixels) {
        val alpha = (px ushr 24) and 0xFF
        if (alpha < 128) continue

        val rf = ((px shr 16) and 0xFF) / 255.0
        val gf = ((px shr 8) and 0xFF) / 255.0
        val bf = (px and 0xFF) / 255.0

        val o = rgbToOklch(rf, gf, bf)

        // 灰的、接近纯黑纯白的，都没什么「色彩性格」，不要
        if (o.c < 0.035) continue
        if (o.l < 0.14 || o.l > 0.96) continue

        // 中间调权重高，太暗太亮权重低
        val midWeight = 1.0 - abs(o.l - 0.58) / 0.58
        val w = o.c * midWeight.coerceIn(0.05, 1.0)

        val index = ((o.h % 360.0 + 360.0) % 360.0 / 30.0).toInt().coerceIn(0, 11)
        buckets[index].add(w, rf, gf, bf)
        total += w
    }

    val best = buckets.maxByOrNull { it.weight }

    // 整张图几乎没有彩色（黑白插画、纯风景照），给一个中性的灰紫，至少不会难看
    if (best == null || total < 0.5 || best.weight <= 0.0) {
        return@withContext Color(0xFF6E6A72)
    }

    best.average() ?: Color(0xFF6E6A72)
}
