package moe.ouom.neriplayer.core.player.resolver.netease

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
 * File: moe.ouom.neriplayer.core.player.resolver.netease/PlayerManagerNeteaseAutoKugouSource
 */

import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.quality.effectiveKugouQuality
import moe.ouom.neriplayer.core.player.runtime.refresh.RefreshResolverSideEffects
import moe.ouom.neriplayer.core.player.url.buildKugouPlaybackAudioInfo
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.kugou.KugouAudioQuality
import moe.ouom.neriplayer.data.model.kugou.KugouPlayUrl
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackUrlCandidate
import moe.ouom.neriplayer.data.model.playback.SongUrlResult
import moe.ouom.neriplayer.platform.kugou.api.KugouApiException

/** 每个检索词最多考察的候选数 */
private const val NETEASE_AUTO_KUGOU_SEARCH_LIMIT = 6

/** 低于这个分数认为不是同一首歌, 宁可放弃也不要放错版本 */
private const val NETEASE_AUTO_KUGOU_MIN_ACCEPT_SCORE = 70

/**
 * 时长容差
 *
 * 打分函数在时长差超过 45 秒时给 0 分, 而"标题相同(55) + 歌手命中(25) = 80"
 * 已经超过接受阈值 —— 也就是说**光看分数, 时长完全不匹配的候选也会被接受**。
 *
 * 酷狗是版权曲库, 同名歌曲往往同时存在原版/live/remix/翻唱, 放到耳朵里会很突兀,
 * 因此这里额外加一道硬闸门: 双方时长都已知且差距超过该值时直接不接受。
 * 任一时长未知则不拦(避免因为缺元数据而误伤正确的歌)。
 */
private const val NETEASE_AUTO_KUGOU_MAX_DURATION_DELTA_MS = 45_000L

/** 除主链外最多预置几个备用候选 */
private const val NETEASE_AUTO_KUGOU_FALLBACK_LIMIT = 2

private val kugouAutoSourceCacheKeyUnsafeRegex = Regex("[^A-Za-z0-9_.-]+")

/**
 * 网易云不可用时, 在**酷狗**查找同一首歌
 *
 * 这是自动换源的第一优先平台: 酷狗是完整版权音源, 命中时比在 B 站找投稿视频
 * 更接近原曲, 音质也更可控。找不到时由调用方继续退到 B 站, 见
 * [tryResolveNeteaseAutoBiliSource]。
 *
 * 匹配口径与 B 站侧完全一致: 标题/歌手/时长统一交给
 * [scoreNeteaseAutoBiliText], 并用同一档最低分卡住弱匹配, 这样换源顺序改变时
 * 的命中行为可预期。
 *
 * @return 命中时返回可直接播放的结果; 未开启 / 无候选 / 分数不足时返回 null
 */
internal suspend fun PlayerManager.tryResolveNeteaseAutoKugouSource(
    song: SongItem,
    sideEffects: RefreshResolverSideEffects
): SongUrlResult? {
    if (!neteaseAutoSourceSwitchEnabled) return null

    val queries = buildNeteaseAutoSourceQueries(song)
    if (queries.isEmpty()) return null

    NPLogger.w(
        "NERI-PlayerManager",
        "Netease source unavailable, trying Kugou auto source: " +
            "song=${song.name}, artist=${song.artist}"
    )

    val preferredQuality = effectiveKugouQuality()
    val visitedHashes = mutableSetOf<String>()
    var primaryResult: SongUrlResult.Success? = null
    val fallbackResults = mutableListOf<PlaybackUrlCandidate>()

    for (query in queries) {
        val candidates = fetchKugouAutoSourceCandidates(query)
            .sortedByDescending { scoreKugouAutoSourceCandidate(song, it) }
            .take(NETEASE_AUTO_KUGOU_SEARCH_LIMIT)

        for (candidate in candidates) {
            val hash = candidate.id.trim()
            if (hash.isEmpty() || !visitedHashes.add(hash)) continue

            val score = scoreKugouAutoSourceCandidate(song, candidate)
            if (score < NETEASE_AUTO_KUGOU_MIN_ACCEPT_SCORE) {
                NPLogger.d(
                    "NERI-PlayerManager",
                    "Skip weak Kugou auto source match: " +
                        "song=${song.name}, hash=$hash, score=$score"
                )
                continue
            }

            val candidateDurationMs = parseKugouAutoSourceDurationMs(candidate.duration)
            if (!isKugouAutoSourceDurationAcceptable(song.durationMs, candidateDurationMs)) {
                NPLogger.w(
                    "NERI-PlayerManager",
                    "Skip Kugou auto source with mismatched duration: song=${song.name}, " +
                        "hash=$hash, expected=${song.durationMs}ms, actual=${candidateDurationMs}ms"
                )
                continue
            }

            val result = resolveKugouAutoSourceCandidate(
                song = song,
                hash = hash,
                candidateDurationMs = candidateDurationMs,
                preferredQuality = preferredQuality,
                sideEffects = sideEffects
            ) ?: continue

            if (primaryResult == null) {
                primaryResult = result
            } else {
                fallbackResults += result.toAutoSourceCandidate()
                if (fallbackResults.size >= NETEASE_AUTO_KUGOU_FALLBACK_LIMIT) {
                    return primaryResult.copy(fallbackCandidates = fallbackResults)
                }
            }
        }
    }

    primaryResult?.let { result ->
        NPLogger.w("NERI-PlayerManager", "Kugou auto source selected: song=${song.name}")
        return result.copy(fallbackCandidates = fallbackResults)
    }

    NPLogger.w(
        "NERI-PlayerManager",
        "Kugou auto source not found: song=${song.name}, artist=${song.artist}"
    )
    return null
}

