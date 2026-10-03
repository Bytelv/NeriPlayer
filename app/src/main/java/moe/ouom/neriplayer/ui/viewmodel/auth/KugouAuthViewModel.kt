package moe.ouom.neriplayer.ui.viewmodel.auth

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
 * File: moe.ouom.neriplayer.ui.viewmodel.auth/KugouAuthViewModel
 */

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.kugou.KugouAuthSession
import moe.ouom.neriplayer.data.model.kugou.KugouDebugLog
import moe.ouom.neriplayer.platform.kugou.api.KugouEndpointConfig
import moe.ouom.neriplayer.platform.kugou.api.client.KugouQrCode
import moe.ouom.neriplayer.platform.kugou.api.client.KugouQrStatus
import moe.ouom.neriplayer.platform.kugou.api.client.KugouUserDetail
import moe.ouom.neriplayer.platform.kugou.repository.KugouVipRepository.KugouVipClaimOutcome

/** 扫码登录的界面状态 */
sealed interface KugouQrUiStatus {
    data object Idle : KugouQrUiStatus
    data object Loading : KugouQrUiStatus
    data object Waiting : KugouQrUiStatus
    data object Scanned : KugouQrUiStatus
    data object Expired : KugouQrUiStatus
    data class Failed(val message: String) : KugouQrUiStatus
}

data class KugouAuthUiState(
    val loggedIn: Boolean = false,
    val nickname: String = "",
    val avatarUrl: String = "",
    val userId: String = "",
    val savedAt: Long = 0L,
    val baseUrl: String = KugouEndpointConfig.DEFAULT_BASE_URL,
    /** 用户自定义的服务端地址, 空串表示使用默认地址 */
    val customBaseUrl: String = "",
    val isVip: Boolean = false,
    val isSuperVip: Boolean = false,
    val isConceptVip: Boolean = false,
    val vipType: Int = 0,
    val vipLoaded: Boolean = false,
    val mobile: String = "",
    val password: String = "",
    val captcha: String = "",
    val sendingCaptcha: Boolean = false,
    val captchaCountdownSec: Int = 0,
    val loggingIn: Boolean = false,
    val refreshingProfile: Boolean = false,
    val loadingVip: Boolean = false,
    val claimingVip: Boolean = false,
    val qrCode: KugouQrCode? = null,
    val qrBitmap: Bitmap? = null,
    val qrStatus: KugouQrUiStatus = KugouQrUiStatus.Idle
) {
    val hasCustomBaseUrl: Boolean get() = customBaseUrl.isNotBlank()

    /** 账号行的副标题: 已登录优先展示昵称, 否则退回用户标识 */
    val displayName: String get() = nickname.ifBlank { userId }
}

sealed interface KugouAuthEvent {
    data class ShowSnack(val message: String) : KugouAuthEvent
    data object LoginSuccess : KugouAuthEvent
    data object LogoutSuccess : KugouAuthEvent
}

/**
 * 酷狗音乐账号与设置
 *
 * 与 `BiliAuthViewModel` / `NeteaseAuthViewModel` 同形: AndroidViewModel + StateFlow
 * 暴露界面状态, Channel 暴露一次性提示。会话与后端地址由 [KugouSessionRepository]
 * 持有, 这里只做订阅与转发, 保证设置页始终反映落盘状态。
 */
class KugouAuthViewModel(app: Application) : AndroidViewModel(app) {

    private val sessionRepo by lazy { AppContainer.kugouSessionRepo }
    private val authClient by lazy { AppContainer.kugouAuthClient }
    private val kugouClient by lazy { AppContainer.kugouClient }
    private val playbackRepository by lazy { AppContainer.kugouPlaybackRepository }
    private val vipRepository by lazy { AppContainer.kugouVipRepository }

    private val _uiState = MutableStateFlow(KugouAuthUiState())
    val uiState: StateFlow<KugouAuthUiState> = _uiState.asStateFlow()

