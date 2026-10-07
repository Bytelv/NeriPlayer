package moe.ouom.neriplayer.platform.kugou.repository

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
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
 * File: moe.ouom.neriplayer.platform.kugou.repository/KugouPlaylistRepositoryTest
 */

import moe.ouom.neriplayer.data.model.kugou.KugouSong
import moe.ouom.neriplayer.platform.kugou.api.KugouApiException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 酷狗歌单解析
 *
 * 用例里的 JSON 取自 2026-10 对 `https://music.api.hoilai.cn` 的真实响应
 * (歌单详情与曲目列表可匿名访问), 字段大小写按后端原样保留 —— 该后端同一份
 * 数据里 `hash` / `FileHash`、`listid` / `ListID` 都可能出现。
 */
class KugouPlaylistRepositoryTest {

    // ---- /user/playlist ----

    @Test
    fun `user playlists are parsed from info array`() {
        val json = JSONObject(
            """
            {
              "status": 1,
              "error_code": 0,
              "userid": 1863870844,
              "info": [
                {
                  "listid": 4,
                  "global_collection_id": "collection_3_1863870844_4_0",
                  "list_create_gid": "collection_3_1863870844_4_0",
                  "name": "「0.8x」慢速歌曲",
                  "pic": "http://c1.kgimg.com/custom/400/20221223/20221223101844795550.jpg",
                  "intro": "我的歌曲集就都是0.8x慢速歌曲",
                  "count": 47,
                  "list_create_username": "ntan",
                  "list_create_userid": 1863870844,
                  "type": 0,
                  "source": 1,
                  "is_def": 0
                },
                {
                  "listid": 11,
                  "name": "我喜欢",
                  "count": 2,
                  "is_def": 2
                }
              ]
            }
            """.trimIndent()
        )

        val playlists = KugouPlaylistRepository.parseUserPlaylistsForTest(json)

        assertEquals(2, playlists.size)
        val first = playlists[0]
        assertEquals("4", first.listId)
        assertEquals("collection_3_1863870844_4_0", first.globalCollectionId)
        assertEquals("「0.8x」慢速歌曲", first.name)
        assertEquals(47, first.trackCount)
        assertEquals("ntan", first.creatorName)
        assertEquals("我的歌曲集就都是0.8x慢速歌曲", first.intro)
        // http 的封面必须升级成 https, 否则明文流量会被拦
        assertEquals(
            "https://c1.kgimg.com/custom/400/20221223/20221223101844795550.jpg",
            first.coverUrl
        )
        assertTrue(first.isPlayable())
        assertFalse(first.isCollected())
        assertTrue(playlists[1].isLikedPlaylist())
        // 没有 global id 时退化成 listid, 仍然可播
        assertEquals("11", playlists[1].globalCollectionId)
    }

    /** 后端字段大小写极不统一, 必须大小写不敏感 */
    @Test
    fun `user playlist parsing ignores field casing`() {
        val json = JSONObject(
            """
            {
              "Info": [
                {
                  "ListID": 7,
                  "Name": "Cased Playlist",
                  "PIC": "https://imge.kugou.com/stdmusic/400/a.jpg",
                  "COUNT": "12",
                  "Is_Def": "1",
                  "Type": "1",
                  "List_Create_Gid": "collection_3_1_7_0",
                  "List_Create_Username": "someone"
                }
              ]
            }
            """.trimIndent()
        )

        val playlists = KugouPlaylistRepository.parseUserPlaylistsForTest(json)

        assertEquals(1, playlists.size)
        val playlist = playlists.first()
        assertEquals("Cased Playlist", playlist.name)
        assertEquals("7", playlist.listId)
        assertEquals("collection_3_1_7_0", playlist.globalCollectionId)
        assertEquals(12, playlist.trackCount)
        assertEquals(1, playlist.isDefault)
        assertEquals(1, playlist.type)
        assertTrue(playlist.isCollected())
        assertEquals("someone", playlist.creatorName)
    }

