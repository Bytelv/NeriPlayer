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
 * File: moe.ouom.neriplayer.platform.kugou.repository/KugouPlaylistRepository
 */

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.SongSourceTags
import moe.ouom.neriplayer.data.model.kugou.KugouDebugLog
import moe.ouom.neriplayer.data.model.kugou.KugouPlaylistPage
import moe.ouom.neriplayer.data.model.kugou.KugouPlaylistSongPage
import moe.ouom.neriplayer.data.model.kugou.KugouPlaylistSummary
import moe.ouom.neriplayer.data.model.kugou.KugouSong
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherChannels
import moe.ouom.neriplayer.platform.kugou.api.KugouApiException
import moe.ouom.neriplayer.platform.kugou.api.client.KugouClient
import moe.ouom.neriplayer.platform.kugou.api.codec.extractKugouSongArray
import moe.ouom.neriplayer.platform.kugou.api.codec.normalizeKugouImageUrl
import moe.ouom.neriplayer.platform.kugou.api.codec.optArrayIgnoreCase
import moe.ouom.neriplayer.platform.kugou.api.codec.optIntIgnoreCase
import moe.ouom.neriplayer.platform.kugou.api.codec.optStringIgnoreCase
import moe.ouom.neriplayer.platform.kugou.api.codec.parseKugouSong
import org.json.JSONArray
import org.json.JSONObject

/**
 * 酷狗"我的歌单"读取
 *
 * 接口形态对齐 KA Music 的 `MusicApi`:
 * - `GET /user/playlist?page=&pagesize=` 取创建 + 收藏的歌单 (需要登录)
 * - `GET /playlist/track/all?id=<global_collection_id>&page=&pagesize=` 取曲目
 * - `GET /playlist/track/all/new?listid=<listid>` 仅支持自建/收藏歌单, 作为兜底
 * - `GET /playlist/detail?ids=<global_collection_id>` 取歌单元数据
 *
 * 实测 (2026-10): 未登录时 `/user/playlist` 回 **HTTP 204 且无正文**, 不是文档里
 * 那套 `error_code`; 因此空正文被归一成 [KugouApiException.SessionRequired],
 * 让界面提示登录而不是显示"加载失败"。
 */
class KugouPlaylistRepository(private val client: KugouClient) {

    /**
     * 当前账号创建与收藏的歌单
     *
     * @throws KugouApiException.SessionRequired 未登录 (HTTP 204 空正文, 或 error_code 20002/20028)
     */
    suspend fun fetchUserPlaylists(
        page: Int = 1,
        pageSize: Int = DEFAULT_PLAYLIST_PAGE_SIZE
    ): KugouPlaylistPage = withContext(Dispatchers.IO) {
        val normalizedPage = page.coerceAtLeast(1)
        val normalizedSize = pageSize.coerceIn(1, MAX_PAGE_SIZE)

        val json = client.getJsonOrNull(
            path = USER_PLAYLIST_PATH,
            query = mapOf(
                "page" to normalizedPage.toString(),
                "pagesize" to normalizedSize.toString()
            )
        ) ?: throw KugouApiException.SessionRequired(SESSION_REQUIRED_MESSAGE)

        val playlists = parseUserPlaylists(json)
        val declaredTotal = json.optIntIgnoreCase("total", "total_count", "count") ?: 0
        KugouPlaylistPage(
            playlists = playlists,
            page = normalizedPage,
            pageSize = normalizedSize,
            hasMore = hasMore(
                loadedCount = playlists.size,
                page = normalizedPage,
                pageSize = normalizedSize,
                declaredTotal = declaredTotal
            ),
            total = declaredTotal.takeIf { it > 0 } ?: playlists.size
        )
    }

