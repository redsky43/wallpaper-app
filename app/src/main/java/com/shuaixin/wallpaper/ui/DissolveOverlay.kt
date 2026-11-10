package com.shuaixin.wallpaper

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt
import kotlin.random.Random

/** 一颗「沙粒」：位置、颜色、大小、出发时间、飞散方向 */
private class Grain(
    val nx: Float,
    val ny: Float,
    val color: Color,
    val scale: Float,
    val delay: Float,
    val dx: Float,
    val dy: Float,
)

// 把画面打成多少格 —— 这两个数字决定「沙粒」的粗细
private const val GRID_COLS = 36
private const val GRID_ROWS = 60

/**
 * 粒子溶解（particle dissolve）。
 *
 * 和「切碎块」不一样的地方：这里把画面采样成 36×60 = 2160 个**圆点**，
 * 每个点的颜色就是它所在位置那一小块画面的真实颜色。
 *
 * 静止时这些点互相重叠、刚好铺满整张图，看起来还是一张完整的画；
 * 一动起来，整幅画面就像被吹散成彩色的沙，露出后面的新图。
 *
 * 每颗沙粒都有：
 *   - 自己的颜色（从原图采样，不是随机色）
 *   - 自己的大小（0.62~1.17 倍，避免看起来是机械的网格）
 *   - 自己的出发延迟（0~0.46 秒，所以是一层层剥离而不是整块掉下来）
 *   - 自己的飞散方向（朝画面外侧，力度随机）
 */
@Composable
fun ParticleDissolve(
    bitmap: Bitmap,
    trigger: Int,
    modifier: Modifier = Modifier,
) {
    var grains by remember { mutableStateOf<List<Grain>>(emptyList()) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(trigger, bitmap) {
        if (trigger <= 1) return@LaunchedEffect

        // 采样在后台线程做，别卡住动画
        val list = withContext(Dispatchers.Default) {
            val small = Bitmap.createScaledBitmap(bitmap, GRID_COLS, GRID_ROWS, true)
            val pixels = IntArray(GRID_COLS * GRID_ROWS)
            small.getPixels(pixels, 0, GRID_COLS, 0, 0, GRID_COLS, GRID_ROWS)
            if (small !== bitmap) small.recycle()

            val random = Random(trigger * 7331L)
            val out = ArrayList<Grain>(GRID_COLS * GRID_ROWS)

            for (row in 0 until GRID_ROWS) {
                for (col in 0 until GRID_COLS) {
                    val pixel = pixels[row * GRID_COLS + col]
                    if (((pixel ushr 24) and 0xFF) < 16) continue

                    val cx = (col + 0.5f) / GRID_COLS - 0.5f
                    val cy = (row + 0.5f) / GRID_ROWS - 0.5f
                    val length = sqrt(cx * cx + cy * cy).coerceAtLeast(0.001f)
                    val force = 0.20f + random.nextFloat() * 0.60f

                    out.add(
                        Grain(
                            nx = (col + 0.5f) / GRID_COLS,
                            ny = (row + 0.5f) / GRID_ROWS,
                            color = Color(pixel),
                            scale = 0.62f + random.nextFloat() * 0.55f,
                            delay = random.nextFloat() * 0.46f,
                            dx = cx / length * force,
                            dy = cy / length * force,
                        ),
                    )
                }
            }
            out
        }

        grains = list
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 920, easing = LinearEasing),
        )
        grains = emptyList()
    }

    Canvas(modifier) {
        val p = progress.value
        if (p >= 1f || grains.isEmpty()) return@Canvas

        val cellWidth = size.width / GRID_COLS
        val cellHeight = size.height / GRID_ROWS
        // 略大于格子，静止时才不会露出缝隙
        val baseRadius = maxOf(cellWidth, cellHeight) * 0.64f

        grains.forEach { grain ->
            val t = ((p - grain.delay) / (1f - grain.delay)).coerceIn(0f, 1f)
            if (t <= 0f) return@forEach

            val drift = t * t              // 先慢后快
            val fade = (1f - t) * (1f - t) // 尾巴干脆，不留雾
            val shrink = 1f - 0.55f * t

            val x = (grain.nx + grain.dx * drift) * size.width
            val y = (grain.ny + grain.dy * drift) * size.height

            drawCircle(
                color = grain.color.copy(alpha = fade),
                radius = baseRadius * grain.scale * shrink,
                center = Offset(x, y),
            )
        }
    }
}
