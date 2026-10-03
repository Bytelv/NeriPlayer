package moe.ouom.neriplayer.core.player.resolver.netease

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网易云自动换源的酷狗分支
 *
 * 酷狗是换源的第一优先平台(完整版权音源), B 站作为其后兜底, 因此这里的
 * 打分与时长解析必须与 B 站侧保持同一口径。
 */
class PlayerManagerNeteaseAutoKugouSourceTest {

    // ------------------------------------------------------------ 时长解析

    /** 酷狗搜索结果给的是 `m:ss`, 必须换算成毫秒供打分使用 */
    @Test
    fun parseDuration_convertsMinutesAndSeconds() {
        assertEquals(114_000L, parseKugouAutoSourceDurationMs("1:54"))
        assertEquals(166_000L, parseKugouAutoSourceDurationMs("2:46"))
        assertEquals(3_600_000L, parseKugouAutoSourceDurationMs("1:00:00"))
    }

    /** 解析不出来时按"时长未知"处理(返回 0), 而不是伪造一个值 */
    @Test
    fun parseDuration_returnsZeroForUnusableInput() {
        assertEquals(0L, parseKugouAutoSourceDurationMs(null))
        assertEquals(0L, parseKugouAutoSourceDurationMs(""))
        assertEquals(0L, parseKugouAutoSourceDurationMs("abc"))
        assertEquals(0L, parseKugouAutoSourceDurationMs("1"))
        assertEquals(0L, parseKugouAutoSourceDurationMs("0:00"))
        // 秒数越界(>=60)说明不是合法时长
        assertEquals(0L, parseKugouAutoSourceDurationMs("1:75"))
    }

    // ------------------------------------------------------------ 候选打分

    /**
     * 标题 + 歌手 + 时长都对上时必须超过接受阈值
     *
     * 复现真机场景: 本地 166.788s 的原版, 搜到时长 2:46(166s) 的同一首。
     */
    @Test
    fun score_acceptsExactMatch() {
        val score = scoreKugouAutoSourceCandidate(
            song = song(name = "SISTERS AND BROTHERS", artist = "Kanye West / Ye", durationMs = 166_788L),
            candidate = candidate(
                songName = "SISTERS AND BROTHERS",
                singer = "Ye (侃爷)、Ye",
                duration = "2:46"
            )
        )

        assertTrue("score=$score 应达到接受阈值", score >= 70)
    }

    /** 标题相同但时长差太多(影视版/翻唱)必须掉到阈值以下 */
    @Test
    fun score_rejectsSameTitleWithDistantDuration() {
        val score = scoreKugouAutoSourceCandidate(
            song = song(name = "SISTERS AND BROTHERS", artist = "Kanye West / Ye", durationMs = 166_788L),
            candidate = candidate(
                songName = "Sisters and Brothers",
                singer = "The Cast of Sofia the First、Sofia",
                duration = "1:54"
            )
        )

        assertTrue("score=$score 不应达到接受阈值", score < 70)
    }

    /** 完全无关的候选必须得低分 */
    @Test
    fun score_rejectsUnrelatedCandidate() {
        val score = scoreKugouAutoSourceCandidate(
            song = song(name = "晴天", artist = "周杰伦", durationMs = 269_000L),
            candidate = candidate(
                songName = "Sisters and Brothers",
                singer = "Coxai",
                duration = "2:44"
            )
        )

        assertTrue("score=$score 不应达到接受阈值", score < 70)
    }

    // ------------------------------------------------------------ 时长闸门

    /**
     * 时长明显不符时即使标题/歌手都对也必须拒绝
     *
     * 打分在时长差 >45s 时给 0 分, 但"标题 55 + 歌手 25 = 80" 已超过 70 阈值,
     * 因此必须靠这道闸门挡住同名不同版本(live/remix/翻唱)。
     */
    @Test
    fun durationGate_rejectsClearlyDifferentLength() {
        // 原版 166.8s vs 影视版 114s: 差 52.8s
        assertFalse(isKugouAutoSourceDurationAcceptable(166_788L, 114_000L))
        assertTrue(isKugouAutoSourceDurationAcceptable(166_788L, 166_000L))
        // 边界: 恰好 45s 放行, 超过则拒
        assertTrue(isKugouAutoSourceDurationAcceptable(180_000L, 225_000L))
        assertFalse(isKugouAutoSourceDurationAcceptable(180_000L, 225_001L))
    }

    /** 任一时长未知时不能拦, 否则缺元数据的正确歌曲会被误拒 */
    @Test
    fun durationGate_allowsUnknownDuration() {
        assertTrue(isKugouAutoSourceDurationAcceptable(0L, 114_000L))
        assertTrue(isKugouAutoSourceDurationAcceptable(166_788L, 0L))
        assertTrue(isKugouAutoSourceDurationAcceptable(0L, 0L))
    }

    // ------------------------------------------------------------ 缓存键

    @Test
    fun cacheKey_usesKugouAutoNamespace() {
        val key = buildNeteaseAutoKugouCacheKey("DA44597C1AB57B411792F2655DCF16BD", "320")

        assertTrue(key.startsWith("kugou-auto-"))
        assertFalse(key.startsWith("bili-auto-"))
        assertFalse(key.startsWith("netease-"))
    }

    /** 同一 hash + 音质必须稳定, 否则缓存反复失效 */
    @Test
    fun cacheKey_isStableAndDistinguishesQuality() {
        val hash = "DA44597C1AB57B411792F2655DCF16BD"
        assertEquals(
            buildNeteaseAutoKugouCacheKey(hash, "320"),
            buildNeteaseAutoKugouCacheKey(hash, "320")
        )
        assertFalse(
            buildNeteaseAutoKugouCacheKey(hash, "320") ==
                buildNeteaseAutoKugouCacheKey(hash, "flac")
        )
    }

    /** hash 里的非法字符要清洗掉, 空音质回退到默认档 */
    @Test
    fun cacheKey_sanitizesHashAndFallsBackToDefaultQuality() {
        val key = buildNeteaseAutoKugouCacheKey("AA:BB/CC", null)

        assertFalse("不应残留非法字符: $key", key.contains(':'))
        assertTrue("应回退到默认音质: $key", key.endsWith("320"))
    }

    // ------------------------------------------------------------ 工具

    private fun song(
        name: String,
        artist: String,
        durationMs: Long
    ) = SongItem(
        id = 1L,
        name = name,
        artist = artist,
        album = "album",
        albumId = 1L,
        durationMs = durationMs,
        coverUrl = null
    )

    private fun candidate(
        songName: String,
        singer: String,
        duration: String
    ) = SongSearchInfo(
        id = "DA44597C1AB57B411792F2655DCF16BD",
        songName = songName,
        singer = singer,
        duration = duration,
        source = MusicPlatform.KUGOU,
        albumName = null,
        coverUrl = null
    )
}
