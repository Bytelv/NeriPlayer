package moe.ouom.neriplayer.data.kugou

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
 * File: moe.ouom.neriplayer.data.kugou/KugouVipClaimScheduler
 */

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.kugou.KugouAuthSession
import moe.ouom.neriplayer.platform.kugou.repository.KugouVipRepository

/**
 * 酷狗每日 VIP 领取调度
 *
 * 与 KA Music 的 `VipBackgroundTask` 对齐: 不做本地日期去重, 而是每次都先问服务端
 * 本月领取记录, 由服务端时间判断"今天". 这样改设备时钟不会重复领取, 也不会因为
 * 换设备或重装而漏领。`claimDailyVip()` 本身是幂等的, 重复调用只会命中
 * `AlreadyClaimed`。
 */
object KugouVipClaimScheduler {

    private const val TAG = "KugouVipClaim"

    private val runMutex = Mutex()

    /** 同一进程内只允许一次并发领取, 避免启动路径被重复触发 */
    private val inFlight = AtomicBoolean(false)

    /**
     * 尝试领取今天的 VIP
     *
     * @param session 当前会话, 未登录时直接跳过
     * @param repository 领取仓库
     * @return 是否真正发起了领取请求
     */
    suspend fun claimIfPossible(
        session: KugouAuthSession,
        repository: KugouVipRepository
    ): Boolean {
        if (!session.isLoggedIn()) {
            NPLogger.d(TAG, "跳过领取: 未登录酷狗")
            return false
        }
        if (!inFlight.compareAndSet(false, true)) {
            NPLogger.d(TAG, "跳过领取: 已有一次领取进行中")
            return false
        }
        return try {
            runMutex.withLock {
                when (val outcome = repository.claimDailyVip()) {
                    is KugouVipRepository.KugouVipClaimOutcome.Claimed -> {
                        NPLogger.i(
                            TAG,
                            "酷狗今日 VIP 领取成功, upgraded=${outcome.upgraded}"
                        )
                    }

                    KugouVipRepository.KugouVipClaimOutcome.AlreadyClaimed -> {
                        NPLogger.d(TAG, "酷狗今日 VIP 已领取, 无需重复")
                    }

                    is KugouVipRepository.KugouVipClaimOutcome.Failed -> {
                        NPLogger.w(TAG, "酷狗今日 VIP 领取失败: ${outcome.reason}")
                    }

                    KugouVipRepository.KugouVipClaimOutcome.SessionRequired -> {
                        NPLogger.w(TAG, "酷狗会话失效, 跳过领取")
                    }
                }
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.w(TAG, "酷狗每日 VIP 领取异常: ${error.message.orEmpty()}")
            false
        } finally {
            inFlight.set(false)
        }
    }
}
