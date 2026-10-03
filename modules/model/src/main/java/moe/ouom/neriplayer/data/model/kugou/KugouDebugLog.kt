package moe.ouom.neriplayer.data.model.kugou

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
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
 * File: moe.ouom.neriplayer.data.model.kugou/KugouDebugLog
 */

/** 一条酷狗请求/响应记录, 已做敏感字段脱敏 */
data class KugouDebugLogEntry(
    val timestampMs: Long,
    val label: String,
    val detail: String
)

/**
 * 酷狗诊断日志的进程内环形缓冲
 *
 * 设备通常不方便取 logcat, 因此把关键交换记录在内存里, 由设置页的酷狗日志
 * 页面直接展示与复制。**写入前必须脱敏**: 会话、token、t1 都不能落进来。
 */
object KugouDebugLog {

    private const val MAX_ENTRIES = 300

    private val entries = ArrayDeque<KugouDebugLogEntry>()
    private val lock = Any()

    fun record(label: String, detail: String, timestampMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            entries.addLast(KugouDebugLogEntry(timestampMs, label, detail))
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
            }
        }
    }

    fun snapshot(): List<KugouDebugLogEntry> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) { entries.clear() }

    /** 供"复制全部"使用 */
    fun toPlainText(): String = snapshot().joinToString(separator = "\n") { entry ->
        "[${entry.timestampMs}] ${entry.label}: ${entry.detail}"
    }

    /** 需要脱敏的字段名, 命中后值替换为 `<hidden>` */
    private val SENSITIVE_KEY_PATTERN = Regex(
        pattern = """"(session_?id|sessionId|token|t1|vip_?token|dfid|mid|userid|user_id|kugouid)""\\s*:\\s*"[^"]*"""",
        option = RegexOption.IGNORE_CASE
    )

    /**
     * 掩掉 JSON 里的凭据字段
     *
     * 只保留字段名, 便于排查"字段是否存在"而不泄露值
     */
    fun maskSensitive(raw: String): String {
        return SENSITIVE_KEY_PATTERN.replace(raw) { match ->
            val key = match.groupValues[1]
            """"$key":"<hidden>""""
        }
    }
}
