@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package moe.ouom.neriplayer.ui.screen.tab.settings.component.kugou

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
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.component.kugou/KugouAuthSheet
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet as ModalBottomSheet
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetDragBlocker
import moe.ouom.neriplayer.ui.screen.tab.settings.component.InlineMessage
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSegmentedTabs
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField
import moe.ouom.neriplayer.ui.screen.tab.settings.state.collectAsStateWithLifecycleCompat
import moe.ouom.neriplayer.ui.viewmodel.auth.KugouAuthUiState
import moe.ouom.neriplayer.ui.viewmodel.auth.KugouAuthViewModel
import moe.ouom.neriplayer.ui.viewmodel.auth.KugouQrUiStatus

/**
 * 酷狗登录面板
 *
 * 只保留扫码一条链路: 密码与短信链路需要额外的风控校验, 实测不如扫码稳定,
 * 因此不再暴露。`initialTab` 仅为兼容旧调用点保留, 当前无用。
 */
@Composable
internal fun KugouAuthSheet(
    vm: KugouAuthViewModel,
    @Suppress("UNUSED_PARAMETER") initialTab: Int,
    inlineMessage: String?,
    onInlineMessageChange: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val state by vm.uiState.collectAsStateWithLifecycleCompat()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(vm) {
        vm.requestQrCode()
    }

    DisposableEffect(vm) {
        onDispose { vm.cancelQrPolling() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .bottomSheetDragBlocker()
                .padding(start = 20.dp, end = 20.dp, bottom = 48.dp, top = 8.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(CoreCommonR.string.settings_kugou_login_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        stringResource(CoreCommonR.string.settings_kugou_login_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AnimatedVisibility(visible = inlineMessage != null, enter = fadeIn(), exit = fadeOut()) {
                    InlineMessage(
                        text = inlineMessage ?: "",
                        onClose = { onInlineMessageChange(null) }
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.72f),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        KugouQrLoginContent(vm, state)
                    }
                }
            }
        }
    }
}

@Composable
private fun KugouPasswordLoginContent(
    vm: KugouAuthViewModel,
    state: KugouAuthUiState
) {
    MiuixSettingsTextField(
        value = state.mobile,
        onValueChange = vm::onMobileChange,
        label = { Text(stringResource(CoreCommonR.string.settings_phone_number_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    MiuixSettingsTextField(
        value = state.password,
        onValueChange = vm::onPasswordChange,
        label = { Text(stringResource(CoreCommonR.string.settings_kugou_password_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    KugouLoginSubmitButton(
        enabled = state.mobile.isNotBlank() && state.password.isNotBlank(),
        loggingIn = state.loggingIn,
        onClick = vm::loginWithPassword
    )
}

@Composable
private fun KugouCaptchaLoginContent(
    vm: KugouAuthViewModel,
    state: KugouAuthUiState
) {
    MiuixSettingsTextField(
        value = state.mobile,
        onValueChange = vm::onMobileChange,
        label = { Text(stringResource(CoreCommonR.string.settings_phone_number_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    MiuixSettingsTextField(
        value = state.captcha,
        onValueChange = vm::onCaptchaChange,
        label = { Text(stringResource(CoreCommonR.string.login_sms_code)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    MiuixSettingsButton(
        enabled = !state.sendingCaptcha && state.captchaCountdownSec <= 0,
        onClick = vm::sendCaptcha,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (state.sendingCaptcha) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(CoreCommonR.string.login_sending))
        } else {
            Text(
                if (state.captchaCountdownSec > 0) {
                    stringResource(
                        CoreCommonR.string.settings_resend_code_countdown,
                        state.captchaCountdownSec
                    )
                } else {
                    stringResource(CoreCommonR.string.login_send_code)
                }
            )
        }
    }

    KugouLoginSubmitButton(
        enabled = state.mobile.isNotBlank() && state.captcha.isNotBlank(),
        loggingIn = state.loggingIn,
        onClick = vm::loginWithCaptcha
    )
}

@Composable
private fun KugouLoginSubmitButton(
    enabled: Boolean,
    loggingIn: Boolean,
    onClick: () -> Unit
) {
    MiuixSettingsButton(
        enabled = enabled && !loggingIn,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (loggingIn) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(CoreCommonR.string.login_logging_in))
        } else {
            Text(stringResource(CoreCommonR.string.login_title))
        }
    }
}

@Composable
private fun KugouQrLoginContent(
    vm: KugouAuthViewModel,
    state: KugouAuthUiState
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = state.qrBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(CoreCommonR.string.settings_kugou_login_title),
                modifier = Modifier.size(200.dp)
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
        }
    }

    Text(
        text = kugouQrStatusText(state),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MiuixSettingsTextButton(onClick = vm::requestQrCode) {
            Text(stringResource(CoreCommonR.string.settings_kugou_qr_refresh))
        }
    }
}

@Composable
private fun kugouQrStatusText(state: KugouAuthUiState): String {
    return when (val status = state.qrStatus) {
        KugouQrUiStatus.Idle,
        KugouQrUiStatus.Loading -> stringResource(CoreCommonR.string.settings_kugou_qr_loading)

        KugouQrUiStatus.Waiting -> stringResource(CoreCommonR.string.settings_kugou_qr_waiting)
        KugouQrUiStatus.Scanned -> stringResource(CoreCommonR.string.settings_kugou_qr_scanned)
        KugouQrUiStatus.Expired -> stringResource(CoreCommonR.string.settings_kugou_qr_expired)
        is KugouQrUiStatus.Failed -> if (status.message.isBlank()) {
            stringResource(CoreCommonR.string.settings_kugou_qr_empty)
        } else {
            stringResource(CoreCommonR.string.settings_kugou_qr_failed, status.message)
        }
    }
}
