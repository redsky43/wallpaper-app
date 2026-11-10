package com.shuaixin.wallpaper

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

/**
 * 划像（wipe）转场。
 *
 * 新画面像一块布从下往上推，把旧画面顶出去，交界线是中间高两边低的弧。
 *
 * 两层都用原图 URL，不用缩略图 —— 这样交界线扫过时，
 * 上下两半的画质是一致的，不会出现「旧图突然变糊」。
 *
 * 旧图始终铺满在底层，任何一帧都不会露出空白。
 */
@Composable
fun WipeLayers(
    currentUrl: String?,
    previousUrl: String?,
    trigger: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val progress = remember { Animatable(1f) }

    LaunchedEffect(trigger, previousUrl) {
        // 第一次打开没有「上一张」，直接完整显示
        if (previousUrl == null) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 720,
                easing = CubicBezierEasing(0.32f, 0f, 0.18f, 1f),
            ),
        )
    }

    Box(modifier) {
        // 底层：上一张，和顶层同样的尺寸参数，保证画质一致
        if (previousUrl != null) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(previousUrl)
                    .size(1200)
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {},
                error = {},
            )
        }

        // 顶层：当前这张，按弧形路径裁剪
        if (currentUrl != null) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(currentUrl)
                    .size(1200)
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        val p = progress.value

                        if (p >= 1f) {
                            drawContent()
                            return@drawWithContent
                        }

                        // 交界线从底部升到顶部
                        val boundary = size.height * (1f - p)
                        // 弧度：宽度的一成三，太大会像波浪，太小看不出
                        val arc = size.width * 0.13f

                        val path = Path().apply {
                            moveTo(-8f, size.height + 8f)
                            lineTo(-8f, boundary)
                            quadraticBezierTo(
                                size.width / 2f,
                                boundary - arc,
                                size.width + 8f,
                                boundary,
                            )
                            lineTo(size.width + 8f, size.height + 8f)
                            close()
                        }

                        clipPath(path) {
                            this@drawWithContent.drawContent()
                        }
                    },
                loading = {},
                error = {},
            )
        }
    }
}
