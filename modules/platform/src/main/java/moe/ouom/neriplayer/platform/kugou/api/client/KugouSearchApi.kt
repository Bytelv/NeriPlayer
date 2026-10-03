package moe.ouom.neriplayer.platform.kugou.api.client

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.platform.kugou.api.client/KugouSearchApi
 */

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.kugou.KugouSong
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongDetails
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.platform.kugou.api.codec.extractKugouSongArray
import moe.ouom.neriplayer.platform.kugou.api.codec.parseKugouSong
import moe.ouom.neriplayer.platform.search.api.SearchApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * 酷狗搜索与歌曲详情
 *
 * 详情接口只下发基础元数据, 歌词由 `KugouLyricsClient` (官方歌词站) 单独负责,
 * 因此这里的 `SongDetails.lyric` 恒为 null, 界面走既有的歌词来源流程。
 */
class KugouSearchApi(
    private val client: KugouClient,
    private val debugLogging: Boolean = false
) : SearchApi {

    override suspend fun search(keyword: String, page: Int): List<SongSearchInfo> {
        val normalizedKeyword = keyword.trim()
        if (normalizedKeyword.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val raw = client.getRaw(
                    path = "/search",
                    query = mapOf(
                        "keywords" to normalizedKeyword,
                        "page" to page.coerceAtLeast(1).toString(),
                        "pagesize" to SEARCH_PAGE_SIZE.toString(),
                        "type" to "song"
                    )
                )
                parseSearchSongs(raw).map { song ->
                    SongSearchInfo(
                        id = song.hash,
                        songName = song.title,
                        singer = song.artist,
                        duration = formatDuration(song.durationMs),
                        source = MusicPlatform.KUGOU,
                        albumName = song.albumName,
                        coverUrl = song.coverUrl
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NPLogger.w(TAG, "酷狗搜索失败: keyword=$normalizedKeyword, ${error.message.orEmpty()}")
                throw error
            }
        }
    }

    /**
     * 酷狗的歌曲主键是 FileHash, 因此搜索结果的 `id` 就是 hash
     */
    override suspend fun getSongInfo(id: String): SongDetails {
        val hash = id.trim()
        if (hash.isEmpty()) throw IOException("酷狗歌曲 hash 为空")
        return withContext(Dispatchers.IO) {
            val song = findSongByHash(hash)
                ?: throw IOException("找不到 hash 为 $hash 的酷狗歌曲")
            SongDetails(
                id = song.hash,
                songName = song.title,
                singer = song.artist,
                album = song.albumName.orEmpty(),
                coverUrl = song.coverUrl,
                lyric = null,
                translatedLyric = null
            )
        }
    }

    /**
     * 详情接口需要 `album_audio_id`, 而调用方只持有 hash
     *
     * 这里用 hash 反查一次搜索接口补齐缺失的专辑信息; 查不到时仍返回仅含
     * hash 的最小结果, 让上层至少能保存歌手与歌名
     */
    internal suspend fun findSongByHash(hash: String): KugouSong? {
        val candidates = runCatching {
            val raw = client.getRaw(
                path = "/search",
                query = mapOf(
                    "keywords" to hash,
                    "page" to "1",
                    "pagesize" to SEARCH_PAGE_SIZE.toString(),
                    "type" to "song"
                )
            )
            parseSearchSongs(raw)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            NPLogger.w(TAG, "酷狗按 hash 反查失败: hash=$hash, ${error.message.orEmpty()}")
            emptyList()
        }
        return candidates.firstOrNull { it.hash.equals(hash, ignoreCase = true) }
    }

    internal fun parseSearchSongs(raw: String): List<KugouSong> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        val root: Any = try {
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        } catch (error: Exception) {
            NPLogger.w(TAG, "酷狗搜索结果解析失败: ${error.message.orEmpty()}")
            return emptyList()
        }
        val array = extractKugouSongArray(root) ?: return emptyList()
        if (debugLogging) {
            NPLogger.d(TAG, "酷狗搜索结果条目数: ${array.length()}")
        }
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::parseKugouSong)
        }
    }

    private fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "0:00"
        val totalSeconds = durationMs / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%d:%02d".format(minutes, seconds)
    }

    companion object {
        private const val TAG = "KugouSearchApi"
        private const val SEARCH_PAGE_SIZE = 20
    }
}
