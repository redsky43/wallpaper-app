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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.sqrt
import kotlin.random.Random

private class Shard(
    val nx: Float,
    val ny: Float,
    val nw: Float,
    val nh: Float,
    val delay: Float,
    val dx: Float,
    val dy: Float,
)

/**
 * 粒子溶解（particle dissolve）。
 *
 * 这是网页和影视后期里做图片过渡的通用办法，不是 Material 规范里的东西：
 * 把画面切成网格，每一小块**带着自己的那部分画面**朝外飞散、缩小、淡出，
 * 底下的新图就一点点露出来。
 *
 * 关键点：进度为 0 时，所有碎片恰好拼回一张完整的旧图，所以看不到接缝；
 * 之后每块按各自的延迟出发，看起来就是画面在崩解。
 *
 * 每块的飞行方向朝画面中心向外，力度随机，越靠外的越快离场。
 */
@Composable
fun DissolveOverlay(
    bitmap: Bitmap,
    trigger: Int,
    modifier: Modifier = Modifier,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var shards by remember { mutableStateOf<List<Shard>>(emptyList()) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(trigger, bitmap) {
        if (trigger <= 1) return@LaunchedEffect

        val cols = 12
        val rows = 18
        val random = Random(trigger * 7331L)
        val list = ArrayList<Shard>(cols * rows)

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                // 相对画面中心的方向，决定这一块往哪飞
                val cx = (col + 0.5f) / cols - 0.5f
                val cy = (row + 0.5f) / rows - 0.5f
                val length = sqrt(cx * cx + cy * cy).coerceAtLeast(0.001f)
                val force = 0.30f + random.nextFloat() * 0.55f

                list.add(
                    Shard(
                        nx = col.toFloat() / cols,
                        ny = row.toFloat() / rows,
                        nw = 1f / cols,
                        nh = 1f / rows,
                        delay = random.nextFloat() * 0.42f,
                        dx = cx / length * force,
                        dy = cy / length * force,
                    ),
                )
            }
        }

        shards = list
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 820, easing = LinearEasing),
        )
        shards = emptyList()
    }

    Canvas(modifier) {
        val p = progress.value
        if (p >= 1f || shards.isEmpty()) return@Canvas

        // 图片按 Crop 铺满，先算出真正可见的那一块区域
        val cover = maxOf(size.width / image.width, size.height / image.height)
        val visibleWidth = size.width / cover
        val visibleHeight = size.height / cover
        val srcLeft = (image.width - visibleWidth) / 2f
        val srcTop = (image.height - visibleHeight) / 2f

        shards.forEach { shard ->
            val t = ((p - shard.delay) / (1f - shard.delay)).coerceIn(0f, 1f)
            if (t <= 0f) return@forEach

            val drift = t * t                       // 先慢后快，像被卷走
            val fade = (1f - t) * (1f - t)          // 尾巴快速消失，不留残影
            val shrink = 1f - 0.45f * t

            val baseW = shard.nw * size.width
            val baseH = shard.nh * size.height
            val drawW = baseW * shrink
            val drawH = baseH * shrink

            val drawX = shard.nx * size.width + shard.dx * size.width * drift - (drawW - baseW) / 2f
            val drawY = shard.ny * size.height + shard.dy * size.height * drift - (drawH - baseH) / 2f

            drawImage(
                image = image,
                srcOffset = IntOffset(
                    (srcLeft + shard.nx * visibleWidth).toInt(),
                    (srcTop + shard.ny * visibleHeight).toInt(),
                ),
                srcSize = IntSize(
                    (shard.nw * visibleWidth).toInt().coerceAtLeast(1),
                    (shard.nh * visibleHeight).toInt().coerceAtLeast(1),
                ),
                dstOffset = IntOffset(drawX.toInt(), drawY.toInt()),
                dstSize = IntSize(
                    drawW.toInt().coerceAtLeast(1),
                    drawH.toInt().coerceAtLeast(1),
                ),
                alpha = fade,
                filterQuality = FilterQuality.Low,
            )
        }
    }
}
