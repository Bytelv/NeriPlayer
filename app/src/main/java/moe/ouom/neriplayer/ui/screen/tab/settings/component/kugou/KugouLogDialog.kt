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
 * File: moe.ouom.neriplayer.ui.screen.tab.settings.component.kugou/KugouLogDialog
 */

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.kugou.KugouDebugLog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton

/**
 * 酷狗诊断日志
 *
 * 设备上取 logcat 不方便, 这里直接展示 [KugouDebugLog] 的内存记录:
 * 每条请求都会记录会话字段的存在性与脱敏后的响应体, 复制出来即可定位
 * "登录了但播放/领取失败"这类问题。
 */
@Composable
internal fun KugouLogDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    // 面板打开期间持续刷新, 方便边操作边观察
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            revision++
        }
    }

    val entries = remember(revision) { KugouDebugLog.snapshot() }
    val text = remember(entries) {
        if (entries.isEmpty()) {
            ""
        } else {
            entries.joinToString("\n") { entry -> "${entry.label}: ${entry.detail}" }
        }
    }

    MiuixSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreCommonR.string.settings_kugou_log_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entries.isEmpty()) {
                    Text(
                        text = stringResource(CoreCommonR.string.settings_kugou_log_empty),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        entries.asReversed().forEach { entry ->
                            Column {
                                Text(
                                    text = entry.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = entry.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            MiuixSettingsTextButton(
                onClick = {
                    copyToClipboard(context, text.ifBlank { "(empty)" })
                }
            ) {
                Text(stringResource(CoreCommonR.string.action_copy))
            }
        },
        dismissButton = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MiuixSettingsTextButton(
                    onClick = {
                        KugouDebugLog.clear()
                        revision++
                    }
                ) {
                    Text(stringResource(CoreCommonR.string.settings_kugou_log_clear))
                }
                MiuixSettingsTextButton(onClick = onDismiss) {
                    Text(stringResource(CoreCommonR.string.action_close))
                }
            }
        }
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("kugou-log", text))
}