    /**
     * 收藏歌单必须优先用 `list_create_gid`
     *
     * `/playlist/track/all` 只认原歌单的 global id, 用错会回 20010。
     */
    @Test
    fun `collected playlist prefers list_create_gid over global id`() {
        val json = JSONObject(
            """
            {
              "info": [
                {
                  "listid": 88,
                  "global_collection_id": "collection_3_999_88_0",
                  "list_create_gid": "collection_3_1863870844_4_0",
                  "list_create_listid": 4,
                  "musiclib_id": 0,
                  "name": "收藏来的歌单",
                  "count": 5,
                  "type": 1
                }
              ]
            }
            """.trimIndent()
        )

        val playlist = KugouPlaylistRepository.parseUserPlaylistsForTest(json).single()

        assertEquals("collection_3_1863870844_4_0", playlist.globalCollectionId)
        assertEquals("collection_3_1863870844_4_0", playlist.sourceGlobalId)
        assertEquals("4", playlist.sourceListId)
        assertEquals("88", playlist.listId)
        // 0 是后端常用的占位值, 不能当成专辑 id
        assertNull(playlist.musicLibId)
    }

    @Test
    fun `empty and missing playlist arrays produce empty lists`() {
        assertTrue(
            KugouPlaylistRepository.parseUserPlaylistsForTest(
                JSONObject("""{"status":1,"error_code":0,"info":[]}""")
            ).isEmpty()
        )
        assertTrue(
            KugouPlaylistRepository.parseUserPlaylistsForTest(
                JSONObject("""{"status":1,"error_code":0}""")
            ).isEmpty()
        )
        assertTrue(
            KugouPlaylistRepository.parseUserPlaylistsForTest(JSONObject("{}")).isEmpty()
        )
    }

    /** 没有名字的歌单在界面上无法呈现, 直接跳过 */
    @Test
    fun `playlist entries without a name are dropped`() {
        val json = JSONObject(
            """
            {
              "info": [
                {"listid": 1, "count": 3},
                {"listid": 2, "name": "  ", "count": 3},
                {"listid": 3, "name": "有效歌单", "count": 3}
              ]
            }
            """.trimIndent()
        )

        val playlists = KugouPlaylistRepository.parseUserPlaylistsForTest(json)

        assertEquals(1, playlists.size)
        assertEquals("有效歌单", playlists.single().name)
    }

    // ---- /playlist/track/all ----

    /** 真实响应: 歌手在 `singerinfo`, 专辑在 `albuminfo`, 封面是 `cover` */
    @Test
    fun `playlist songs are parsed from a real track page`() {
        val json = JSONObject(
            """
            {
              "count": 47,
              "songs": [
                {
                  "name": "先说谎的人 (0.8X)",
                  "hash": "6B5DCE5832B0CC91F3CB90FECF2B5B02",
                  "timelen": 184344,
                  "album_id": "58271602",
                  "fileid": 102,
                  "singerinfo": [
                    {"id": 8893172, "name": "涵の心事."}
                  ],
                  "albuminfo": {"id": 58271602, "name": "涵の心事."},
                  "cover": "http://imge.kugou.com/stdmusic/400/20220606/20220606174747878564.jpg",
                  "mixsongid": 417327542,
                  "audio_id": 169879265,
                  "privilege": 0
                }
              ],
              "status": 1,
              "error_code": 0,
              "begin_idx": 0,
              "pagesize": 1
            }
            """.trimIndent()
        )

        val page = KugouPlaylistRepository.parsePlaylistSongPageForTest(json, page = 1, pageSize = 1)

        assertEquals(47, page.total)
        // 47 首里的第 1 页 (每页 1 首) 还有下一页
        assertTrue(page.hasMore)
        val song = page.songs.single()
        assertEquals("6B5DCE5832B0CC91F3CB90FECF2B5B02", song.hash)
        assertEquals("先说谎的人 (0.8X)", song.title)
        assertEquals("涵の心事.", song.artist)
        assertEquals("涵の心事.", song.albumName)
        assertEquals("58271602", song.albumId)
        assertEquals(184_344L, song.durationMs)
        assertEquals(
            "https://imge.kugou.com/stdmusic/400/20220606/20220606174747878564.jpg",
            song.coverUrl
        )
    }