    /**
     * 歌单曲目
     *
     * 优先用 global collection id 走 `/playlist/track/all`; 该接口对部分收藏歌单
     * 会回 `error_code=20010 get other list file fail`, 此时用 `listid` 回退到
     * `/playlist/track/all/new` (文档注明该接口只支持自建与收藏的歌单)。
     */
    suspend fun fetchPlaylistSongs(
        globalCollectionId: String?,
        listId: String? = null,
        page: Int = 1,
        pageSize: Int = DEFAULT_SONG_PAGE_SIZE
    ): KugouPlaylistSongPage = withContext(Dispatchers.IO) {
        val globalId = globalCollectionId?.trim().orEmpty()
        val userListId = listId?.trim().orEmpty()
        if (globalId.isEmpty() && userListId.isEmpty()) {
            throw KugouApiException.BadRequest("酷狗歌单 id 为空")
        }

        val normalizedPage = page.coerceAtLeast(1)
        val normalizedSize = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        var lastFailure: KugouApiException? = null

        if (globalId.isNotEmpty()) {
            try {
                val json = client.getJsonOrNull(
                    path = TRACK_ALL_PATH,
                    query = trackQuery("id", globalId, normalizedPage, normalizedSize)
                )
                if (json != null) {
                    return@withContext parsePlaylistSongPage(json, normalizedPage, normalizedSize)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: KugouApiException) {
                lastFailure = error
                if (userListId.isEmpty() || !shouldFallbackToUserTrackEndpoint(error)) throw error
                NPLogger.w(
                    TAG,
                    "酷狗 /playlist/track/all 失败, 回退 listid: id=$globalId, ${error.message.orEmpty()}"
                )
            }
        }

        if (userListId.isNotEmpty()) {
            try {
                val json = client.getJsonOrNull(
                    path = TRACK_ALL_NEW_PATH,
                    query = trackQuery("listid", userListId, normalizedPage, normalizedSize)
                )
                if (json != null) {
                    return@withContext parsePlaylistSongPage(json, normalizedPage, normalizedSize)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: KugouApiException) {
                throw error
            }
        }

        // 两个接口都只回了 204 空正文, 该后端用它表示"没有有效会话"
        lastFailure?.let { throw it }
        throw KugouApiException.SessionRequired(SESSION_REQUIRED_MESSAGE)
    }

    /**
     * 歌单元数据, 失败时返回 null
     *
     * 只用来补齐列表页拿不到的封面/曲目数, 因此不向上抛业务错误。
     */
    suspend fun fetchPlaylistDetail(globalCollectionId: String): KugouPlaylistSummary? =
        withContext(Dispatchers.IO) {
            val id = globalCollectionId.trim()
            if (id.isEmpty()) return@withContext null
            try {
                val json = client.getJsonOrNull(
                    path = PLAYLIST_DETAIL_PATH,
                    query = mapOf("ids" to id)
                ) ?: return@withContext null
                parsePlaylistDetail(json)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NPLogger.w(TAG, "酷狗歌单详情查询失败: id=$id, ${error.message.orEmpty()}")
                null
            }
        }

    /**
     * 把歌曲加入酷狗歌单
     *
     * 契约以官方服务端实现为准 (`module/playlist_tracks_add.js`), 它把请求转发到
     * 酷狗真实的 `/cloudlist.service/v6/add_song`:
     *
     * ```
     * POST /playlist/tracks/add?last_time=<秒级时间戳>&last_area=gztx&userid=..&token=..
     * body = {
     *   userid, token, listid, list_ver: 0, type: 0,
     *   slow_upload: 1, scene: "false;null",
     *   data: "歌名|hash|album_id|mixsongid[,歌名|hash|...]"
     * }
     * ```
     *
     * 早先只发 `{"listid":.., "songs":[{hash,name}]}`: 字段名不对(`data` 而非 `songs`),
     * 且缺 `userid`/`token`/`list_ver`/`slow_upload`/`scene` 与全部查询参数。服务端
     * 因此**回了 status=1 却什么都没写**, 响应里回显的 hash 是一个数字 id 而不是我们
     * 发的 32 位 FileHash —— 表现为"提示添加成功但歌单里没有这首歌"。
     *
     * @return 成功的首数; 无可用条目或失败返回 0
     */
    suspend fun addSongsToPlaylist(
        listId: String,
        songs: List<KugouSong>
    ): Int = withContext(Dispatchers.IO) {
        val normalizedListId = listId.trim()
        if (normalizedListId.isEmpty()) return@withContext 0

        val dataPayload = buildTracksAddSongs(songs)
        if (dataPayload.length() == 0) {
            NPLogger.w(TAG, "酷狗加歌到歌单: 无可用的 hash, 已跳过")
            return@withContext 0
        }

        val session = client.currentSession()
        val userId = session.userId.trim()
        val token = session.token.trim()

        // 该接口的请求体格式反复试探都只能靠真机确认: 把真正发出的内容记下来,
        // 一次复现即可判断是"没带上歌曲"还是"字段名不对"
        KugouDebugLog.record(
            label = "ADD tracks",
            detail = "listid=$normalizedListId, userId=${userId.ifBlank { "<空>" }}, " +
                "tokenLen=${token.length}, 首歌数=${songs.size}, " +
                "songs=${dataPayload.toString().take(240)}"
        )

        try {
            val json = client.postJsonBody(
                path = TRACKS_ADD_PATH,
                query = mapOf(
                    "last_time" to (System.currentTimeMillis() / 1000L).toString(),
                    "last_area" to "gztx",
                    "userid" to userId,
                    "token" to token,
                    // 官方服务端把上游的 `data` 作为查询参数转发(管道串); 这里一并
                    // 带上, 以免该后端是从查询串取它 —— 实测 body 里的 `data` 不被识别
                    "data" to buildTracksAddPipeData(songs)
                ),
                body = JSONObject()
                    .put("userid", userId)
                    .put("token", token)
                    .put("listid", normalizedListId)
                    .put("list_ver", 0)
                    .put("type", 0)
                    .put("slow_upload", 1)
                    .put("scene", "false;null")
                    .put("songs", dataPayload)
            )
            if (json == null) {
                NPLogger.w(TAG, "酷狗加歌到歌单: 服务端返回空响应, listid=$normalizedListId")
                return@withContext 0
            }
            val status = json.optInt("status")
            // 该后端成功时回 status=1; 也有只回 errorCode=0 的情况
            val errorCode = json.optInt("errorCode").takeIf { it != 0 }
                ?: json.optInt("error_code").takeIf { it != 0 }
                ?: json.optInt("errcode").takeIf { it != 0 }
            if (status != 1 && errorCode != null) {
                if (errorCode in KugouClient.SESSION_REQUIRED_ERROR_CODES) {
                    throw KugouApiException.SessionRequired(SESSION_REQUIRED_MESSAGE)
                }
                NPLogger.w(
                    TAG,
                    "酷狗加歌到歌单失败: listid=$normalizedListId, " +
                        "errorCode=$errorCode, msg=${json.optString("msg")}, status=$status"
                )
                return@withContext 0
            }
            if (status != 1) {
                NPLogger.w(
                    TAG,
                    "酷狗加歌到歌单未见成功标记: listid=$normalizedListId, status=$status"
                )
                return@withContext 0
            }
            songs.count { it.hash.isNotBlank() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: KugouApiException.SessionRequired) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "酷狗加歌到歌单异常: listid=$normalizedListId, ${error.message.orEmpty()}")
            0
        }
    }

    /**
     * 从酷狗歌单移除歌曲
     *
     * 实测契约(**与添加接口不同, 参数走查询串**):
     * `POST /playlist/tracks/del?listid=<歌单 listid>&fileids=<fileid[,fileid]>`
     * 把参数放进 JSON body 会被回 "The listid field is required." /
     * "The fileids field is required."。
     *
     * `fileids` 用的是歌单接口下发的 `fileid`, 不是 `hash` —— 因此
     * [KugouSong.fileId] 为空时无法移除。
     *
     * @return 是否成功
     */
    suspend fun removeSongsFromPlaylist(
        listId: String,
        fileIds: List<String>
    ): Boolean = withContext(Dispatchers.IO) {
        val normalizedListId = listId.trim()
        val normalizedFileIds = fileIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (normalizedListId.isEmpty() || normalizedFileIds.isEmpty()) {
            NPLogger.w(
                TAG,
                "酷狗移出歌单: listid 或 fileids 为空, 已跳过 " +
                    "(listid=$normalizedListId, fileIds=${normalizedFileIds.size})"
            )
            return@withContext false
        }

        try {
            val json = client.postJsonBody(
                path = TRACKS_DEL_PATH,
                body = JSONObject(),
                query = mapOf(
                    "listid" to normalizedListId,
                    "fileids" to normalizedFileIds.joinToString(",")
                )
            )
            // 空响应视为成功: 该接口成功时不保证回 JSON
            if (json == null) return@withContext true

            val errorCode = json.optInt("errorCode").takeIf { it != 0 }
                ?: json.optInt("error_code").takeIf { it != 0 }
                ?: json.optInt("errcode").takeIf { it != 0 }
            if (errorCode != null) {
                if (errorCode in KugouClient.SESSION_REQUIRED_ERROR_CODES) {
                    throw KugouApiException.SessionRequired(SESSION_REQUIRED_MESSAGE)
                }
                NPLogger.w(
                    TAG,
                    "酷狗移出歌单失败: listid=$normalizedListId, errorCode=$errorCode, " +
                        "msg=${json.optString("msg")}"
                )
                return@withContext false
            }
            // status=0 且无 errorCode 也视为失败
            if (json.has("status") && json.optInt("status") != 1) {
                NPLogger.w(
                    TAG,
                    "酷狗移出歌单未见成功标记: listid=$normalizedListId, " +
                        "status=${json.optInt("status")}"
                )
                return@withContext false
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: KugouApiException.SessionRequired) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "酷狗移出歌单异常: listid=$normalizedListId, ${error.message.orEmpty()}"
            )
            false
        }
    }

