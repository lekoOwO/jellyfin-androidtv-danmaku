package org.jellyfin.androidtv.ui.playback.danmaku

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber
import java.io.IOException
import java.io.StringReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * dandanplay 弹幕 API 客户端，同时支持 jellyfin-plugin-danmu 的服务端 XML 弹幕接口。
 */
class DandanplayClient(okHttpClient: OkHttpClient) {

    private val client: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    data class AnimeResult(
        val animeId: Long,
        val animeTitle: String,
        val typeDescription: String,
        val type: String,
        val episodes: List<EpisodeResult>,
    )

    data class EpisodeResult(
        val episodeId: Long,
        val episodeTitle: String,
    )

    /**
     * 按番剧名称搜索剧集
     */
    suspend fun searchEpisodes(apiBaseUrl: String, animeName: String): List<AnimeResult> = withContext(Dispatchers.IO) {
        val encodedName = URLEncoder.encode(animeName, Charsets.UTF_8.name())
        val url = "${apiV2(apiBaseUrl)}/search/episodes?anime=$encodedName"
        parseSearchResponse(JSONObject(requestString(url)))
    }

    /**
     * 获取指定剧集的弹幕（含第三方关联源）
     */
    suspend fun getComments(apiBaseUrl: String, episodeId: Long, chConvert: Int): List<DanmakuComment> =
        withContext(Dispatchers.IO) {
            val url = "${apiV2(apiBaseUrl)}/comment/$episodeId?withRelated=true&chConvert=$chConvert"
            parseCommentsResponse(JSONObject(requestString(url)))
        }

    /**
     * 通过视频播放页 URL 临时获取额外弹幕源。
     */
    suspend fun getCommentsByUrl(
        apiBaseUrl: String,
        videoUrl: String,
        chConvert: Int,
    ): List<DanmakuComment> = withContext(Dispatchers.IO) {
        val encodedUrl = URLEncoder.encode(videoUrl.trim(), Charsets.UTF_8.name())
        val url = "${apiV2(apiBaseUrl)}/extcomment?chConvert=$chConvert&url=$encodedUrl"
        parseCommentsResponse(JSONObject(requestString(url)))
    }

    /**
     * 从 jellyfin-plugin-danmu 服务端插件获取 XML 弹幕
     */
    suspend fun getPluginXmlComments(
        serverBaseUrl: String,
        itemId: String,
        accessToken: String?,
    ): List<DanmakuComment>? = withContext(Dispatchers.IO) {
        val url = buildString {
            append(serverBaseUrl.trimEnd('/'))
            append("/api/danmu/")
            append(itemId)
            append("/raw")
            if (!accessToken.isNullOrEmpty()) {
                append("?api_key=")
                append(accessToken)
            }
        }
        val xmlText = runCatching { requestString(url) }
            .onFailure { e -> Timber.d(e, "Failed to load XML danmaku from server plugin") }
            .getOrNull()
            ?.takeIf { text -> text.isNotBlank() }
            ?: return@withContext null

        runCatching { parseXmlComments(xmlText) }
            .onFailure { e -> Timber.w(e, "Failed to parse XML danmaku") }
            .getOrNull()
            ?.takeIf { comments -> comments.isNotEmpty() }
    }

    @Suppress("MagicNumber", "NestedBlockDepth")
    private fun parseXmlComments(xmlText: String): List<DanmakuComment> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xmlText))

        val result = mutableListOf<DanmakuComment>()
        var currentP: String? = null
        val textBuilder = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> if (parser.name == "d") {
                    currentP = parser.getAttributeValue(null, "p")
                    textBuilder.setLength(0)
                }
                XmlPullParser.TEXT -> if (currentP != null) {
                    textBuilder.append(parser.text)
                }
                XmlPullParser.END_TAG -> if (parser.name == "d") {
                    val p = currentP
                    if (p != null) {
                        val parts = p.split(',')
                        if (parts.size >= 4) {
                            val sender = parts.getOrElse(6) { "" }
                            val converted = "${parts[0]},${parts[1]},${parts[3]},$sender"
                            DanmakuComment.fromDandanplay(converted, textBuilder.toString())?.let(result::add)
                        }
                    }
                    currentP = null
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun apiV2(apiBaseUrl: String): String {
        val base = apiBaseUrl.trim().trimEnd('/')
        if (base.isEmpty()) throw IOException("Danmaku API base URL is empty")
        return if (base.endsWith("/api/v2")) base else "$base/api/v2"
    }

    private fun requestString(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/xml, */*")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected HTTP code ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 20L

        internal fun parseSearchResponse(json: JSONObject): List<AnimeResult> {
            val animes = json.optJSONArray("animes") ?: return emptyList()
            return buildList {
                for (i in 0 until animes.length()) {
                    val anime = animes.optJSONObject(i) ?: continue
                    parseAnime(anime)?.let { add(it) }
                }
            }
        }

        private fun parseAnime(anime: JSONObject): AnimeResult? {
            // A missing episodes array is invalid; an explicitly empty array is still a valid search result.
            val episodes = anime.optJSONArray("episodes") ?: return null
            return AnimeResult(
                animeId = anime.optLong("animeId"),
                animeTitle = anime.optString("animeTitle"),
                typeDescription = anime.optString("typeDescription"),
                type = anime.optString("type"),
                episodes = parseEpisodes(episodes),
            )
        }

        private fun parseEpisodes(episodes: JSONArray): List<EpisodeResult> = buildList {
            for (i in 0 until episodes.length()) {
                val episode = episodes.optJSONObject(i) ?: continue
                add(EpisodeResult(episode.optLong("episodeId"), episode.optString("episodeTitle")))
            }
        }

        internal fun parseCommentsResponse(json: JSONObject): List<DanmakuComment> {
            val comments = json.optJSONArray("comments") ?: return emptyList()
            return buildList {
                for (i in 0 until comments.length()) {
                    val comment = comments.optJSONObject(i) ?: continue
                    DanmakuComment.fromDandanplay(comment.optString("p"), comment.optString("m"))?.let { add(it) }
                }
            }
        }
    }
}
