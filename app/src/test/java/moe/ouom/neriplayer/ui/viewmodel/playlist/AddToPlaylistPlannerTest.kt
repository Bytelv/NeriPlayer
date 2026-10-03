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
import org.junit.Assert.assertNull
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

        val result = resolveAddToPlaylistOutcome(
            song = song(isKugouSource = true, kugouHash = "HASH-A"),
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
                hash = "HASH-A",
                title = "测试歌曲",
                artist = "测试歌手"
            ),
            result
        )
        assertEquals("属于酷狗的歌曲不该触发搜索", 0, searchCalls)
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

    @Test
    fun `kugou add song carries the hash in both hash and id fields`() {
        val kugouSong = buildKugouPlaylistAddSong(
            title = "我们应该算爱过吧",
            artist = "郑润泽",
            hash = "8E10D8825DDE03BCABBDE13E5A4150D2"
        )

        assertEquals("8E10D8825DDE03BCABBDE13E5A4150D2", kugouSong.hash)
        assertEquals("8E10D8825DDE03BCABBDE13E5A4150D2", kugouSong.id)
        assertEquals("我们应该算爱过吧", kugouSong.title)
        assertEquals("郑润泽", kugouSong.artist)
    }
}
