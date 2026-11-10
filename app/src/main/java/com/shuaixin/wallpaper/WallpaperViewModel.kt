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
)

class WallpaperViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = WallpaperRepository()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        next()
    }

    /** 换一张 */
    fun next() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { repository.random() }
                .onSuccess { item ->
                    _state.update { it.copy(current = item, loading = false) }
                    updateSeed(item)
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(loading = false, error = e.message ?: "网络不太顺")
                    }
                }
        }
    }

    /** 后台把图片缩小后取主色，不阻塞界面 */
    private fun updateSeed(item: WallpaperItem) {
        viewModelScope.launch {
            runCatching {
                val request = ImageRequest.Builder(getApplication())
                    .data(item.imageUrl)
                    .allowHardware(false)
                    .size(128)
                    .build()
                val result = getApplication<Application>().imageLoader.execute(request)
                val bitmap = (result as? SuccessResult)?.drawable?.toBitmap()
                if (bitmap != null) {
                    val seed = extractSeedColor(bitmap)
                    bitmap.recycle()
                    _state.update { it.copy(seed = seed) }
                }
            }
        }
    }

    /** 设为壁纸 */
    fun applyWallpaper(target: WallpaperTarget) {
        val url = _state.value.current?.imageUrl ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val bitmap = loadFullBitmap(url)
            val ok = if (bitmap == null) false else {
                val r = WallpaperSetter.apply(getApplication(), bitmap, target)
                bitmap.recycle()
                r
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
            val ok = if (bitmap == null) false else {
                val name = "wallpaper_${System.currentTimeMillis()}"
                val r = ImageSaver.save(getApplication(), bitmap, name)
                bitmap.recycle()
                r
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

    private suspend fun loadFullBitmap(url: String): Bitmap? {
        return runCatching {
            val request = ImageRequest.Builder(getApplication())
                .data(url)
                .allowHardware(false)
                .build()
            val result = getApplication<Application>().imageLoader.execute(request)
            (result as? SuccessResult)?.drawable?.toBitmap()
        }.getOrNull()
    }
}
