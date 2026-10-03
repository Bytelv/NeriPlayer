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
 * File: moe.ouom.neriplayer.platform.kugou.repository/KugouVipRepository
 */

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.kugou.KugouAuthSession
import moe.ouom.neriplayer.data.model.kugou.KugouVipClaimResult
import moe.ouom.neriplayer.data.model.kugou.KugouVipReceiveDay
import moe.ouom.neriplayer.data.model.kugou.KugouVipReceiveHistory
import moe.ouom.neriplayer.platform.kugou.api.KugouApiException
import moe.ouom.neriplayer.platform.kugou.api.KugouEndpointConfig
import moe.ouom.neriplayer.platform.kugou.api.client.KugouClient
import org.json.JSONObject

/**
 * 每日自动领取酷狗概念版 VIP
 *
 * 移植 KA Music 的 `VipBackgroundTask`: 先查本月领取记录, 今天没领就领
 * 概念版 VIP, 再把权益升级到超级会员。所有判定都以**服务端时间**为准, 避免
 * 设备时钟被改动导致重复或漏领。
 */
class KugouVipRepository(private val client: KugouClient) {

    /** 领取流程的完整结果, [KugouVipClaimOutcome.AlreadyClaimed] 表示无需再次请求 */
    sealed interface KugouVipClaimOutcome {
        data class Claimed(val upgraded: Boolean) : KugouVipClaimOutcome
        data object AlreadyClaimed : KugouVipClaimOutcome
        data class Failed(val reason: String) : KugouVipClaimOutcome
        data object SessionRequired : KugouVipClaimOutcome
    }