    /**
     * 构造官方契约里的 `data` 参数
     *
     * 官方服务端按 `params.data.split(',')` 再 `split('|')` 取值, 顺序固定为
     * `歌曲名|hash|album_id|mixsongid`:
     *
     * ```javascript
     * name: data[0], hash: data[1],
     * album_id: Number(data[2] || 0), mixsongid: Number(data[3] || 0)
     * ```
     *
     * 因此这里必须按该顺序拼接; 缺的字段留空, 由服务端兜成 0。
     * 歌名里的 `,`/`|` 会破坏分隔, 统一替换成空格。
     */
    internal fun buildTracksAddData(songs: List<KugouSong>): String {
        val seen = mutableSetOf<String>()
        return songs.mapNotNull { song ->
            val hash = song.hash.trim()
            if (hash.isEmpty() || !seen.add(hash)) return@mapNotNull null
            val name = song.title.trim()
                .replace(',', ' ')
                .replace('|', ' ')
            val albumId = song.albumId?.trim().orEmpty()
            // 没有 mixsongid 时留空: 服务端 Number('' || 0) => 0
            "${name}|${hash}|${albumId}|"
        }.joinToString(",")
    }

    /**
     * 上游 `data` 的管道格式: `歌名|hash|album_id|mixsongid`, 多首用逗号分隔
     *
     * 官方 `playlist_tracks_add.js` 就是这么拼给酷狗 `/cloudlist.service/v6/add_song`
     * 的。该后端**自身入参**用的是 `songs` 对象数组, 但 body 里的 `data` 不被识别;
     * 这里把管道串同时挂到查询参数上, 兼容"它从查询串取 data"的实现。
     *
     * 歌名里的 `,`/`|` 会破坏分隔, 统一替换成空格。
     */
    internal fun buildTracksAddPipeData(songs: List<KugouSong>): String {
        val seen = mutableSetOf<String>()
        return songs.mapNotNull { song ->
            val hash = song.hash.trim()
            if (hash.isEmpty() || !seen.add(hash)) return@mapNotNull null
            val name = song.title.trim().replace(',', ' ').replace('|', ' ')
            "${name}|${hash}|${song.albumId?.trim().orEmpty()}|${song.id.trim()}"
        }.joinToString(",")
    }

