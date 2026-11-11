package com.shuaixin.wallpaper.color

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OKLCH 色彩空间。
 *
 * 为什么不用 HSL：HSL 里相同的 L 值在不同色相上看起来明暗完全不同（蓝和黄的 L=50% 差很多）。
 * OKLCH 是感知均匀的，和 Material 3 内部用的 HCT 一样，保证「同一个 tone 值 = 同样的视觉明度」。
 */
data class Oklch(val l: Double, val c: Double, val h: Double)

private fun srgbToLinear(v: Double): Double =
    if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

private fun linearToSrgb(v: Double): Double =
    if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1.0 / 2.4) - 0.055

fun rgbToOklch(r: Double, g: Double, b: Double): Oklch {
    val lr = srgbToLinear(r)
    val lg = srgbToLinear(g)
    val lb = srgbToLinear(b)

    val l = 0.4122214708 * lr + 0.5363325363 * lg + 0.0514459929 * lb
    val m = 0.2119034982 * lr + 0.6806995451 * lg + 0.1073969566 * lb
    val s = 0.0883024619 * lr + 0.2817188376 * lg + 0.6299787005 * lb

    val l_ = cbrt(l)
    val m_ = cbrt(m)
    val s_ = cbrt(s)

    val okL = 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_
    val okA = 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_
    val okB = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_

    val chroma = sqrt(okA * okA + okB * okB)
    var hue = Math.toDegrees(atan2(okB, okA))
    if (hue < 0) hue += 360.0

    return Oklch(okL, chroma, hue)
}

private fun oklchToLinearRgb(l: Double, c: Double, h: Double): Triple<Double, Double, Double> {
    val rad = Math.toRadians(h)
    val a = c * cos(rad)
    val b = c * sin(rad)

    val l_ = l + 0.3963377774 * a + 0.2158037573 * b
    val m_ = l - 0.1055613458 * a - 0.0638541728 * b
    val s_ = l - 0.0894841775 * a - 1.2914855480 * b

    val ll = l_ * l_ * l_
    val mm = m_ * m_ * m_
    val ss = s_ * s_ * s_

    return Triple(
        4.0767416621 * ll - 3.3077115913 * mm + 0.2309699292 * ss,
        -1.2684380046 * ll + 2.6097574011 * mm - 0.3413193965 * ss,
        -0.0041960863 * ll - 0.7034186147 * mm + 1.7076147010 * ss,
    )
}

fun oklchToColor(o: Oklch): Color {
    val (lr, lg, lb) = oklchToLinearRgb(
        o.l.coerceIn(0.0, 1.0),
        o.c.coerceAtLeast(0.0),
        o.h,
    )
    val r = linearToSrgb(lr.coerceIn(0.0, 1.0))
    val g = linearToSrgb(lg.coerceIn(0.0, 1.0))
    val b = linearToSrgb(lb.coerceIn(0.0, 1.0))
    return Color(
        red = r.toFloat().coerceIn(0f, 1f),
        green = g.toFloat().coerceIn(0f, 1f),
        blue = b.toFloat().coerceIn(0f, 1f),
    )
}

fun Color.toOklch(): Oklch = rgbToOklch(
    red.toDouble(),
    green.toDouble(),
    blue.toDouble(),
)

/** 判断某个 OKLCH 值是否落在 sRGB 色域内（避免调出来一个屏幕上显示不出的颜色）。 */
private fun inGamut(l: Double, c: Double, h: Double): Boolean {
    val (r, g, b) = oklchToLinearRgb(l, c, h)
    val eps = 0.0001
    return r in -eps..(1 + eps) && g in -eps..(1 + eps) && b in -eps..(1 + eps)
}

/**
 * Material 3 的 tone 是 CIELAB 的 L*（0=纯黑，100=纯白）。
 * 这里换算成 OKLab 的 L，保证 0~100 的档位观感和 MD3 一致。
 */
private fun toneToOklabL(tone: Double): Double = ((tone + 16.0) / 116.0).coerceIn(0.0, 1.0)

/**
 * 在一个色相上取指定 tone 的颜色；若超出色域就自动降低彩度，直到能显示为止。
 */
private fun paletteColor(hue: Double, chroma: Double, tone: Double): Color {
    val l = toneToOklabL(tone)
    val maxChroma = chroma.coerceIn(0.0, 0.37)

    if (inGamut(l, maxChroma, hue)) {
        return oklchToColor(Oklch(l, maxChroma, hue))
    }

    // 二分查找能显示的彩度上限。
    // 原来是一格一格往下减，最坏要试 400 次；改成二分之后最多 14 次。
    // 配色动画每帧都要重算整张色板，这一处直接决定了拖动时会不会掉帧。
    var low = 0.0
    var high = maxChroma
    repeat(14) {
        val mid = (low + high) * 0.5
        if (inGamut(l, mid, hue)) low = mid else high = mid
    }
    return oklchToColor(Oklch(l, low, hue))
}

/**
 * 由一张图片的主色，生成一整套 Material 3 配色。
 *
 * 原理和 MD3 的 tonal spot 方案一致：
 * - primary   用原色相，彩度稍降
 * - secondary 同色相，彩度再降（用于次要元素）
 * - tertiary  色相偏移约 60 度（用于点缀、对比）
 * - neutral   同色相的极低彩度版本（用于背景、表面）
 *
 * 这样换一张图，整个 app 的情绪就换一次，但对比度和可读性始终有保证。
 */
