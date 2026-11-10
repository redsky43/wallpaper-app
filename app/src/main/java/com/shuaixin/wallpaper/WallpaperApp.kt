@file:OptIn(ExperimentalMaterial3Api::class)

package com.shuaixin.wallpaper

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.shuaixin.wallpaper.ui.theme.WallpaperTheme
import com.shuaixin.wallpaper.util.WallpaperTarget

@Composable
fun WallpaperApp(vm: WallpaperViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val dark = isSystemInDarkTheme()

    // 种子色平滑过渡：换图时整个界面的颜色一起缓缓流动，而不是硬切
    val animatedSeed by animateColorAsState(
        targetValue = state.seed,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "seed",
    )

    WallpaperTheme(seed = animatedSeed, dark = dark) {
        WallpaperScreen(state = state, vm = vm)
    }
}

@Composable
private fun WallpaperScreen(state: UiState, vm: WallpaperViewModel) {
    var sheetVisible by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val cardCorner = rememberDeviceCorner()

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.consumeMessage()
    }

    Box(Modifier.fillMaxSize()) {
        // 第一层：整张图放大 + 重度模糊，取它的颜色当环境底色
        Backdrop(url = state.current?.imageUrl, seed = state.seed)

        // 第二层：所有内容
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 22.dp),
        ) {
            Spacer(Modifier.height(6.dp))

            ArtistBlock(
                name = state.current?.artistName,
                onClick = { },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(18.dp))

            WallpaperCard(
                item = state.current,
                corner = cardCorner,
                loading = state.loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            Spacer(Modifier.height(34.dp))

            ActionRow(
                enabled = state.current != null && !state.busy,
                onSetWallpaper = { sheetVisible = true },
                onNext = vm::next,
                onSave = vm::save,
            )

            Spacer(Modifier.height(26.dp))
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(bottom = 12.dp),
        )
    }

    if (sheetVisible) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { sheetVisible = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        ) {
            SheetContent(
                onPick = { target ->
                    sheetVisible = false
                    vm.applyWallpaper(target)
                },
            )
        }
    }
}

/** 背景层：模糊的图 + 种子色染色 + 一层由浅到深的遮挡，保证前景文字可读 */
@Composable
private fun Backdrop(url: String?, seed: Color) {
    val context = LocalContext.current

    Box(Modifier.fillMaxSize()) {
        if (url != null) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(url)
                    .size(180)
                    .crossfade(500)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(72.dp),
                loading = {},
                error = {},
            )
        }

        // 用图片主色染一遍，让背景带着图里的情绪
        Box(
            Modifier
                .fillMaxSize()
                .background(seed.copy(alpha = 0.42f)),
        )

        // 上浅下深，保证底部按钮和文字始终清晰
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.10f),
                        0.55f to Color.Black.copy(alpha = 0.30f),
                        1.0f to Color.Black.copy(alpha = 0.62f),
                    ),
                ),
        )
    }
}

/** 中间的图片卡片：大圆角 + 向下投射的阴影，像浮在颜色之上 */
@Composable
private fun WallpaperCard(
    item: com.shuaixin.wallpaper.data.WallpaperItem?,
    corner: Dp,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(corner)
    val context = LocalContext.current

    Box(
        modifier = modifier
            .shadow(
                elevation = 26.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.42f),
                spotColor = Color.Black.copy(alpha = 0.60f),
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        if (item != null) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(item.imageUrl)
                    .crossfade(650)
                    .build(),
                contentDescription = item.artistName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                },
                error = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "这张没加载出来",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

/** 右上角：画师头像 + 竖排的名字 */
@Composable
private fun ArtistBlock(
    name: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .shadow(8.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f))
                .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                .clickable(
                    enabled = name != null,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name?.take(1) ?: "·",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(10.dp))

        if (!name.isNullOrBlank()) {
            VerticalName(name)
        }
    }
}

/** 竖排文字：一个字一行，日文/中文天然适合这样排 */
@Composable
private fun VerticalName(name: String) {
    val chars = name.trim().take(7).toList()
    val truncated = name.trim().length > 7

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        chars.forEach { ch ->
            Text(
                text = ch.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.88f),
            )
        }
        if (truncated) {
            Text(
                text = "…",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
    }
}

/** 底部三个圆形按钮 */
@Composable
private fun ActionRow(
    enabled: Boolean,
    onSetWallpaper: () -> Unit,
    onNext: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(30.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionCircle(
            icon = Icons.Outlined.Wallpaper,
            label = "设为壁纸",
            primary = false,
            enabled = enabled,
            onClick = onSetWallpaper,
        )
        ActionCircle(
            icon = Icons.Filled.AutoAwesome,
            label = "换一张",
            primary = true,
            enabled = enabled,
            onClick = onNext,
        )
        ActionCircle(
            icon = Icons.Outlined.Download,
            label = "保存",
            primary = false,
            enabled = enabled,
            onClick = onSave,
        )
    }
}

@Composable
private fun ActionCircle(
    icon: ImageVector,
    label: String,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val container = when {
        primary -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f)
    }
    val content = when {
        primary -> MaterialTheme.colorScheme.onPrimary
        else -> Color.White
    }
    val size = if (primary) 70.dp else 60.dp
    val iconSize = if (primary) 28.dp else 24.dp
    val alpha = if (enabled) 1f else 0.45f

    Box(
        modifier = Modifier
            .size(size)
            .shadow(
                elevation = if (primary) 16.dp else 10.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
            )
            .clip(CircleShape)
            .background(container.copy(alpha = container.alpha * alpha))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.material3.ripple(),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = content.copy(alpha = alpha),
            modifier = Modifier.size(iconSize),
        )
    }
}

/** 「设为壁纸」的底部菜单 */
@Composable
private fun SheetContent(onPick: (WallpaperTarget) -> Unit) {
    Column(Modifier.padding(bottom = 28.dp)) {
        Text(
            text = "设置壁纸",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 26.dp, bottom = 6.dp),
        )

        SheetItem(
            icon = Icons.Outlined.Home,
            title = "仅主屏幕",
            subtitle = "桌面会变，锁屏不动",
            onClick = { onPick(WallpaperTarget.HOME) },
        )
        SheetItem(
            icon = Icons.Outlined.Lock,
            title = "仅锁屏",
            subtitle = "只在解锁前显示",
            onClick = { onPick(WallpaperTarget.LOCK) },
        )
        SheetItem(
            icon = Icons.Outlined.Layers,
            title = "主屏幕和锁屏",
            subtitle = "两处一起换",
            onClick = { onPick(WallpaperTarget.BOTH) },
        )
    }
}

@Composable
private fun SheetItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(title, style = MaterialTheme.typography.bodyLarge)
        },
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
