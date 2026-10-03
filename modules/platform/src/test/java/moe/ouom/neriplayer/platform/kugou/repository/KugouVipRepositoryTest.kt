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
 * File: moe.ouom.neriplayer.platform.kugou.repository/KugouVipRepositoryTest
 */

import java.util.TimeZone
import moe.ouom.neriplayer.platform.kugou.repository.KugouVipRepository.Companion.CONCEPT_VIP_TYPE
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouVipRepositoryTest {

    /**
     * 未登录时后端回 400 + error_code=20002, 必须识别成"需要登录"
     * 否则界面只会显示笼统的"领取失败"
     */
    @Test
    fun `session required error code is surfaced on claim result`() {
        val json = JSONObject(
            """{"status":0,"error_code":20002,"error_msg":"","data":""}"""
        )
        val result = KugouVipRepository.parseClaimResultForTest(json)

        assertFalse(result.isSuccess)
        assertTrue(result.sessionRequired)
        assertEquals(20002, result.errorCode)
    }

    @Test
    fun `official verification error code is also treated as session required`() {
        val json = JSONObject("""{"status":0,"errcode":20028,"error":"本次请求需要验证"}""")
        val result = KugouVipRepository.parseClaimResultForTest(json)

        assertFalse(result.isSuccess)
        assertTrue(result.sessionRequired)
        assertEquals("本次请求需要验证", result.message)
    }

    /** 成功领取: status=1 */
    @Test
    fun `successful claim is recognized`() {
        val json = JSONObject("""{"status":1,"error_code":0}""")
        val result = KugouVipRepository.parseClaimResultForTest(json)

        assertTrue(result.isSuccess)
        assertFalse(result.sessionRequired)
        assertNull(result.errorCode)
    }

    /** 普通业务失败不应被误判成未登录 */
    @Test
    fun `generic business failure is not session required`() {
        val json = JSONObject("""{"status":0,"error_code":20001,"error_msg":"already received"}""")
        val result = KugouVipRepository.parseClaimResultForTest(json)

        assertFalse(result.isSuccess)
        assertFalse(result.sessionRequired)
        assertEquals("already received", result.message)
    }

    @Test
    fun `receive history reads days and server time`() {
        val json = JSONObject(
            """
            {
              "month":"2026-10",
              "server_time":1767225600000,
              "list":[
                {"day":"2026-10-01","receive_vip":1,"vip_type":"tvip"},
                {"day":"2026-10-02","receive_vip":0,"vip_type":""}
              ]
            }
            """.trimIndent()
        )
        val history = KugouVipRepository.parseReceiveHistoryForTest(json)

        assertEquals("2026-10", history.month)
        assertEquals(2, history.days.size)
        assertTrue(history.days[0].received)
        assertEquals(CONCEPT_VIP_TYPE, history.days[0].vipType)
        assertNull(history.days[1].vipType)
        assertNull(history.recordFor("2026-10-03"))
        assertEquals("2026-10-01", history.recordFor("2026-10-01")?.day)
    }

    /** 缺少 server_time 时退化为本机时间, 不能抛异常 */
    @Test
    fun `receive history tolerates missing server time`() {
        val json = JSONObject("""{"month":"2026-10","list":[]}""")
        val history = KugouVipRepository.parseReceiveHistoryForTest(json)

        assertTrue(history.serverTimeMs > 0L)
        assertTrue(history.days.isEmpty())
    }

    /** "今天"按服务端时间与东八区切分 */
    @Test
    fun `server day is formatted in asia shanghai`() {
        // 2026-01-01T00:00:00+08:00 -> 该时刻在 UTC 仍是 2025-12-31
        val formatted = KugouVipRepository.formatServerDayForTest(1767196800000L)
        assertEquals("2026-01-01", formatted)
    }

    /**
     * `/youth/day/vip` 的 `receive_day` 是文档标注的必选参数
     *
     * 这里锁住格式, 避免以后改成带时间或换时区导致上游以 131001 拒绝
     */
    @Test
    fun `receive day uses iso date format`() {
        val formatted = KugouVipRepository.formatServerDayForTest(1767196800000L)
        assertTrue(
            "receive_day 必须是 yyyy-MM-dd",
            Regex("""^\d{4}-\d{2}-\d{2}$""").matches(formatted)
        )
    }

    /**
     * 该后端的 `server_time` 是**秒级**时间戳
     *
     * 早先按毫秒解析会得到 1970-01-22 并把它当作 receive_day 发给上游, 直接被
     * 以 131001 拒绝 —— 这是领取失败的真正原因, 不是账号资质问题。
     */
    @Test
    fun `second based server time is converted to millis`() {
        val seconds = 1_791_005_496L
        val normalized = KugouVipRepository.normalizeServerTimeMsForTest(seconds)

        assertEquals(seconds * 1_000L, normalized)
        // 1791005496 秒 = 2026-10-03 13:31:36 +08:00; 若误当毫秒会得到 1970-01-21
        assertEquals("2026-10-03", KugouVipRepository.formatServerDayForTest(seconds))
    }

    @Test
    fun `millisecond server time is left untouched`() {
        val millis = 1_791_005_496_000L
        assertEquals(millis, KugouVipRepository.normalizeServerTimeMsForTest(millis))
    }

    /** 异常时间戳必须回退到本机时间, 不能产出 1970 年 */
    @Test
    fun `implausible timestamps fall back to now`() {
        val now = System.currentTimeMillis()
        listOf(0L, -1L, 1L, 86_400L).forEach { raw ->
            val normalized = KugouVipRepository.normalizeServerTimeMsForTest(raw)
            assertTrue("raw=$raw should fall back", normalized >= now - 60_000L)
        }
        // 即便直接喂进格式化, 也不会产出 1970 年
        val day = KugouVipRepository.formatServerDayForTest(1L)
        assertTrue("不能是 1970 年: $day", !day.startsWith("1970"))
    }

    /** 领取记录里的 server_time 也要走同一套归一化 */
    @Test
    fun `history normalizes second based server time`() {
        val json = JSONObject(
            """{"month":"2026-10","server_time":1791005496,"list":[]}"""
        )
        val history = KugouVipRepository.parseReceiveHistoryForTest(json)

        assertEquals(1_791_005_496_000L, history.serverTimeMs)
    }
}
