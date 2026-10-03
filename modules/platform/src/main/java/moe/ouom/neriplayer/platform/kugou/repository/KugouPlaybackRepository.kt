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
 * File: moe.ouom.neriplayer.platform.kugou.repository/KugouPlaybackRepository
 */

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.kugou.KugouAudioQuality
import moe.ouom.neriplayer.data.model.kugou.KugouAuthSession
import moe.ouom.neriplayer.data.model.kugou.KugouPlayUrl
import moe.ouom.neriplayer.data.model.kugou.KugouVipInfo
import moe.ouom.neriplayer.platform.kugou.api.KugouApiException
import moe.ouom.neriplayer.platform.kugou.api.KugouEndpointConfig
import moe.ouom.neriplayer.platform.kugou.api.client.KugouClient
import org.json.JSONObject

/**
 * 酷狗播放地址解析
 *
 * 后端的 `/song/url` 需要有效会话: 未登录时酷狗返回 `errcode=20028`
 * ("本次请求需要验证"), 这里翻译成 [KugouApiException.SessionRequired] 让上层
 * 提示用户登录, 而不是静默失败。
 */
class KugouPlaybackRepository(private val client: KugouClient) {

    /**
     * 按偏好音质解析直链, 失败时逐级降级
     *
     * @param hash 歌曲 FileHash
     * @param albumId 专辑 ID, 可空
     * @param albumAudioId MixSongID, 可空
     */
    suspend fun resolvePlayUrl(
        hash: String,
        albumId: String? = null,
        albumAudioId: String? = null,
        preferredQuality: String
    ): KugouPlayUrl? = withContext(Dispatchers.IO) {
        val normalizedHash = hash.trim()
        if (normalizedHash.isEmpty()) return@withContext null

        val preferred = KugouAudioQuality.fromApiValue(preferredQuality)
        var sessionRequired: KugouApiException.SessionRequired? = null

        for (quality in KugouAudioQuality.fallbacks(preferred)) {
            val result = fetchPlayUrl(
                hash = normalizedHash,
                albumId = albumId,
                albumAudioId = albumAudioId,
                quality = quality
            )
            when (result) {
                is PlayUrlAttempt.Resolved -> {
                    if (quality != preferred) {
                        NPLogger.w(
                            TAG,
                            "酷狗音质降级: hash=$normalizedHash, preferred=${preferred.apiValue}, " +
                                "resolved=${quality.apiValue}"
                        )
                    }
                    return@withContext result.playUrl
                }

                is PlayUrlAttempt.SessionRequired -> sessionRequired = result.error
                PlayUrlAttempt.Unavailable -> Unit
            }
        }

        sessionRequired?.let { throw it }
        NPLogger.w(TAG, "酷狗未取到可用直链: hash=$normalizedHash, preferred=${preferred.apiValue}")
        null
    }

