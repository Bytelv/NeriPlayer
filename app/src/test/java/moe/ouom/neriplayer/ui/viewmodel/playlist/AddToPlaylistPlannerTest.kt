package moe.ouom.neriplayer.ui.viewmodel.playlist

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
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
 * File: moe.ouom.neriplayer.ui.viewmodel.playlist/AddToPlaylistPlannerTest
 */

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.data.model.playlist.AddToPlaylistPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨平台加歌的判定逻辑
 *
 * 覆盖三条主路径: 属于目标平台 → 直接添加 (不搜索); 不属于 → 搜索后取候选;
 * 匹配不到 / id 不可用 → 明确失败且不提交。
 */
class AddToPlaylistPlannerTest {

    private fun song(
        name: String = "测试歌曲",
        artist: String = "测试歌手",
        durationMs: Long = 180_000L,
        isNeteaseSource: Boolean = false,
        neteaseSongId: Long = 0L,
        isKugouSource: Boolean = false,
        kugouHash: String = ""
    ) = AddToPlaylistSong(
        name = name,
        artist = artist,
        durationMs = durationMs,
        isNeteaseSource = isNeteaseSource,
        neteaseSongId = neteaseSongId,
        isKugouSource = isKugouSource,
        kugouHash = kugouHash
    )

    private fun candidate(
        id: String,
        songName: String = "测试歌曲",
        singer: String = "测试歌手",
        platform: MusicPlatform = MusicPlatform.CLOUD_MUSIC
    ) = SongSearchInfo(
        id = id,
        songName = songName,
        singer = singer,
        duration = "3:00",
        source = platform,
        albumName = null,
        coverUrl = null
    )

    // ---------------------------------------------------------------- 直接添加

    @Test
    fun `netease source song is added directly without searching`() = runTest {
        var searchCalls = 0

        val result = resolveAddToPlaylistOutcome(
            song = song(isNeteaseSource = true, neteaseSongId = 42L),
            platform = AddToPlaylistPlatform.NETEASE,
            search = {
                searchCalls += 1
                PlatformSearchResult.Found(candidate("999"))
            }
        )

        assertEquals(AddToPlaylistResolution.NeteaseSongId(42L), result)
        assertEquals("属于网易云的歌曲不该触发搜索", 0, searchCalls)
    }

    @Test
    fun `kugou source song is added directly without searching`() = runTest {
        var searchCalls = 0
        val hash = "8E10D8825DDE03BCABBDE13E5A4150D2"

        val result = resolveAddToPlaylistOutcome(
            song = song(isKugouSource = true, kugouHash = hash),
            platform = AddToPlaylistPlatform.KUGOU,
            search = {
                searchCalls += 1
                PlatformSearchResult.Found(
                    candidate("OTHER", platform = MusicPlatform.KUGOU)
                )
            }
        )

        assertEquals(
            AddToPlaylistResolution.KugouHash(
                hash = hash,
                title = "测试歌曲",
                artist = "测试歌手"
            ),
            result
        )
        assertEquals("属于酷狗的歌曲不该触发搜索", 0, searchCalls)
    }

    /**
     * 形态不对的 hash 不能当 FileHash 用
     *
     * 真机回归: 网易云歌曲把数字 songId 放进 `audioId`, 被当成酷狗 hash 发出去,
     * 服务端回"歌曲列表不能为空" —— 因为 10 位数字根本不是 FileHash。
     */
    @Test
    fun `non hash shaped value is not used as a kugou hash`() = runTest {
        var searchCalls = 0

        val result = resolveAddToPlaylistOutcome(
            song = song(
                name = "九月底",
                artist = "余佳运",
                isKugouSource = false,
                // 典型的网易云 songId 形态
                kugouHash = "1325711261"
            ),
            platform = AddToPlaylistPlatform.KUGOU,
            search = {
                searchCalls += 1
                PlatformSearchResult.Found(
                    candidate(
                        id = "9983DA61BCDB296EED401069F82F7484",
                        songName = "九月底",
                        singer = "余佳运",
                        platform = MusicPlatform.KUGOU
                    )
                )
            }
        )

        assertEquals("形态不对必须改走搜索", 1, searchCalls)
        assertEquals(
            AddToPlaylistResolution.KugouHash(
                hash = "9983DA61BCDB296EED401069F82F7484",
                title = "九月底",
                artist = "余佳运"
            ),
            result
        )
    }

