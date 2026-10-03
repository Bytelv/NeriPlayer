package moe.ouom.neriplayer.data.model.playlist

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
 * File: moe.ouom.neriplayer.data.model.playlist/AddToPlaylistTarget
 */

/**
 * "添加到歌单" 弹窗里的目标平台
 *
 * 本地歌单走本地仓库, 网易云 / 酷狗走各自的远端写歌单接口, 因此三者的
 * "歌单 id" 类型并不一致, 统一在 [AddToPlaylistTarget.id] 里用字符串承载。
 */
enum class AddToPlaylistPlatform {
    LOCAL,
    NETEASE,
    KUGOU
}

/**
 * 一个可选的"加到哪个歌单"目标
 *
 * 只是展示 + 提交所需的最小信息: 名字、曲目数用于列表呈现, id 用于提交。
 * 远端目标的 [id] 就是平台自己的主键:
 * - 网易云: `playlistId`
 * - 酷狗: `listid` (`KugouPlaylistSummary.listId`, 写歌单接口 `/playlist/tracks/add` 认这个)
 * - 本地: `LocalPlaylist.id`
 */
data class AddToPlaylistTarget(
    val platform: AddToPlaylistPlatform,
    val id: String,
    val name: String,
    val trackCount: Int = 0
) {
    /** 稳定 key: 列表 item key 与"提交中"状态都用它 */
    val key: String get() = "${platform.name}:$id"
}
