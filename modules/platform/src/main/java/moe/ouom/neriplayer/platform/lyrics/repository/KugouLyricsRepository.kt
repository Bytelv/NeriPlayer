package moe.ouom.neriplayer.platform.lyrics.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.platform.lyrics.api.client.KugouLyricsClient
import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouLyricsPayload
import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouSongSearchResult
import moe.ouom.neriplayer.platform.lyrics.matching.kugouLyricSearchKeyword
import moe.ouom.neriplayer.platform.lyrics.matching.rankKugouLyricCandidates
import moe.ouom.neriplayer.platform.lyrics.matching.rankKugouSongsForPlayback
import moe.ouom.neriplayer.common.logging.NPLogger

/**
 * 播放期取词结果
 *
 * [usedHash] 明确标记命中的是"FileHash 精确检索"还是"文本检索兜底", 让上层的
 * 回退链与日志能区分这两层, 而不是靠猜。
 */
data class KugouPlaybackLyricLookup(
    val payload: KugouLyricsPayload,
    val usedHash: Boolean
)

class KugouLyricsRepository(private val client: KugouLyricsClient) {
    suspend fun searchSongs(keyword: String, limit: Int = 8): List<KugouSongSearchResult> =
        client.searchSongs(keyword, limit)

    suspend fun getBestLyrics(song: KugouSongSearchResult): String? = getBestLyricPayload(song)?.lyrics

    suspend fun getBestLyricPayload(song: KugouSongSearchResult): KugouLyricsPayload? = withContext(Dispatchers.IO) {
        try {
            val candidates = rankKugouLyricCandidates(
                candidates = client.searchLyricCandidates(song),
                expectedDurationMs = song.durationMs
            )
            for (candidate in candidates) {
                client.downloadKrcLyric(candidate)?.let { payload ->
                    return@withContext payload
                }
            }
            for (candidate in candidates) {
                client.downloadLrcLyric(candidate)?.let { payload ->
                    return@withContext payload
                }
            }
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.d(TAG, "Kugou lyric lookup failed: ${error.message}")
            null
        }
    }

    /**
     * 播放期酷狗音源取词
     *
     * 队列条目的 [hash] 就是酷狗 FileHash, 比"歌手 - 歌名"文本检索精确得多,
     * 因此先用 hash 检索; 未命中(或没有 hash)再退回文本检索。
     * [durationMs] 允许为 0: 这里的入口不因缺时长而放弃检索。
     */
    suspend fun getBestLyricPayloadForPlayback(
        hash: String?,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null
    ): KugouPlaybackLyricLookup? = withContext(Dispatchers.IO) {
        try {
            val normalizedHash = hash?.trim().orEmpty()
            if (normalizedHash.isNotEmpty()) {
                getBestLyricPayload(
                    KugouSongSearchResult(
                        id = normalizedHash,
                        hash = normalizedHash,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = durationMs
                    )
                )?.takeIf { it.lyrics.isNotBlank() }?.let { payload ->
                    return@withContext KugouPlaybackLyricLookup(payload = payload, usedHash = true)
                }
                NPLogger.d(
                    TAG,
                    "酷狗 hash 检索未命中, 回退文本检索: hash=$normalizedHash, title=$title"
                )
            }
            getBestLyricPayloadByTextSearch(
                title = title,
                artist = artist,
                durationMs = durationMs
            )?.let { payload ->
                return@withContext KugouPlaybackLyricLookup(payload = payload, usedHash = false)
            }
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.d(TAG, "Kugou playback lyric lookup failed: ${error.message}")
            null
        }
    }

    private suspend fun getBestLyricPayloadByTextSearch(
        title: String,
        artist: String,
        durationMs: Long
    ): KugouLyricsPayload? {
        val keyword = kugouLyricSearchKeyword(title = title, artist = artist) ?: return null
        val candidates = client.searchSongs(keyword)
        val ranked = rankKugouSongsForPlayback(
            candidates = candidates,
            title = title,
            artist = artist,
            durationMs = durationMs
        )
        for (song in ranked) {
            getBestLyricPayload(song)?.takeIf { it.lyrics.isNotBlank() }?.let { payload ->
                NPLogger.d(TAG, "酷狗文本检索命中: keyword=$keyword, hash=${song.hash}")
                return payload
            }
        }
        return null
    }

    private companion object {
        const val TAG = "KugouLyricsRepository"
    }
}
