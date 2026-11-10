package com.shuaixin.wallpaper

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.shuaixin.wallpaper.color.extractSeedColor
import com.shuaixin.wallpaper.data.WallpaperItem
import com.shuaixin.wallpaper.data.WallpaperRepository
import com.shuaixin.wallpaper.util.ImageSaver
import com.shuaixin.wallpaper.util.WallpaperSetter
import com.shuaixin.wallpaper.util.WallpaperTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val current: WallpaperItem? = null,
    val seed: Color = Color(0xFF6E6A72),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    /** 上一张图，用来做粒子溶解 */
    val previousBitmap: Bitmap? = null,
    /** 每换一张图 +1 */
    val transitionId: Int = 0,
)

class WallpaperViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = WallpaperRepository()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val queue = ArrayDeque<WallpaperItem>()
    private val seedCache = mutableMapOf<String, Color>()

    /**
     * 每张图保留一份中等尺寸位图：一图两用，既用来算主色，也用来做溶解的碎片。
     * 分辨率不用太高，反正是要撕碎的东西。
     */
    private val bitmapCache = mutableMapOf<String, Bitmap>()

    private var filling = false
    private var seedJob: Job? = null

    private val app: Application get() = getApplication()

    init {
        bootstrap()
    }

    private fun bootstrap() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val first = runCatching { repository.random() }.getOrNull()
            if (first == null) {
                _state.update { it.copy(loading = false, error = "网络不太顺，点一下再试") }
                return@launch
            }

            val seed = ensureSeed(first)
            _state.update {
                it.copy(
                    current = first,
                    seed = seed ?: it.seed,
                    loading = false,
                    transitionId = it.transitionId + 1,
                )
            }
            preloadImage(first)
            refill()
        }
    }

    fun next() {
        seedJob?.cancel()

        val oldItem = _state.value.current
        val nextItem = queue.removeFirstOrNull()

        if (nextItem != null) {
            val cached = seedCache[nextItem.imageUrl]
            _state.update {
                it.copy(
                    current = nextItem,
                    seed = cached ?: it.seed,
                    loading = false,
                    error = null,
                    previousBitmap = bitmapCache[oldItem?.imageUrl],
                    transitionId = it.transitionId + 1,
                )
            }
            if (cached == null) refreshSeed(nextItem)
        } else {
            viewModelScope.launch {
                _state.update { it.copy(loading = true, error = null) }
                val item = runCatching { repository.random() }.getOrNull()
                if (item == null) {
                    _state.update { it.copy(loading = false, error = "这次没抽到") }
                    return@launch
                }
                val seed = ensureSeed(item)
                _state.update {
                    it.copy(
                        current = item,
                        seed = seed ?: it.seed,
                        loading = false,
                        previousBitmap = bitmapCache[oldItem?.imageUrl],
                        transitionId = it.transitionId + 1,
                    )
                }
                preloadImage(item)
            }
        }

        refill()
    }

    private fun refill() {
        if (filling) return
        filling = true
        viewModelScope.launch {
            try {
                while (queue.size < TARGET_QUEUE) {
                    val item = runCatching { repository.random() }.getOrNull() ?: break
                    if (queue.any { it.imageUrl == item.imageUrl }) continue
                    queue.addLast(item)
                    ensureSeed(item)
                    preloadImage(item)
                }
            } finally {
                filling = false
            }
        }
    }

    private fun preloadImage(item: WallpaperItem) {
        viewModelScope.launch {
            runCatching {
                // 尺寸要和卡片显示时一致，否则内存缓存命不中，
                // 换图那一刻还得重新解码，就会出现「空一帧」的暗闪
                val request = ImageRequest.Builder(app)
                    .data(item.imageUrl)
                    .size(1200)
                    .build()
                app.imageLoader.enqueue(request)
            }
        }
    }

    /** 算主色，顺便把位图留在内存里给溶解用 */
    private suspend fun ensureSeed(item: WallpaperItem): Color? {
        seedCache[item.imageUrl]?.let { return it }
        val bitmap = bitmapCache[item.imageUrl] ?: loadRaster(item.imageUrl, 640) ?: return null
        bitmapCache[item.imageUrl] = bitmap
        val seed = extractSeedColor(bitmap)
        seedCache[item.imageUrl] = seed
        return seed
    }

    private fun refreshSeed(item: WallpaperItem) {
        seedJob = viewModelScope.launch {
            val seed = ensureSeed(item) ?: return@launch
            if (_state.value.current?.imageUrl == item.imageUrl) {
                _state.update { it.copy(seed = seed) }
            }
        }
    }

    fun applyWallpaper(target: WallpaperTarget) {
        val url = _state.value.current?.imageUrl ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val bitmap = loadFullBitmap(url)
            val ok = if (bitmap == null) {
                false
            } else {
                val result = WallpaperSetter.apply(app, bitmap, target)
                bitmap.recycle()
                result
            }
            _state.update {
                it.copy(
                    busy = false,
                    message = if (ok) "壁纸已设置" else "设置失败，重试一下",
                )
            }
        }
    }

    fun save() {
        val item = _state.value.current ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val bitmap = loadFullBitmap(item.imageUrl)
            val ok = if (bitmap == null) {
                false
            } else {
                val result = ImageSaver.save(app, bitmap, "wallpaper_${System.currentTimeMillis()}")
                bitmap.recycle()
                result
            }
            _state.update {
                it.copy(
                    busy = false,
                    message = if (ok) "已保存到相册" else "保存失败，重试一下",
                )
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /** 解码成指定宽度的位图，软件位图才能直接拿去 Canvas 画 */
    private suspend fun loadRaster(url: String, width: Int): Bitmap? = runCatching {
        val request = ImageRequest.Builder(app)
            .data(url)
            .allowHardware(false)
            .size(width)
            .build()
        val result = app.imageLoader.execute(request)
        (result as? SuccessResult)?.drawable?.toBitmap()
    }.getOrNull()

    private suspend fun loadFullBitmap(url: String): Bitmap? = runCatching {
        val request = ImageRequest.Builder(app)
            .data(url)
            .allowHardware(false)
            .build()
        val result = app.imageLoader.execute(request)
        (result as? SuccessResult)?.drawable?.toBitmap()
    }.getOrNull()

    private companion object {
        const val TARGET_QUEUE = 3
    }
}
