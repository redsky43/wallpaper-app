package com.shuaixin.wallpaper.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 图片源。
 *
 * 主源 yande.re：动漫壁纸站，每张图都有用户评分，可以按分数门槛筛。
 * 这比「随机抽一张」可靠得多 —— 分数低的图根本进不来。
 *
 * 备源 nekos.best：画风偏新，但没有评分机制，质量参差，只当兜底。
 *
 * 统一要求：竖图、宽度 1000 以上、高度 1500 以上，否则设成壁纸会糊。
 */
class WallpaperRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val userAgent = "WallpaperApp/0.1 (Android; personal use)"

    suspend fun random(): WallpaperItem = withContext(Dispatchers.IO) {
        repeat(3) {
            runCatching { fetchYande() }.getOrNull()?.let { return@withContext it }
        }
        repeat(3) {
            runCatching { fetchNekos() }.getOrNull()?.let { return@withContext it }
        }
        throw IllegalStateException("这次没抽到合适的图")
    }

    /**
     * yande.re：一次取 40 条候选，本地随机挑一条。
     * 比 limit=1 好的地方是遇到横图或者异常数据可以换下一条，不用重新请求。
     */
    private fun fetchYande(): WallpaperItem? {
        val page = (1..80).random()
        val tags = "rating:safe+score:%3E60+width:1000..3000+height:1500..4000"
        val url = "https://yande.re/post.json?tags=$tags&limit=40&page=$page"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body?.string() ?: return null
            val array = JSONArray(text)
            if (array.length() == 0) return null

            // 候选里随机打乱顺序，避免总是取第一条
            val indices = (0 until array.length()).shuffled()

            for (index in indices) {
                val post = array.getJSONObject(index)
                val width = post.optInt("width")
                val height = post.optInt("height")
                if (width <= 0 || height <= 0) continue
                if (width >= height) continue          // 横图不要
                if (height < 1500) continue

                // sample 版约 1500px，比几 MB 的原图快得多，壁纸够用
                val imageUrl = post.optString("sample_url").takeIf { it.isNotBlank() }
                    ?: post.optString("jpeg_url").takeIf { it.isNotBlank() }
                    ?: post.optString("file_url").takeIf { it.isNotBlank() }
                    ?: continue

                val id = post.optInt("id")

                return WallpaperItem(
                    imageUrl = imageUrl,
                    artistName = null,
                    artistUrl = null,
                    sourceUrl = if (id > 0) "https://yande.re/post/show/$id" else null,
                    title = if (id > 0) "yande.re #$id" else null,
                    width = post.optInt("sample_width", width),
                    height = post.optInt("sample_height", height),
                    source = "yande.re",
                )
            }
            return null
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
