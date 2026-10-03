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
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.component.kugou/KugouSettingsSection
 */

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.platform.kugou.api.KugouEndpointConfig
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField
import moe.ouom.neriplayer.ui.screen.tab.settings.state.collectAsStateWithLifecycleCompat
import moe.ouom.neriplayer.ui.viewmodel.auth.KugouAuthEvent
import moe.ouom.neriplayer.ui.viewmodel.auth.KugouAuthUiState
import moe.ouom.neriplayer.ui.viewmodel.auth.KugouAuthViewModel

/**
 * 酷狗音乐账号设置区
 *
 * 与 Bili / 网易云账号行并列渲染在"账号"页内, 自身持有登录面板与服务端地址
 * 对话框的显示状态, 因此不依赖 [moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsAccountAuthController]。
 */
@Composable
internal fun KugouSettingsSection(
    modifier: Modifier = Modifier,
    vm: KugouAuthViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by vm.uiState.collectAsStateWithLifecycleCompat()
    var showLoginSheet by rememberSaveable { mutableStateOf(false) }
    var showBaseUrlDialog by rememberSaveable { mutableStateOf(false) }
    var showLogDialog by rememberSaveable { mutableStateOf(false) }
    var showLogoutDialog by rememberSaveable { mutableStateOf(false) }
    var inlineMsg by remember { mutableStateOf<String?>(null) }
    val latestShowLoginSheet by rememberUpdatedState(showLoginSheet)

    LaunchedEffect(vm) {
        // Channel 只能有一个消费者, 这里独占收集并按面板可见性分发提示
        vm.events.collect { event ->
            when (event) {
                is KugouAuthEvent.ShowSnack -> {
                    if (latestShowLoginSheet) {
                        inlineMsg = event.message
                    } else {
                        AppFeedback.show(context = context, message = event.message)
                    }
                }

                KugouAuthEvent.LoginSuccess -> {
                    showLoginSheet = false
                    inlineMsg = null
                    AppFeedback.show(
                        context = context,
                        message = context.getString(CoreCommonR.string.settings_kugou_login_success)
                    )
                }

                KugouAuthEvent.LogoutSuccess -> {
                    showLogoutDialog = false
                    AppFeedback.show(
                        context = context,
                        message = context.getString(CoreCommonR.string.settings_kugou_logout_success)
                    )
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        KugouAccountListItem(
            state = state,
            onOpenLogin = {
                inlineMsg = null
                showLoginSheet = true
            },
            onOpenLogout = { showLogoutDialog = true }
        )
        KugouBaseUrlListItem(
            state = state,
            onOpenDialog = { showBaseUrlDialog = true }
        )
        KugouLogListItem(onOpenDialog = { showLogDialog = true })
        KugouVipListItem(
            state = state,
            onClaim = vm::claimDailyVip
        )
    }

    if (showLoginSheet) {
        KugouAuthSheet(
            vm = vm,
            initialTab = 0,
            inlineMessage = inlineMsg,
            onInlineMessageChange = { inlineMsg = it },
            onDismiss = { showLoginSheet = false }
        )
    }

    if (showBaseUrlDialog) {
        KugouBaseUrlDialog(
            state = state,
            onSave = { raw ->
                vm.saveBaseUrl(raw)
                showBaseUrlDialog = false
            },
            onReset = {
                vm.clearBaseUrl()
                showBaseUrlDialog = false
            },
            onDismiss = { showBaseUrlDialog = false }
        )
    }

    if (showLogDialog) {
        KugouLogDialog(onDismiss = { showLogDialog = false })
    }

    if (showLogoutDialog) {
        MiuixSettingsDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text(stringResource(CoreCommonR.string.settings_kugou_logout_confirm_title)) },
            text = { Text(stringResource(CoreCommonR.string.settings_kugou_logout_confirm_message)) },
            confirmButton = {
                MiuixSettingsTextButton(onClick = vm::logout) {
                    Text(
                        stringResource(CoreCommonR.string.settings_saved_cookie_logout),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                MiuixSettingsTextButton(onClick = { showLogoutDialog = false }) {
                    Text(stringResource(CoreCommonR.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun KugouAccountListItem(
    state: KugouAuthUiState,
    onOpenLogin: () -> Unit,
    onOpenLogout: () -> Unit
) {
    val logoutAction: (@Composable () -> Unit)? = if (state.loggedIn) {
        {
            MiuixSettingsTextButton(onClick = onOpenLogout) {
                Text(stringResource(CoreCommonR.string.settings_saved_cookie_logout))
            }
        }
    } else {
        null
    }
    val supportingText = if (state.loggedIn) {
        stringResource(
            CoreCommonR.string.settings_kugou_status_logged_in,
            state.displayName.ifBlank { state.userId }
        )
    } else {
        stringResource(CoreCommonR.string.settings_kugou_status_missing)
    }

    ListItem(
        leadingContent = {
            Icon(
                imageVector = Icons.Filled.Audiotrack,
                contentDescription = stringResource(CoreCommonR.string.platform_kugou),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(stringResource(CoreCommonR.string.platform_kugou)) },
        supportingContent = { Text(supportingText) },
        trailingContent = logoutAction,
        // 已登录时点击不再弹登录面板, 避免重复登录造成困惑
        modifier = Modifier.settingsItemClickable {
            if (!state.loggedIn) onOpenLogin()
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

/** 诊断日志入口: 打开后可复制脱敏后的请求/响应记录 */
@Composable
private fun KugouLogListItem(
    onOpenDialog: () -> Unit
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Cloud,
                contentDescription = stringResource(CoreCommonR.string.settings_kugou_log),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(stringResource(CoreCommonR.string.settings_kugou_log)) },
        supportingContent = { Text(stringResource(CoreCommonR.string.settings_kugou_log_desc)) },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.settingsItemClickable(onClick = onOpenDialog),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun KugouBaseUrlListItem(
    state: KugouAuthUiState,
    onOpenDialog: () -> Unit
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Cloud,
                contentDescription = stringResource(CoreCommonR.string.settings_kugou_base_url),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(stringResource(CoreCommonR.string.settings_kugou_base_url)) },
        supportingContent = {
            Text(stringResource(CoreCommonR.string.settings_kugou_base_url_current, state.baseUrl))
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier.settingsItemClickable(onClick = onOpenDialog),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun KugouVipListItem(
    state: KugouAuthUiState,
    onClaim: () -> Unit
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Star,
                contentDescription = stringResource(CoreCommonR.string.settings_kugou_vip),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(stringResource(CoreCommonR.string.settings_kugou_vip)) },
        supportingContent = { Text(kugouVipStatusText(state)) },
        trailingContent = {
            MiuixSettingsTextButton(
                enabled = state.loggedIn && !state.claimingVip,
                onClick = onClaim
            ) {
                Text(
                    if (state.claimingVip) {
                        stringResource(CoreCommonR.string.settings_kugou_vip_claiming)
                    } else {
                        stringResource(CoreCommonR.string.settings_kugou_vip_claim)
                    }
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun kugouVipStatusText(state: KugouAuthUiState): String {
    if (!state.loggedIn) return stringResource(CoreCommonR.string.settings_kugou_login_required)
    if (state.loadingVip && !state.vipLoaded) {
        return stringResource(CoreCommonR.string.settings_kugou_vip_loading)
    }
    if (!state.vipLoaded) return stringResource(CoreCommonR.string.settings_kugou_vip_desc)
    return when {
        state.isSuperVip -> stringResource(CoreCommonR.string.settings_kugou_vip_super)
        state.isConceptVip -> stringResource(CoreCommonR.string.settings_kugou_vip_concept)
        state.isVip -> stringResource(CoreCommonR.string.settings_kugou_vip_normal)
        else -> stringResource(CoreCommonR.string.settings_kugou_vip_none)
    }
}

@Composable
private fun KugouBaseUrlDialog(
    state: KugouAuthUiState,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember(state.baseUrl) { mutableStateOf(state.customBaseUrl) }
    val isValid = input.isBlank() || KugouEndpointConfig.isValidBaseUrl(input)

    MiuixSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreCommonR.string.settings_kugou_base_url)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(CoreCommonR.string.settings_kugou_base_url_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                MiuixSettingsTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(CoreCommonR.string.settings_kugou_base_url)) },
                    placeholder = { Text(KugouEndpointConfig.DEFAULT_BASE_URL) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isValid) {
                        stringResource(CoreCommonR.string.settings_kugou_base_url_current, state.baseUrl)
                    } else {
                        stringResource(CoreCommonR.string.settings_kugou_base_url_invalid)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isValid) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        },
        confirmButton = {
            MiuixSettingsButton(
                enabled = isValid,
                onClick = { onSave(input) }
            ) {
                Text(stringResource(CoreCommonR.string.action_confirm))
            }
        },
        dismissButton = {
            Row {
                MiuixSettingsTextButton(onClick = onReset) {
                    Text(stringResource(CoreCommonR.string.settings_kugou_base_url_reset))
                }
                MiuixSettingsTextButton(onClick = onDismiss) {
                    Text(stringResource(CoreCommonR.string.action_cancel))
                }
            }
        }
    )
}