    /** 查询账号 VIP 状态, 供设置页展示 */
    suspend fun fetchVipInfo(): KugouVipInfo? =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = client.getJson("/user/vip/detail")
                parseVipInfo(json)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                NPLogger.w(TAG, "酷狗 VIP 查询失败: ${error.message.orEmpty()}")
                null
            }
        }

    private suspend fun fetchPlayUrl(
        hash: String,
        albumId: String?,
        albumAudioId: String?,
        quality: KugouAudioQuality
    ): PlayUrlAttempt {
        return try {
            val json = client.getJson(
                path = "/song/url",
                query = buildMap {
                    put("hash", hash)
                    put("quality", quality.apiValue)
                    albumId?.takeIf { it.isNotBlank() }?.let { put("album_id", it) }
                    albumAudioId?.takeIf { it.isNotBlank() }?.let { put("album_audio_id", it) }
                    put("free_part", "false")
                }
            )
            parsePlayUrl(json, quality)?.let(PlayUrlAttempt::Resolved)
                ?: run {
                    // 拿到 200 但没有直链: 把后端原始响应留下, 便于区分权限不足与参数问题
                    NPLogger.w(
                        TAG,
                        "酷狗未返回直链: hash=$hash, quality=${quality.apiValue}, " +
                            "albumId=${albumId.orEmpty()}, albumAudioId=${albumAudioId.orEmpty()}, " +
                            "raw=${json.toString().take(LOG_BODY_LIMIT)}"
                    )
                    PlayUrlAttempt.Unavailable
                }
        } catch (error: CancellationException) {
            throw error
        } catch (error: KugouApiException.SessionRequired) {
            PlayUrlAttempt.SessionRequired(error)
        } catch (error: Exception) {
            NPLogger.w(
                TAG,
                "酷狗直链解析失败: hash=$hash, quality=${quality.apiValue}, ${error.message.orEmpty()}"
            )
            PlayUrlAttempt.Unavailable
        }
    }

    /**
     * 解析 `/song/url` 的响应
     *
     * 该后端把 `url` 下成**字符串数组** (主链 + 备用链):
     * `{"url":["http://...mp3","http://...mp3"]}`
     *
     * `JSONObject.optString` 对数组会返回其字符串化结果 (`["http://..."]`),
     * 直接拿去播放必然失败, 因此必须按数组逐个取。
     */
    internal fun parsePlayUrl(json: JSONObject, requested: KugouAudioQuality): KugouPlayUrl? {
        val urls = collectPlayUrls(json)
        val primary = urls.firstOrNull() ?: return null
        val backup = urls.drop(1).firstOrNull()
            ?: json.optString("backup_url").trim().takeIf { it.isNotEmpty() }
        return KugouPlayUrl(
            url = primary,
            backupUrl = backup,
            durationMs = json.optLong("timelength").takeIf { it > 0L } ?: 0L,
            fileSize = json.optLong("fileSize").takeIf { it > 0L }
                ?: json.optLong("file_size").takeIf { it > 0L }
                ?: 0L,
            fileExtension = json.optString("extName").trim().takeIf { it.isNotEmpty() },
            quality = requested.apiValue
        )
    }

    /** `url` 可能是字符串或字符串数组, 统一收集成有序候选 */
    private fun collectPlayUrls(json: JSONObject): List<String> {
        val array = json.optJSONArray("url")
        if (array != null) {
            return (0 until array.length())
                .mapNotNull { index ->
                    array.optString(index).trim()
                        .takeIf { it.startsWith("http", ignoreCase = true) }
                }
                .distinct()
        }
        return json.optString("url")
            .trim()
            .takeIf { it.startsWith("http", ignoreCase = true) }
            ?.let(::listOf)
            ?: emptyList()
    }

    internal fun parseVipInfo(json: JSONObject): KugouVipInfo {
        return KugouVipInfo(
            isVip = json.optInt("is_vip") == 1,
            isSuperVip = json.optBoolean("isSuperVip"),
            isConceptVip = json.optBoolean("isConceptVip"),
            vipType = json.optInt("vip_type")
        )
    }

    private sealed interface PlayUrlAttempt {
        data class Resolved(val playUrl: KugouPlayUrl) : PlayUrlAttempt
        data class SessionRequired(val error: KugouApiException.SessionRequired) : PlayUrlAttempt
        data object Unavailable : PlayUrlAttempt
    }

    companion object {
        private const val TAG = "KugouPlaybackRepository"
        private const val LOG_BODY_LIMIT = 400

        // ---- 测试专用转发: 解析函数不触碰网络 ----

        internal fun parsePlayUrlForTest(
            json: JSONObject,
            requested: KugouAudioQuality
        ): KugouPlayUrl? = KugouPlaybackRepository(UNUSED_CLIENT).parsePlayUrl(json, requested)

        private val UNUSED_CLIENT: KugouClient by lazy {
            KugouClient(
                okHttpClient = okhttp3.OkHttpClient(),
                baseUrlProvider = { KugouEndpointConfig.DEFAULT_BASE_URL },
                sessionProvider = { KugouAuthSession.Empty }
            )
        }
    }
}
