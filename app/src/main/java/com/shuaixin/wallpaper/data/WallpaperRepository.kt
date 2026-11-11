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
 * 主源 nekos.best：实测 9.9 MB/s，出图快，画风是用户认可的。缺点是没有质量评分，
 * 偶尔会混进画得比较潦草的图。
 *
 * 备源 lolicon（Pixiv）：画质稳定，但服务器偶尔不稳。
 *
 * 之前试过 yande.re：图确实好（有评分门槛），但服务器只有 0.6 MB/s，
 * 实际用起来经常刷不出来，只能放弃。
 */
class WallpaperRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val userAgent = "WallpaperApp/0.1 (Android; personal use)"

    private companion object {
        const val MIN_HEIGHT = 1500
        const val MIN_WIDTH = 1000
    }

    suspend fun random(): WallpaperItem = withContext(Dispatchers.IO) {
        repeat(3) {
            runCatching { fetchNekos() }.getOrNull()?.let { return@withContext it }
        }
        repeat(2) {
            runCatching { fetchLolicon() }.getOrNull()?.let { return@withContext it }
        }
        throw IllegalStateException("这次没抽到合适的图")
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
                if (height < MIN_HEIGHT || width < MIN_WIDTH) return null
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

    private fun fetchLolicon(): WallpaperItem? {
        val request = Request.Builder()
            .url("https://api.lolicon.app/setu/v2?num=1&r18=0&size=original&excludeAI=true")
            .header("User-Agent", userAgent)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body?.string() ?: return null
            val data = JSONObject(text).optJSONArray("data") ?: return null
            if (data.length() == 0) return null

            val item = data.getJSONObject(0)
            val width = item.optInt("width")
            val height = item.optInt("height")

            if (width > 0 && height > 0) {
                if (height < MIN_HEIGHT || width < MIN_WIDTH) return null
                if (width > height) return null
            }

            val urls = item.optJSONObject("urls") ?: return null
            val original = urls.optString("original").takeIf { it.isNotBlank() } ?: return null
            val pid = item.optLong("pid")
            val uid = item.optLong("uid")

            return WallpaperItem(
                imageUrl = original,
                artistName = item.optString("author").takeIf { it.isNotBlank() },
                artistUrl = if (uid > 0) "https://www.pixiv.net/users/$uid" else null,
                sourceUrl = if (pid > 0) "https://www.pixiv.net/artworks/$pid" else null,
                title = item.optString("title").takeIf { it.isNotBlank() },
                width = width,
                height = height,
                source = "Pixiv",
            )
        }
    }
}
