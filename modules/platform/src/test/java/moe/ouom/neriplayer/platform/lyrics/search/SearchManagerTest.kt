package moe.ouom.neriplayer.platform.lyrics.search

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.platform.search.api.SearchApi
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongDetails
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SearchManagerTest {
    private val manager = SearchManager { error("selection must not access a client") }

    @Test
    fun `selectBestSearchCandidate accepts only a nearby same-song candidate`() {
        val result = manager.selectBestSearchCandidate(
            songName = "Signal",
            songArtist = "Artist One / Artist Two",
            songDurationMs = 180_000L,
            candidates = listOf(
                candidate(id = "wrong-artist", singer = "Another Artist", duration = "3:00"),
                candidate(id = "wrong-duration", duration = "3:30"),
                candidate(
                    id = "match",
                    singer = "Artist Two/Artist One",
                    duration = "3:08"
                )
            )
        )

        assertEquals("match", result?.id)
    }

    @Test
    fun `selectBestSearchCandidate rejects unknown or distant duration`() {
        val nearbyCandidate = candidate(id = "nearby", duration = "3:08")

        assertNull(
            manager.selectBestSearchCandidate(
                songName = "Signal",
                songArtist = "Artist One",
                songDurationMs = 0L,
                candidates = listOf(nearbyCandidate)
            )
        )
        assertNull(
            manager.selectBestSearchCandidate(
                songName = "Signal",
                songArtist = "Artist One",
                songDurationMs = 180_000L,
                candidates = listOf(candidate(id = "distant", duration = "4:00"))
            )
        )
    }

    @Test
    fun `search resolves current provider and keeps first ten results`() = runTest {
        val platforms = mutableListOf<MusicPlatform>()
        val queries = mutableListOf<Pair<String, Int>>()
        var currentApi = fakeApi { keyword, page ->
            queries += keyword to page
            List(12) { candidate("$it", duration = "3:00") }
        }
        val search = SearchManager { platform ->
            platforms += platform
            currentApi
        }
        assertEquals((0..9).map(Int::toString), search.search("Signal", MusicPlatform.QQ_MUSIC).map { it.id })
        currentApi = fakeApi { _, _ -> emptyList() }
        assertEquals(emptyList<SongSearchInfo>(), search.search("new", MusicPlatform.CLOUD_MUSIC))
        assertEquals(listOf(MusicPlatform.QQ_MUSIC, MusicPlatform.CLOUD_MUSIC), platforms)
        assertEquals(listOf("Signal" to 1), queries)
    }

    @Test
    fun `automatic match can use cloud when QQ search fails`() = runTest {
        val platforms = mutableListOf<MusicPlatform>()
        val expected = candidate("match", duration = "3:00")
        val search = SearchManager { platform ->
            platforms += platform
            fakeApi { _, _ ->
                if (platform == MusicPlatform.QQ_MUSIC) throw IOException("unavailable")
                listOf(expected)
            }
        }
        assertSame(expected, search.findBestSearchCandidate("Signal", "Artist One", 180_000L))
        assertEquals(listOf(MusicPlatform.QQ_MUSIC, MusicPlatform.CLOUD_MUSIC), platforms)
    }

    @Test
    fun `unknown duration skips automatic network lookup`() = runTest {
        assertNull(manager.findBestSearchCandidate("Signal", "Artist One", 0L))
    }

    @Test
    fun `automatic match stops source lookup on cancellation`() {
        val failure = CancellationException("cancelled")
        val platforms = mutableListOf<MusicPlatform>()
        val search = SearchManager { platform ->
            platforms += platform
            fakeApi { _, _ -> throw failure }
        }
        val actual = assertThrows(CancellationException::class.java) {
            runTest { search.findBestSearchCandidate("Signal", "Artist One", 180_000L) }
        }
        assertSame(failure, generateSequence<Throwable>(actual) { it.cause }.last())
        assertEquals(listOf(MusicPlatform.QQ_MUSIC), platforms)
    }

    @Test
    fun `manual search propagates request errors and cancellation`() {
        for (failure in listOf(IOException("offline"), CancellationException("cancelled"))) {
            val search = SearchManager { fakeApi { _, _ -> throw failure } }
            val actual = assertThrows(failure.javaClass) {
                runTest { search.search("Signal", MusicPlatform.QQ_MUSIC) }
            }
            assertSame(failure, generateSequence<Throwable>(actual) { it.cause }.last())
        }
    }

    // ---- 按目标平台匹配 (跨平台加歌) ----

    /** 跨平台加歌必须只查目标平台: 拿到别的平台 id 会加错歌 */
    @Test
    fun `platform match only queries the requested platform`() = runTest {
        val platforms = mutableListOf<MusicPlatform>()
        val match = candidate(id = "kugou-hash", duration = "3:00")
            .copy(source = MusicPlatform.KUGOU)
        val search = SearchManager { platform ->
            platforms += platform
            fakeApi { _, _ -> if (platform == MusicPlatform.KUGOU) listOf(match) else emptyList() }
        }

        val result = search.findBestCandidateOnPlatform(
            platform = MusicPlatform.KUGOU,
            songName = "Signal",
            songArtist = "Artist One",
            songDurationMs = 180_000L
        )

        assertSame(match, result)
        assertEquals(listOf(MusicPlatform.KUGOU), platforms)
    }

    @Test
    fun `platform match returns null when nothing is close enough`() = runTest {
        val search = SearchManager { fakeApi { _, _ -> listOf(candidate("far", duration = "4:30")) } }

        assertNull(
            search.findBestCandidateOnPlatform(
                platform = MusicPlatform.CLOUD_MUSIC,
                songName = "Signal",
                songArtist = "Artist One",
                songDurationMs = 180_000L
            )
        )
    }

    /** 没有时长就没法验证版本, 不该打网络 (调用方据此提示"未找到匹配") */
    @Test
    fun `platform match without a duration skips the network`() = runTest {
        val platforms = mutableListOf<MusicPlatform>()
        val search = SearchManager { platform ->
            platforms += platform
            fakeApi { _, _ -> listOf(candidate("match", duration = "3:00")) }
        }

        assertNull(
            search.findBestCandidateOnPlatform(
                platform = MusicPlatform.CLOUD_MUSIC,
                songName = "Signal",
                songArtist = "Artist One",
                songDurationMs = 0L
            )
        )
        assertEquals(emptyList<MusicPlatform>(), platforms)
    }

    /** 搜索失败必须抛出, 让调用方区分"搜索失败"与"匹配不到" */
    @Test
    fun `platform match propagates search failures`() {
        val failure = IOException("offline")
        val search = SearchManager { fakeApi { _, _ -> throw failure } }

        val actual = assertThrows(IOException::class.java) {
            runTest {
                search.findBestCandidateOnPlatform(
                    platform = MusicPlatform.KUGOU,
                    songName = "Signal",
                    songArtist = "Artist One",
                    songDurationMs = 180_000L
                )
            }
        }
        assertSame(failure, generateSequence<Throwable>(actual) { it.cause }.last())
    }

    private fun fakeApi(search: suspend (String, Int) -> List<SongSearchInfo>): SearchApi =
        object : SearchApi {
            override suspend fun search(keyword: String, page: Int) = search.invoke(keyword, page)
            override suspend fun getSongInfo(id: String): SongDetails = error("unexpected detail lookup")
        }

    private fun candidate(
        id: String,
        singer: String = "Artist One",
        duration: String
    ): SongSearchInfo {
        return SongSearchInfo(
            id = id,
            songName = "Signal",
            singer = singer,
            duration = duration,
            source = MusicPlatform.CLOUD_MUSIC,
            albumName = null,
            coverUrl = null
        )
    }
}
