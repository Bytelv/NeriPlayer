package moe.ouom.neriplayer.platform.kugou.auth

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
 * File: moe.ouom.neriplayer.platform.kugou.auth/KugouSessionRepository
 */

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.kugou.KugouAuthSession
import org.json.JSONObject

private const val KUGOU_AUTH_PREFS = "kugou_auth_secure_prefs"
private const val KEY_KUGOU_SESSION = "kugou_session"
private const val KEY_KUGOU_BASE_URL = "kugou_base_url"

/**
 * 酷狗会话仓库
 *
 * 与 `NeteaseCookieRepository` / `BiliCookieRepository` 保持同一形态: 加密落盘,
 * 对外暴露 StateFlow。后端地址与会话放在一起, 因为换服务器必须重新登录。
 */
class KugouSessionRepository(private val context: Context) {

    private val mutationLock = Any()
    private var encryptedPrefs: SharedPreferences
    private val _sessionFlow: MutableStateFlow<KugouAuthSession>
    private val _baseUrlFlow: MutableStateFlow<String>

    val sessionFlow: StateFlow<KugouAuthSession>
        get() = _sessionFlow.asStateFlow()

    val baseUrlFlow: StateFlow<String>
        get() = _baseUrlFlow.asStateFlow()

    init {
        encryptedPrefs = openEncryptedPrefsWithRecovery()
        _sessionFlow = MutableStateFlow(loadSession())
        _baseUrlFlow = MutableStateFlow(
            encryptedPrefs.getString(KEY_KUGOU_BASE_URL, null).orEmpty()
        )
    }

    fun currentSession(): KugouAuthSession = _sessionFlow.value

    fun currentBaseUrl(): String = _baseUrlFlow.value

    fun saveSession(session: KugouAuthSession): Boolean {
        val normalized = session.normalized()
        return synchronized(mutationLock) {
            runCatching {
                encryptedPrefs.edit {
                    putString(KEY_KUGOU_SESSION, normalized.toJson())
                }
            }.onFailure { error ->
                NPLogger.w(TAG, "酷狗会话写入失败", error)
                return@synchronized false
            }
            _sessionFlow.value = normalized
            NPLogger.d(TAG, "酷狗会话已保存: userid=${normalized.userId}")
            true
        }
    }

    /**
     * 只更新会话标识
     *
     * 后端会在响应头里轮换 session id, 此时不应覆盖已保存的用户资料
     */
    fun updateSessionId(sessionId: String): Boolean {
        val rotated = sessionId.trim()
        if (rotated.isEmpty()) return false
        val current = _sessionFlow.value
        if (rotated == current.sessionId) return false
        return saveSession(current.copy(sessionId = rotated))
    }

    fun saveBaseUrl(baseUrl: String): Boolean {
        val trimmed = baseUrl.trim()
        return synchronized(mutationLock) {
            runCatching {
                encryptedPrefs.edit {
                    if (trimmed.isEmpty()) {
                        remove(KEY_KUGOU_BASE_URL)
                    } else {
                        putString(KEY_KUGOU_BASE_URL, trimmed)
                    }
                }
            }.onFailure { error ->
                NPLogger.w(TAG, "酷狗服务端地址写入失败", error)
                return@synchronized false
            }
            _baseUrlFlow.value = trimmed
            true
        }
    }

    fun clear() {
        synchronized(mutationLock) {
            encryptedPrefs.edit { remove(KEY_KUGOU_SESSION) }
            _sessionFlow.value = KugouAuthSession.Empty
            NPLogger.d(TAG, "酷狗会话已清除")
        }
    }

    private fun loadSession(): KugouAuthSession {
        val raw = runCatching { encryptedPrefs.getString(KEY_KUGOU_SESSION, null) }
            .getOrElse { error ->
                NPLogger.w(TAG, "酷狗会话读取失败, 重建加密存储", error)
                rebuildEncryptedStorage()
                null
            }
            .orEmpty()
        if (raw.isBlank()) return KugouAuthSession.Empty
        return runCatching { decodeSession(raw) }
            .getOrElse { error ->
                NPLogger.w(TAG, "酷狗会话解析失败, 已丢弃", error)
                KugouAuthSession.Empty
            }
    }

    private fun openEncryptedPrefsWithRecovery(): SharedPreferences {
        return runCatching { createEncryptedPrefs() }
            .getOrElse { error ->
                NPLogger.w(TAG, "酷狗安全存储打开失败, 清空后重建", error)
                runCatching { context.deleteSharedPreferences(KUGOU_AUTH_PREFS) }
                createEncryptedPrefs()
            }
    }

    private fun rebuildEncryptedStorage() {
        runCatching { context.deleteSharedPreferences(KUGOU_AUTH_PREFS) }
        encryptedPrefs = openEncryptedPrefsWithRecovery()
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            KUGOU_AUTH_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun KugouAuthSession.toJson(): String = JSONObject().apply {
        put("token", token)
        put("t1", t1)
        put("sessionId", sessionId)
        put("userId", userId)
        put("nickname", nickname)
        put("avatarUrl", avatarUrl)
        put("isVip", isVip)
        put("isConceptVip", isConceptVip)
        put("savedAt", savedAt)
    }.toString()

    private fun decodeSession(raw: String): KugouAuthSession {
        val json = JSONObject(raw)
        return KugouAuthSession(
            token = json.optString("token"),
            t1 = json.optString("t1"),
            sessionId = json.optString("sessionId"),
            userId = json.optString("userId"),
            nickname = json.optString("nickname"),
            avatarUrl = json.optString("avatarUrl"),
            isVip = json.optBoolean("isVip"),
            isConceptVip = json.optBoolean("isConceptVip"),
            savedAt = json.optLong("savedAt")
        ).normalized()
    }

    companion object {
        private const val TAG = "KugouSessionRepository"
    }
}
