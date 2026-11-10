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
    /** 上一张的 URL，划像时垫在下面用 —— 用 URL 而不是位图，画质才和原图一致 */
    val previousUrl: String? = null,
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
    private val seedCache = mutableMapOf<String, Color>()

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
                    previousUrl = oldItem?.imageUrl,
                    seed = cached ?: it.seed,
                    loading = false,
                    error = null,
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
                        previousUrl = oldItem?.imageUrl,
                        seed = seed ?: it.seed,
                        loading = false,
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
                    if (item.imageUrl == _state.value.current?.imageUrl) continue
                    queue.addLast(item)
                    ensureSeed(item)
                    preloadImage(item)
                }
            } finally {
                filling = false
            }
        }
    }

    /**
     * 把图下到缓存里，尺寸必须和卡片显示时一致，
     * 否则内存缓存命不中，换图那一瞬间还得重新解码。
     */
    private fun preloadImage(item: WallpaperItem) {
        viewModelScope.launch {
            runCatching {
                val request = ImageRequest.Builder(app)
                    .data(item.imageUrl)
                    .size(1200)
                    .build()
                app.imageLoader.enqueue(request)
            }
        }
    }

    /** 只为了取主色，用小图就够了，取完立刻回收 */
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