    /** 多歌手用 `/` 连接, 与搜索结果的 `SingerName` 形态保持一致 */
    @Test
    fun `multiple singers are joined and casing is ignored`() {
        val json = JSONObject(
            """
            {
              "Count": "2",
              "Songs": [
                {
                  "Name": "合唱曲",
                  "FileHash": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                  "TimeLen": "60000",
                  "SingerInfo": [{"Name": "甲"}, {"NAME": "乙"}],
                  "AlbumInfo": {"Name": "合唱专辑"},
                  "Image": "http://imge.kugou.com/a.jpg"
                },
                {
                  "songname": "周杰伦 - 晴天",
                  "hash": "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
                  "SingerName": "周杰伦",
                  "time_length": "269"
                }
              ]
            }
            """.trimIndent()
        )

        val page = KugouPlaylistRepository.parsePlaylistSongPageForTest(json, page = 1, pageSize = 2)

        assertEquals(2, page.songs.size)
        assertEquals("甲/乙", page.songs[0].artist)
        assertEquals("合唱专辑", page.songs[0].albumName)
        assertEquals(60_000L, page.songs[0].durationMs)
        // `FileName` 里的 "歌手 - " 前缀要剥掉, 时长字段是秒
        assertEquals("晴天", page.songs[1].title)
        assertEquals("周杰伦", page.songs[1].artist)
        assertEquals(269_000L, page.songs[1].durationMs)
        // 第二页是空的, 因为 count=2 且已加载 2 条
        assertFalse(page.hasMore)
    }

    /** 未登录 / 歌单文件取不到时后端回 400 + error_code, 必须是空列表而不是崩溃 */
    @Test
    fun `failed track response yields an empty page`() {
        val json = JSONObject(
            """{"count":0,"songs":[],"status":0,"error_code":20010,"errmsg":"get other list file fail","data":{}}"""
        )

        val page = KugouPlaylistRepository.parsePlaylistSongPageForTest(json)

        assertTrue(page.songs.isEmpty())
        assertEquals(0, page.total)
        assertFalse(page.hasMore)
    }

    @Test
    fun `missing songs array yields an empty page`() {
        val page = KugouPlaylistRepository.parsePlaylistSongPageForTest(JSONObject("{}"))

        assertTrue(page.songs.isEmpty())
        assertFalse(page.hasMore)
    }

    /** 没有 count 时, "整页返回" 视为还有下一页 */
    @Test
    fun `pagination falls back to full page detection without a total`() {
        val json = JSONObject(
            """
            {
              "songs": [
                {"name": "A", "hash": "H1"},
                {"name": "B", "hash": "H2"}
              ]
            }
            """.trimIndent()
        )

        val full = KugouPlaylistRepository.parsePlaylistSongPageForTest(json, page = 1, pageSize = 2)
        assertTrue(full.hasMore)
        assertEquals(2, full.total)

        val partial = KugouPlaylistRepository.parsePlaylistSongPageForTest(json, page = 1, pageSize = 5)
        assertFalse(partial.hasMore)
    }

    // ---- /playlist/detail ----

