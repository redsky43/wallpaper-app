package com.shuaixin.wallpaper

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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private class Particle(
    val nx: Float,      // 出生点，相对卡片区域的 0~1 坐标
    val ny: Float,
    val vx: Float,      // 速度，按卡片宽高归一化
    val vy: Float,
    val size: Float,    // 直径，单位 dp
    val color: Color,
    val delay: Float,   // 0~0.35，错开出发时间
    val gravity: Float,
)

/**
 * 换图时的粒子过渡。
 *
 * 做法参考了 skydoves/compose-animations 里 Confetti Burst 的思路：
 * 每颗粒子在触发瞬间算好初速度和方向，之后靠一个线性进度统一推进，
 * 位置按缓动函数展开，透明度衰减 —— 全部在一张 Canvas 里一次画完，不依赖任何第三方库。
 *
 * 视觉上是从图片区域「炸开一片光点」，配合新图的淡入，读起来像旧图碎掉了。
 */
@Composable
fun ParticleOverlay(
    trigger: Int,
    bounds: Rect,
    palette: List<Color>,
    modifier: Modifier = Modifier,
) {
    var particles by remember { mutableStateOf<List<Particle>>(emptyList()) }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(trigger) {
        if (trigger <= 0 || bounds.width <= 1f || bounds.height <= 1f) return@LaunchedEffect

        val random = Random(trigger * 7919L)
        val count = 110
        val list = ArrayList<Particle>(count)

        repeat(count) {
            val angle = random.nextFloat() * 2f * PI.toFloat()
            val speed = 0.10f + random.nextFloat() * 0.42f
            val upward = if (random.nextFloat() < 0.65f) -0.16f else 0.06f

            list.add(
                Particle(
                    nx = random.nextFloat(),
                    ny = random.nextFloat(),
                    vx = cos(angle) * speed,
                    vy = sin(angle) * speed + upward,
                    size = 1.1f + random.nextFloat() * 3.6f,
                    color = palette[random.nextInt(palette.size)],
                    delay = random.nextFloat() * 0.34f,
                    gravity = 0.35f + random.nextFloat() * 0.85f,
                ),
            )
        }

        particles = list
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 1050, easing = LinearEasing),
        )
        particles = emptyList()
    }

    val p = progress.value

    Canvas(modifier) {
        if (p >= 1f || particles.isEmpty()) return@Canvas

        val areaWidth = bounds.width
        val areaHeight = bounds.height
        val originX = bounds.left
        val originY = bounds.top

        particles.forEach { particle ->
            val local = ((p - particle.delay) / (1f - particle.delay)).coerceIn(0f, 1f)
            if (local <= 0f) return@forEach

            // easeOutQuad：一开始冲得快，后面慢慢停
            val ease = 1f - (1f - local) * (1f - local)

            val x = originX + (particle.nx + particle.vx * ease) * areaWidth
            val y = originY + (particle.ny + particle.vy * ease + particle.gravity * ease * ease) * areaHeight

            val fade = (1f - local) * (1f - local)
            val radius = particle.size.dp.toPx() * (1f - 0.4f * local)

            // 外圈柔光 + 内圈实心，做出发光感
            drawCircle(
                color = particle.color.copy(alpha = fade * 0.22f),
                radius = radius * 2.6f,
                center = Offset(x, y),
            )
            drawCircle(
                color = particle.color.copy(alpha = fade * 0.92f),
                radius = radius,
                center = Offset(x, y),
            )
        }
    }
}
