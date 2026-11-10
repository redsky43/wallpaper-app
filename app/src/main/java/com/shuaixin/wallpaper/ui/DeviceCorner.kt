package com.shuaixin.wallpaper

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 读取这台手机屏幕的物理圆角半径，让卡片和屏幕的弧度呼应。
 *
 * Android 12 起 Display.getRoundedCorner() 能拿到真实半径；
 * 拿不到（模拟器、外接屏、三方向圆角不同）就退回 28dp 的大圆角。
 */
@Composable
fun rememberDeviceCorner(fallback: Dp = 28.dp): Dp {
    val context = LocalContext.current

    return remember(context) {
        runCatching {
            val display = context.display ?: return@runCatching fallback

            val radiusPx = (0..3).mapNotNull { position ->
                runCatching { display.getRoundedCorner(position)?.radius }.getOrNull()
            }.maxOrNull() ?: 0

            if (radiusPx <= 0) return@runCatching fallback

            val density = context.resources.displayMetrics.density
            val deviceDp = radiusPx / density

            // 手机圆角本身偏小（十几 dp），用在整张卡片上会显得很方，
            // 所以取一个下限，既保留设备弧度又撑得住画面。
            maxOf(deviceDp, fallback.value).dp
        }.getOrDefault(fallback)
    }
}