    /** 实测: 歌单详情直接把对象放在根节点, 不包在 data 数组里 */
    @Test
    fun `playlist detail is parsed from the root object`() {
        val json = JSONObject(
            """
            {
              "listid": 4,
              "global_collection_id": "collection_3_1863870844_4_0",
              "list_create_gid": "collection_3_1863870844_4_0",
              "name": "「0.8x」慢速歌曲",
              "pic": "http://c1.kgimg.com/custom/{size}/20221223/20221223101844795550.jpg",
              "intro": "我的歌曲集就都是0.8x慢速歌曲",
              "count": 47,
              "list_create_username": "ntan",
              "heat": 0,
              "is_def": 0,
              "status": 1,
              "error_code": null
            }
            """.trimIndent()
        )

        val detail = KugouPlaylistRepository.parsePlaylistDetailForTest(json)

        assertEquals("collection_3_1863870844_4_0", detail?.globalCollectionId)
        assertEquals("「0.8x」慢速歌曲", detail?.name)
        assertEquals(47, detail?.trackCount)
        assertEquals("ntan", detail?.creatorName)
        // {size} 占位符必须被替换成真实尺寸, 否则图片库拿到非法 URL 会加载失败
        assertEquals(
            "https://c1.kgimg.com/custom/400/20221223/20221223101844795550.jpg",
            detail?.coverUrl
        )
    }

    /** 其它部署可能把歌单包在 `data` 或 `data.info` 里 */
    @Test
    fun `playlist detail tolerates wrapped payloads`() {
        val wrapped = KugouPlaylistRepository.parsePlaylistDetailForTest(
            JSONObject("""{"status":1,"data":[{"name":"包裹歌单","listid":9,"count":3}]}""")
        )
        assertEquals("包裹歌单", wrapped?.name)
        assertEquals("9", wrapped?.globalCollectionId)

        val nested = KugouPlaylistRepository.parsePlaylistDetailForTest(
            JSONObject("""{"status":1,"data":{"info":[{"name":"嵌套歌单","listid":10}]}}""")
        )
        assertEquals("嵌套歌单", nested?.name)

        assertNull(KugouPlaylistRepository.parsePlaylistDetailForTest(JSONObject("{}")))
    }

    // ---- 回退策略 ----

    /** "需要登录"换接口也没用, 只有业务失败才值得用 listid 再试 */
    @Test
    fun `only business failures trigger the listid fallback`() {
        assertFalse(
            KugouPlaylistRepository.shouldFallbackToUserTrackEndpointForTest(
                KugouApiException.SessionRequired("需要验证")
            )
        )
        assertTrue(
            KugouPlaylistRepository.shouldFallbackToUserTrackEndpointForTest(
                KugouApiException.BadRequest("get other list file fail")
            )
        )
        assertTrue(
            KugouPlaylistRepository.shouldFallbackToUserTrackEndpointForTest(
                KugouApiException.ServerError("HTTP 500")
            )
        )
    }

    // ---- SongItem 映射 ----

    @Test
    fun `playlist songs map to playable kugou queue entries`() {
        val json = JSONObject(
            """
            {
              "songs": [
                {
                  "name": "NUNA3.0",
                  "hash": "DFDED7F8E0D5BBD9AEE65881ADA50F7B",
                  "timelen": 223869,
                  "singerinfo": [{"name": "郑润泽"}],
                  "cover": "http://imge.kugou.com/stdmusic/400/20250318/20250318151028523869.jpg"
                }
              ]
            }
            """.trimIndent()
        )

        val song = KugouPlaylistRepository.parsePlaylistSongPageForTest(json)
            .songs
            .single()
            .toKugouQueueSong()

        // 播放器与下载解析器靠 channelId 分派到酷狗分支, audioId 必须是 FileHash
        assertEquals("kugou", song.channelId)
        assertEquals("DFDED7F8E0D5BBD9AEE65881ADA50F7B", song.audioId)
        assertEquals("NUNA3.0", song.name)
        assertEquals("郑润泽", song.artist)
        assertEquals("Kugou", song.album)
        assertEquals(223_869L, song.durationMs)
        // 与搜索结果用同一套稳定身份, 保证跨入口去重一致
        assertEquals("Kugou:DFDED7F8E0D5BBD9AEE65881ADA50F7B", song.sourceStableKey)
        assertTrue(song.id >= 0L)
    }

    // ---------------------------------------------------------------- 写歌单

    private fun kugouSong(
        hash: String,
        title: String
    ) = KugouSong(
        id = "1",
        hash = hash,
        title = title,
        artist = "artist"
    )