    private val _events = Channel<KugouAuthEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var captchaCountdownJob: Job? = null
    private var qrPollingJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            sessionRepo.sessionFlow.collect { session ->
                val wasLoggedIn = _uiState.value.loggedIn
                applySession(session)
                if (!wasLoggedIn && session.isLoggedIn()) {
                    refreshVipInfo()
                    // 启动时的自动领取可能早于本次登录, 登录成功后补领一次
                    autoClaimDailyVipIfNeeded()
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            sessionRepo.baseUrlFlow.collect { rawBaseUrl ->
                _uiState.update { current ->
                    current.copy(
                        customBaseUrl = rawBaseUrl,
                        baseUrl = rawBaseUrl.ifBlank { KugouEndpointConfig.DEFAULT_BASE_URL }
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- 表单输入

    fun onMobileChange(value: String) {
        val filtered = value.filter { it.isDigit() }.take(MOBILE_MAX_LENGTH)
        _uiState.update { it.copy(mobile = filtered) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value) }
    }

    fun onCaptchaChange(value: String) {
        _uiState.update { it.copy(captcha = value.filter { char -> char.isDigit() }) }
    }

    // ---------------------------------------------------------------- 密码 / 短信

    fun loginWithPassword() {
        val state = _uiState.value
        if (state.mobile.isBlank()) {
            notify(CoreCommonR.string.settings_kugou_mobile_required)
            return
        }
        if (state.password.isBlank()) {
            notify(CoreCommonR.string.settings_kugou_password_required)
            return
        }
        val mobile = state.mobile.trim()
        val password = state.password
        performLogin { authClient.loginByPassword(mobile, password, KUGOU_COUNTRY_CODE) }
    }

    fun sendCaptcha() {
        val mobile = _uiState.value.mobile.trim()
        if (mobile.isBlank()) {
            notify(CoreCommonR.string.settings_kugou_mobile_required)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(sendingCaptcha = true) }
            val sent = runCatching { authClient.sendCaptcha(mobile) }.getOrDefault(false)
            _uiState.update { it.copy(sendingCaptcha = false) }
            if (sent) {
                notify(CoreCommonR.string.settings_kugou_captcha_sent)
                startCaptchaCountdown()
            } else {
                notify(CoreCommonR.string.settings_kugou_captcha_send_failed)
            }
        }
    }

    fun loginWithCaptcha() {
        val state = _uiState.value
        if (state.mobile.isBlank()) {
            notify(CoreCommonR.string.settings_kugou_mobile_required)
            return
        }
        if (state.captcha.isBlank()) {
            notify(CoreCommonR.string.settings_kugou_captcha_required)
            return
        }
        val mobile = state.mobile.trim()
        val captcha = state.captcha.trim()
        performLogin { authClient.loginByCaptcha(mobile, captcha, KUGOU_COUNTRY_CODE) }
    }

    private fun performLogin(request: suspend () -> KugouAuthSession?) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(loggingIn = true) }
            val session = runCatching { request() }.getOrNull()
            _uiState.update { it.copy(loggingIn = false) }
            if (session == null) {
                notify(CoreCommonR.string.settings_kugou_login_failed)
                return@launch
            }
            persistSession(session)
            refreshUserProfile()
        }
    }

    // ---------------------------------------------------------------- 扫码

