package moe.ouom.neriplayer.platform.kugou.api.codec

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
 * File: moe.ouom.neriplayer.platform.kugou.api.codec/KugouResponseCodec
 */

import moe.ouom.neriplayer.data.model.kugou.KugouSong
import org.json.JSONArray
import org.json.JSONObject

/**
 * 酷狗后端字段大小写极其不统一 (`MixSongID` / `mixsongid`, `FileHash` / `hash`)
 *
 * 这里统一做大小写不敏感读取, 避免为每种叫法各写一条分支
 */
internal fun JSONObject.optValueIgnoreCase(vararg keys: String): Any? {
    for (key in keys) {
        if (has(key)) {
            val value = opt(key)
            if (value != null && value != JSONObject.NULL) return value
        }
    }
    val lowerToActual = keys().asSequence().associateBy { it.lowercase() }
    for (key in keys) {
        val actual = lowerToActual[key.lowercase()] ?: continue
        val value = opt(actual)
        if (value != null && value != JSONObject.NULL) return value
    }
    return null
}

internal fun JSONObject.optStringIgnoreCase(vararg keys: String): String? {
    val value = optValueIgnoreCase(*keys) ?: return null
    val text = when (value) {
        is String -> value
        is Number -> value.toString()
        else -> value.toString()
    }
    return text.trim().takeIf { it.isNotEmpty() }
}

internal fun JSONObject.optLongIgnoreCase(vararg keys: String): Long? {
    val value = optValueIgnoreCase(*keys) ?: return null
    return when (value) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }
}

internal fun JSONObject.optIntIgnoreCase(vararg keys: String): Int? {
    val value = optValueIgnoreCase(*keys) ?: return null
    return when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toDoubleOrNull()?.toInt()
        else -> null
    }
}

internal fun JSONObject.optBooleanIgnoreCase(vararg keys: String): Boolean? {
    val value = optValueIgnoreCase(*keys) ?: return null
    return when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when (value.trim().lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
        else -> null
    }
}

/**
 * 把后端返回的歌曲节点解码成规范化模型
 *
 * 歌名优先用 `OriSongName` + `Suffix` (不含歌手的原始名), 缺失时退回
 * `FileName` 并剥掉"歌手 - "前缀
 */
internal fun parseKugouSong(item: JSONObject): KugouSong? {
    val hash = item.optStringIgnoreCase("FileHash", "hash", "hash_320", "hash_flac")
        ?: return null
    if (hash.isBlank()) return null

    val artist = resolveKugouArtist(item).orEmpty()

    val title = buildKugouTitle(item, artist)

    return KugouSong(
        id = item.optStringIgnoreCase("MixSongID", "mixsongid", "Audioid", "audio_id", "songid", "fileid")
            ?: hash,
        hash = hash,
        title = title,
        artist = artist.ifBlank { "未知艺人" },
        albumId = item.optStringIgnoreCase("AlbumID", "album_id"),
        albumName = resolveKugouAlbumName(item),
        coverUrl = normalizeKugouImageUrl(
            item.optStringIgnoreCase("Image", "sizable_cover", "img", "cover")
        ),
        durationMs = resolveKugouDurationMs(item)
    )
}

/**
 * 歌手字段有两种形态
 *
 * `/search` 直接给 `SingerName`, 而 `/playlist/track/all` 只给 `singerinfo`
 * 数组 (`[{id, name, avatar}]`), 这里统一成 `歌手A/歌手B`。
 */
internal fun resolveKugouArtist(item: JSONObject): String? {
    item.optStringIgnoreCase("SingerName", "author_name", "singername", "singer_name")
        ?.let { return it }

    val singers = item.optValueIgnoreCase("singerinfo", "singerInfo", "singers") as? JSONArray
        ?: return null
    val names = (0 until singers.length()).mapNotNull { index ->
        singers.optJSONObject(index)?.optStringIgnoreCase("name", "singer_name", "author_name")
    }
    return names.joinToString("/").takeIf { it.isNotBlank() }
}

/** `/playlist/track/all` 的专辑名藏在 `albuminfo.name` 里 */
internal fun resolveKugouAlbumName(item: JSONObject): String? {
    item.optStringIgnoreCase("AlbumName", "album_name")?.let { return it }

    val albumInfo = item.optValueIgnoreCase("albuminfo", "albumInfo", "album_info") as? JSONObject
        ?: return null
    return albumInfo.optStringIgnoreCase("name", "album_name")
}

private fun buildKugouTitle(item: JSONObject, artist: String): String {
    val originalName = item.optStringIgnoreCase("OriSongName")
    val suffix = item.optStringIgnoreCase("Suffix").orEmpty()
    if (!originalName.isNullOrBlank()) {
        return if (suffix.isBlank()) originalName else "$originalName $suffix"
    }
    val displayName = item.optStringIgnoreCase("FileName", "songname", "name", "audio_name")
        ?: return "未知歌曲"
    return stripArtistNamePrefix(displayName, artist)
}

/**
 * 去掉 `FileName` 里形如 "周杰伦 - 晴天" 的歌手前缀
 */
internal fun stripArtistNamePrefix(displayName: String, artist: String): String {
    val separators = listOf(" - ", " – ", "-")
    for (separator in separators) {
        val index = displayName.indexOf(separator)
        if (index <= 0) continue
        val prefix = displayName.substring(0, index).trim()
        if (prefix.isEmpty()) continue
        val artistMatches = artist.isNotBlank() &&
            artist.split('/', '、', ',', '&')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .any { it.equals(prefix, ignoreCase = true) }
        if (artistMatches || artist.isBlank()) {
            val remainder = displayName.substring(index + separator.length).trim()
            if (remainder.isNotEmpty()) return remainder
        }
    }
    return displayName
}

/**
 * 时长字段在不同接口里的单位不一致: `Duration`/`time_length` 是秒, `timelen` 是毫秒
 */
private fun resolveKugouDurationMs(item: JSONObject): Long {
    item.optLongIgnoreCase("timelen", "timelength")?.let { millis ->
        if (millis > 0L) return millis
    }
    item.optLongIgnoreCase("Duration", "time_length", "duration")?.let { seconds ->
        if (seconds > 0L) return seconds * 1000L
    }
    return 0L
}

/**
 * 酷狗封面走 http 的 imge.kugou.com, 统一升级到 https 避免明文流量被拦
 */
internal fun normalizeKugouImageUrl(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return when {
        value.startsWith("http://", ignoreCase = true) ->
            "https://" + value.substring("http://".length)
        value.startsWith("https://", ignoreCase = true) -> value
        value.startsWith("//") -> "https:$value"
        else -> value
    }
}

/**
 * 大小写不敏感地取数组字段
 *
 * `JSONObject.optJSONArray` 是精确匹配, 而该后端连 `songs` / `Songs` 都会混用
 */
internal fun JSONObject.optArrayIgnoreCase(vararg keys: String): JSONArray? =
    optValueIgnoreCase(*keys) as? JSONArray

/**
 * `/search` 既可能返回裸数组, 也可能包成 `{ songs: [...] }`, 这里统一取数组
 */
internal fun extractKugouSongArray(root: Any?): JSONArray? = when (root) {
    is JSONArray -> root
    is JSONObject -> {
        val data: Any? = root.opt("data")
        root.optArrayIgnoreCase("songs", "song", "lists", "info")
            ?: (data as? JSONObject)?.optArrayIgnoreCase("songs", "song", "lists", "info")
    }
    else -> null
}