    /**
     * 构造 `songs` 数组
     *
     * **字段名以实测为准**: 该后端读的是 `songs`(对象数组), `listid` 同样在 body 里。
     * 曾误按官方 `playlist_tracks_add.js` 的 `data`(管道分隔字符串) 改写, 结果服务端
     * 直接回 `40005 歌曲列表不能为空` —— `data` 是它**转发给酷狗上游**时用的格式,
     * 不是它自己的入参格式。
     *
     * 元素需要 `hash`/`name`/`album_id`/`mixsongid` 四项:
     * - 实测该后端要求 `mixsongid` 是**字符串**(传数字会回类型错)
     * - 缺项时上游回"歌曲列表不能为空", 因此能带上就带上
     * - 不要用 hash 顶替 `mixsongid`, 否则上游找不到歌曲
     */
    internal fun buildTracksAddSongs(songs: List<KugouSong>): JSONArray {
        val result = JSONArray()
        val seen = mutableSetOf<String>()
        songs.forEach { song ->
            val hash = song.hash.trim()
            if (hash.isEmpty() || !seen.add(hash)) return@forEach
            result.put(
                JSONObject()
                    .put("hash", hash)
                    .put("name", song.title.trim())
                    .put("album_id", song.albumId?.trim().orEmpty())
                    .put("mixsongid", song.id.trim())
            )
        }
        return result
    }

