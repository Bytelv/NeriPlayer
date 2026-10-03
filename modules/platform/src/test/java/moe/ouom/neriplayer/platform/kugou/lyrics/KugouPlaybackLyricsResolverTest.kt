package moe.ouom.neriplayer.platform.kugou.lyrics

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouLyricCandidate
import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouLyricsPayload
import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouSongSearchResult
import moe.ouom.neriplayer.data.model.lyrics.lrclib.LrcLibRecord
import moe.ouom.neriplayer.platform.lyrics.api.client.KugouLyricsClient
import moe.ouom.neriplayer.platform.lyrics.api.client.LrcLibClient
import moe.ouom.neriplayer.platform.lyrics.repository.KugouLyricsRepository
import moe.ouom.neriplayer.platform.lyrics.repository.LrcLibLyricsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

/**
 * 酷狗音源曲目播放期取词的回退链:
 * FileHash 精确检索 -> 酷狗文本检索 -> LRCLIB
 */
class KugouPlaybackLyricsResolverTest {

    private val kugouClient = mock(KugouLyricsClient::class.java)
    private val lrcLibClient = mock(LrcLibClient::class.java)
    private val resolver = KugouPlaybackLyricsResolver(
        kugouLyricsRepository = KugouLyricsRepository(kugouClient),
        lrcLibLyricsRepository = LrcLibLyricsRepository(lrcLibClient)
    )

    private val hash = "HASH123"
    private val hashSong = KugouSongSearchResult(
        id = hash,
        hash = hash,
        title = "Signal",
        artist = "Artist",
        album = null,
        durationMs = 180_000L
    )
    private val candidate = KugouLyricCandidate(
        id = "candidate",
        accessKey = "key",
        durationMs = 180_000L,
        score = 90
    )

    @Test
    fun `hash lookup is used before text search`() = runTest {
        `when`(kugouClient.searchLyricCandidates(hashSong)).thenReturn(listOf(candidate))
        `when`(kugouClient.downloadKrcLyric(candidate))
            .thenReturn(KugouLyricsPayload("[1000,900](1000,300,0)word"))

        val result = resolver.resolve(
            hash = hash,
            title = "Signal",
            artist = "Artist",
            durationMs = 180_000L
        )

        assertEquals(KugouPlaybackLyricOrigin.KUGOU_HASH, result?.origin)
        assertEquals("[1000,900](1000,300,0)word", result?.lyrics)
        verify(kugouClient).searchLyricCandidates(hashSong)
        // hash 命中时不应该再打一次文本检索; 这里用精确关键词, 避免
        // searchSongs 的默认参数与 Mockito 匹配器混用
        verify(kugouClient, never()).searchSongs("Artist - Signal")
        verifyNoMoreInteractions(lrcLibClient)
    }

    @Test
    fun `hash miss falls back to kugou text search`() = runTest {
        val textSong = hashSong.copy(
            id = "other",
            hash = "OTHER",
            durationMs = 179_000L
        )
        `when`(kugouClient.searchLyricCandidates(hashSong)).thenReturn(emptyList())
        `when`(kugouClient.searchSongs("Artist - Signal")).thenReturn(listOf(textSong))
        `when`(kugouClient.searchLyricCandidates(textSong)).thenReturn(listOf(candidate))
        `when`(kugouClient.downloadKrcLyric(candidate))
            .thenReturn(KugouLyricsPayload("[1000,900](1000,300,0)text"))

        val result = resolver.resolve(
            hash = hash,
            title = "Signal",
            artist = "Artist",
            durationMs = 180_000L
        )

        assertEquals(KugouPlaybackLyricOrigin.KUGOU_TEXT_SEARCH, result?.origin)
        assertEquals("[1000,900](1000,300,0)text", result?.lyrics)
    }

    @Test
    fun `unknown duration still queries by hash`() = runTest {
        val unknownDurationSong = hashSong.copy(durationMs = 0L)
        `when`(kugouClient.searchLyricCandidates(unknownDurationSong)).thenReturn(listOf(candidate))
        `when`(kugouClient.downloadKrcLyric(candidate))
            .thenReturn(KugouLyricsPayload("[1000,900](1000,300,0)zero-duration"))

        val result = resolver.resolve(
            hash = hash,
            title = "Signal",
            artist = "Artist",
            durationMs = 0L
        )

        assertEquals(KugouPlaybackLyricOrigin.KUGOU_HASH, result?.origin)
        assertEquals("[1000,900](1000,300,0)zero-duration", result?.lyrics)
        verify(kugouClient).searchLyricCandidates(unknownDurationSong)
    }

