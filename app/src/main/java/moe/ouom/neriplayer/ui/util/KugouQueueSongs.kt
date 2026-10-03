package moe.ouom.neriplayer.ui.util

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
 * File: moe.ouom.neriplayer.ui.util/KugouQueueSongs
 */

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.SongSourceTags
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherChannels
import moe.ouom.neriplayer.data.model.music.SongSearchInfo

/**
 * 把酷狗搜索结果转成可播放/可下载的队列条目
 *
 * 酷狗以 FileHash 作为播放主键, 因此把它放进 `audioId`; `channelId` 显式标成
 * kugou, 让 `PlayerManager.resolveSongUrl` 与下载解析器能分派到酷狗分支。
 * `sourceStableKey` 提供稳定身份, 不依赖合成出来的自增 id。
 */
fun SongSearchInfo.toKugouQueueSong(): SongItem = SongItem(
    id = stableKugouQueueId(id),
    name = songName,
    artist = singer,
    album = SongSourceTags.KUGOU,
    albumId = 0L,
    durationMs = parseKugouDurationMs(duration),
    coverUrl = coverUrl,
    mediaUri = null,
    channelId = ListenTogetherChannels.KUGOU,
    audioId = id,
    sourceStableKey = "${SongSourceTags.KUGOU}:$id"
)

/**
 * 结果行没有数字 id, 但 `SongItem.id` 是 Long
 *
 * 用 hash 的稳定散列填充, 负数取绝对值避免与 0 混淆; 真正的身份由
 * `sourceStableKey` 决定, 这个值只用于展示与去重兜底。
 */
private fun stableKugouQueueId(hash: String): Long {
    val value = hash.hashCode().toLong()
    return if (value < 0L) -value else value
}

/** 搜索结果里的时长是 `m:ss` / `h:mm:ss` 文本 */
private fun parseKugouDurationMs(value: String): Long {
    val parts = value.trim().split(':')
    if (parts.size !in 2..3) return 0L
    val numbers = parts.map { it.trim().toLongOrNull() ?: return 0L }
    val seconds = when (numbers.size) {
        2 -> numbers[0] * 60L + numbers[1]
        3 -> numbers[0] * 3_600L + numbers[1] * 60L + numbers[2]
        else -> return 0L
    }
    return seconds.takeIf { it > 0L }?.times(1_000L) ?: 0L
}
