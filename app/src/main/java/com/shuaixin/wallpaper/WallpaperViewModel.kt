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
    /** 每换一张图 +1，界面靠它触发粒子动效 */
    val transitionId: Int = 0,
)

class WallpaperViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = WallpaperRepository()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 后台已经准备好的下几张图 */
    private val queue = ArrayDeque<WallpaperItem>()

    /** 提前算好的主色，换图时直接跳过去，不用再算一遍 */
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

    /**
     * 换一张。
     * 优先从后台队列里拿——图和颜色都已经准备好，点下去就是瞬间的。
     */
    fun next() {
        seedJob?.cancel()

        val nextItem = queue.removeFirstOrNull()
        if (nextItem != null) {
            val cached = seedCache[nextItem.imageUrl]
            _state.update {
                it.copy(
                    current = nextItem,
                    seed = cached ?: it.seed,
                    loading = false,
                    error = null,
                    transitionId = it.transitionId + 1,
                )
            }
            if (cached == null) refreshSeed(nextItem)
        } else {
            // 队列空（网络断过），现场抓
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
                        transitionId = it.transitionId + 1,
                    )
                }
                preloadImage(item)
            }
        }

        refill()
    }

    /** 把后台队列补满到 3 张，同时预加载图片和解码主色 */
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

    /** 只把图片下到磁盘缓存，界面切过去时不用等网络 */
    private fun preloadImage(item: WallpaperItem) {
        viewModelScope.launch {
            runCatching {
                val request = ImageRequest.Builder(app)
                    .data(item.imageUrl)
                    .size(1100)
                    .build()
                app.imageLoader.enqueue(request)
            }
        }
    }

    private suspend fun ensureSeed(item: WallpaperItem): Color? {
        seedCache[item.imageUrl]?.let { return it }
        val bitmap = loadSmallBitmap(item.imageUrl) ?: return null
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

    /** 设为壁纸 */
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

    /** 保存到相册 */
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

    private suspend fun loadSmallBitmap(url: String): Bitmap? = runCatching {
        val request = ImageRequest.Builder(app)
            .data(url)
            .allowHardware(false)
            .size(96)
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
