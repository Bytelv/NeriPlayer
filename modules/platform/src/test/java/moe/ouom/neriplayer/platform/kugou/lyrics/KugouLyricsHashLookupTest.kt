package moe.ouom.neriplayer.platform.kugou.lyrics

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouLyricCandidate
import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouLyricsPayload
import moe.ouom.neriplayer.data.model.lyrics.kugou.KugouSongSearchResult
import moe.ouom.neriplayer.platform.lyrics.api.client.KugouLyricsClient
import moe.ouom.neriplayer.platform.lyrics.repository.KugouLyricsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

/**
 * 播放期取词: FileHash 精确检索优先, 文本检索只做兜底
 */
class KugouLyricsHashLookupTest {

    private val client = mock(KugouLyricsClient::class.java)
    private val repository = KugouLyricsRepository(client)

    private val hash = "FILEHASH"
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
    fun `hash lookup hits without any text search`() = runTest {
        `when`(client.searchLyricCandidates(hashSong)).thenReturn(listOf(candidate))
        `when`(client.downloadKrcLyric(candidate))
            .thenReturn(KugouLyricsPayload("[1000,900](1000,300,0)hash"))

        val lookup = repository.getBestLyricPayloadForPlayback(
            hash = hash,
            title = "Signal",
            artist = "Artist",
            durationMs = 180_000L
        )

        assertTrue(lookup?.usedHash == true)
        assertEquals("[1000,900](1000,300,0)hash", lookup?.payload?.lyrics)
        // 用精确关键词而不是 anyString(): searchSongs 带默认参数, 混用匹配器会
        // 触发 Mockito 的 InvalidUseOfMatchersException
        verify(client, never()).searchSongs("Artist - Signal")
    }

    @Test
    fun `unknown duration is still sent as a hash lookup`() = runTest {
        // 时长未知时仍然要用 FileHash 精确检索: 只有传了 (hash, durationMs = 0) 才会命中这个桩
        val unknownDurationSong = hashSong.copy(durationMs = 0L)
        `when`(client.searchLyricCandidates(unknownDurationSong)).thenReturn(listOf(candidate))
        `when`(client.downloadLrcLyric(candidate)).thenReturn(KugouLyricsPayload("[00:01.00]line"))

        val lookup = repository.getBestLyricPayloadForPlayback(
            hash = hash,
            title = "Signal",
            artist = "Artist",
            durationMs = 0L
        )

        assertTrue(lookup?.usedHash == true)
        assertEquals("[00:01.00]line", lookup?.payload?.lyrics)
        verify(client).searchLyricCandidates(unknownDurationSong)
    }

    @Test
    fun `hash miss falls back to text search and reports the layer`() = runTest {
        val textSong = hashSong.copy(id = "other", hash = "OTHER", durationMs = 181_000L)
        `when`(client.searchLyricCandidates(hashSong)).thenReturn(emptyList())
        `when`(client.searchSongs("Artist - Signal")).thenReturn(listOf(textSong))
        `when`(client.searchLyricCandidates(textSong)).thenReturn(listOf(candidate))
        `when`(client.downloadKrcLyric(candidate))
            .thenReturn(KugouLyricsPayload("[1000,900](1000,300,0)text"))

        val lookup = repository.getBestLyricPayloadForPlayback(
            hash = hash,
            title = "Signal",
            artist = "Artist",
            durationMs = 180_000L
        )

        assertFalse(lookup?.usedHash ?: true)
        assertEquals("[1000,900](1000,300,0)text", lookup?.payload?.lyrics)

        val ordered = inOrder(client)
        ordered.verify(client).searchLyricCandidates(hashSong)
        ordered.verify(client).searchSongs("Artist - Signal")
        ordered.verify(client).searchLyricCandidates(textSong)
    }

    @Test
    fun `text search ranks the duration compatible candidate first`() = runTest {
        val offDuration = hashSong.copy(id = "off", hash = "OFF", durationMs = 300_000L)
        val onDuration = hashSong.copy(id = "on", hash = "ON", durationMs = 180_000L)
        val offCandidate = candidate.copy(id = "off-candidate")
        val onCandidate = candidate.copy(id = "on-candidate")
        `when`(client.searchLyricCandidates(hashSong)).thenReturn(emptyList())
        `when`(client.searchSongs("Artist - Signal")).thenReturn(listOf(offDuration, onDuration))
        `when`(client.searchLyricCandidates(onDuration)).thenReturn(listOf(onCandidate))
        `when`(client.downloadKrcLyric(onCandidate))
            .thenReturn(KugouLyricsPayload("[1000,900](1000,300,0)on-duration"))

        val lookup = repository.getBestLyricPayloadForPlayback(
            hash = hash,
            title = "Signal",
            artist = "Artist",
            durationMs = 180_000L
        )

        assertEquals("[1000,900](1000,300,0)on-duration", lookup?.payload?.lyrics)
        verify(client, never()).searchLyricCandidates(offDuration)
        verify(client, never()).downloadKrcLyric(offCandidate)
    }

    @Test
    fun `blank hash goes straight to text search`() = runTest {
        val textSong = hashSong.copy(id = "other", hash = "OTHER")
        `when`(client.searchSongs("Artist - Signal")).thenReturn(listOf(textSong))
        `when`(client.searchLyricCandidates(textSong)).thenReturn(emptyList())

        assertNull(
            repository.getBestLyricPayloadForPlayback(
                hash = "  ",
                title = "Signal",
                artist = "Artist",
                durationMs = 180_000L
            )
        )
        verify(client, never()).searchLyricCandidates(hashSong)
    }

    @Test
    fun `blank lyric payload does not count as a hash hit`() = runTest {
        val textSong = hashSong.copy(id = "other", hash = "OTHER")
        `when`(client.searchLyricCandidates(hashSong)).thenReturn(listOf(candidate))
        `when`(client.downloadKrcLyric(candidate)).thenReturn(KugouLyricsPayload("   "))
        `when`(client.downloadLrcLyric(candidate)).thenReturn(null)
        `when`(client.searchSongs("Artist - Signal")).thenReturn(listOf(textSong))
        `when`(client.searchLyricCandidates(textSong)).thenReturn(emptyList())

        assertNull(
            repository.getBestLyricPayloadForPlayback(
                hash = hash,
                title = "Signal",
                artist = "Artist",
                durationMs = 180_000L
            )
        )
        verify(client).searchSongs("Artist - Signal")
    }

    @Test
    fun `title only keyword is used when artist is blank`() = runTest {
        `when`(client.searchSongs("Signal")).thenReturn(emptyList())

        assertNull(
            repository.getBestLyricPayloadForPlayback(
                hash = null,
                title = "Signal",
                artist = "  ",
                durationMs = 0L
            )
        )
        verify(client).searchSongs("Signal")
    }
}
