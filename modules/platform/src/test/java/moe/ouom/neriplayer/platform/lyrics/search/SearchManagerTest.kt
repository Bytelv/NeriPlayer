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
        duration: String,
        songName: String = "Signal"
    ): SongSearchInfo {
        return SongSearchInfo(
            id = id,
            songName = songName,
            singer = singer,
            duration = duration,
            source = MusicPlatform.CLOUD_MUSIC,
            albumName = null,
            coverUrl = null
        )
    }

    // -------------------------------------------------- 多歌手写法差异 (真机回归)

    /**
     * 上游用 `、` 拼接多个歌手, 且可能重复出现
     *
     * 该分隔符**不在** artistSeparatorRegex 里, 所以歌手会被当成单个元素;
     * 若再要求"歌手集合完全相等", 就会出现明明搜到了却判为没有匹配。
     * 这里锁住"存在真实重叠即可"。
     */
    @Test
    fun `multi artist separated by ideographic comma still matches`() {
        val result = manager.selectBestSearchCandidate(
            songName = "Sisters and Brothers",
            songArtist = "Sofia",
            songDurationMs = 114_000L,
            candidates = listOf(
                candidate(
                    id = "5A6F4097FFFF0CD2E7D8C728FEDD4347",
                    singer = "The Cast of Sofia the First、Sofia、Sofia",
                    duration = "1:54",
                    songName = "Sisters and Brothers"
                )
            )
        )

        assertEquals("5A6F4097FFFF0CD2E7D8C728FEDD4347", result?.id)
    }

    /** 歌手顺序不同、或一方是另一方的子串, 都应视为同一批演唱者 */
    @Test
    fun `artist order and containment still match`() {
        assertEquals(
            "reordered",
            manager.selectBestSearchCandidate(
                songName = "Signal",
                songArtist = "Artist Two / Artist One",
                songDurationMs = 180_000L,
                candidates = listOf(
                    candidate(id = "reordered", singer = "Artist One、Artist Two", duration = "3:00")
                )
            )?.id
        )

        assertEquals(
            "contained",
            manager.selectBestSearchCandidate(
                songName = "Signal",
                songArtist = "Sofia",
                songDurationMs = 180_000L,
                candidates = listOf(
                    candidate(id = "contained", singer = "Sofia the First", duration = "3:00")
                )
            )?.id
        )
    }

    /** 歌名相同但演唱者完全无关时仍必须拒绝, 避免命中同名不同版本 */
    @Test
    fun `same title with unrelated artist is still rejected`() {
        assertNull(
            manager.selectBestSearchCandidate(
                songName = "Signal",
                songArtist = "Artist One",
                songDurationMs = 180_000L,
                candidates = listOf(
                    candidate(id = "cover", singer = "Totally Different Band", duration = "3:00")
                )
            )
        )
    }

    /** 时长明显不符的候选不能因为歌手匹配就被接受 */
    @Test
    fun `artist overlap does not override duration mismatch`() {
        assertNull(
            manager.selectBestSearchCandidate(
                songName = "Signal",
                songArtist = "Artist One",
                songDurationMs = 180_000L,
                candidates = listOf(
                    candidate(id = "far", singer = "Artist One", duration = "9:30")
                )
            )
        )
    }

    /**
     * 同名不同版本共存时必须选中时长相符的那一个
     *
     * 真实数据(酷狗搜 "SISTERS AND BROTHERS"): 结果页同时存在 114s 的影视版、
     * 164s/218s 的其它翻唱, 以及 166s 的原版。原曲时长 166788ms 只能匹配原版。
     */
    @Test
    fun `same title picks the duration compatible version`() {
        val result = manager.selectBestSearchCandidate(
            songName = "SISTERS AND BROTHERS",
            songArtist = "Kanye West / Ye",
            songDurationMs = 166_788L,
            candidates = listOf(
                candidate(
                    id = "animation-114",
                    singer = "The Cast of Sofia the First、Sofia、Sofia",
                    duration = "1:54",
                    songName = "Sisters and Brothers"
                ),
                candidate(
                    id = "cover-164",
                    singer = "Coxai",
                    duration = "2:44",
                    songName = "Sisters and Brothers (Explicit)"
                ),
                candidate(
                    id = "cover-218",
                    singer = "Julia St. Louis、Emilio Foglio",
                    duration = "3:38",
                    songName = "Sisters and Brothers"
                ),
                candidate(
                    id = "original-166",
                    singer = "Ye (侃爷)、Ye",
                    duration = "2:46",
                    songName = "SISTERS AND BROTHERS"
                )
            )
        )

        // 只有原版时长落在容差内 (166.8s vs 166s), 其余版本差 50s 以上
        assertEquals("original-166", result?.id)
    }
}