    @Test
    fun `kugou line timed fallback is used when word timing is unavailable`() = runTest {
        `when`(kugouClient.searchLyricCandidates(hashSong)).thenReturn(listOf(candidate))
        `when`(kugouClient.downloadKrcLyric(candidate)).thenReturn(null)
        `when`(kugouClient.downloadLrcLyric(candidate))
            .thenReturn(KugouLyricsPayload("[00:01.00]line timed"))

        val result = resolver.resolve(
            hash = hash,
            title = "Signal",
            artist = "Artist",
            durationMs = 180_000L
        )

        assertEquals(KugouPlaybackLyricOrigin.KUGOU_HASH, result?.origin)
        assertEquals("[00:01.00]line timed", result?.lyrics)
    }

    @Test
    fun `lrcLib is used when kugou has nothing`() = runTest {
        `when`(kugouClient.searchLyricCandidates(hashSong)).thenReturn(emptyList())
        `when`(kugouClient.searchSongs("Artist - Signal")).thenReturn(emptyList())
        `when`(lrcLibClient.searchLyrics("Artist - Signal")).thenReturn(
            listOf(
                LrcLibRecord(
                    syncedLyrics = "[00:01.00]one\n[00:05.00]two\n[00:09.00]three",
                    plainLyrics = null,
                    trackName = "Signal",
                    artistName = "Artist",
                    durationSeconds = 180L
                )
            )
        )

        val result = resolver.resolve(
            hash = hash,
            title = "Signal",
            artist = "Artist",
            durationMs = 180_000L
        )

        assertEquals(KugouPlaybackLyricOrigin.LRCLIB, result?.origin)
        assertEquals("[00:01.00]one\n[00:05.00]two\n[00:09.00]three", result?.lyrics)
    }

    @Test
    fun `lrcLib fallback stays usable when queue duration is unknown`() = runTest {
        val unknownDurationSong = hashSong.copy(durationMs = 0L)
        `when`(kugouClient.searchLyricCandidates(unknownDurationSong)).thenReturn(emptyList())
        `when`(kugouClient.searchSongs("Artist - Signal")).thenReturn(emptyList())
        `when`(lrcLibClient.searchLyrics("Artist - Signal")).thenReturn(
            listOf(
                LrcLibRecord(
                    syncedLyrics = null,
                    plainLyrics = "one\ntwo\nthree",
                    trackName = "Signal",
                    artistName = "Artist",
                    durationSeconds = 180L
                )
            )
        )

        val result = resolver.resolve(
            hash = hash,
            title = "Signal",
            artist = "Artist",
            durationMs = 0L
        )

        assertEquals(KugouPlaybackLyricOrigin.LRCLIB, result?.origin)
        assertTrue(result?.lyrics?.isNotBlank() == true)
    }

    @Test
    fun `lrcLib candidate with mismatched identity is rejected`() = runTest {
        `when`(kugouClient.searchLyricCandidates(hashSong)).thenReturn(emptyList())
        `when`(kugouClient.searchSongs("Artist - Signal")).thenReturn(emptyList())
        `when`(lrcLibClient.searchLyrics("Artist - Signal")).thenReturn(
            listOf(
                LrcLibRecord(
                    syncedLyrics = "[00:01.00]one\n[00:05.00]two\n[00:09.00]three",
                    plainLyrics = null,
                    trackName = "Totally Different",
                    artistName = "Another Artist",
                    durationSeconds = 180L
                )
            )
        )

        val result = resolver.resolve(
            hash = hash,
            title = "Signal",
            artist = "Artist",
            durationMs = 180_000L
        )

        assertNull(result)
    }

    @Test
    fun `missing hash goes straight to text search`() = runTest {
        val textSong = hashSong.copy(id = "other", hash = "OTHER")
        `when`(kugouClient.searchSongs("Artist - Signal")).thenReturn(listOf(textSong))
        `when`(kugouClient.searchLyricCandidates(textSong)).thenReturn(listOf(candidate))
        `when`(kugouClient.downloadKrcLyric(candidate))
            .thenReturn(KugouLyricsPayload("[1000,900](1000,300,0)no-hash"))

        val result = resolver.resolve(
            hash = "   ",
            title = "Signal",
            artist = "Artist",
            durationMs = 180_000L
        )

        assertEquals(KugouPlaybackLyricOrigin.KUGOU_TEXT_SEARCH, result?.origin)
        verify(kugouClient, never()).searchLyricCandidates(hashSong)
    }

    @Test
    fun `empty lyric text is not reported as a hit`() = runTest {
        `when`(kugouClient.searchLyricCandidates(hashSong)).thenReturn(listOf(candidate))
        `when`(kugouClient.downloadKrcLyric(candidate)).thenReturn(KugouLyricsPayload("   "))
        `when`(kugouClient.downloadLrcLyric(candidate)).thenReturn(null)
        `when`(kugouClient.searchSongs("Artist - Signal")).thenReturn(emptyList())
        `when`(lrcLibClient.searchLyrics("Artist - Signal")).thenReturn(emptyList())

        assertNull(
            resolver.resolve(
                hash = hash,
                title = "Signal",
                artist = "Artist",
                durationMs = 180_000L
            )
        )
    }
}
