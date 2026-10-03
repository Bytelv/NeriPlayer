package moe.ouom.neriplayer.platform.lyrics.matching

import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouLyricCandidate
import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouSongSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KugouLyricSearchPolicyTest {

    @Test
    fun `keyword follows the artist dash title convention`() {
        assertEquals("Artist - Signal", kugouLyricSearchKeyword(title = "Signal", artist = "Artist"))
        assertEquals("Signal", kugouLyricSearchKeyword(title = " Signal ", artist = "  "))
        assertEquals("Artist", kugouLyricSearchKeyword(title = "", artist = "Artist"))
        assertNull(kugouLyricSearchKeyword(title = "  ", artist = ""))
    }

    @Test
    fun `lyric candidates keep score order when the expected duration is unknown`() {
        val longer = KugouLyricCandidate("longer", "key", 300_000L, 90)
        val shorter = KugouLyricCandidate("shorter", "key", 60_000L, 90)

        // 时长未知时不能拿 |candidate - 0| 当距离, 否则最短的候选会被排到最前
        assertEquals(
            listOf(longer, shorter),
            rankKugouLyricCandidates(listOf(longer, shorter), expectedDurationMs = 0L)
        )
    }

    @Test
    fun `lyric candidates prefer duration compatible ones when the expected duration is known`() {
        val near = KugouLyricCandidate("near", "key", 180_000L, 90)
        val far = KugouLyricCandidate("far", "key", 220_000L, 90)
        val lowerScore = KugouLyricCandidate("lower", "key", 180_000L, 80)

        assertEquals(
            listOf(near, far, lowerScore),
            rankKugouLyricCandidates(listOf(lowerScore, far, near), expectedDurationMs = 180_000L)
        )
    }

    @Test
    fun `song candidates are not dropped when the expected duration is unknown`() {
        val noDuration = kugouSong(hash = "no-duration", durationMs = 0L)
        val longDuration = kugouSong(hash = "long", durationMs = 300_000L)

        assertEquals(
            listOf(noDuration, longDuration),
            rankKugouSongsForPlayback(
                candidates = listOf(noDuration, longDuration),
                title = "Signal",
                artist = "Artist",
                durationMs = 0L
            )
        )
    }

    @Test
    fun `song candidates with incompatible duration are dropped and trimmed to the limit`() {
        val expected = 180_000L
        val incompatible = kugouSong(hash = "incompatible", durationMs = 600_000L)
        val compatible = (1..KUGOU_PLAYBACK_LYRIC_CANDIDATE_LIMIT + 2).map { index ->
            kugouSong(hash = "compatible-$index", durationMs = expected)
        }

        val ranked = rankKugouSongsForPlayback(
            candidates = listOf(incompatible) + compatible,
            title = "Signal",
            artist = "Artist",
            durationMs = expected
        )

        assertEquals(KUGOU_PLAYBACK_LYRIC_CANDIDATE_LIMIT, ranked.size)
        assertEquals(false, ranked.any { it.hash == incompatible.hash })
    }

    @Test
    fun `title matches are ranked ahead of unrelated candidates`() {
        val unrelated = kugouSong(hash = "unrelated", durationMs = 180_000L, title = "Completely Different")
        val matching = kugouSong(hash = "matching", durationMs = 180_000L)

        assertEquals(
            listOf(matching, unrelated),
            rankKugouSongsForPlayback(
                candidates = listOf(unrelated, matching),
                title = "Signal",
                artist = "Artist",
                durationMs = 180_000L
            )
        )
    }

    private fun kugouSong(
        hash: String,
        durationMs: Long,
        title: String = "Signal"
    ) = KugouSongSearchResult(
        id = hash,
        hash = hash,
        title = title,
        artist = "Artist",
        album = null,
        durationMs = durationMs
    )
}