    /**
     * 写歌单的入参是 `songs` 对象数组(实测)
     *
     * 曾误按官方 `playlist_tracks_add.js` 的 `data`(管道分隔字符串) 改写, 结果
     * 服务端回 `40005 歌曲列表不能为空` —— `data` 是它**转发给酷狗上游**时的格式,
     * 不是它自己的入参格式。这里锁住真实入参, 避免再次被官方实现带偏。
     */
    @Test
    fun `tracks add songs uses objects with hash and name`() {
        val json = JSONArray(
            KugouPlaylistRepository.buildTracksAddSongsForTest(
                listOf(kugouSong("8E10D8825DDE03BCABBDE13E5A4150D2", "我们应该算爱过吧"))
            )
        )

        assertEquals(1, json.length())
        assertEquals("8E10D8825DDE03BCABBDE13E5A4150D2", json.getJSONObject(0).getString("hash"))
        assertEquals("我们应该算爱过吧", json.getJSONObject(0).getString("name"))
    }

    /** 多首全部保留, 顺序不变 */
    @Test
    fun `tracks add songs keeps every entry`() {
        val json = JSONArray(
            KugouPlaylistRepository.buildTracksAddSongsForTest(
                listOf(
                    kugouSong("AAA", "first"),
                    kugouSong("BBB", "second")
                )
            )
        )

        assertEquals(2, json.length())
        assertEquals("AAA", json.getJSONObject(0).getString("hash"))
        assertEquals("BBB", json.getJSONObject(1).getString("hash"))
    }

    /** JSON 会自行转义, 标题里的分隔符无需清洗 */
    @Test
    fun `tracks add songs keeps delimiter characters in the title`() {
        val json = JSONArray(
            KugouPlaylistRepository.buildTracksAddSongsForTest(
                listOf(kugouSong("AAA", "bad,title|here"))
            )
        )

        assertEquals("bad,title|here", json.getJSONObject(0).getString("name"))
    }

    /** `hash` 是必需项, 缺它的条目必须被丢弃而不是发出无效请求 */
    @Test
    fun `tracks add songs drops entries without hash`() {
        val json = JSONArray(
            KugouPlaylistRepository.buildTracksAddSongsForTest(
                listOf(
                    kugouSong("", "no hash"),
                    kugouSong("BBB", "   "),
                    kugouSong("CCC", "valid")
                )
            )
        )

        assertEquals(2, json.length())
        assertEquals("BBB", json.getJSONObject(0).getString("hash"))
        assertEquals("CCC", json.getJSONObject(1).getString("hash"))
    }

    @Test
    fun `tracks add songs is empty when nothing usable`() {
        assertEquals(
            0,
            JSONArray(
                KugouPlaylistRepository.buildTracksAddSongsForTest(emptyList())
            ).length()
        )
        assertEquals(
            0,
            JSONArray(
                KugouPlaylistRepository.buildTracksAddSongsForTest(listOf(kugouSong("", "")))
            ).length()
        )
    }

    /** 同一首歌重复提交没有意义, 按 hash 去重后只发一次 */
    @Test
    fun `tracks add songs deduplicates by hash`() {
        val json = JSONArray(
            KugouPlaylistRepository.buildTracksAddSongsForTest(
                listOf(
                    kugouSong("AAA", "same"),
                    kugouSong("AAA", "same again")
                )
            )
        )

        assertEquals(1, json.length())
    }

    /** 有专辑 id 时一并带上(酷狗上游也需要它) */
    @Test
    fun `tracks add songs carries album id`() {
        val withAlbum = KugouSong(
            id = "1",
            hash = "AAA",
            title = "song",
            artist = "artist",
            albumId = "58271602"
        )
        val json = JSONArray(
            KugouPlaylistRepository.buildTracksAddSongsForTest(listOf(withAlbum))
        )

        assertEquals("58271602", json.getJSONObject(0).getString("album_id"))
    }

