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
 * File: moe.ouom.neriplayer.platform.kugou.repository/KugouPlaybackRepositoryTest
 */

import moe.ouom.neriplayer.data.model.kugou.KugouAudioQuality
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouPlaybackRepositoryTest {

    private val primary = "http://fs.youthandroid2.kugou.com/a/full/ap3116_x.mp3"
    private val backup = "http://fs.youthandroid.kugou.com/a/full/ap3116_x.mp3"

    /**
     * 该后端把 `url` 下成字符串数组
     *
     * 早先用 optString 会拿到 `["http://..."]` 这种字符串化结果并直接交给播放器,
     * 表现为"能取到响应但无法播放", 这里锁住数组解析。
     */
    @Test
    fun `play url array yields primary and backup`() {
        val json = JSONObject(
            """{"url":["$primary","$backup"],"hash":"ABC","status":1}"""
        )
        val result = KugouPlaybackRepository.parsePlayUrlForTest(json, KugouAudioQuality.High)

        assertEquals(primary, result?.url)
        assertEquals(backup, result?.backupUrl)
        // 绝不能把数组的字符串形式当成 URL
        assertTrue(result!!.url.startsWith("http"))
        assertTrue(!result.url.contains("["))
    }

    @Test
    fun `single string url is still accepted`() {
        val json = JSONObject("""{"url":"$primary","status":1}""")
        val result = KugouPlaybackRepository.parsePlayUrlForTest(json, KugouAudioQuality.High)

        assertEquals(primary, result?.url)
        assertNull(result?.backupUrl)
    }

    /** url 为 null 或空数组时视为不可用, 不能抛异常 */
    @Test
    fun `empty or null url is unavailable`() {
        assertNull(
            KugouPlaybackRepository.parsePlayUrlForTest(
                JSONObject("""{"url":null,"status":0}"""),
                KugouAudioQuality.High
            )
        )
        assertNull(
            KugouPlaybackRepository.parsePlayUrlForTest(
                JSONObject("""{"url":[],"status":0}"""),
                KugouAudioQuality.High
            )
        )
    }

    /** 数组里混入非 http 项时应被过滤 */
    @Test
    fun `non http entries are filtered out`() {
        val json = JSONObject("""{"url":["","not-a-url","$primary"],"status":1}""")
        val result = KugouPlaybackRepository.parsePlayUrlForTest(json, KugouAudioQuality.High)

        assertEquals(primary, result?.url)
    }

    /** 备用链只作为候选, 与主链一起去重 */
    @Test
    fun `candidate urls deduplicate`() {
        val json = JSONObject("""{"url":["$primary","$primary","$backup"],"status":1}""")
        val result = KugouPlaybackRepository.parsePlayUrlForTest(json, KugouAudioQuality.High)

        assertEquals(2, result?.candidateUrls()?.size)
    }

    @Test
    fun `quality is carried through`() {
        val json = JSONObject("""{"url":["$primary"],"status":1}""")
        val result = KugouPlaybackRepository.parsePlayUrlForTest(json, KugouAudioQuality.Lossless)

        assertEquals("flac", result?.quality)
    }
}