    /** 大小写都算合法 FileHash(酷狗返回过大写, 也可能是小写) */
    @Test
    fun `file hash shape accepts both letter cases`() {
        assertTrue(looksLikeKugouFileHash("8E10D8825DDE03BCABBDE13E5A4150D2"))
        assertTrue(looksLikeKugouFileHash("8e10d8825dde03bcabbde13e5a4150d2"))
        assertTrue(looksLikeKugouFileHash("  8E10D8825DDE03BCABBDE13E5A4150D2  "))
    }

    /** 长度不对 / 含非十六进制字符 / 空值都不算 FileHash */
    @Test
    fun `file hash shape rejects invalid values`() {
        assertFalse(looksLikeKugouFileHash(null))
        assertFalse("空串不算", looksLikeKugouFileHash(""))
        assertFalse("数字 id 不算", looksLikeKugouFileHash("1325711261"))
        assertFalse("少一位", looksLikeKugouFileHash("8E10D8825DDE03BCABBDE13E5A4150D"))
        assertFalse("多一位", looksLikeKugouFileHash("8E10D8825DDE03BCABBDE13E5A4150D2A"))
        assertFalse("含非十六进制字符", looksLikeKugouFileHash("8E10D8825DDE03BCABBDE13E5A4150ZG"))
    }

    // ---------------------------------------------------------------- 需要搜索

    @Test
    fun `foreign song searches the target platform and uses the matched candidate`() = runTest {
        var searchCalls = 0

        val result = resolveAddToPlaylistOutcome(
            song = song(),
            platform = AddToPlaylistPlatform.NETEASE,
            search = {
                searchCalls += 1
                PlatformSearchResult.Found(candidate("3556"))
            }
        )

        assertEquals(AddToPlaylistResolution.NeteaseSongId(3556L), result)
        assertEquals(1, searchCalls)
    }

    @Test
    fun `kugou match keeps the candidate name for the write payload`() = runTest {
        val result = resolveAddToPlaylistOutcome(
            song = song(name = "本地文件名"),
            platform = AddToPlaylistPlatform.KUGOU,
            search = {
                PlatformSearchResult.Found(
                    candidate(
                        id = "8E10D8825DDE03BCABBDE13E5A4150D2",
                        songName = "我们应该算爱过吧",
                        singer = "郑润泽",
                        platform = MusicPlatform.KUGOU
                    )
                )
            }
        )

        assertEquals(
            AddToPlaylistResolution.KugouHash(
                hash = "8E10D8825DDE03BCABBDE13E5A4150D2",
                title = "我们应该算爱过吧",
                artist = "郑润泽"
            ),
            result
        )
    }

    @Test
    fun `search failure is reported instead of being treated as a match`() = runTest {
        val result = resolveAddToPlaylistOutcome(
            song = song(),
            platform = AddToPlaylistPlatform.KUGOU,
            search = { PlatformSearchResult.Failed("timeout") }
        )

        assertEquals(
            AddToPlaylistResolution.SearchFailed(AddToPlaylistPlatform.KUGOU, "timeout"),
            result
        )
    }

    // ---------------------------------------------------------------- 匹配不到

    @Test
    fun `no candidate means an explicit no-match and nothing is submitted`() = runTest {
        val netease = resolveAddToPlaylistOutcome(
            song = song(),
            platform = AddToPlaylistPlatform.NETEASE,
            search = { PlatformSearchResult.NotFound }
        )
        val kugou = resolveAddToPlaylistOutcome(
            song = song(),
            platform = AddToPlaylistPlatform.KUGOU,
            search = { PlatformSearchResult.NotFound }
        )

        assertEquals(AddToPlaylistResolution.NoMatch(AddToPlaylistPlatform.NETEASE), netease)
        assertEquals(AddToPlaylistResolution.NoMatch(AddToPlaylistPlatform.KUGOU), kugou)
    }

    // ---------------------------------------------------------------- id 非法

    @Test
    fun `unparsable netease id from search is rejected`() = runTest {
        val result = resolveAddToPlaylistOutcome(
            song = song(),
            platform = AddToPlaylistPlatform.NETEASE,
            search = { PlatformSearchResult.Found(candidate("not-a-number")) }
        )

        assertEquals(
            AddToPlaylistResolution.Failed(AddToPlaylistFailure.INVALID_NETEASE_SONG_ID),
            result
        )
    }

