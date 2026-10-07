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
 * File: moe.ouom.neriplayer.platform.kugou.api.client/KugouClient
 */

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.kugou.KugouAuthSession
import moe.ouom.neriplayer.data.model.kugou.KugouDebugLog
import moe.ouom.neriplayer.network.http.awaitResponse
import moe.ouom.neriplayer.platform.kugou.api.KugouApiException
import moe.ouom.neriplayer.platform.kugou.api.KugouEndpointConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 当前会话的读取入口
 *
 * 客户端在每次请求时取一次最新会话, 避免登录后旧实例仍带着空凭据
 */
fun interface KugouSessionProvider {
    fun currentSession(): KugouAuthSession
}

/**
 * 酷狗后端 HTTP 客户端
 *
 * 只负责三件事: 拼地址、带会话请求头、把 HTTP 层错误翻译成
 * [KugouApiException]。业务字段解析全部交给 codec。
 */
class KugouClient(
    private val okHttpClient: OkHttpClient,
    private val baseUrlProvider: () -> String,
    private val sessionProvider: KugouSessionProvider,
    private val debugLogging: Boolean = false
) {

    /**
     * 会话可在此次请求中被后端刷新, 由 repository 负责持久化
     */
    private val _sessionUpdates = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /**
     * 读取当前会话
     *
     * 写接口(加歌到歌单)需要把 `userid`/`token` 一并放进请求体与查询串, 与官方
     * 服务端实现一致, 因此仓库层要能取到会话。
     */
    fun currentSession(): KugouAuthSession = sessionProvider.currentSession()

    fun drainRotatedSessionIds(): List<String> {
        val updates = mutableListOf<String>()
        while (true) {
            val next = _sessionUpdates.poll() ?: break
            updates += next
        }
        return updates
    }

    suspend fun getJson(
        path: String,
        query: Map<String, String?> = emptyMap()
    ): JSONObject = withContext(Dispatchers.IO) {
        getJsonOrNull(path, query)
            ?: throw KugouApiException.ServerError("酷狗返回空响应: path=$path")
    }

    /**
     * 部分接口直接返回 JSON 数组 (`/search`), 因此单独提供一个原始入口
     */
    suspend fun getRaw(
        path: String,
        query: Map<String, String?> = emptyMap()
    ): String = executeRaw(path = path, query = query, method = "GET")

    /**
     * 写接口: `POST` + JSON body
     *
     * 实测该后端的写接口与文档不一致, 必须满足三点, 否则拿不到业务响应:
     * - 用 **POST**(GET 得到 405 Method Not Allowed)
     * - 带 `Content-Type: application/json`(缺失直接 415 Unsupported Media Type)
     * - body **非空**(空 body 会回 "A non-empty request body is required.")
     *
     * 仅仅把参数放进查询串是不够的: 后端只认 body 里的字段
     * (查询串版本会回 "ListId 不能为空")。
     */
    suspend fun postJsonBody(
        path: String,
        body: JSONObject,
        query: Map<String, String?> = emptyMap()
    ): JSONObject? {
        val payload = body.toString()
        val raw = executeRaw(
            path = path,
            query = query,
            method = "POST",
            body = payload.toRequestBody(JSON_MEDIA_TYPE)
        ).trim()
        if (raw.isEmpty()) return null
        return try {
            JSONObject(raw)
        } catch (error: Exception) {
            throw KugouApiException.ServerError("酷狗响应不是合法 JSON: path=$path", error)
        }
    }

    private suspend fun executeRaw(
        path: String,
        query: Map<String, String?>,
        method: String,
        body: RequestBody? = null
    ): String = withContext(Dispatchers.IO) {
        val request = buildRequest(path = path, query = query, method = method, body = body)
        val (code, responseBody) = try {
            okHttpClient.newCall(request).awaitResponse { response ->
                captureRotatedSession(response.header(KugouEndpointConfig.SESSION_ID_HEADER))
                response.code to response.body.string()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            throw KugouApiException.ServerError(
                "酷狗请求失败: path=$path, ${error.message.orEmpty()}",
                error
            )
        }

        if (debugLogging) {
            NPLogger.d(TAG, "kugou response: $method $path, code=$code, length=${responseBody.length}")
        }

        // 诊断缓冲: 设备上不便取 logcat, 这里保留一次脱敏后的交换记录
        KugouDebugLog.record(
            label = "HTTP $code $method $path",
            detail = buildString {
                append("query=").append(maskQuery(query))
                if (responseBody.isNotBlank()) {
                    append(" | body=")
                        .append(KugouDebugLog.maskSensitive(responseBody).take(BODY_LOG_LIMIT))
                }
            }
        )

        if (code !in 200..299) {
            throw translateHttpFailure(path = path, code = code, body = responseBody)
        }
        // 204 / 空体是该后端的合法应答 (例如未登录时的领取接口), 交给 codec 判定
        responseBody
    }

    /** 查询串里 hash 等非敏感值保留, 便于核对请求参数 */
    private fun maskQuery(query: Map<String, String?>): String =
        query.entries
            .filter { !it.value.isNullOrEmpty() }
            .joinToString(",") { (key, value) -> "$key=$value" }

    /**
     * 读取 JSON 体, 允许空体
     *
     * 播放/领取类接口在未登录时会回 204 无内容, 这里不能当成协议错误
     */
    suspend fun getJsonOrNull(
        path: String,
        query: Map<String, String?> = emptyMap()
    ): JSONObject? {
        val raw = getRaw(path, query).trim()
        if (raw.isEmpty()) return null
        return try {
            JSONObject(raw)
        } catch (error: Exception) {
            throw KugouApiException.ServerError("酷狗响应不是合法 JSON: path=$path", error)
        }
    }

    private fun buildRequest(
        path: String,
        query: Map<String, String?>,
        method: String = "GET",
        body: RequestBody? = null
    ): Request {
        val base = KugouEndpointConfig.normalizeBaseUrl(baseUrlProvider())
        val url = buildUrl(base, path, query)
            ?: throw KugouApiException.BadRequest("酷狗服务端地址无效: $base")

        val session = sessionProvider.currentSession()
        // 只记录字段是否存在, 不记录值: 用于判断凭据是否齐全导致的鉴权失败
        KugouDebugLog.record(
            label = "REQ $method $path",
            detail = "hasSessionId=${session.sessionId.isNotBlank()}, " +
                "hasToken=${session.token.isNotBlank()}, " +
                "hasT1=${session.t1.isNotBlank()}, " +
                "hasUserId=${session.userId.isNotBlank()}"
        )
        return Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .apply {
                if (session.sessionId.isNotBlank()) {
                    header(KugouEndpointConfig.SESSION_ID_HEADER, session.sessionId)
                }
                if (session.t1.isNotBlank()) {
                    header(KugouEndpointConfig.T1_HEADER, session.t1)
                }
            }
            .method(method, body)
            .build()
    }

    private fun buildUrl(base: String, path: String, query: Map<String, String?>): HttpUrl? {
        val cleanPath = path.removePrefix("/")
        val full = "$base/$cleanPath"
        val parsed = full.toHttpUrlOrNull() ?: return null
        val builder = parsed.newBuilder()
        query.forEach { (key, value) ->
            // 与 KA Music 的 AppConfig.apiUri 一致: 空值不下发
            if (!value.isNullOrEmpty()) {
                builder.addQueryParameter(key, value)
            }
        }
        return builder.build()
    }

    /**
     * 读取响应头里回写的会话
     *
     * 后端在部分接口上用 `X-Kg-Session-Id` 响应头刷新会话, 客户端把它交回给会话仓库
     */
    private fun captureRotatedSession(sessionId: String?) {
        val rotated = sessionId?.trim()?.takeIf { it.isNotBlank() } ?: return
        if (rotated == sessionProvider.currentSession().sessionId) return
        _sessionUpdates += rotated
    }

    private fun translateHttpFailure(path: String, code: Int, body: String): KugouApiException {
        val parsed = runCatching { JSONObject(body) }.getOrNull()
        val errorCode = parsed?.optInt("errcode")
            ?.takeIf { it != 0 }
            ?: parsed?.optInt("error_code")?.takeIf { it != 0 }
        // 该后端的错误说明在 error_msg; error / message 是其它风格的兜底
        val message = listOf("error_msg", "error", "message", "errmsg")
            .firstNotNullOfOrNull { key ->
                parsed?.optString(key)?.trim()?.takeIf { it.isNotEmpty() }
            }
            ?: errorCode?.let { "errorCode=$it" }
            ?: "HTTP $code"

        NPLogger.w(TAG, "kugou request failed: path=$path, code=$code, errcode=$errorCode, msg=$message")

        return when {
            errorCode in SESSION_REQUIRED_ERROR_CODES ||
                message.contains(SESSION_REQUIRED_KEYWORD) ->
                KugouApiException.SessionRequired(message)

            code in 500..599 -> KugouApiException.ServerError("酷狗服务端错误: $message")
            else -> KugouApiException.BadRequest(message)
        }
    }

    companion object {
        private const val TAG = "KugouClient"
        private const val BODY_LOG_LIMIT = 600
        private const val USER_AGENT = "NeriPlayer/1.0 (https://github.com/cwuom/NeriPlayer)"

        /** 写接口缺它会被直接拒绝(415 Unsupported Media Type) */
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /**
         * 表示"缺少有效会话"的错误码
         *
         * 20028 是酷狗官方的"本次请求需要验证", 20002 是后端在未登录时对
         * 用户相关接口返回的码
         */
        val SESSION_REQUIRED_ERROR_CODES = setOf(20002, 20028)

        /** 旧调用点沿用单个常量 */
        const val SESSION_REQUIRED_ERROR_CODE = 20028
        private const val SESSION_REQUIRED_KEYWORD = "需要验证"
    }
}