    private fun trackQuery(
        idKey: String,
        idValue: String,
        page: Int,
        pageSize: Int
    ): Map<String, String?> = mapOf(
        idKey to idValue,
        "page" to page.toString(),
        "pagesize" to pageSize.toString()
    )

    /**
     * 是否值得改用 `listid` 再试一次
     *
     * "需要登录"换接口也没用, 其余错误 (例如 20010 歌单文件取不到) 才回退。
     */
    internal fun shouldFallbackToUserTrackEndpoint(error: KugouApiException): Boolean =
        error !is KugouApiException.SessionRequired

    internal fun parseUserPlaylists(json: JSONObject): List<KugouPlaylistSummary> {
        val array = findPlaylistArray(json) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::parseKugouPlaylistSummary)
        }
    }

    /**
     * `/user/playlist` 的 `info[]` 元素
     *
     * 名字是必需的: 没有名字的条目在界面上无法呈现, 直接丢弃。
     */
    internal fun parseKugouPlaylistSummary(item: JSONObject): KugouPlaylistSummary? {
        val name = item.optStringIgnoreCase("name", "listname", "title") ?: return null
        val sourceGlobalId = item.optStringIgnoreCase("list_create_gid")
        val globalCollectionId = item.optStringIgnoreCase("global_collection_id")
        val listId = optNonZeroId(item, "listid", "list_id")
        return KugouPlaylistSummary(
            listId = listId,
            // 与 KA Music 一致: 收藏歌单要靠 list_create_gid 才能取到曲目
            globalCollectionId = sourceGlobalId ?: globalCollectionId ?: listId,
            name = name,
            coverUrl = normalizeKugouImageUrl(
                item.optStringIgnoreCase("pic", "cover", "flexible_cover", "img")
            ),
            trackCount = item.optIntIgnoreCase("count", "song_count", "songcount") ?: 0,
            creatorName = item.optStringIgnoreCase(
                "list_create_username",
                "nickname",
                "user_name",
                "username"
            ),
            intro = item.optStringIgnoreCase("intro", "description"),
            isDefault = item.optIntIgnoreCase("is_def", "is_default") ?: 0,
            type = item.optIntIgnoreCase("type") ?: 0,
            source = item.optIntIgnoreCase("source") ?: 0,
            sourceGlobalId = sourceGlobalId,
            sourceListId = item.optStringIgnoreCase("list_create_listid"),
            musicLibId = optNonZeroId(item, "musiclib_id")
        )
    }

    internal fun parsePlaylistSongPage(
        json: JSONObject,
        page: Int,
        pageSize: Int
    ): KugouPlaylistSongPage {
        val array = extractKugouSongArray(json)
        val songs = if (array == null) {
            emptyList()
        } else {
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::parseKugouSong)
            }
        }
        val declaredTotal = json.optIntIgnoreCase("count", "total", "total_count") ?: 0
        return KugouPlaylistSongPage(
            songs = songs,
            page = page,
            pageSize = pageSize,
            total = declaredTotal.takeIf { it > 0 } ?: songs.size,
            hasMore = hasMore(
                loadedCount = songs.size,
                page = page,
                pageSize = pageSize,
                declaredTotal = declaredTotal
            ),
            // 自建歌单只有在 list_info 里才带封面
            coverUrl = resolveListInfoCoverUrl(json)
        )
    }

    /**
     * 取 `list_info.pic`
     *
     * 该字段可能是**空字符串**(歌单没设封面), 也可能带 `{size}` 占位符,
     * 都由 [normalizeKugouImageUrl] 统一处理。
     */
    private fun resolveListInfoCoverUrl(json: JSONObject): String? {
        val listInfo = json.optJSONObject("list_info")
            ?: json.optJSONObject("listInfo")
            ?: return null
        return normalizeKugouImageUrl(
            listInfo.optStringIgnoreCase("pic", "cover", "flexible_cover", "img")
        )
    }

    internal fun parsePlaylistDetail(json: JSONObject): KugouPlaylistSummary? {
        val node = extractPlaylistDetailObject(json) ?: return null
        return parseKugouPlaylistSummary(node)
    }

    /**
     * 该后端把歌单对象**直接放在根节点**, 但其它部署可能包成 `data` / `data.info`
     */
    private fun extractPlaylistDetailObject(json: JSONObject): JSONObject? {
        val looksLikePlaylist = json.optStringIgnoreCase("name") != null &&
            (
                json.has("global_collection_id") ||
                    json.has("listid") ||
                    json.has("list_create_gid")
                )
        if (looksLikePlaylist) return json

        val data: Any? = json.opt("data")
        return when (data) {
            is JSONObject -> data.optJSONObject("info")
                ?: data.optArrayIgnoreCase("info", "list")?.optJSONObject(0)
                ?: data.takeIf { it.optStringIgnoreCase("name") != null }

            is JSONArray -> data.optJSONObject(0)
            else -> json.optArrayIgnoreCase("info", "list")?.optJSONObject(0)
        }
    }

    /**
     * `info` 既可能挂在根节点, 也可能包在 `data` 里
     */
    private fun findPlaylistArray(json: JSONObject): JSONArray? {
        json.optArrayIgnoreCase("info", "playlists", "playlist", "list")
            ?.let { return it }

        val data: Any? = json.opt("data")
        return (data as? JSONObject)?.optArrayIgnoreCase("info", "playlists", "playlist", "list")
    }

    /**
     * 数字 id 字段统一成字符串, 并丢掉该后端常用的 `0` 占位
     */
    private fun optNonZeroId(json: JSONObject, vararg keys: String): String? =
        json.optStringIgnoreCase(*keys)?.takeIf { it != "0" }

    /**
     * 有 `count` 时按总数判断; 没有总数时"整页返回"就当还有下一页
     */
    private fun hasMore(
        loadedCount: Int,
        page: Int,
        pageSize: Int,
        declaredTotal: Int
    ): Boolean = if (declaredTotal > 0) {
        page.toLong() * pageSize.toLong() < declaredTotal.toLong()
    } else {
        loadedCount >= pageSize
    }

    companion object {
        private const val TAG = "KugouPlaylistRepository"

        private const val USER_PLAYLIST_PATH = "/user/playlist"
        private const val TRACK_ALL_PATH = "/playlist/track/all"
        private const val TRACK_ALL_NEW_PATH = "/playlist/track/all/new"
        private const val PLAYLIST_DETAIL_PATH = "/playlist/detail"

        /** 写接口, 必须 POST (GET 会得到 405) */
        private const val TRACKS_ADD_PATH = "/playlist/tracks/add"

        /** 移出歌单: POST, 参数走查询串 (与 add 不同) */
        private const val TRACKS_DEL_PATH = "/playlist/tracks/del"

        internal const val SESSION_REQUIRED_MESSAGE = "酷狗未登录, 无法获取歌单"

        /** KA Music 的 `userPlaylists` 默认每页 30 */
        const val DEFAULT_PLAYLIST_PAGE_SIZE = 30

        /** 曲目页比歌单页大, 减少翻页次数 */
        const val DEFAULT_SONG_PAGE_SIZE = 60

        private const val MAX_PAGE_SIZE = 500

        // ---- 测试专用转发: 解析函数不依赖实例状态, 但挂在类上 ----

        internal fun parseUserPlaylistsForTest(json: JSONObject): List<KugouPlaylistSummary> =
            KugouPlaylistRepository(UNUSED_CLIENT).parseUserPlaylists(json)

        internal fun parsePlaylistSongPageForTest(
            json: JSONObject,
            page: Int = 1,
            pageSize: Int = DEFAULT_SONG_PAGE_SIZE
        ): KugouPlaylistSongPage =
            KugouPlaylistRepository(UNUSED_CLIENT).parsePlaylistSongPage(json, page, pageSize)

        internal fun parsePlaylistDetailForTest(json: JSONObject): KugouPlaylistSummary? =
            KugouPlaylistRepository(UNUSED_CLIENT).parsePlaylistDetail(json)

        internal fun shouldFallbackToUserTrackEndpointForTest(error: KugouApiException): Boolean =
            KugouPlaylistRepository(UNUSED_CLIENT).shouldFallbackToUserTrackEndpoint(error)

        internal fun buildTracksAddSongsForTest(songs: List<KugouSong>): String =
            KugouPlaylistRepository(UNUSED_CLIENT).buildTracksAddSongs(songs).toString()

        /** 解析函数不会触碰网络, 占位实例的创建延迟到真正调用时 */
        private val UNUSED_CLIENT: KugouClient by lazy {
            KugouClient(
                okHttpClient = okhttp3.OkHttpClient(),
                baseUrlProvider = { moe.ouom.neriplayer.platform.kugou.api.KugouEndpointConfig.DEFAULT_BASE_URL },
                sessionProvider = { moe.ouom.neriplayer.data.model.kugou.KugouAuthSession.Empty }
            )
        }
    }
}