    @Test
    fun `non-positive netease id is rejected`() {
        listOf("0", "-3", "  ").forEach { raw ->
            assertEquals(
                "id=$raw 不能当成可用歌曲 id",
                AddToPlaylistResolution.Failed(AddToPlaylistFailure.INVALID_NETEASE_SONG_ID),
                resolveSearchedCandidate(AddToPlaylistPlatform.NETEASE, candidate(raw))
            )
        }
        assertEquals(
            AddToPlaylistResolution.NoMatch(AddToPlaylistPlatform.NETEASE),
            resolveSearchedCandidate(AddToPlaylistPlatform.NETEASE, null)
        )
    }

    @Test
    fun `netease source song without a usable id is rejected before searching`() = runTest {
        var searchCalls = 0

        val result = resolveAddToPlaylistOutcome(
            song = song(isNeteaseSource = true, neteaseSongId = 0L),
            platform = AddToPlaylistPlatform.NETEASE,
            search = {
                searchCalls += 1
                PlatformSearchResult.NotFound
            }
        )

        assertEquals(
            AddToPlaylistResolution.Failed(AddToPlaylistFailure.INVALID_NETEASE_SONG_ID),
            result
        )
        assertEquals(0, searchCalls)
    }

    @Test
    fun `kugou source song without a hash is rejected before searching`() = runTest {
        var searchCalls = 0

        val result = resolveAddToPlaylistOutcome(
            song = song(isKugouSource = true, kugouHash = "   "),
            platform = AddToPlaylistPlatform.KUGOU,
            search = {
                searchCalls += 1
                PlatformSearchResult.NotFound
            }
        )

        assertEquals(
            AddToPlaylistResolution.Failed(AddToPlaylistFailure.MISSING_KUGOU_HASH),
            result
        )
        assertEquals(0, searchCalls)
    }

    @Test
    fun `blank kugou hash from a candidate is rejected`() {
        assertEquals(
            AddToPlaylistResolution.Failed(AddToPlaylistFailure.MISSING_KUGOU_HASH),
            resolveSearchedCandidate(
                AddToPlaylistPlatform.KUGOU,
                candidate("   ", platform = MusicPlatform.KUGOU)
            )
        )
    }

    /**
     * 手里已有酷狗 hash 时, 即使歌曲被判成其它平台也不该再去搜索
     *
     * 真机回归: 网易云歌曲经自动换源播放到酷狗后, 歌曲身份仍是网易云(只有播放
     * 地址指向酷狗), 于是被当成"跨平台"去做文本搜索, 搜不到就报"未找到匹配" ——
     * 表现为"音源明明来自酷狗, 却加不进酷狗歌单"。换源时其实已经拿到正确 hash。
     */
    @Test
    fun `known kugou hash is reused without searching even for a foreign song`() = runTest {
        var searchCalls = 0

        val result = resolveAddToPlaylistOutcome(
            song = song(
                name = "Paris in the Rain",
                artist = "Lauv",
                isKugouSource = false,
                kugouHash = "DA44597C1AB57B411792F2655DCF16BD"
            ),
            platform = AddToPlaylistPlatform.KUGOU,
            search = {
                searchCalls += 1
                PlatformSearchResult.NotFound
            }
        )

        assertEquals(
            AddToPlaylistResolution.KugouHash(
                hash = "DA44597C1AB57B411792F2655DCF16BD",
                title = "Paris in the Rain",
                artist = "Lauv"
            ),
            result
        )
        assertEquals("已知 hash 必须直连, 不该触发搜索", 0, searchCalls)
    }

    // ---------------------------------------------------------------- 网易云

    /**
     * 网易云来源歌曲用本地 id 直连, 不搜索
     *
     * `song.id` 不一定真是可用的 songId, 因此直连失败时由 ViewModel 层回退搜索
     * (见 `AddToPlaylistViewModel.addToNeteasePlaylist`), 计划层只负责"能直连就直连"。
     */
    @Test
    fun `netease source song uses its id directly`() = runTest {
        var searchCalls = 0

        val result = resolveAddToPlaylistOutcome(
            song = song(isNeteaseSource = true, neteaseSongId = 3556L),
            platform = AddToPlaylistPlatform.NETEASE,
            search = {
                searchCalls += 1
                PlatformSearchResult.NotFound
            }
        )

        assertEquals(AddToPlaylistResolution.NeteaseSongId(3556L), result)
        assertEquals(0, searchCalls)
    }

