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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

    // 种子色平滑过渡：换图时整个界面的颜色一起缓缓流动
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
    var cardBounds by remember { mutableStateOf(Rect.Zero) }
    val snackbar = remember { SnackbarHostState() }
    val cardCorner = rememberDeviceCorner()
    val scheme = MaterialTheme.colorScheme

    val palette = remember(scheme.primary, scheme.tertiary, scheme.secondary) {
        listOf(
            scheme.primary,
            scheme.tertiary,
            scheme.secondary,
            scheme.primaryContainer,
            Color.White,
        )
    }

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
            Spacer(Modifier.height(4.dp))

            ArtistRow(
                name = state.current?.artistName,
                title = state.current?.title,
                source = state.current?.source,
            )

            Spacer(Modifier.height(16.dp))

            WallpaperCard(
                item = state.current,
                corner = cardCorner,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onGloballyPositioned { cardBounds = it.boundsInRoot() },
            )

            Spacer(Modifier.height(30.dp))

            ActionRow(
                enabled = state.current != null && !state.busy,
                onSetWallpaper = { sheetVisible = true },
                onNext = vm::next,
                onSave = vm::save,
            )

            Spacer(Modifier.height(24.dp))
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

/** 背景层：模糊的图 + 种子色染色 + 一层由浅到深的遮挡 */
@Composable
private fun Backdrop(url: String?, seed: Color) {
    val context = LocalContext.current

    Box(Modifier.fillMaxSize()) {
        if (url != null) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(url)
                    .size(200)
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

        Box(
            Modifier
                .fillMaxSize()
                .background(seed.copy(alpha = 0.42f)),
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.12f),
                        0.55f to Color.Black.copy(alpha = 0.30f),
                        1.0f to Color.Black.copy(alpha = 0.62f),
                    ),
                ),
        )
    }
}

/** 中间的图片卡片：大圆角 + 向下投射的阴影 */
@Composable
private fun WallpaperCard(
    item: com.shuaixin.wallpaper.data.WallpaperItem?,
    corner: Dp,
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
                    .crossfade(620)
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

/** 左上角：画师头像 + 横排信息，全部使用衬线字体 */
@Composable
private fun ArtistRow(
    name: String?,
    title: String?,
    source: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .shadow(10.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.60f))
                .border(1.dp, Color.White.copy(alpha = 0.24f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name?.trim()?.take(1)?.ifBlank { null } ?: "・",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Serif,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.width(13.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = name ?: "佚名",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            val sub = title ?: source
            if (!sub.isNullOrBlank()) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Serif,
                    color = Color.White.copy(alpha = 0.68f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
    val container = if (primary) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f)
    }
    val content = if (primary) MaterialTheme.colorScheme.onPrimary else Color.White
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
            fontFamily = FontFamily.Serif,
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
            Text(title, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Serif)
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
