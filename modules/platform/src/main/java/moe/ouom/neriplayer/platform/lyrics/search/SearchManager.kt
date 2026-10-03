package moe.ouom.neriplayer.platform.lyrics.search

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
 * File: moe.ouom.neriplayer.platform.lyrics.search/SearchManager
 * Created: 2025/8/17
 */

import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.platform.search.api.SearchApi
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.kugou.KugouDebugLog
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.platform.lyrics.matching.isExternalLyricDurationCompatible
private const val MINIMUM_MATCH_SCORE = 60

/** 诊断缓冲只保留一次匹配的摘要, 避免把缓冲区刷满 */
private const val MAX_LOGGED_REJECTIONS = 4
private const val MAX_MATCH_DIAGNOSTIC_CHARS = 320

class SearchManager(private val searchApi: (MusicPlatform) -> SearchApi) {

    private val whitespaceRegex by lazy(LazyThreadSafetyMode.PUBLICATION) { Regex("\\s+") }
    private val artistSeparatorRegex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Regex(
            "\\s*([/,\\u3001\\uFF0C&])\\s*|\\s+(feat\\.?|ft\\.?)\\s+|\\s+[xX]\\s+",
            RegexOption.IGNORE_CASE
        )
    }

    suspend fun search(
        keyword: String,
        platform: MusicPlatform,
    ): List<SongSearchInfo> = withContext(Dispatchers.IO) {
        val api = searchApi(platform)

        NPLogger.d("SearchManager", "try to search $keyword")
        try {
            api.search(keyword, page = 1).take(10)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NPLogger.e("SearchManager", "Failed to find match", e)
            throw e
        }
    }

    suspend fun findBestSearchCandidate(
        songName: String,
        songArtist: String,
        songDurationMs: Long
    ): SongSearchInfo? = withContext(Dispatchers.IO) {
        if (songDurationMs <= 0L) {
            NPLogger.d(
                "SearchManager",
                "Skipping automatic lyric match without a known duration: $songName / $songArtist"
            )
            return@withContext null
        }

        NPLogger.d("SearchManager", "try to match $songName / $songArtist / $songDurationMs")

        val searchResults = buildList {
            addAll(searchCandidates(songName, searchApi(MusicPlatform.QQ_MUSIC), "qq"))
            addAll(searchCandidates(songName, searchApi(MusicPlatform.CLOUD_MUSIC), "cloud"))
        }
        if (searchResults.isEmpty()) {
            return@withContext null
        }

        val candidate = selectBestSearchCandidate(
            songName = songName,
            songArtist = songArtist,
            songDurationMs = songDurationMs,
            candidates = searchResults
        )
        if (candidate == null) {
            NPLogger.d(
                "SearchManager",
                "No duration-compatible lyric match for $songName / $songArtist"
            )
        }
        candidate
    }

    /**
     * 只在指定平台内搜索并挑选最佳候选
     *
     * [findBestSearchCandidate] 固定查 QQ + 网易云, 那是给"找歌词"用的; 跨平台加歌
     * 必须拿到**目标平台自己的** id (网易云 songId / 酷狗 FileHash), 所以单独暴露
     * 一个按平台搜索的入口, 打分与时长校验仍复用同一套 [selectBestSearchCandidate]。
     *
     * 与 [findBestSearchCandidate] 的另一个区别: 搜索请求本身失败会**抛出异常**,
     * 而不是退化成"没有候选", 这样调用方能区分"匹配不到"和"搜索失败"。
     *
     * @param searchKeyword 实际发给平台的检索词, 默认就是 [songName]。
     *   只按歌名检索经常把正确版本挤出结果页 (同名翻唱/动画版会占满前面),
     *   带上歌手能显著提高命中率; 但**校验仍用 [songName]**, 两者分开传。
     * @return 没有可用候选时返回 null; 时长为 0 或歌名为空时不做任何网络请求
     */
    suspend fun findBestCandidateOnPlatform(
        platform: MusicPlatform,
        songName: String,
        songArtist: String,
        songDurationMs: Long,
        searchKeyword: String = songName
    ): SongSearchInfo? = withContext(Dispatchers.IO) {
        if (songName.isBlank() || songDurationMs <= 0L) {
            NPLogger.d(
                "SearchManager",
                "Skipping $platform match without a name/duration: $songName / $songArtist"
            )
            return@withContext null
        }

        val keyword = searchKeyword.trim().ifBlank { songName }
        val candidates = search(keyword = keyword, platform = platform)
        if (candidates.isEmpty()) {
            recordMatchDiagnostics(
                songName = songName,
                summary = "搜索无结果: keyword='$keyword', platform=$platform"
            )
            return@withContext null
        }

        selectBestSearchCandidate(
            songName = songName,
            songArtist = songArtist,
            songDurationMs = songDurationMs,
            candidates = candidates
        )
    }

    internal fun selectBestSearchCandidate(
        songName: String,
        songArtist: String,
        songDurationMs: Long,
        candidates: List<SongSearchInfo>
    ): SongSearchInfo? {
        val normalizedSongName = normalizeText(songName)
        val normalizedArtist = normalizeText(songArtist)
        val normalizedArtists = normalizeArtists(songArtist)
        if (
            normalizedSongName.isBlank() ||
            normalizedArtist.isBlank() ||
            normalizedArtists.isEmpty() ||
            songDurationMs <= 0L
        ) {
            recordMatchDiagnostics(
                songName = songName,
                summary = "跳过: 名称/歌手/时长为空 (durationMs=$songDurationMs)"
            )
            return null
        }

        // 记录拒绝原因, 便于在"搜到了却提示没匹配"时直接定位
        val rejections = mutableListOf<String>()

        val best = candidates.mapNotNull { candidate ->
            val candidateDurationMs = parseDurationMs(candidate.duration) ?: run {
                rejections += "${candidate.songName}: 时长无法解析(${candidate.duration})"
                return@mapNotNull null
            }
            if (!isExternalLyricDurationCompatible(songDurationMs, candidateDurationMs)) {
                rejections +=
                    "${candidate.songName}: 时长不符(期望${songDurationMs}ms 实际${candidateDurationMs}ms)"
                return@mapNotNull null
            }

            val candidateSongName = normalizeText(candidate.songName)
            val candidateArtist = normalizeText(candidate.singer)
            val candidateArtists = normalizeArtists(candidate.singer)

            // 标题必须一致或互相包含, 具体程度交给 scoreCandidate 打分
            if (
                candidateSongName != normalizedSongName &&
                !candidateSongName.contains(normalizedSongName) &&
                !normalizedSongName.contains(candidateSongName)
            ) {
                rejections += "${candidate.songName}: 标题不符(期望'$normalizedSongName')"
                return@mapNotNull null
            }

            /*
             * 歌手不做"集合完全相等"判断
             *
             * 上游歌手的写法极不统一: 同一批人可能用 "、" 拼接、顺序不同、甚至重复
             * 出现 (例如 "A、B、B"), 归一化成集合后反而与本地不等, 导致明明能匹配
             * 的歌曲被判为"没有匹配"。这里只要求存在**真实重叠**, 用来挡掉
             * 歌名相同但演唱者完全不同的版本; 具体程度仍由 scoreCandidate 打分,
             * 并以 MINIMUM_MATCH_SCORE 收口。
             *
             * 本地歌手为空时无从校验, 交给标题 + 时长把关。
             */
            if (normalizedArtist.isNotBlank() && candidateArtist.isNotBlank()) {
                val hasArtistOverlap =
                    candidateArtists.intersect(normalizedArtists).isNotEmpty() ||
                        candidateArtist.contains(normalizedArtist) ||
                        normalizedArtist.contains(candidateArtist)
                if (!hasArtistOverlap) {
                    rejections += "${candidate.songName}: 歌手无重叠(期望'$normalizedArtist' 实际'$candidateArtist')"
                    return@mapNotNull null
                }
            }

            SearchCandidateScore(
                candidate = candidate,
                score = scoreCandidate(
                    candidate = candidate,
                    targetSongName = normalizedSongName,
                    targetArtist = normalizedArtist,
                    targetArtists = normalizedArtists
                ),
                durationDeltaMs = abs(songDurationMs - candidateDurationMs)
            )
        }.filter {
            if (it.score < MINIMUM_MATCH_SCORE) {
                rejections +=
                    "${it.candidate.songName}: 分数不足(${it.score} < $MINIMUM_MATCH_SCORE)"
                false
            } else {
                true
            }
        }
            .sortedWith(
                compareByDescending<SearchCandidateScore> { it.score }
                    .thenBy { it.durationDeltaMs }
            )
            .firstOrNull()
            ?.candidate

        recordMatchDiagnostics(
            songName = songName,
            summary = buildString {
                append("候选=").append(candidates.size)
                append(", 命中=").append(best?.songName ?: "无")
                if (best == null) {
                    rejections.take(MAX_LOGGED_REJECTIONS).forEach {
                        append(" | 拒绝: ").append(it)
                    }
                }
            }
        )
        return best
    }

    /** 匹配诊断写入酷狗诊断缓冲: 设备上不便取 logcat, 这是唯一可见的通道 */
    private fun recordMatchDiagnostics(songName: String, summary: String) {
        runCatching {
            KugouDebugLog.record(
                label = "MATCH ${songName.take(40)}",
                detail = summary.take(MAX_MATCH_DIAGNOSTIC_CHARS)
            )
        }
    }

    private suspend fun searchCandidates(
        keyword: String,
        api: SearchApi,
        label: String
    ): List<SongSearchInfo> {
        return try {
            api.search(keyword, page = 1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NPLogger.w(
                "SearchManager",
                "Failed to search $label for $keyword: ${e.message}"
            )
            emptyList()
        }
    }

    private fun scoreCandidate(
        candidate: SongSearchInfo,
        targetSongName: String,
        targetArtist: String,
        targetArtists: Set<String>
    ): Int {
        val candidateSongName = normalizeText(candidate.songName)
        val candidateArtist = normalizeText(candidate.singer)
        val candidateArtists = normalizeArtists(candidate.singer)

        var score = when {
            candidateSongName == targetSongName -> 100
            candidateSongName.contains(targetSongName) || targetSongName.contains(candidateSongName) -> 60
            else -> 0
        }

        if (targetArtist.isNotBlank() || targetArtists.isNotEmpty()) {
            score += when {
                candidateArtist == targetArtist -> 40
                candidateArtists.intersect(targetArtists).isNotEmpty() -> 25
                candidateArtist.contains(targetArtist) || targetArtist.contains(candidateArtist) -> 15
                else -> 0
            }
        }

        if (!candidate.coverUrl.isNullOrBlank()) score += 2
        if (!candidate.albumName.isNullOrBlank()) score += 1
        return score
    }

    private fun parseDurationMs(value: String): Long? {
        val parts = value.trim().split(':')
        if (parts.size !in 2..3) return null
        val values = parts.map { it.toLongOrNull() ?: return null }
        val totalSeconds = when (values.size) {
            2 -> {
                val (minutes, seconds) = values
                if (minutes < 0L || seconds !in 0L..59L) return null
                minutes * 60L + seconds
            }

            3 -> {
                val (hours, minutes, seconds) = values
                if (hours < 0L || minutes !in 0L..59L || seconds !in 0L..59L) return null
                hours * 3_600L + minutes * 60L + seconds
            }

            else -> return null
        }
        return totalSeconds.takeIf { it > 0L }?.times(1_000L)
    }

    private fun normalizeText(value: String): String {
        return value.trim().lowercase().replace(whitespaceRegex, " ")
    }

    private fun normalizeArtists(value: String): Set<String> {
        return artistSeparatorRegex.split(value)
            .asSequence()
            .map(::normalizeText)
            .filter { it.isNotBlank() }
            .toSet()
    }

}
