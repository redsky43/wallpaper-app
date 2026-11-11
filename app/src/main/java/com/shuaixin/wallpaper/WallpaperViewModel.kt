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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class UiState(
    val current: WallpaperItem? = null,
    val previousUrl: String? = null,
    /** 上一张的位图，划像底层用它，切换瞬间零延迟 */
    val previousRaster: Bitmap? = null,
    val seed: Color = Color(0xFF6E6A72),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val transitionId: Int = 0,
)

class WallpaperViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = WallpaperRepository()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val queue = ArrayDeque<WallpaperItem>()
    private val seedCache = ConcurrentHashMap<String, Color>()
    private val rasterCache = LinkedHashMap<String, Bitmap>()

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
            ensureRaster(first)
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
                    previousUrl = oldItem?.imageUrl,
                    previousRaster = oldItem?.let { item -> rasterCache[item.imageUrl] },
                    seed = cached ?: it.seed,
                    loading = false,
                    error = null,
                    transitionId = it.transitionId + 1,
                )
            }
            if (cached == null) refreshSeed(nextItem)
            viewModelScope.launch { ensureRaster(nextItem) }
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
                        previousUrl = oldItem?.imageUrl,
                        previousRaster = oldItem?.let { old -> rasterCache[old.imageUrl] },
                        seed = seed ?: it.seed,
                        loading = false,
                        transitionId = it.transitionId + 1,
                    )
                }
                ensureRaster(item)
            }
        }

        refill()
    }

    /**
     * 后台备货。
     *
     * 三个要点：
     *   1. 元数据并发抓（三个请求同时发，不用排队）
     *   2. 图片并发下载（三张同时下，总耗时约等于最慢的那张）
     *   3. 先把候选塞进队列再下载 —— 这样即使图还没下完，
     *      用户点「换一张」也能立刻拿到 URL 开始显示，而不是干等
     */
    private fun refill() {
        if (filling) return
        filling = true

        viewModelScope.launch {
            try {
                val currentUrl = _state.value.current?.imageUrl

                val candidates = coroutineScope {
                    List(TARGET_QUEUE) {
                        async { runCatching { repository.random() }.getOrNull() }
                    }.awaitAll()
                }
                    .filterNotNull()
                    .distinctBy { it.imageUrl }
                    .filter { it.imageUrl != currentUrl }
                    .filter { item -> queue.none { it.imageUrl == item.imageUrl } }

                if (candidates.isEmpty()) return@launch

                queue.addAll(candidates)

                coroutineScope {
                    candidates.forEach { item ->
                        launch(Dispatchers.IO) {
                            downloadToCache(item)
                            ensureSeed(item)
                            ensureRaster(item)
                        }
                    }
                }
            } finally {
                filling = false
            }
        }
    }

    /** 把图下到磁盘缓存，尺寸和显示时一致 */
    private suspend fun downloadToCache(item: WallpaperItem) {
        runCatching {
            val request = ImageRequest.Builder(app)
                .data(item.imageUrl)
                .size(1200)
                .build()
            app.imageLoader.execute(request)
        }
    }

    private suspend fun ensureRaster(item: WallpaperItem): Bitmap? {
        rasterCache[item.imageUrl]?.let { return it }
        val bitmap = loadRaster(item.imageUrl, 1200) ?: return null
        rasterCache[item.imageUrl] = bitmap
        trimRasterCache()
        return bitmap
    }

    private fun trimRasterCache() {
        while (rasterCache.size > 3) {
            val state = _state.value
            val protected = setOfNotNull(state.current?.imageUrl, state.previousUrl)
            val oldest = rasterCache.keys.firstOrNull { it !in protected } ?: break
            rasterCache.remove(oldest)
        }
    }

    private suspend fun ensureSeed(item: WallpaperItem): Color? {
        seedCache[item.imageUrl]?.let { return it }
        val bitmap = loadRaster(item.imageUrl, 256) ?: return null
        val seed = extractSeedColor(bitmap)
        bitmap.recycle()
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