    fun requestQrCode() {
        cancelQrPolling()
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(qrStatus = KugouQrUiStatus.Loading, qrCode = null, qrBitmap = null)
            }
            val qrCode = runCatching { authClient.requestQrCode() }.getOrNull()
            if (qrCode == null || !qrCode.isValid()) {
                _uiState.update { it.copy(qrStatus = KugouQrUiStatus.Failed("")) }
                return@launch
            }
            _uiState.update {
                it.copy(
                    qrCode = qrCode,
                    qrBitmap = decodeQrBitmap(qrCode.imageUrl),
                    qrStatus = KugouQrUiStatus.Waiting
                )
            }
            startQrPolling(qrCode.key)
        }
    }

    /** 关闭扫码面板或切换标签时必须调用, 否则轮询会一直挂在后台 */
    fun cancelQrPolling() {
        qrPollingJob?.cancel()
        qrPollingJob = null
    }

    private fun startQrPolling(key: String) {
        qrPollingJob?.cancel()
        qrPollingJob = viewModelScope.launch(Dispatchers.IO) {
            repeat(QR_MAX_POLL_ATTEMPTS) {
                delay(QR_POLL_INTERVAL_MS)
                val status = runCatching { authClient.checkQrCode(key) }
                    .getOrElse { error -> KugouQrStatus.Failed(error.message.orEmpty()) }
                when (status) {
                    is KugouQrStatus.Pending -> _uiState.update { current ->
                        if (status.statusCode == QR_STATUS_SCANNED) {
                            current.copy(qrStatus = KugouQrUiStatus.Scanned)
                        } else {
                            current.copy(qrStatus = KugouQrUiStatus.Waiting)
                        }
                    }

                    is KugouQrStatus.Confirmed -> {
                        persistSession(status.session)
                        refreshUserProfile()
                        return@launch
                    }

                    is KugouQrStatus.Failed -> {
                        _uiState.update { it.copy(qrStatus = KugouQrUiStatus.Failed(status.message)) }
                        return@launch
                    }
                }
            }
            _uiState.update { it.copy(qrStatus = KugouQrUiStatus.Expired) }
        }
    }

    // ---------------------------------------------------------------- 账号维护

    fun refreshUserProfile() {
        if (!_uiState.value.loggedIn) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(refreshingProfile = true) }
            val detail = runCatching { authClient.fetchUserDetail() }.getOrNull()
            _uiState.update { it.copy(refreshingProfile = false) }
            if (detail == null) {
                notify(CoreCommonR.string.settings_kugou_profile_refresh_failed)
                return@launch
            }
            mergeProfile(detail)
        }
    }

    fun refreshVipInfo() {
        if (!_uiState.value.loggedIn) {
            _uiState.update {
                it.copy(
                    isVip = false,
                    isSuperVip = false,
                    isConceptVip = false,
                    vipType = 0,
                    vipLoaded = false
                )
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(loadingVip = true) }
            val info = runCatching { playbackRepository.fetchVipInfo() }.getOrNull()
            _uiState.update { current ->
                if (info == null) {
                    current.copy(loadingVip = false)
                } else {
                    current.copy(
                        loadingVip = false,
                        isVip = info.isVip,
                        isSuperVip = info.isSuperVip,
                        isConceptVip = info.isConceptVip,
                        vipType = info.vipType,
                        vipLoaded = true
                    )
                }
            }
            if (info != null) {
                sessionRepo.saveSession(
                    sessionRepo.currentSession().copy(
                        isVip = info.isVip,
                        isConceptVip = info.isConceptVip
                    )
                )
            }
        }
    }

    fun claimDailyVip() {
        if (!_uiState.value.loggedIn) {
            notify(CoreCommonR.string.settings_kugou_login_required)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            when (val outcome = runClaim()) {
                is KugouVipClaimOutcome.Claimed -> {
                    notify(
                        if (outcome.upgraded) {
                            CoreCommonR.string.settings_kugou_vip_claimed_upgraded
                        } else {
                            CoreCommonR.string.settings_kugou_vip_claimed
                        }
                    )
                }

                KugouVipClaimOutcome.AlreadyClaimed ->
                    notify(CoreCommonR.string.settings_kugou_vip_already_claimed)

                is KugouVipClaimOutcome.Failed -> {
                    val restrictedCode = conceptRestrictionCode(outcome.reason)
                    when {
                        // 该接口仅对概念版账号开放, 给出可操作的解释而不是原始错误码
                        restrictedCode != null -> notify(
                            CoreCommonR.string.settings_kugou_vip_claim_restricted,
                            restrictedCode
                        )

                        outcome.reason.isBlank() ->
                            notify(CoreCommonR.string.settings_kugou_vip_claim_failed_generic)

                        else ->
                            notify(CoreCommonR.string.settings_kugou_vip_claim_failed, outcome.reason)
                    }
                }

                KugouVipClaimOutcome.SessionRequired ->
                    notify(CoreCommonR.string.settings_kugou_login_required)
            }
        }
    }

    /**
     * 未登录 -> 已登录时静默补领当天会员
     *
     * 启动路径上的那次触发可能早于本次登录, 因此登录成功后再补一次; 领取本身由
     * 服务端记录判定, 已领过只会得到 [KugouVipClaimOutcome.AlreadyClaimed]。
     * 这里不弹提示, 只有真正领到才告知用户, 避免每次登录都打扰。
     */
    private suspend fun autoClaimDailyVipIfNeeded() {
        when (val outcome = runClaim(silent = true)) {
            is KugouVipClaimOutcome.Claimed -> notify(
                if (outcome.upgraded) {
                    CoreCommonR.string.settings_kugou_vip_claimed_upgraded
                } else {
                    CoreCommonR.string.settings_kugou_vip_claimed
                }
            )

            else -> Unit
        }
    }

    private suspend fun runClaim(silent: Boolean = false): KugouVipClaimOutcome {
        if (!_uiState.value.loggedIn && !silent) {
            return KugouVipClaimOutcome.SessionRequired
        }
        _uiState.update { it.copy(claimingVip = true) }
        val outcome = runCatching { vipRepository.claimDailyVip() }.getOrElse { error ->
            KugouVipClaimOutcome.Failed(error.message.orEmpty())
        }
        _uiState.update { it.copy(claimingVip = false) }
        if (outcome is KugouVipClaimOutcome.Claimed) {
            refreshVipInfo()
        }
        return outcome
    }

    fun logout() {
        cancelQrPolling()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { authClient.logout() }
            sessionRepo.clear()
            _uiState.update {
                it.copy(
                    isVip = false,
                    isSuperVip = false,
                    isConceptVip = false,
                    vipType = 0,
                    vipLoaded = false,
                    password = "",
                    captcha = "",
                    qrCode = null,
                    qrBitmap = null,
                    qrStatus = KugouQrUiStatus.Idle
                )
            }
            _events.trySend(KugouAuthEvent.LogoutSuccess)
        }
    }

    // ---------------------------------------------------------------- 服务端地址

    fun saveBaseUrl(rawBaseUrl: String) {
        val trimmed = rawBaseUrl.trim()
        if (!KugouEndpointConfig.isValidBaseUrl(trimmed)) {
            notify(CoreCommonR.string.settings_kugou_base_url_invalid)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val stored = if (trimmed.isBlank()) "" else KugouEndpointConfig.normalizeBaseUrl(trimmed)
            if (sessionRepo.saveBaseUrl(stored)) {
                notify(CoreCommonR.string.settings_kugou_base_url_saved)
            } else {
                notify(CoreCommonR.string.settings_kugou_base_url_save_failed)
            }
        }
    }

    fun clearBaseUrl() {
        viewModelScope.launch(Dispatchers.IO) {
            if (sessionRepo.saveBaseUrl("")) {
                notify(CoreCommonR.string.settings_kugou_base_url_reset_done)
            } else {
                notify(CoreCommonR.string.settings_kugou_base_url_save_failed)
            }
        }
    }

    // ---------------------------------------------------------------- 内部工具

    private fun applySession(session: KugouAuthSession) {
        _uiState.update { current ->
            val loggedIn = session.isLoggedIn()
            current.copy(
                loggedIn = loggedIn,
                nickname = session.nickname,
                avatarUrl = session.avatarUrl,
                userId = session.userId,
                savedAt = session.savedAt,
                isVip = if (loggedIn) session.isVip else false,
                isConceptVip = if (loggedIn) session.isConceptVip else false,
                isSuperVip = if (loggedIn) current.isSuperVip else false,
                vipType = if (loggedIn) current.vipType else 0,
                vipLoaded = if (loggedIn) current.vipLoaded else false
            )
        }
    }

    private fun mergeProfile(detail: KugouUserDetail) {
        val current = sessionRepo.currentSession()
        if (!current.isLoggedIn()) return
        val nickname = detail.nickname.ifBlank { current.nickname }
        val avatarUrl = detail.avatarUrl.ifBlank { current.avatarUrl }
        if (nickname == current.nickname && avatarUrl == current.avatarUrl) return
        sessionRepo.saveSession(current.copy(nickname = nickname, avatarUrl = avatarUrl))
    }

    /**
     * 落盘登录结果
     *
     * 两点关键:
     * 1. 后端会在响应头里轮换 session id, [kugouClient] 只是把它暂存下来, 必须在
     *    [KugouSessionRepository.saveSession] **之前**合并, 否则新会话会缺少登录标识
     * 2. 扫码登录只下发 `token`, 不含 `t1`; 而播放与会员接口需要 `t1` 参与鉴权,
     *    缺失时酷狗会回 `ERROR_CODE_FAILED_RUNTIME_CHECK`。这里补一次
     *    `/login/token` 换取完整凭据 (与 KA Music 的处理一致)
     */
    private suspend fun persistSession(session: KugouAuthSession) {
        val rotated = runCatching { kugouClient.drainRotatedSessionIds().lastOrNull() }
            .getOrNull()
        val merged = if (rotated.isNullOrBlank()) session else session.copy(sessionId = rotated)

        val completed = if (merged.t1.isBlank()) {
            refreshSessionCredentials(merged) ?: merged
        } else {
            merged
        }

        if (!sessionRepo.saveSession(completed)) {
            notify(CoreCommonR.string.settings_kugou_login_failed)
            return
        }
        _uiState.update { it.copy(password = "", captcha = "") }
        _events.trySend(KugouAuthEvent.LoginSuccess)
    }

    /**
     * 用当前 token 调 `/login/token` 换回 `t1`
     *
     * 失败时返回 null, 由调用方保留原会话: 宁可少一个字段, 也不要因为刷新失败
     * 把已经扫码成功的登录整个丢掉。
     */
    private suspend fun refreshSessionCredentials(
        session: KugouAuthSession
    ): KugouAuthSession? {
        return runCatching {
            val refreshed = authClient.refreshToken()
            if (refreshed == null) {
                KugouDebugLog.record("AUTH /login/token", "未取到会话, 沿用原凭据")
                return@runCatching null
            }
            val rotated = runCatching { kugouClient.drainRotatedSessionIds().lastOrNull() }
                .getOrNull()
            KugouDebugLog.record(
                label = "AUTH /login/token",
                detail = "hasToken=${refreshed.token.isNotBlank()}, " +
                    "hasT1=${refreshed.t1.isNotBlank()}, " +
                    "hasUserId=${refreshed.userId.isNotBlank()}"
            )
            // 保留扫码返回的昵称头像, 只补齐 token / t1 等鉴权字段
            session.copy(
                token = refreshed.token.ifBlank { session.token },
                t1 = refreshed.t1.ifBlank { session.t1 },
                sessionId = rotated?.takeIf { it.isNotBlank() } ?: session.sessionId,
                userId = refreshed.userId.ifBlank { session.userId },
                isVip = refreshed.isVip || session.isVip,
                savedAt = System.currentTimeMillis()
            )
        }.onFailure { error ->
            NPLogger.w(
                "KugouAuthViewModel",
                "刷新酷狗 t1 失败, 沿用原会话: ${error.message.orEmpty()}"
            )
            KugouDebugLog.record(
                label = "AUTH /login/token",
                detail = "失败: ${error.message.orEmpty()}"
            )
        }.getOrNull()
    }

    private fun startCaptchaCountdown() {
        captchaCountdownJob?.cancel()
        captchaCountdownJob = viewModelScope.launch {
            _uiState.update { it.copy(captchaCountdownSec = CAPTCHA_COUNTDOWN_SECONDS) }
            while (true) {
                delay(1_000L)
                val remaining = _uiState.value.captchaCountdownSec - 1
                _uiState.update { it.copy(captchaCountdownSec = remaining.coerceAtLeast(0)) }
                if (remaining <= 0) break
            }
        }
    }

    private fun notify(resId: Int, vararg formatArgs: Any) {
        val message = runCatching {
            getApplication<Application>().getString(resId, *formatArgs)
        }.getOrNull() ?: return
        _events.trySend(KugouAuthEvent.ShowSnack(message))
    }

    /**
     * 从失败原因里取出"仅限概念版"类错误码
     *
     * 领取结果会把后端错误码拼成 `errorCode=131001`, 这里解析出来供界面给出
     * 可操作的解释; 其它错误仍按原文展示。
     */
    private fun conceptRestrictionCode(reason: String): String? {
        val match = ERROR_CODE_PATTERN.find(reason) ?: return null
        val code = match.groupValues[1]
        return code.takeIf { it in CONCEPT_RESTRICTED_ERROR_CODES }
    }

    /** `imageUrl` 形如 `data:image/png;base64,…`, 也可能直接是裸 base64 */
    private fun decodeQrBitmap(imageUrl: String): Bitmap? {
        val trimmed = imageUrl.trim()
        if (trimmed.isEmpty()) return null
        val payload = if (trimmed.startsWith(DATA_URI_PREFIX)) {
            trimmed.substringAfter(',', "")
        } else {
            trimmed
        }
        if (payload.isBlank()) return null
        return runCatching {
            val bytes = Base64.decode(payload, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    override fun onCleared() {
        captchaCountdownJob?.cancel()
        cancelQrPolling()
        super.onCleared()
    }

    companion object {
        /** 与网易云链路一致, 酷狗后端同样接受 `countrycode` */
        private const val KUGOU_COUNTRY_CODE = "86"
        private const val MOBILE_MAX_LENGTH = 11
        private const val CAPTCHA_COUNTDOWN_SECONDS = 60
        private const val QR_POLL_INTERVAL_MS = 2_000L
        /** 2 秒一次, 约 3 分钟后视为过期 */
        private const val QR_MAX_POLL_ATTEMPTS = 90
        private const val DATA_URI_PREFIX = "data:"

        /** 后端扫码状态码: 2 表示已扫码待确认 (4 见 [moe.ouom.neriplayer.platform.kugou.api.client.KugouAuthClient.QR_STATUS_CONFIRMED]) */
        private const val QR_STATUS_SCANNED = 2

        /** 领取失败原因里形如 `errorCode=131001` */
        private val ERROR_CODE_PATTERN = Regex("""errorCode=(\d+)""")

        /**
         * 表示"该账号不具备概念版领取资格"的上游错误码
         *
         * 文档说明 `/youth/day/vip` 仅对酷狗概念版账号开放, 非概念版账号会被上游拒绝
         */
        private val CONCEPT_RESTRICTED_ERROR_CODES = setOf("131001")

        /** 供界面层复用: [KugouSessionRepository] 未配置时的有效地址 */        val defaultBaseUrl: String get() = KugouEndpointConfig.DEFAULT_BASE_URL
    }
}
