package moe.ouom.neriplayer.platform.kugou.api.client

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
 * File: moe.ouom.neriplayer.platform.kugou.api.client/KugouAuthClient
 */

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.kugou.KugouAuthSession

/**
 * 酷狗登录
 *
 * 提供密码与短信验证码两条链路, 以及扫码登录所需的 key/轮询两步。
 * 会话由后端通过 `X-Kg-Session-Id` 响应头下发, [KugouClient] 会把它取出来
 * 交给会话仓库持久化。
 */
class KugouAuthClient(private val client: KugouClient) {

    suspend fun sendCaptcha(mobile: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.getJson("/captcha/sent", mapOf("mobile" to mobile))
            true
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            NPLogger.w(TAG, "酷狗验证码发送失败: ${error.message.orEmpty()}")
            false
        }
    }

    /** 手机号 + 密码 */
    suspend fun loginByPassword(
        mobile: String,
        password: String,
        countryCode: String = "86"
    ): KugouAuthSession? = withContext(Dispatchers.IO) {
        loginRequest(
            path = "/login/cellphone",
            query = mapOf(
                "mobile" to mobile,
                "password" to password,
                "countrycode" to countryCode
            )
        )
    }

    /** 手机号 + 短信验证码 */
    suspend fun loginByCaptcha(
        mobile: String,
        captcha: String,
        countryCode: String = "86"
    ): KugouAuthSession? = withContext(Dispatchers.IO) {
        loginRequest(
            path = "/login/cellphone",
            query = mapOf(
                "mobile" to mobile,
                "code" to captcha,
                "countrycode" to countryCode
            )
        )
    }

    /** 用已保存的 token 刷新会话, 顺带拿回 t1 */
    suspend fun refreshToken(): KugouAuthSession? = withContext(Dispatchers.IO) {
        loginRequest(path = "/login/token", query = emptyMap())
    }

    suspend fun logout() {
        withContext(Dispatchers.IO) {
            runCatching { client.getJson("/login/logout") }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    NPLogger.w(TAG, "酷狗登出请求失败: ${error.message.orEmpty()}")
                }
        }
    }

    /** 申请扫码登录的二维码 */
    suspend fun requestQrCode(): KugouQrCode? = withContext(Dispatchers.IO) {
        runCatching {
            val json = client.getJson("/login/qr/key")
            KugouQrCode(
                key = json.optString("qrcode").trim(),
                imageUrl = json.optString("qrcode_img").trim()
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            NPLogger.w(TAG, "酷狗二维码获取失败: ${error.message.orEmpty()}")
            null
        }
    }

    /** 轮询扫码状态; [KugouQrStatus.Confirmed] 时携带可用会话 */
    suspend fun checkQrCode(key: String): KugouQrStatus = withContext(Dispatchers.IO) {
        runCatching {
            val json = client.getJson("/login/qr/check", mapOf("key" to key))
            val statusCode = json.optInt("status")
            val token = json.optString("token").trim().takeIf { it.isNotEmpty() }
            if (statusCode == QR_STATUS_CONFIRMED) {
                KugouQrStatus.Confirmed(
                    session = KugouAuthSession(
                        token = token.orEmpty(),
                        userId = json.optString("userid").trim(),
                        nickname = json.optString("nickname").trim(),
                        avatarUrl = json.optString("pic").trim(),
                        savedAt = System.currentTimeMillis()
                    )
                )
            } else {
                KugouQrStatus.Pending(statusCode)
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            NPLogger.w(TAG, "酷狗扫码状态查询失败: ${error.message.orEmpty()}")
            KugouQrStatus.Failed(error.message.orEmpty())
        }
    }

    suspend fun fetchUserDetail(): KugouUserDetail? = withContext(Dispatchers.IO) {
        runCatching {
            val json = client.getJson("/user/detail")
            KugouUserDetail(
                nickname = json.optString("nickname").trim(),
                avatarUrl = json.optString("pic").trim()
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            NPLogger.w(TAG, "酷狗用户信息获取失败: ${error.message.orEmpty()}")
            null
        }
    }

    private suspend fun loginRequest(
        path: String,
        query: Map<String, String?>
    ): KugouAuthSession? {
        return runCatching {
            val json = client.getJson(path, query)
            val sessionId = client.drainRotatedSessionIds().lastOrNull().orEmpty()
            KugouAuthSession(
                token = json.optString("token").trim(),
                t1 = json.optString("t1").trim(),
                sessionId = sessionId,
                userId = json.optString("userid").trim(),
                nickname = json.optString("nickname").trim(),
                avatarUrl = json.optString("pic").trim(),
                isVip = json.optInt("is_vip") == 1,
                savedAt = System.currentTimeMillis()
            ).takeIf { it.isLoggedIn() }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            NPLogger.w(TAG, "酷狗登录失败: path=$path, ${error.message.orEmpty()}")
            null
        }
    }

    companion object {
        private const val TAG = "KugouAuthClient"

        /** 后端约定的扫码"已确认"状态码 */
        const val QR_STATUS_CONFIRMED = 4
    }
}

data class KugouQrCode(
    val key: String,
    val imageUrl: String
) {
    fun isValid(): Boolean = key.isNotBlank()
}

sealed interface KugouQrStatus {
    data class Pending(val statusCode: Int) : KugouQrStatus
    data class Confirmed(val session: KugouAuthSession) : KugouQrStatus
    data class Failed(val message: String) : KugouQrStatus
}

data class KugouUserDetail(
    val nickname: String,
    val avatarUrl: String
)
