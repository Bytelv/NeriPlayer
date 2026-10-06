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
 * File: moe.ouom.neriplayer.ui.viewmodel.playlist/AddToPlaylistPlanner
 */

import moe.ouom.neriplayer.data.model.kugou.KugouSong
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.data.model.playlist.AddToPlaylistPlatform

/**
 * 当前歌曲的"平台归属 + 已知 id"
 *
 * 与 `SongItem` 解耦: 这里只保留匹配与提交需要的最小信息, 因此整套判定逻辑可以
 * 在纯 JVM 单测里覆盖, 不需要 Android 运行时。
 */
data class AddToPlaylistSong(
    val name: String,
    val artist: String,
    val durationMs: Long,
    /** 歌曲本身就是网易云来源 */
    val isNeteaseSource: Boolean = false,
    /** 仅当 [isNeteaseSource] 时有意义 (`SongItem.id`) */
    val neteaseSongId: Long = 0L,
    /** 歌曲本身就是酷狗来源 */
    val isKugouSource: Boolean = false,
    /** 仅当 [isKugouSource] 时有意义 (`SongItem.audioId` = FileHash) */
    val kugouHash: String = ""
)

/**
 * 无法提交时的明确原因
 *
 * 这些情况一律**不发起写请求**, 也不拿当前歌曲的 id 硬提交。
 */
enum class AddToPlaylistFailure {
    /** 没有正在播放的歌曲 */
    SONG_UNAVAILABLE,

    /** 网易云目标: 歌曲虽是网易云来源, 但本地 id 不可用 */
    INVALID_NETEASE_SONG_ID,

    /** 酷狗目标: 歌曲虽是酷狗来源, 但没有可用的 FileHash */
    MISSING_KUGOU_HASH,

    /** 本地歌单不走远端提交路径 */
    UNSUPPORTED_PLATFORM
}

/**
 * 一次"加到远端歌单"的解析结果
 *
 * - [NeteaseSongId] / [KugouHash]: 已经拿到目标平台自己的 id, 可以直接提交
 * - [NeedsPlatformSearch]: 歌曲不属于目标平台, 必须先搜索匹配
 * - [SearchFailed]: 搜索本身失败 (网络/接口异常), 界面要给出原因
 * - [NoMatch]: 搜索过但没有可用候选 (界面必须明确提示, 不提交)
 * - [Failed]: 明确失败, 不提交
 */
sealed interface AddToPlaylistResolution {
    data class NeteaseSongId(val songId: Long) : AddToPlaylistResolution

    /** [title] / [artist] 是写歌单 payload 要用的那一份, 可能来自搜索命中的候选 */
    data class KugouHash(
        val hash: String,
        val title: String = "",
        val artist: String = ""
    ) : AddToPlaylistResolution

    data class NeedsPlatformSearch(val platform: AddToPlaylistPlatform) : AddToPlaylistResolution

    data class SearchFailed(
        val platform: AddToPlaylistPlatform,
        val detail: String? = null
    ) : AddToPlaylistResolution

    data class NoMatch(val platform: AddToPlaylistPlatform) : AddToPlaylistResolution

    data class Failed(val failure: AddToPlaylistFailure) : AddToPlaylistResolution
}

/** 目标平台搜索的结果: 区分"没有匹配"和"搜索失败" */
sealed interface PlatformSearchResult {
    data class Found(val candidate: SongSearchInfo) : PlatformSearchResult

    data object NotFound : PlatformSearchResult

    data class Failed(val detail: String? = null) : PlatformSearchResult
}

/** 只有这两个平台支持"加到远端歌单" */
fun AddToPlaylistPlatform.toMusicPlatformOrNull(): MusicPlatform? = when (this) {
    AddToPlaylistPlatform.LOCAL -> null
    AddToPlaylistPlatform.NETEASE -> MusicPlatform.CLOUD_MUSIC
    AddToPlaylistPlatform.KUGOU -> MusicPlatform.KUGOU
}

/**
 * 目标平台搜索用的关键字
 *
 * 带歌手。**只按歌名检索经常搜不到正确版本**: 同名翻唱/影视版会把结果页前面占满,
 * 真正的原版挤不进平台默认返回的第一页(实测酷狗搜 "SISTERS AND BROTHERS" 前 6 条
 * 全是别人的版本, 而 "Kanye West Sisters and Brothers" 第一条就是原版)。
 *
 * 歌手缺失时退回歌名, 不能因为缺歌手就搜不了。
 * 注意: 这里只影响**检索词**, 歌名校验与写歌单 payload 仍用 [AddToPlaylistSong.name]。
 */
fun AddToPlaylistSong.searchKeyword(): String {
    val trimmedName = name.trim()
    val trimmedArtist = artist.trim()
    return when {
        trimmedName.isEmpty() -> trimmedArtist
        trimmedArtist.isEmpty() -> trimmedName
        else -> "$trimmedArtist $trimmedName"
    }
}

/**
 * 判定"这次点击该直接添加还是先搜索"
 *
 * 属于目标平台就用本地已知 id 直接添加; 否则交给目标平台的搜索匹配。
 *
 * 酷狗额外有一条: **只要手里已经有 FileHash 就直接用**, 不再去搜索。
 * 原因有两层:
 * - 同厂商不该再搜一遍。歌曲本身就来自酷狗(搜索/歌单)时 hash 是权威身份,
 *   搜索纯属多余。
 * - 更关键的是自动换源: 网易云歌曲换源播放到酷狗后, 歌曲**身份仍是网易云**
 *   (只有播放地址指向酷狗), 因此会被判成"跨平台", 转而去做文本搜索 —— 搜不到
 *   就报"未找到匹配"。而换源时其实已经拿到了正确的 hash, 这里复用它即可,
 *   既避免那次多余的搜索, 也彻底消除"音源是酷狗却加不进酷狗歌单"的怪现象。
 */