/**
 * 把歌单曲目转成可播放/可下载的队列条目
 *
 * 与 app 层的 `SongSearchInfo.toKugouQueueSong` 保持同一套约定: 酷狗以 FileHash
 * 作为播放主键, 因此 `channelId` 固定 `kugou`, `audioId` 放 hash;
 * `sourceStableKey` 用 `Kugou:<hash>`, 保证同一首歌从搜索或歌单进入时身份一致
 * (去重、收藏、同步都以它为准)。
 */
fun KugouSong.toKugouQueueSong(): SongItem = SongItem(
    id = stableKugouQueueId(hash),
    name = title,
    artist = artist,
    album = SongSourceTags.KUGOU,
    albumId = 0L,
    durationMs = durationMs,
    coverUrl = coverUrl,
    mediaUri = null,
    channelId = ListenTogetherChannels.KUGOU,
    audioId = hash,
    sourceStableKey = "${SongSourceTags.KUGOU}:$hash"
)

/**
 * 歌单条目没有数字 id, 但 `SongItem.id` 是 Long
 *
 * 用 hash 的稳定散列填充; 真正的身份由 `sourceStableKey` 决定, 这里只做兜底。
 */
internal fun stableKugouQueueId(hash: String): Long {
    val value = hash.hashCode().toLong()
    return if (value < 0L) -value else value
}
