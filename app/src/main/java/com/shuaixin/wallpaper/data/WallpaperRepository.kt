package com.shuaixin.wallpaper.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 图片源。
 *
 * 主源 Wallhaven：
 *   - 全站是精选壁纸，分辨率动辄 3000×4000
 *   - 每张图有收藏数，可以用「评分排序」把好图顶上来
 *   - 支持按「竖图」「最低分辨率」「只要二次元」「只要全年龄」精确筛
 *   - 实测下载 6.2 MB/s，一张 2MB 的图 0.34 秒
 *   - 不需要 API key
 *
 * 备源 nekos.best：快，而且带画师名和 Pixiv 链接，Wallhaven 挂了还能出图。
 */
class WallpaperRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val userAgent = "WallpaperApp/0.1 (Android; personal use)"

    suspend fun random(): WallpaperItem = withContext(Dispatchers.IO) {
        repeat(3) {
            runCatching { fetchWallhaven() }.getOrNull()?.let { return@withContext it }
        }
        repeat(2) {
            runCatching { fetchNekos() }.getOrNull()?.let { return@withContext it }
        }
        throw IllegalStateException("这次没抽到合适的图")
    }

    private fun fetchWallhaven(): WallpaperItem? {
        // 分类 010 = 只要二次元；purity 100 = 只要全年龄
        // toplist = 按评分排行；ratios=portrait 只出竖图
        val page = (1..8).random()
        val url = "https://wallhaven.cc/api/v1/search" +
            "?categories=010" +
            "&purity=100" +
            "&sorting=toplist" +
            "&order=desc" +
            "&atleast=1080x1900" +
            "&ratios=portrait" +
            "&page=$page"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body?.string() ?: return null
            val data = JSONObject(text).optJSONArray("data") ?: return null
            if (data.length() == 0) return null

            // 结果已经按评分排好，从前 10 名里随机挑，既保证质量又不至于每次都同一张
            val pool = minOf(data.length(), 10)
            val post = data.getJSONObject((0 until pool).random())

            val path = post.optString("path").takeIf { it.isNotBlank() } ?: return null
            val width = post.optInt("dimension_x")
            val height = post.optInt("dimension_y")

            if (width <= 0 || height <= 0) return null
            if (width >= height) return null
            if (height < 1900) return null

            val favorites = post.optInt("favorites")
            val pageUrl = post.optString("url").takeIf { it.isNotBlank() }

            return WallpaperItem(
                imageUrl = path,
                artistName = null,
                artistUrl = null,
                sourceUrl = pageUrl,
                title = if (favorites > 0) "♥ $favorites  ·  ${width}×${height}" else "${width}×${height}",
                width = width,
                height = height,
                source = "Wallhaven",
            )
        }
    }

    private fun fetchNekos(): WallpaperItem? {
        val category = listOf("neko", "waifu").random()
        val request = Request.Builder()
            .url("https://nekos.best/api/v2/$category?amount=1")
            .header("User-Agent", userAgent)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body?.string() ?: return null
            val results = JSONObject(text).optJSONArray("results") ?: return null
            if (results.length() == 0) return null

            val item = results.getJSONObject(0)
            val dims = item.optJSONObject("dimensions")
            val width = dims?.optInt("width") ?: 0
            val height = dims?.optInt("height") ?: 0

            if (width > 0 && height > 0) {
                if (height < 1500 || width < 1000) return null
                if (width > height) return null
            }

            return WallpaperItem(
                imageUrl = item.optString("url"),
                artistName = item.optString("artist_name").takeIf { it.isNotBlank() },
                artistUrl = item.optString("artist_href").takeIf { it.isNotBlank() },
                sourceUrl = item.optString("source_url").takeIf { it.isNotBlank() },
                title = null,
                width = width,
                height = height,
                source = "nekos.best",
            )
        }
    }
}
