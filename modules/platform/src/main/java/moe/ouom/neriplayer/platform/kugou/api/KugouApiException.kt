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
 * File: moe.ouom.neriplayer.platform.kugou.api/KugouApiException
 */

/** 酷狗后端返回的可识别错误 */
sealed class KugouApiException(message: String) : Exception(message) {

    /**
     * 后端要求先建立会话才能继续, 对应 `errcode=20028` 一类的"本次请求需要验证"
     *
     * 播放直链接口在未登录时就会走到这里, 调用方应提示用户去设置页登录
     */
    class SessionRequired(message: String) : KugouApiException(message)

    /** 请求本身不合法, 例如 hash 缺失或参数组合错误 */
    class BadRequest(message: String) : KugouApiException(message)

    /** 服务端 5xx 或无法解析的响应 */
    class ServerError(message: String, cause: Throwable? = null) : KugouApiException(message) {
        init {
            if (cause != null) initCause(cause)
        }
    }

    /** 业务失败: HTTP 成功但 `status != 1` */
    class BusinessError(
        message: String,
        val errorCode: Int?
    ) : KugouApiException(message)
}
