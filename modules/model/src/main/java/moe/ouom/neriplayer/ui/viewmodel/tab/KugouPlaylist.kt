package moe.ouom.neriplayer.ui.viewmodel.tab

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import moe.ouom.neriplayer.data.model.kugou.KugouPlaylistSummary

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
 * File: moe.ouom.neriplayer.ui.viewmodel.tab/KugouPlaylist
 */

/**
 * 媒体库里的酷狗歌单条目
 *
 * 与 [BiliPlaylist] / [YouTubeMusicPlaylist] 同级: 可 Parcelize, 便于媒体库宿主
 * 在 `rememberSaveable` 中保存"当前打开的歌单"。
 */
@Parcelize
data class KugouPlaylist(
    val listId: String,
    val globalCollectionId: String,
    val name: String,
    val creatorName: String = "",
    val coverUrl: String = "",
    val trackCount: Int = 0,
    val intro: String = "",
    /** 后端 `is_def == 2`: "我喜欢" */
    val isDefaultPlaylist: Boolean = false,
    /** 后端 `type == 1`: 收藏的歌单, 否则是自建 */
    val isCollected: Boolean = false
) : Parcelable

/** 仓库层模型 → 界面模型 */
fun KugouPlaylistSummary.toKugouPlaylist(): KugouPlaylist = KugouPlaylist(
    listId = listId.orEmpty(),
    globalCollectionId = globalCollectionId.orEmpty(),
    name = name,
    creatorName = creatorName.orEmpty(),
    coverUrl = coverUrl.orEmpty(),
    trackCount = trackCount,
    intro = intro.orEmpty(),
    isDefaultPlaylist = isLikedPlaylist(),
    isCollected = isCollected()
)
