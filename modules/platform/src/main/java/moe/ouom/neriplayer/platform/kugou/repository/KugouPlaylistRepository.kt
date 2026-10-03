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
            )
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
