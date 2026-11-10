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
 * 主源 nekos.best：速度快、稳定、自带画师名和 Pixiv 原图链接。
 * 备源 lolicon：Pixiv 图源，画质和构图更好，但服务偶尔不稳。
 *
 * 两个都会检查是不是竖图，横图对手机壁纸没意义，直接丢掉重来。
 */
class WallpaperRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val userAgent = "WallpaperApp/0.1 (Android; personal use)"

    suspend fun random(): WallpaperItem = withContext(Dispatchers.IO) {
        // 主源试两次，失败再换备源，保证点了「换一张」几乎不会空手而归
        runCatching { fetchNekos() }.getOrNull()?.let { return@withContext it }
        runCatching { fetchNekos() }.getOrNull()?.let { return@withContext it }
        runCatching { fetchLolicon() }.getOrNull()?.let { return@withContext it }
        throw IllegalStateException("所有图片源都连不上")
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

            // 横图不要
            if (width > 0 && height > 0 && width > height) return null

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
            val root = JSONObject(text)
            val data = root.optJSONArray("data") ?: return null
            if (data.length() == 0) return null

            val item = data.getJSONObject(0)
            val width = item.optInt("width")
            val height = item.optInt("height")
            if (width > 0 && height > 0 && width > height) return null

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
                source = "pixiv",
            )
        }
    }
}