private fun SongUrlResult.Success.toAutoSourceCandidate(): PlaybackUrlCandidate {
    return PlaybackUrlCandidate(
        url = url,
        candidateUrls = candidateUrls,
        mimeType = mimeType,
        expectedContentLength = expectedContentLength,
        audioInfo = audioInfo,
        representationIdentity = representationIdentity,
        cacheKeyOverride = cacheKeyOverride
    )
}

private suspend fun PlayerManager.fetchKugouAutoSourceCandidates(
    query: String
): List<SongSearchInfo> {
    return runCatching {
        kugouSearchApi.search(keyword = query, page = 1)
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        NPLogger.w(
            "NERI-PlayerManager",
            "Kugou auto source search failed: query=$query, error=${error.message}"
        )
        emptyList()
    }
}

/**
 * 解析搜索结果的时长
 *
 * 酷狗返回 `m:ss` 或 `h:mm:ss`; 解析不出来时返回 0, 由打分函数按"时长未知"
 * 处理, 不会因此直接否决候选。
 */
internal fun parseKugouAutoSourceDurationMs(raw: String?): Long {
    val parts = raw?.trim()?.takeIf { it.isNotEmpty() }?.split(':') ?: return 0L
    if (parts.size !in 2..3) return 0L
    val values = parts.map { it.trim().toLongOrNull() ?: return 0L }
    val seconds = when (values.size) {
        2 -> {
            val (minutes, secs) = values
            if (minutes < 0L || secs !in 0L..59L) return 0L
            minutes * 60L + secs
        }

        else -> {
            val (hours, minutes, secs) = values
            if (hours < 0L || minutes !in 0L..59L || secs !in 0L..59L) return 0L
            hours * 3_600L + minutes * 60L + secs
        }
    }
    return seconds.takeIf { it > 0L }?.times(1_000L) ?: 0L
}

/**
 * 酷狗候选打分
 *
 * 直接复用 B 站侧的打分函数: 二者都是"标题 + 歌手 + 时长"三要素, 口径统一
 * 才能保证换源顺序变化时行为可预期。
 */
internal fun scoreKugouAutoSourceCandidate(
    song: SongItem,
    candidate: SongSearchInfo
): Int {
    val durationSec = (parseKugouAutoSourceDurationMs(candidate.duration) / 1000L).toInt()
    return scoreNeteaseAutoBiliText(
        song = song,
        title = candidate.songName,
        author = candidate.singer,
        durationSec = durationSec
    )
}

private suspend fun PlayerManager.resolveKugouAutoSourceCandidate(
    song: SongItem,
    hash: String,
    candidateDurationMs: Long,
    preferredQuality: String,
    sideEffects: RefreshResolverSideEffects
): SongUrlResult.Success? {
    val playUrl = runCatching {
        kugouPlaybackRepository.resolvePlayUrl(
            hash = hash,
            albumId = null,
            albumAudioId = null,
            preferredQuality = preferredQuality
        )
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        if (error is KugouApiException.SessionRequired) {
            // 未登录酷狗时该平台整体不可用: 交给 B 站兜底, 不当作播放错误上抛
            NPLogger.w("NERI-PlayerManager", "Kugou auto source requires login, skipping")
            return null
        }
        NPLogger.w(
            "NERI-PlayerManager",
            "Kugou auto source resolve failed: hash=$hash, error=${error.message}"
        )
        return null
    } ?: return null

    if (playUrl.url.isBlank()) return null

    val durationMs = playUrl.durationMs.takeIf { it > 0L }
        ?: candidateDurationMs.takeIf { it > 0L }
    if (durationMs != null && durationMs > 0L) {
        sideEffects.updateDuration {
            maybeUpdateSongDuration(song, durationMs)
        }
    }

    return SongUrlResult.Success(
        url = playUrl.url,
        candidateUrls = playUrl.candidateUrls().drop(1),
        durationMs = durationMs,
        mimeType = null,
        expectedContentLength = playUrl.fileSize.takeIf { it > 0L },
        audioInfo = buildKugouPlaybackAudioInfo(
            playUrl = playUrl,
            requestedQualityKey = preferredQuality,
            fallbackDurationMs = song.durationMs,
            getLocalizedString = { getLocalizedString(it) }
        ),
        cacheKeyOverride = buildNeteaseAutoKugouCacheKey(
            hash = hash,
            qualityKey = playUrl.quality
        )
    )
}

/**
 * 时长闸门
 *
 * 任一方时长未知(<=0)时放行: 缺元数据不应该让正确的歌被拒。
 */
internal fun isKugouAutoSourceDurationAcceptable(
    expectedDurationMs: Long,
    candidateDurationMs: Long
): Boolean {
    if (expectedDurationMs <= 0L || candidateDurationMs <= 0L) return true
    return kotlin.math.abs(candidateDurationMs - expectedDurationMs) <=
        NETEASE_AUTO_KUGOU_MAX_DURATION_DELTA_MS
}

internal fun buildNeteaseAutoKugouCacheKey(hash: String, qualityKey: String?): String {
    val hashPart = kugouAutoSourceCacheKeyUnsafeRegex
        .replace(hash.trim(), "_")
        .trim('_')
        .ifBlank { "unknown" }
    val qualityPart = qualityKey
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }
        ?.let { kugouAutoSourceCacheKeyUnsafeRegex.replace(it, "_").trim('_') }
        ?: KugouAudioQuality.High.apiValue
    return "kugou-auto-$hashPart-$qualityPart"
}
