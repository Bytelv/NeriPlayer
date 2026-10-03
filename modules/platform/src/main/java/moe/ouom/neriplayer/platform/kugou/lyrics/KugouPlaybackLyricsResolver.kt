package moe.ouom.neriplayer.platform.kugou.lyrics

import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.lyrics.lrclib.LrcLibResult
import moe.ouom.neriplayer.lyrics.parser.convertPlainLyricsToEntries
import moe.ouom.neriplayer.lyrics.parser.toEditableLyricsText
import moe.ouom.neriplayer.platform.lyrics.matching.isExternalLyricDurationCompatible
import moe.ouom.neriplayer.platform.lyrics.matching.isReliableLyricMatchIdentity
import moe.ouom.neriplayer.platform.lyrics.matching.kugouLyricSearchKeyword
import moe.ouom.neriplayer.platform.lyrics.repository.KugouLyricsRepository
import moe.ouom.neriplayer.platform.lyrics.repository.LrcLibLyricsRepository

/** 一次播放期取词最终命中在哪一层, 便于日志与测试断言回退链 */
enum class KugouPlaybackLyricOrigin {
    /** 按 FileHash 精确命中酷狗歌词站 */
    KUGOU_HASH,

    /** hash 未命中(或没有 hash), 按"歌手 - 歌名"文本检索命中酷狗歌词站 */
    KUGOU_TEXT_SEARCH,

    /** 酷狗歌词站没有可用候选, 回退到 LRCLIB */
    LRCLIB
}

data class KugouPlaybackLyricResult(
    val lyrics: String,
    val translatedLyrics: String? = null,
    val origin: KugouPlaybackLyricOrigin
) {
    fun isEmpty(): Boolean = lyrics.isBlank()
}

/**
 * 酷狗音源曲目的播放期取词回退链
 *
 * 队列条目的 `audioId` 就是酷狗 FileHash, 属于**精确**身份, 因此顺序固定为:
 * 1. hash 精确检索酷狗歌词站 ([KugouPlaybackLyricOrigin.KUGOU_HASH])
 * 2. 酷狗歌词站文本检索 ([KugouPlaybackLyricOrigin.KUGOU_TEXT_SEARCH])
 * 3. LRCLIB 关键词检索 ([KugouPlaybackLyricOrigin.LRCLIB])
 *
 * 时长未知(`durationMs <= 0`)时不会放弃: 酷狗歌词站的 `duration` 只是匹配提示,
 * hash 本身足够精确; LRCLIB 侧改用不带时长的候选检索。
 */
class KugouPlaybackLyricsResolver(
    private val kugouLyricsRepository: KugouLyricsRepository,
    private val lrcLibLyricsRepository: LrcLibLyricsRepository
) {

    suspend fun resolve(
        hash: String?,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null
    ): KugouPlaybackLyricResult? = withContext(Dispatchers.IO) {
        try {
            resolveFromKugou(
                hash = hash,
                title = title,
                artist = artist,
                durationMs = durationMs,
                album = album
            ) ?: resolveFromLrcLib(
                title = title,
                artist = artist,
                durationMs = durationMs
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            NPLogger.d(TAG, "酷狗播放期取词失败: ${error.message}")
            null
        }
    }

    private suspend fun resolveFromKugou(
        hash: String?,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?
    ): KugouPlaybackLyricResult? {
        val lookup = kugouLyricsRepository.getBestLyricPayloadForPlayback(
            hash = hash,
            title = title,
            artist = artist,
            durationMs = durationMs,
            album = album
        ) ?: return null
        if (lookup.payload.lyrics.isBlank()) return null
        return KugouPlaybackLyricResult(
            lyrics = lookup.payload.lyrics,
            translatedLyrics = lookup.payload.translatedLyrics?.takeIf { it.isNotBlank() },
            origin = if (lookup.usedHash) {
                KugouPlaybackLyricOrigin.KUGOU_HASH
            } else {
                KugouPlaybackLyricOrigin.KUGOU_TEXT_SEARCH
            }
        )
    }

    private suspend fun resolveFromLrcLib(
        title: String,
        artist: String,
        durationMs: Long
    ): KugouPlaybackLyricResult? {
        val keyword = kugouLyricSearchKeyword(title = title, artist = artist) ?: return null
        val candidates = lrcLibLyricsRepository.searchLyricsCandidates(keyword)
        val selected = selectLrcLibCandidate(
            candidates = candidates,
            title = title,
            artist = artist,
            durationMs = durationMs
        ) ?: return null
        val lyrics = buildLrcLibLyricText(selected, durationMs) ?: return null
        NPLogger.d(TAG, "酷狗歌词未命中, LRCLIB 兜底命中: '$title' by '$artist', keyword=$keyword")
        return KugouPlaybackLyricResult(
            lyrics = lyrics,
            translatedLyrics = null,
            origin = KugouPlaybackLyricOrigin.LRCLIB
        )
    }

    private companion object {
        const val TAG = "KugouPlaybackLyricsResolver"

        /**
         * LRCLIB 候选筛选
         *
         * 先要求歌名/歌手可靠匹配; 时长已知时再优先"时长兼容且带逐行时间轴"的候选,
         * 时长未知时只用身份匹配, 不因为缺时长丢掉候选。
         */
        fun selectLrcLibCandidate(
            candidates: List<LrcLibResult>,
            title: String,
            artist: String,
            durationMs: Long
        ): LrcLibResult? {
            return candidates.asSequence()
                .filter {
                    isReliableLyricMatchIdentity(
                        expectedTitle = title,
                        expectedArtist = artist,
                        candidateTitle = it.trackName,
                        candidateArtist = it.artistName
                    )
                }
                .filter { !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() }
                .sortedWith(
                    compareByDescending<LrcLibResult> { !it.syncedLyrics.isNullOrBlank() }
                        .thenByDescending {
                            // durationSeconds 是跨模块的 public 属性, K2 不允许 smart cast,
                            // 必须先取到局部变量
                            val candidateSeconds = it.durationSeconds
                            durationMs > 0L && candidateSeconds != null &&
                                isExternalLyricDurationCompatible(
                                    expectedDurationMs = durationMs,
                                    candidateDurationMs = candidateSeconds * 1_000L
                                )
                        }
                        .thenBy { candidateDurationDeltaMs(it.durationSeconds, durationMs) }
                )
                .firstOrNull()
        }

        fun candidateDurationDeltaMs(candidateDurationSeconds: Long?, expectedDurationMs: Long): Long {
            val candidateDurationMs = candidateDurationSeconds?.times(1_000L) ?: return Long.MAX_VALUE
            if (expectedDurationMs <= 0L) return 0L
            return abs(candidateDurationMs - expectedDurationMs)
        }

        /** LRCLIB 的纯文本歌词借候选自身的时长铺时间轴, 队列时长未知时也能用 */
        fun buildLrcLibLyricText(
            result: LrcLibResult,
            fallbackDurationMs: Long
        ): String? {
            result.syncedLyrics?.takeIf { it.isNotBlank() }?.let { return it }
            val plainLyrics = result.plainLyrics?.takeIf { it.isNotBlank() } ?: return null
            val durationMs = result.durationSeconds?.times(1_000L)?.takeIf { it > 0L }
                ?: fallbackDurationMs
            return convertPlainLyricsToEntries(plainLyrics, durationMs)
                .toEditableLyricsText()
                .takeIf { it.isNotBlank() }
                ?: plainLyrics
        }
    }
}
