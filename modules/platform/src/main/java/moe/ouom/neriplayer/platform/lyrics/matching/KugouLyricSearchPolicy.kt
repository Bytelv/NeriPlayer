package moe.ouom.neriplayer.platform.lyrics.matching

import kotlin.math.abs
import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouLyricCandidate
import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouSongSearchResult

/**
 * 播放期文本兜底检索的候选上限
 *
 * hash 精确检索才是主路径, 文本检索只在 hash 未命中时使用, 因此不必像
 * 元数据匹配那样铺开取很多候选。
 */
internal const val KUGOU_PLAYBACK_LYRIC_CANDIDATE_LIMIT = 5

/**
 * 酷狗歌词站的关键词约定是 `歌手 - 歌名`
 *
 * 歌名或歌手缺失时只发另一部分, 两者都缺失时返回 null 表示"不要带 keyword 参数"
 * (歌词站允许只用 hash 检索)。
 */
fun kugouLyricSearchKeyword(title: String, artist: String): String? {
    val normalizedTitle = title.trim()
    val normalizedArtist = artist.trim()
    return when {
        normalizedTitle.isEmpty() && normalizedArtist.isEmpty() -> null
        normalizedArtist.isEmpty() -> normalizedTitle
        normalizedTitle.isEmpty() -> normalizedArtist
        else -> "$normalizedArtist - $normalizedTitle"
    }
}

/**
 * 文本兜底候选的排序
 *
 * 时长已知时优先时长兼容的候选; 时长未知(`durationMs <= 0`)时不参与过滤与排序,
 * 只按歌名/歌手相似度挑, 避免"队列没有时长就整条歌词链路放弃"。
 */
fun rankKugouSongsForPlayback(
    candidates: List<KugouSongSearchResult>,
    title: String,
    artist: String,
    durationMs: Long
): List<KugouSongSearchResult> {
    return candidates.asSequence()
        .filter { song ->
            isLyricDetailLookupDurationAllowed(
                expectedDurationMs = durationMs,
                candidateDurationMs = song.durationMs
            )
        }
        .sortedWith(
            compareByDescending<KugouSongSearchResult> {
                durationMs > 0L && it.durationMs > 0L &&
                    isExternalLyricDurationCompatible(durationMs, it.durationMs)
            }
                .thenByDescending { scoreLyricMatchTitle(title, it.title) }
                .thenByDescending { scoreLyricMatchArtist(artist, it.artist) }
                .thenByDescending { scoreLyricMatchDuration(durationMs, it.durationMs) }
        )
        .take(KUGOU_PLAYBACK_LYRIC_CANDIDATE_LIMIT)
        .toList()
}

/**
 * 歌词候选的排序
 *
 * 时长未知时只按歌词站给的匹配分排, 不能拿 `|candidateDuration - 0|` 当距离 ——
 * 那会把"最短的候选"排到最前。
 */
fun rankKugouLyricCandidates(
    candidates: List<KugouLyricCandidate>,
    expectedDurationMs: Long
): List<KugouLyricCandidate> {
    if (expectedDurationMs <= 0L) {
        return candidates.sortedByDescending { it.score }
    }
    return candidates.sortedWith(
        compareByDescending<KugouLyricCandidate> { it.score }
            .thenBy { abs(it.durationMs - expectedDurationMs) }
    )
}