    /**
     * 跨平台加歌时, 条目的 hash 来自搜索结果 (`SongSearchInfo.id` = FileHash)
     *
     * app 层用 `KugouSong(id = hash, hash = hash, title = 歌名)` 构造, 这里锁住
     * 这种形态依然能产出合法的 songs 条目。
     */
    @Test
    fun `tracks add songs accepts a search matched song`() {
        val matched = KugouSong(
            id = "8E10D8825DDE03BCABBDE13E5A4150D2",
            hash = "8E10D8825DDE03BCABBDE13E5A4150D2",
            title = "我们应该算爱过吧",
            artist = "郑润泽"
        )
        val json = JSONArray(
            KugouPlaylistRepository.buildTracksAddSongsForTest(listOf(matched))
        )

        assertEquals(
            "8E10D8825DDE03BCABBDE13E5A4150D2",
            json.getJSONObject(0).getString("hash")
        )
    }

    /**
     * 歌单接口的 `fileid` 必须被保留
     *
     * 它是 `POST /playlist/tracks/del` 的 `fileids` 参数; 早先它被混进 `id` 的兜底链
     * 而没有单独保留, 导致无法从歌单移除曲目。
     */
    @Test
    fun `playlist song keeps its file id`() {
        val json = JSONObject(
            """
            {
              "songs": [
                {
                  "name": "先说谎的人",
                  "hash": "6B5DCE5832B0CC91F3CB90FECF2B5B02",
                  "timelen": 184344,
                  "fileid": 102,
                  "mixsongid": 417327542
                }
              ]
            }
            """.trimIndent()
        )

        val song = KugouPlaylistRepository.parsePlaylistSongPageForTest(json).songs.single()

        assertEquals("102", song.fileId)
        // id 仍是专辑音频 ID, 不能被 fileid 顶替
        assertEquals("417327542", song.id)
    }

    /** 搜索接口没有 fileid, 此时应为 null 而不是伪造一个值 */
    @Test
    fun `song without file id leaves it null`() {
        val json = JSONObject(
            """
            {
              "songs": [
                {
                  "name": "NUNA3.0",
                  "hash": "DFDED7F8E0D5BBD9AEE65881ADA50F7B",
                  "timelen": 223869
                }
              ]
            }
            """.trimIndent()
        )

        val song = KugouPlaylistRepository.parsePlaylistSongPageForTest(json).songs.single()

        assertNull(song.fileId)
    }

    /**
     * 曲目接口的 `list_info.pic` 必须被带出来
     *
     * 自建歌单在 `/user/playlist` 与 `/playlist/detail` 里的 `pic` 是空字符串,
     * 详情页封面只能靠这里兜底 (实测该字段带 `{size}` 占位符)。
     */
    @Test
    fun `playlist song page keeps the list info cover`() {
        val json = JSONObject(
            """
            {
              "count": 1,
              "list_info": {
                "name": "「0.8x」慢速歌曲",
                "pic": "http://c1.kgimg.com/custom/{size}/20221223/20221223101844795550.jpg"
              },
              "songs": [
                { "name": "先说谎的人", "hash": "6B5DCE5832B0CC91F3CB90FECF2B5B02", "timelen": 184344 }
              ]
            }
            """.trimIndent()
        )

        val page = KugouPlaylistRepository.parsePlaylistSongPageForTest(json)

        // http 升级为 https, 且 {size} 替换为真实尺寸
        assertEquals(
            "https://c1.kgimg.com/custom/400/20221223/20221223101844795550.jpg",
            page.coverUrl
        )
    }

    /** 没有 list_info (或 pic 为空) 时封面应为 null, 让上层去退到首曲封面 */
    @Test
    fun `playlist song page cover is null when absent or blank`() {
        assertNull(
            KugouPlaylistRepository.parsePlaylistSongPageForTest(
                JSONObject("""{"songs":[{"hash":"AAA"}]}""")
            ).coverUrl
        )
        assertNull(
            KugouPlaylistRepository.parsePlaylistSongPageForTest(
                JSONObject("""{"list_info":{"pic":""},"songs":[{"hash":"AAA"}]}""")
            ).coverUrl
        )
    }
}