fun planAddToRemotePlaylist(
    song: AddToPlaylistSong?,
    platform: AddToPlaylistPlatform
): AddToPlaylistResolution {
    if (song == null || song.name.isBlank()) {
        return AddToPlaylistResolution.Failed(AddToPlaylistFailure.SONG_UNAVAILABLE)
    }

    // 已知 hash 就是最强匹配, 优先于任何搜索
    val knownKugouHash = song.kugouHash.trim().takeIf { it.isNotEmpty() }

    return when (platform) {
        AddToPlaylistPlatform.LOCAL ->
            AddToPlaylistResolution.Failed(AddToPlaylistFailure.UNSUPPORTED_PLATFORM)

        AddToPlaylistPlatform.NETEASE -> if (song.isNeteaseSource) {
            song.neteaseSongId
                .takeIf { it > 0L }
                ?.let(AddToPlaylistResolution::NeteaseSongId)
                ?: AddToPlaylistResolution.Failed(AddToPlaylistFailure.INVALID_NETEASE_SONG_ID)
        } else {
            AddToPlaylistResolution.NeedsPlatformSearch(platform)
        }

        AddToPlaylistPlatform.KUGOU -> when {
            knownKugouHash != null -> AddToPlaylistResolution.KugouHash(
                hash = knownKugouHash,
                title = song.name,
                artist = song.artist
            )
            // 明确是酷狗来源却没有 hash: 报明确原因, 不要退化成搜索
            song.isKugouSource ->
                AddToPlaylistResolution.Failed(AddToPlaylistFailure.MISSING_KUGOU_HASH)

            else -> AddToPlaylistResolution.NeedsPlatformSearch(platform)
        }
    }
}

/**
 * 完整解析一次"加到远端歌单"
 *
 * [search] 只在歌曲不属于目标平台时才会被调用 —— 测试据此断言"属于目标平台
 * 就不该触发搜索"; 属于目标平台时直接用本地已知 id, 拿到候选后也必须回到
 * [resolveSearchedCandidate] 做 id 校验。
 */
suspend fun resolveAddToPlaylistOutcome(
    song: AddToPlaylistSong?,
    platform: AddToPlaylistPlatform,
    search: suspend (AddToPlaylistSong) -> PlatformSearchResult
): AddToPlaylistResolution {
    val plan = planAddToRemotePlaylist(song, platform)
    if (plan !is AddToPlaylistResolution.NeedsPlatformSearch) return plan

    val context = song ?: return AddToPlaylistResolution.Failed(AddToPlaylistFailure.SONG_UNAVAILABLE)
    return when (val result = search(context)) {
        is PlatformSearchResult.Found -> resolveSearchedCandidate(platform, result.candidate)
        PlatformSearchResult.NotFound -> AddToPlaylistResolution.NoMatch(platform)
        is PlatformSearchResult.Failed ->
            AddToPlaylistResolution.SearchFailed(platform, result.detail)
    }
}

/**
 * 把目标平台的搜索结果收敛成可直接提交的 id
 *
 * - 网易云: `SongSearchInfo.id` 就是网易云 songId, 必须能解析成正数
 * - 酷狗: `SongSearchInfo.id` 就是 FileHash
 * - 没有候选 (null) → [AddToPlaylistResolution.NoMatch], 界面提示"未找到匹配"
 */
fun resolveSearchedCandidate(
    platform: AddToPlaylistPlatform,
    candidate: SongSearchInfo?
): AddToPlaylistResolution {
    if (candidate == null) return AddToPlaylistResolution.NoMatch(platform)

    return when (platform) {
        AddToPlaylistPlatform.LOCAL ->
            AddToPlaylistResolution.Failed(AddToPlaylistFailure.UNSUPPORTED_PLATFORM)

        AddToPlaylistPlatform.NETEASE -> candidate.id
            .trim()
            .toLongOrNull()
            ?.takeIf { it > 0L }
            ?.let(AddToPlaylistResolution::NeteaseSongId)
            ?: AddToPlaylistResolution.Failed(AddToPlaylistFailure.INVALID_NETEASE_SONG_ID)

        AddToPlaylistPlatform.KUGOU -> candidate.id
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let {
                AddToPlaylistResolution.KugouHash(
                    hash = it,
                    title = candidate.songName.trim(),
                    artist = candidate.singer.trim()
                )
            }
            ?: AddToPlaylistResolution.Failed(AddToPlaylistFailure.MISSING_KUGOU_HASH)
    }
}

/**
 * 酷狗写歌单条目
 *
 * `/playlist/tracks/add` 的 `data` 最少需要 `歌曲名|hash`, 因此 hash 同时充当
 * `id` 与 `hash` 字段 (酷狗侧 `id` 是 album_audio_id, 这里没有就不编造)。
 */
fun buildKugouPlaylistAddSong(title: String, artist: String, hash: String): KugouSong = KugouSong(
    id = hash,
    hash = hash,
    title = title,
    artist = artist
)