fun buildScheme(seed: Color, dark: Boolean): ColorScheme {
    val seedOklch = seed.toOklch()

    var hue = seedOklch.h
    var baseChroma = seedOklch.c

    // 图太灰时给一个最低彩度，否则整个 app 会变成一片死灰
    if (baseChroma < 0.03) baseChroma = 0.03

    // 中性色尽量保留一点色相倾向，但不能让背景发脏
    val neutralChroma = (baseChroma * 0.055).coerceIn(0.0, 0.022)
    val neutralVariantChroma = (baseChroma * 0.10).coerceIn(0.0, 0.038)

    val primaryChroma = (baseChroma * 0.92).coerceAtLeast(0.05)
    val secondaryChroma = (baseChroma * 0.42).coerceAtLeast(0.025)
    val tertiaryChroma = (baseChroma * 0.72).coerceAtLeast(0.04)
    val tertiaryHue = (hue + 62.0) % 360.0

    fun p(tone: Int) = paletteColor(hue, primaryChroma, tone.toDouble())
    fun s(tone: Int) = paletteColor(hue, secondaryChroma, tone.toDouble())
    fun t(tone: Int) = paletteColor(tertiaryHue, tertiaryChroma, tone.toDouble())
    fun n(tone: Int) = paletteColor(hue, neutralChroma, tone.toDouble())
    fun nv(tone: Int) = paletteColor(hue, neutralVariantChroma, tone.toDouble())

    // 错误色固定用红色系，不跟着图片走（安全相关的颜色必须稳定）
    fun e(tone: Int) = paletteColor(25.0, 0.19, tone.toDouble())

    return if (dark) {
        darkColorScheme(
            primary = p(80),
            onPrimary = p(20),
            primaryContainer = p(30),
            onPrimaryContainer = p(90),
            inversePrimary = p(40),

            secondary = s(80),
            onSecondary = s(20),
            secondaryContainer = s(30),
            onSecondaryContainer = s(90),

            tertiary = t(80),
            onTertiary = t(20),
            tertiaryContainer = t(30),
            onTertiaryContainer = t(90),

            background = n(6),
            onBackground = n(90),
            surface = n(6),
            onSurface = n(90),
            surfaceVariant = nv(30),
            onSurfaceVariant = nv(80),
            surfaceTint = p(80),
            inverseSurface = n(90),
            inverseOnSurface = n(20),

            surfaceBright = n(24),
            surfaceDim = n(6),
            surfaceContainerLowest = n(4),
            surfaceContainerLow = n(10),
            surfaceContainer = n(12),
            surfaceContainerHigh = n(17),
            surfaceContainerHighest = n(22),

            outline = nv(60),
            outlineVariant = nv(30),
            scrim = Color.Black,

            error = e(80),
            onError = e(20),
            errorContainer = e(30),
            onErrorContainer = e(90),
        )
    } else {
        lightColorScheme(
            primary = p(40),
            onPrimary = p(100),
            primaryContainer = p(90),
            onPrimaryContainer = p(10),
            inversePrimary = p(80),

            secondary = s(40),
            onSecondary = s(100),
            secondaryContainer = s(90),
            onSecondaryContainer = s(10),

            tertiary = t(40),
            onTertiary = t(100),
            tertiaryContainer = t(90),
            onTertiaryContainer = t(10),

            background = n(98),
            onBackground = n(10),
            surface = n(98),
            onSurface = n(10),
            surfaceVariant = nv(90),
            onSurfaceVariant = nv(30),
            surfaceTint = p(40),
            inverseSurface = n(20),
            inverseOnSurface = n(95),

            surfaceBright = n(98),
            surfaceDim = n(87),
            surfaceContainerLowest = n(100),
            surfaceContainerLow = n(96),
            surfaceContainer = n(94),
            surfaceContainerHigh = n(92),
            surfaceContainerHighest = n(90),

            outline = nv(50),
            outlineVariant = nv(80),
            scrim = Color.Black,

            error = e(40),
            onError = e(100),
            errorContainer = e(90),
            onErrorContainer = e(10),
        )
    }
}

/**
 * 给 UI 用：把一个颜色调成指定透明度，方便做强弱的层次。
 */
fun Color.alpha(a: Float): Color = this.copy(alpha = a.coerceIn(0f, 1f))

/** 感知亮度，用来决定在这个背景上放白字还是黑字。 */
fun Color.perceivedLightness(): Double = toOklch().l

fun Color.contrastOn(): Color =
    if (perceivedLightness() > 0.62) Color(0xFF1A1A1A) else Color.White

internal fun hsvSaturationHint(color: Color): Double {
    val max = maxOf(color.red, color.green, color.blue).toDouble()
    val min = minOf(color.red, color.green, color.blue).toDouble()
    return if (max <= 0.0) 0.0 else (max - min) / max
}

internal fun distance(a: Oklch, b: Oklch): Double {
    val hd = abs(a.h - b.h).let { if (it > 180) 360 - it else it }
    return sqrt((a.l - b.l) * (a.l - b.l) + (a.c - b.c) * (a.c - b.c) + (hd / 360.0) * (hd / 360.0))
}