    /**
     * 查询本月领取记录
     *
     * 未登录时后端返回 20002, 这里向上抛 [KugouApiException.SessionRequired],
     * 避免把"需要登录"退化成无法区分的通用失败
     */
    suspend fun fetchReceiveHistory(): KugouVipReceiveHistory? = withContext(Dispatchers.IO) {
        try {
            val json = client.getJsonOrNull("/youth/month/vip/record")
                ?: return@withContext null
            parseReceiveHistory(json)
        } catch (error: CancellationException) {
            throw error
        } catch (error: KugouApiException.SessionRequired) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "酷狗 VIP 领取记录查询失败: ${error.message.orEmpty()}")
            null
        }
    }

    /**
     * 执行一次"今天是否已领, 未领则领取并升级"的完整流程
     */
    suspend fun claimDailyVip(): KugouVipClaimOutcome = withContext(Dispatchers.IO) {
        val history = try {
            fetchReceiveHistory()
        } catch (error: KugouApiException.SessionRequired) {
            NPLogger.w(TAG, "酷狗未登录, 无法领取每日 VIP")
            return@withContext KugouVipClaimOutcome.SessionRequired
        } ?: return@withContext KugouVipClaimOutcome.Failed("无法获取领取记录")

        val today = formatServerDay(history.serverTimeMs)
        val todayRecord = history.recordFor(today)

        if (todayRecord == null) {
            val claim = requestDailyVip(today)
            if (claim.sessionRequired) {
                return@withContext KugouVipClaimOutcome.SessionRequired
            }
            if (!claim.isSuccess) {
                return@withContext KugouVipClaimOutcome.Failed(
                    claim.message ?: "领取失败(errorCode=${claim.errorCode})"
                )
            }
            // 服务端发放有延迟, 与 KA Music 保持一致
            delay(CLAIM_SETTLE_DELAY_MS)
            val upgraded = requestUpgradeVip(today)
            return@withContext KugouVipClaimOutcome.Claimed(upgraded = upgraded.isSuccess)
        }

        if (isConceptVipType(todayRecord.vipType)) {
            val upgraded = requestUpgradeVip(today)
            return@withContext KugouVipClaimOutcome.Claimed(upgraded = upgraded.isSuccess)
        }

        NPLogger.d(TAG, "酷狗今日 VIP 已领取, type=${todayRecord.vipType.orEmpty()}")
        KugouVipClaimOutcome.AlreadyClaimed
    }

    /**
     * 领取当天的一次性 VIP
     *
     * `/youth/day/vip` 的 `receive_day` 是文档标注的**必选参数** (格式 `yyyy-MM-dd`),
     * 缺失时上游会以 `errorCode=131001` 一类错误拒绝。
     *
     * 未登录时后端回 204 空体 (或 error_code=20002), 都归一成
     * [KugouVipClaimResult.sessionRequired]
     */
    suspend fun requestDailyVip(receiveDay: String): KugouVipClaimResult = withContext(Dispatchers.IO) {
        claimRequest(
            path = "/youth/day/vip",
            label = "每日 VIP",
            query = mapOf("receive_day" to receiveDay)
        )
    }

    /** 把当日概念版权益升级为超级会员; 需先成功领取当天 VIP */
    suspend fun requestUpgradeVip(receiveDay: String? = null): KugouVipClaimResult =
        withContext(Dispatchers.IO) {
            claimRequest(
                path = "/youth/day/vip/upgrade",
                label = "VIP 升级",
                query = buildMap {
                    receiveDay?.takeIf { it.isNotBlank() }?.let { put("receive_day", it) }
                }
            )
        }

    private suspend fun claimRequest(
        path: String,
        label: String,
        query: Map<String, String?>
    ): KugouVipClaimResult {
        return try {
            val json = client.getJsonOrNull(path, query)
                ?: return KugouVipClaimResult(
                    isSuccess = false,
                    sessionRequired = true,
                    message = "服务端未返回内容, 可能未登录"
                )
            val result = parseClaimResult(json)
            if (!result.isSuccess && !result.sessionRequired) {
                NPLogger.w(
                    TAG,
                    "酷狗$label 被拒绝: errorCode=${result.errorCode}, msg=${result.message.orEmpty()}"
                )
            }
            result
        } catch (error: CancellationException) {
            throw error
        } catch (error: KugouApiException.SessionRequired) {
            NPLogger.w(TAG, "酷狗$label 需要登录: ${error.message.orEmpty()}")
            KugouVipClaimResult(
                isSuccess = false,
                sessionRequired = true,
                message = error.message
            )
        } catch (error: Exception) {
            NPLogger.w(TAG, "酷狗$label 失败: ${error.message.orEmpty()}")
            KugouVipClaimResult(isSuccess = false, message = error.message)
        }
    }

    internal fun parseReceiveHistory(json: JSONObject): KugouVipReceiveHistory {
        val list = json.optJSONArray("list")
        val days = buildList {
            if (list != null) {
                for (index in 0 until list.length()) {
                    val item = list.optJSONObject(index) ?: continue
                    val day = item.optString("day").trim().takeIf { it.isNotEmpty() } ?: continue
                    add(
                        KugouVipReceiveDay(
                            day = day,
                            received = item.optInt("receive_vip") == 1,
                            vipType = item.optString("vip_type").trim().takeIf { it.isNotEmpty() }
                        )
                    )
                }
            }
        }
        return KugouVipReceiveHistory(
            month = json.optString("month").trim(),
            serverTimeMs = normalizeServerTimeMs(json.optLong("server_time")),
            days = days
        )
    }

    /**
     * 归一化服务端时间戳
     *
     * 该后端返回的是**秒级**时间戳 (例如 `1791005496`), 早先按毫秒解析会得到
     * 1970 年, 并把这个日期当作 `receive_day` 发出去, 直接被上游以 131001 拒绝。
     */
    internal fun normalizeServerTimeMs(raw: Long): Long {
        if (raw <= 0L) return System.currentTimeMillis()
        // 秒级时间戳换算成毫秒后约 1e12 量级; 小于该量级一律当作秒
        val normalized = if (raw < MILLIS_THRESHOLD) raw * 1_000L else raw
        // 仍早于 2000 年说明数据异常, 用本机时间兜底
        return normalized.takeIf { it >= MIN_PLAUSIBLE_EPOCH_MS } ?: System.currentTimeMillis()
    }

    internal fun parseClaimResult(json: JSONObject): KugouVipClaimResult {
        val status = json.optInt("status")
        val errorCode = json.optInt("error_code").takeIf { it != 0 }
            ?: json.optInt("errcode").takeIf { it != 0 }
        val message = listOf("error_msg", "error", "message", "errmsg")
            .firstNotNullOfOrNull { key ->
                json.optString(key).trim().takeIf { it.isNotEmpty() }
            }
        return KugouVipClaimResult(
            isSuccess = status == 1,
            errorCode = errorCode,
            message = message,
            sessionRequired = errorCode in KugouClient.SESSION_REQUIRED_ERROR_CODES
        )
    }

    /**
     * 服务端时间戳转 `yyyy-MM-dd`
     *
     * 固定用东八区, 因为后端按酷狗的国内时区切分"今天"。
     * 时间戳明显不合理时回退到本机时间, 绝不能让 1970 年这种日期发到上游。
     */
    internal fun formatServerDay(serverTimeMs: Long): String {
        val safeTimeMs = normalizeServerTimeMs(serverTimeMs)
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone(SERVER_TIME_ZONE)
        }
        return formatter.format(Date(safeTimeMs))
    }

    private fun isConceptVipType(vipType: String?): Boolean =
        vipType?.trim()?.lowercase() == CONCEPT_VIP_TYPE

    companion object {
        private const val TAG = "KugouVipRepository"
        private const val CLAIM_SETTLE_DELAY_MS = 1_000L
        private const val SERVER_TIME_ZONE = "Asia/Shanghai"

        /**
         * 低于该值的时间戳视为秒级
         *
         * 1e11 毫秒约等于 1973 年, 而任何真实的秒级时间戳 (约 1e9) 都远小于它
         */
        private const val MILLIS_THRESHOLD = 100_000_000_000L

        /** 2000-01-01T00:00:00Z, 早于它的时间戳一律视为异常 */
        private const val MIN_PLAUSIBLE_EPOCH_MS = 946_684_800_000L

        /** KA Music 用 `tvip` 表示概念版 VIP */
        const val CONCEPT_VIP_TYPE = "tvip"

        // ---- 测试专用转发: 解析函数本身不依赖实例状态, 但挂在类上 ----

        internal fun parseClaimResultForTest(json: JSONObject): KugouVipClaimResult =
            KugouVipRepository(UNUSED_CLIENT).parseClaimResult(json)

        internal fun parseReceiveHistoryForTest(json: JSONObject): KugouVipReceiveHistory =
            KugouVipRepository(UNUSED_CLIENT).parseReceiveHistory(json)

        internal fun formatServerDayForTest(serverTimeMs: Long): String =
            KugouVipRepository(UNUSED_CLIENT).formatServerDay(serverTimeMs)

        internal fun normalizeServerTimeMsForTest(raw: Long): Long =
            KugouVipRepository(UNUSED_CLIENT).normalizeServerTimeMs(raw)

        /**
         * 解析函数不会触碰网络, 因此这里只需要一个占位实例
         *
         * 用懒加载避免在类初始化阶段对 OkHttp 产生任何副作用
         */
        private val UNUSED_CLIENT: KugouClient by lazy {
            KugouClient(
                okHttpClient = okhttp3.OkHttpClient(),
                baseUrlProvider = { KugouEndpointConfig.DEFAULT_BASE_URL },
                sessionProvider = { KugouAuthSession.Empty }
            )
        }
    }
}
