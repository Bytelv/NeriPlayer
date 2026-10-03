package moe.ouom.neriplayer.data.model.kugou

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
 * File: moe.ouom.neriplayer.data.model.kugou/KugouPlaylistModels
 */

/**
 * 酷狗用户歌单条目 (`/user/playlist` 的 `info[]`)
 *
 * 字段命名对齐 KA Music 的 `PlaylistSummary.fromUser`。取值优先级与它保持一致:
 * `list_create_gid` > `global_collection_id` > `listid`, 因为 `/playlist/track/all`
 * 只认 global collection id, 而收藏歌单里 "list_create_gid" 才是原歌单的全局 id。
 */
data class KugouPlaylistSummary(
    /** `/playlist/track/all/new` 的 `listid`, 仅自建与收藏歌单有 */
    val listId: String? = null,
    /** `/playlist/track/all` 与 `/playlist/detail` 的主键 */
    val globalCollectionId: String? = null,
    val name: String,
    val coverUrl: String? = null,
    val trackCount: Int = 0,
    val creatorName: String? = null,
    val intro: String? = null,
    /** 后端 `is_def`: 2 表示"我喜欢" */
    val isDefault: Int = 0,
    /** 后端 `type`: 0 自建, 1 收藏 */
    val type: Int = 0,
    val source: Int = 0,
    /** 收藏歌单的来源全局 id (`list_create_gid`) */
    val sourceGlobalId: String? = null,
    /** 收藏歌单的来源用户内 id (`list_create_listid`) */
    val sourceListId: String? = null,
    /** 收藏专辑时的专辑 id (`musiclib_id`) */
    val musicLibId: String? = null
) {
    fun isLikedPlaylist(): Boolean = isDefault == LIKED_DEFAULT_FLAG

    fun isCollected(): Boolean = type == COLLECTED_TYPE

    /** 没有可用 id 的歌单无法取曲目, 界面应过滤掉 */
    fun isPlayable(): Boolean =
        !globalCollectionId.isNullOrBlank() || !listId.isNullOrBlank()

    companion object {
        /** KA Music 用 `is_def == 2` 标记"我喜欢" */
        const val LIKED_DEFAULT_FLAG = 2

        /** 后端 `type`: 1 为收藏歌单 */
        const val COLLECTED_TYPE = 1
    }
}

/** 用户歌单分页结果 */
data class KugouPlaylistPage(
    val playlists: List<KugouPlaylistSummary>,
    val page: Int,
    val pageSize: Int,
    val hasMore: Boolean,
    val total: Int = 0
)

/**
 * 歌单曲目分页结果
 *
 * 酷狗没有统一的总数语义: `/playlist/track/all` 回 `count`, 缺失时退化成
 * "本页满了就还有下一页"。
 */
data class KugouPlaylistSongPage(
    val songs: List<KugouSong>,
    val page: Int,
    val pageSize: Int,
    val total: Int,
    val hasMore: Boolean
)