    // ---------------------------------------------------------------- 其它

    @Test
    fun `missing song is rejected`() = runTest {
        assertEquals(
            AddToPlaylistResolution.Failed(AddToPlaylistFailure.SONG_UNAVAILABLE),
            resolveAddToPlaylistOutcome(
                song = null,
                platform = AddToPlaylistPlatform.NETEASE,
                search = { PlatformSearchResult.NotFound }
            )
        )
        assertEquals(
            AddToPlaylistResolution.Failed(AddToPlaylistFailure.SONG_UNAVAILABLE),
            resolveAddToPlaylistOutcome(
                song = song(name = "   "),
                platform = AddToPlaylistPlatform.KUGOU,
                search = { PlatformSearchResult.NotFound }
            )
        )
    }

    @Test
    fun `local platform never goes through the remote path`() {
        assertEquals(
            AddToPlaylistResolution.Failed(AddToPlaylistFailure.UNSUPPORTED_PLATFORM),
            planAddToRemotePlaylist(song(), AddToPlaylistPlatform.LOCAL)
        )
        assertNull(AddToPlaylistPlatform.LOCAL.toMusicPlatformOrNull())
        assertEquals(
            MusicPlatform.CLOUD_MUSIC,
            AddToPlaylistPlatform.NETEASE.toMusicPlatformOrNull()
        )
        assertEquals(MusicPlatform.KUGOU, AddToPlaylistPlatform.KUGOU.toMusicPlatformOrNull())
    }

    @Test
    fun `search keyword is artist followed by trimmed name`() {
        assertEquals("测试歌手 晴天", song(name = "  晴天  ").searchKeyword())
    }

    /**
     * 检索词必须带歌手
     *
     * 真机回归: 只按歌名搜 "SISTERS AND BROTHERS" 时, 结果页被同名翻唱/影视版占满,
     * 原版(Kanye West / Ye, 166s)挤不进第一页, 导致匹配失败; 带上歌手后第一条即原版。
     */
    @Test
    fun `search keyword includes the artist`() {
        assertEquals(
            "Kanye West SISTERS AND BROTHERS",
            song(name = "SISTERS AND BROTHERS", artist = "Kanye West").searchKeyword()
        )
        assertEquals(
            "周杰伦 晴天",
            song(name = "  晴天  ", artist = "  周杰伦  ").searchKeyword()
        )
    }

    /** 歌手或歌名缺失时不能拼出多余空格, 也不能因此搜不了 */
    @Test
    fun `search keyword degrades gracefully when a side is missing`() {
        assertEquals("晴天", song(name = "晴天", artist = "   ").searchKeyword())
        assertEquals("周杰伦", song(name = "  ", artist = "周杰伦").searchKeyword())
    }

    // ---------------------------------------------------------------- 酷狗写歌单条目

    /**
     * `id` 承载 `mixsongid`, **不能**再用 hash 顶替
     *
     * 实测该后端要求 `mixsongid` 是字符串; 早先用 hash 顶替会让上游找不到歌曲。
     */
    @Test
    fun `kugou add song keeps hash and mixsongid separate`() {
        val kugouSong = buildKugouPlaylistAddSong(
            title = "我们应该算爱过吧",
            artist = "郑润泽",
            hash = "8E10D8825DDE03BCABBDE13E5A4150D2",
            albumId = "12739065",
            mixSongId = "122505983"
        )

        assertEquals("8E10D8825DDE03BCABBDE13E5A4150D2", kugouSong.hash)
        assertEquals("122505983", kugouSong.id)
        assertEquals("12739065", kugouSong.albumId)
        assertEquals("我们应该算爱过吧", kugouSong.title)
        assertEquals("郑润泽", kugouSong.artist)
    }

    /** 拿不到 mixsongid / album 时留空, 不要编造 */
    @Test
    fun `kugou add song leaves unknown album and mixsongid empty`() {
        val kugouSong = buildKugouPlaylistAddSong(
            title = "song",
            artist = "artist",
            hash = "8E10D8825DDE03BCABBDE13E5A4150D2"
        )

        assertEquals("", kugouSong.id)
        assertNull(kugouSong.albumId)
    }
}
