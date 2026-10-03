package moe.ouom.neriplayer.platform.lyrics.api.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.platform.lyrics.api.codec.decodeKugouKrcDownloadPayload
import moe.ouom.neriplayer.platform.lyrics.api.codec.decodeKugouLyricDownload
import moe.ouom.neriplayer.platform.lyrics.api.codec.parseKugouLyricCandidates
import moe.ouom.neriplayer.platform.lyrics.api.codec.parseKugouSearchResults
import moe.ouom.neriplayer.platform.lyrics.matching.kugouLyricSearchKeyword
import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouLyricCandidate
import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouLyricsPayload
import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouSongSearchResult
import moe.ouom.neriplayer.common.logging.NPLogger
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class KugouLyricsClient(private val okHttpClient: OkHttpClient) {

    suspend fun searchSongs(keyword: String, limit: Int = SEARCH_LIMIT): List<KugouSongSearchResult> =
        withContext(Dispatchers.IO) {
            if (keyword.isBlank()) return@withContext emptyList()
            try {
                val url = "http://mobilecdn.kugou.com/api/v3/search/song".toHttpUrl().newBuilder()
                    .addQueryParameter("format", "json")
                    .addQueryParameter("keyword", keyword)
                    .addQueryParameter("page", "1")
                    .addQueryParameter("pagesize", limit.coerceIn(1, SEARCH_LIMIT).toString())
                    .addQueryParameter("showtype", "1")
                    .build()
                val body = executeString(url.toString()) ?: return@withContext emptyList()
                parseKugouSearchResults(body)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NPLogger.d(TAG, "Kugou search failed: ${error.message}")
                emptyList()
            }
        }

    /**
     * 按 [KugouSongSearchResult] 检索歌词候选
     *
     * `keyword`/`duration`/`hash` 在歌词站都是可缺省参数: 时长未知(`durationMs <= 0`)时
     * 不能发 `duration=0` 假装有时长, 否则歌词站会按 0 毫秒去比对而漏掉候选;
     * 反过来只用 hash 也足够精确。因此三个参数都只在有值时带上。
     */
    suspend fun searchLyricCandidates(song: KugouSongSearchResult): List<KugouLyricCandidate> {
        val builder = "https://lyrics.kugou.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("ver", "1")
            .addQueryParameter("man", "yes")
            .addQueryParameter("client", "pc")
        kugouLyricSearchKeyword(
            title = song.title,
            artist = song.artist
        )?.let { keyword -> builder.addQueryParameter("keyword", keyword) }
        song.hash.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { hash -> builder.addQueryParameter("hash", hash) }
        song.durationMs
            .takeIf { it > 0L }
            ?.let { durationMs -> builder.addQueryParameter("duration", durationMs.toString()) }
        val body = executeString(builder.build().toString()) ?: return emptyList()
        return parseKugouLyricCandidates(body)
    }

    suspend fun downloadKrcLyric(candidate: KugouLyricCandidate): KugouLyricsPayload? {
        val url = "https://lyrics.kugou.com/download".toHttpUrl().newBuilder()
            .addQueryParameter("ver", "1")
            .addQueryParameter("client", "mobi")
            .addQueryParameter("id", candidate.id)
            .addQueryParameter("accesskey", candidate.accessKey)
            .addQueryParameter("fmt", "krc")
            .addQueryParameter("charset", "utf8")
            .build()
        val body = executeString(url.toString()) ?: return null
        return decodeKugouKrcDownloadPayload(body)
    }

    suspend fun downloadLrcLyric(candidate: KugouLyricCandidate): KugouLyricsPayload? {
        val url = "https://lyrics.kugou.com/download".toHttpUrl().newBuilder()
            .addQueryParameter("ver", "1")
            .addQueryParameter("client", "pc")
            .addQueryParameter("id", candidate.id)
            .addQueryParameter("accesskey", candidate.accessKey)
            .addQueryParameter("fmt", "lrc")
            .addQueryParameter("charset", "utf8")
            .build()
        val body = executeString(url.toString()) ?: return null
        return decodeKugouLyricDownload(body)?.let(::KugouLyricsPayload)
    }

    private suspend fun executeString(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                NPLogger.d(TAG, "Kugou request returned ${response.code} for ${request.url.host}")
                return null
            }
            response.body.string().takeIf { it.isNotBlank() }
        }
    }

    companion object {
        private const val TAG = "KugouLyricsClient"
        private const val SEARCH_LIMIT = 8
        private const val USER_AGENT = "NeriPlayer/1.0 (https://github.com/cwuom/NeriPlayer)"
    }
}
