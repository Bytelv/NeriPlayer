package moe.ouom.neriplayer.core.player.metadata

import android.util.LruCache
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.SongSourceTags
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherChannels
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchRequest
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.platform.kugou.lyrics.KugouPlaybackLyricOrigin
import moe.ouom.neriplayer.platform.kugou.lyrics.KugouPlaybackLyricResult
import moe.ouom.neriplayer.platform.kugou.lyrics.KugouPlaybackLyricsResolver
import moe.ouom.neriplayer.platform.lyrics.repository.EditableLyricsMatcher
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

/**
 * 播放期酷狗取词接入点
 *
 * 酷狗音源曲目的歌词必须按 FileHash 走酷狗歌词站, 而不是拿 hash 合成的 `song.id`
 * 去网易云碰运气。
 */
class KugouPlaybackLyricsResolutionTest {

    @Test
    fun `kugou source songs take over platform lyrics until another source is matched`() {
        assertTrue(shouldResolveKugouPlatformLyrics(kugouSong()))
        assertTrue(
            shouldResolveKugouPlatformLyrics(
                kugouSong().copy(matchedLyricSource = MusicPlatform.KUGOU)
            )
        )
        // 已经匹配到别的来源(手动或自动)时不接管
        assertFalse(
            shouldResolveKugouPlatformLyrics(
                kugouSong().copy(matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "42")
            )
        )
        assertFalse(shouldResolveKugouPlatformLyrics(neteaseSong()))
    }

    @Test
    fun `preferred kugou source resolves kugou tracks by hash`() = runTest {
        val song = kugouSong()
        val resolver = mock(KugouPlaybackLyricsResolver::class.java)
        val neteaseClient = mock(NeteaseClient::class.java)
        `when`(resolver.resolveFor(song)).thenReturn(
            KugouPlaybackLyricResult(
                lyrics = "[1000,900](1000,300,0)hash",
                origin = KugouPlaybackLyricOrigin.KUGOU_HASH
            )
        )

        val result = PlayerLyricsProvider.tryGetPreferredLyricSourceResult(
            song = song,
            preference = LyricSourcePreference.Kugou,
            preferWordTimed = false,
            editableLyricsMatcher = mock(EditableLyricsMatcher::class.java),
            neteaseClient = neteaseClient,
            neteaseLyricsCache = neteaseLyricsCache(),
            kugouPlaybackLyricsResolver = resolver
        )

        assertEquals(LyricSourcePreference.Kugou, result?.source)
        assertEquals("hash", result?.lyrics?.single()?.text)
        verify(resolver).resolveFor(song)
        verifyNoInteractions(neteaseClient)
    }

    @Test
    fun `preferred kugou source falls back to kugou text matching when hash lookup misses`() = runTest {
        val song = kugouSong().copy(name = "Kugou Text Fallback Signal")
        val resolver = mock(KugouPlaybackLyricsResolver::class.java)
        `when`(resolver.resolveFor(song)).thenReturn(null)
        val matcher = mock(EditableLyricsMatcher::class.java)
        `when`(
            matcher.matchHighConfidenceLyricsForSource(
                kugouTextMatchRequest(song),
                EditableLyricMatchSource.KUGOU
            )
        ).thenReturn(emptyList())

        val result = preferredKugouResult(song, matcher, resolver)

        // hash 与文本检索都没命中时返回 null, 交给上层既有回退路径
        assertNull(result)
        verify(matcher).matchHighConfidenceLyricsForSource(
            kugouTextMatchRequest(song),
            EditableLyricMatchSource.KUGOU
        )
    }

    @Test
    fun `non kugou tracks never consult the kugou resolver`() = runTest {
        val song = neteaseSong()
        val resolver = mock(KugouPlaybackLyricsResolver::class.java)
        val matcher = mock(EditableLyricsMatcher::class.java)
        `when`(
            matcher.matchHighConfidenceLyricsForSource(
                kugouTextMatchRequest(song),
                EditableLyricMatchSource.KUGOU
            )
        ).thenReturn(emptyList())

        val result = preferredKugouResult(song, matcher, resolver)

        assertNull(result)
        verifyNoInteractions(resolver)
    }

    private suspend fun preferredKugouResult(
        song: SongItem,
        matcher: EditableLyricsMatcher,
        resolver: KugouPlaybackLyricsResolver
    ) = PlayerLyricsProvider.tryGetPreferredLyricSourceResult(
        song = song,
        preference = LyricSourcePreference.Kugou,
        preferWordTimed = false,
        editableLyricsMatcher = matcher,
        neteaseClient = mock(NeteaseClient::class.java),
        neteaseLyricsCache = neteaseLyricsCache(),
        kugouPlaybackLyricsResolver = resolver
    )

    /**
     * 与 `PlayerLyricsProvider` 里构造的文本匹配请求保持一致
     *
     * 这里刻意不用 Mockito 匹配器: Kotlin 的非空参数会在调用点插入 null 检查,
     * 而 `any()`/`eq()` 本身返回 null, 用真实参数匹配更稳。
     */
    private fun kugouTextMatchRequest(song: SongItem) = EditableLyricMatchRequest(
        keyword = listOf(song.name, song.artist)
            .filter { it.isNotBlank() }
            .joinToString(" "),
        trackName = song.name,
        artistName = song.artist,
        albumName = song.album,
        durationMs = song.durationMs,
        preferWordTimed = false,
        sources = setOf(EditableLyricMatchSource.KUGOU)
    )

    private fun neteaseLyricsCache(): LruCache<Long, NeteaseLyricsCacheEntry> {
        @Suppress("UNCHECKED_CAST")
        return mock(LruCache::class.java) as LruCache<Long, NeteaseLyricsCacheEntry>
    }

    private fun kugouSong(): SongItem = SongItem(
        id = 12_345L,
        name = "Kugou Hash Lookup Signal",
        artist = "Artist One",
        album = SongSourceTags.KUGOU,
        albumId = 0L,
        durationMs = 180_000L,
        coverUrl = null,
        mediaUri = "https://kugou.example/stream.mp3",
        channelId = ListenTogetherChannels.KUGOU,
        audioId = "HASH-KUGOU-HASH-LOOKUP"
    )

    private fun neteaseSong(): SongItem = SongItem(
        id = 4_242L,
        name = "Netease Signal",
        artist = "Artist One",
        album = "Album One",
        albumId = 0L,
        durationMs = 180_000L,
        coverUrl = null,
        mediaUri = "https://music.163.com/song/media/outer/url?id=4242.mp3"
    )

    /** 让桩与校验共用同一组实参, 避免录制/校验参数不一致 */
    private suspend fun KugouPlaybackLyricsResolver.resolveFor(song: SongItem): KugouPlaybackLyricResult? =
        resolve(
            hash = song.audioId,
            title = song.name,
            artist = song.artist,
            durationMs = song.durationMs,
            album = null
        )
}
