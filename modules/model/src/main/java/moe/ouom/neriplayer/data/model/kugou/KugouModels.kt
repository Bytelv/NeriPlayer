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
 * File: moe.ouom.neriplayer.data.model.kugou/KugouModels
 */

/**
 * 酷狗歌曲条目
 *
 * 后端 [https://github.com/MakcRe/KuGouMusicApi] 风格的响应字段大小写并不统一
 * (`MixSongID` / `mixsongid`, `FileHash` / `hash`), 因此统一解码成规范化模型
 */
data class KugouSong(
    /** 专辑音频 ID, `/song/url` 的 `album_audio_id` 参数 */
    val id: String,
    /** 文件 hash, 播放地址与歌词接口的主键 */
    val hash: String,
    val title: String,
    val artist: String,
    val albumId: String? = null,
    val albumName: String? = null,
    val coverUrl: String? = null,
    val durationMs: Long = 0L
) {
    fun isValid(): Boolean = hash.isNotBlank()
}

/**
 * `/song/url` 的解析结果
 *
 * [backupUrl] 是酷狗 CDN 的备用直链, 主链失效时可回退
 */
data class KugouPlayUrl(
    val url: String,
    val backupUrl: String? = null,
    val durationMs: Long = 0L,
    val fileSize: Long = 0L,
    val fileExtension: String? = null,
    /** 后端返回的实际音质等级, 用于降级后的界面展示 */
    val quality: String? = null
) {
    fun isValid(): Boolean = url.isNotBlank()

    /** 主链优先, 备用链兜底, 供播放器的多候选逻辑使用 */
    fun candidateUrls(): List<String> = listOfNotNull(
        url.takeIf { it.isNotBlank() },
        backupUrl?.takeIf { it.isNotBlank() && it != url }
    ).distinct()
}

/**
 * 播放音质等级
 *
 * [apiValue] 直接作为 `/song/url` 的 `quality` 参数传给后端
 */
enum class KugouAudioQuality(val apiValue: String) {
    Standard("128"),
    High("320"),
    Lossless("flac"),
    HiRes("hires");

    companion object {
        val Default: KugouAudioQuality = High

        /**
         * 兼容后端可能返回的数字码或字符串等级, 无法识别时回退默认值
         */
        fun fromApiValue(value: String?): KugouAudioQuality {
            val normalized = value?.trim()?.lowercase().orEmpty()
            if (normalized.isEmpty()) return Default
            return entries.firstOrNull { it.apiValue == normalized }
                ?: when (normalized) {
                    "standard", "128k", "128000" -> Standard
                    "high", "320k", "320000" -> High
                    "lossless", "flac", "无损" -> Lossless
                    "hires", "hi-res", "highres" -> HiRes
                    else -> Default
                }
        }

        /** 解析失败或会员失效时的降级顺序, 保证尽量能出声 */
        fun fallbacks(preferred: KugouAudioQuality): List<KugouAudioQuality> = when (preferred) {
            HiRes -> listOf(HiRes, Lossless, High, Standard)
            Lossless -> listOf(Lossless, High, Standard)
            High -> listOf(High, Standard)
            Standard -> listOf(Standard)
        }.distinct()
    }
}

/**
 * 酷狗登录会话
 *
 * 后端以 `X-Kg-Session-Id` / `t1` 两个请求头鉴权, 二者都随登录状态持久化
 */
data class KugouAuthSession(
    val token: String = "",
    val t1: String = "",
    val sessionId: String = "",
    val userId: String = "",
    val nickname: String = "",
    val avatarUrl: String = "",
    val isVip: Boolean = false,
    val isConceptVip: Boolean = false,
    val savedAt: Long = 0L
) {
    /** 后端至少需要 session 或 token 之一才能识别账号 */
    fun isLoggedIn(): Boolean =
        sessionId.isNotBlank() || token.isNotBlank() || t1.isNotBlank() || userId.isNotBlank()

    fun loginIdentity(): String = userId.ifBlank { sessionId.ifBlank { token } }

    fun normalized(savedAt: Long = this.savedAt): KugouAuthSession = copy(
        token = token.trim(),
        t1 = t1.trim(),
        sessionId = sessionId.trim(),
        userId = userId.trim(),
        nickname = nickname.trim(),
        avatarUrl = avatarUrl.trim(),
        savedAt = savedAt
    )

    companion object {
        val Empty = KugouAuthSession()
    }
}

/** 每日听歌领 VIP 的单日记录 */
data class KugouVipReceiveDay(
    /** 格式 `yyyy-MM-dd` */
    val day: String,
    val received: Boolean,
    /** `tvip` 概念版 / `svip` 超级会员 */
    val vipType: String?
)

/**
 * 本月 VIP 领取历史
 *
 * `serverTimeMs` 用服务端时间判断"今天", 避免设备时钟偏移导致重复领取
 */
data class KugouVipReceiveHistory(
    val month: String,
    val serverTimeMs: Long,
    val days: List<KugouVipReceiveDay>
) {
    fun recordFor(day: String): KugouVipReceiveDay? = days.firstOrNull { it.day == day }
}

/** 领取结果, [errorCode] 保留后端原始错误码便于排查 */
data class KugouVipClaimResult(
    val isSuccess: Boolean,
    val errorCode: Int? = null,
    val message: String? = null,
    /** 服务端要求先建立会话; 上层据此引导登录而不是报"领取失败" */
    val sessionRequired: Boolean = false
)

/** 酷狗账号 VIP 详情 */
data class KugouVipInfo(
    val isVip: Boolean,
    val isSuperVip: Boolean,
    val isConceptVip: Boolean,
    val vipType: Int = 0
)
