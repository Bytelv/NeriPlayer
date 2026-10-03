package moe.ouom.neriplayer.platform.kugou.api

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
 * File: moe.ouom.neriplayer.platform.kugou.api/KugouEndpointConfig
 */

/**
 * 酷狗后端地址
 *
 * 默认值对应 KA Music 使用的 KuGouMusicApi 风格服务端。该服务端本身承担酷狗
 * 官方接口的参数签名与设备标识生成, 客户端只负责携带会话请求头, 因此地址必须
 * 允许用户改指自己私有部署的实例。
 */
object KugouEndpointConfig {

    const val DEFAULT_BASE_URL = "https://music.api.hoilai.cn"

    /** 后端以 `X-Kg-Session-Id` 承载登录态 */
    const val SESSION_ID_HEADER = "X-Kg-Session-Id"

    /** 后端以 `t1` 承载部分接口所需的二次凭据 */
    const val T1_HEADER = "t1"

    private const val HTTP_PREFIX = "http://"
    private const val HTTPS_PREFIX = "https://"

    /**
     * 归一化用户填写的服务端地址
     *
     * 允许省略协议头, 并统一去掉结尾斜杠, 保证拼接路径时不会出现双斜杠
     */
    fun normalizeBaseUrl(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return DEFAULT_BASE_URL
        val withScheme = when {
            trimmed.startsWith(HTTP_PREFIX, ignoreCase = true) -> trimmed
            trimmed.startsWith(HTTPS_PREFIX, ignoreCase = true) -> trimmed
            else -> "$HTTPS_PREFIX$trimmed"
        }
        return withScheme.trimEnd('/')
    }

    /** 后端地址必须能解析出主机名, 否则视为无效配置 */
    fun isValidBaseUrl(raw: String?): Boolean {
        val normalized = normalizeBaseUrl(raw)
        val host = normalized
            .removePrefix(HTTPS_PREFIX)
            .removePrefix(HTTP_PREFIX)
            .substringBefore('/')
            .substringBefore('?')
        return host.isNotBlank() && !host.contains(' ')
    }
}
