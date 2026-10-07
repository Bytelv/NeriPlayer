package moe.ouom.neriplayer.ui.screen.debug

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
 * File: moe.ouom.neriplayer.ui.screen.debug/KugouLogScreen
 */

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight

/**
 * 酷狗诊断日志（调试界面入口）
 *
 * 展示 [KugouDebugLog] 的内存记录: 每条请求都记录了会话字段的存在性与脱敏后的
 * 响应体, 复制出来即可定位"登录了但播放/加歌/领取失败"这类问题。
 *
 * 这个面板原先是挂在「第三方平台登录」里的, 与账号设置混在一起; 现在改挂到专门的
 * 调试界面, 账号页只保留账号相关的内容。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("LocalContextResourcesRead")
fun KugouLogScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    // 打开期间持续刷新, 方便边操作边观察
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            revision++
        }
    }

    val entries = remember(revision) { KugouDebugLog.snapshot() }
    val text = remember(entries) {
        entries.joinToString("\n") { entry -> "${entry.label}: ${entry.detail}" }
    }

    // 局部函数需在 text 之后声明: Kotlin 要求先定义后引用
    fun copyLogs() {
        clipboardManager.setPrimaryClip(
            ClipData.newPlainText(
                "kugou-diagnostic-log",
                text.ifBlank { "(empty)" }
            )
        )
        AppFeedback.show(
            context = context,
            message = context.getString(CoreCommonR.string.toast_copied)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(CoreCommonR.string.settings_kugou_log_title)) },
                navigationIcon = {
                    HapticIconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(CoreCommonR.string.action_back)
                        )
                    }
                },
                actions = {
                    HapticIconButton(onClick = { copyLogs() }) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = stringResource(CoreCommonR.string.action_copy)
                        )
                    }
                    HapticIconButton(
                        onClick = {
                            KugouDebugLog.clear()
                            revision++
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(CoreCommonR.string.settings_kugou_log_clear)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        if (entries.isEmpty()) {
            Text(
                text = stringResource(CoreCommonR.string.settings_kugou_log_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = LocalMiniPlayerHeight.current + 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 最新的在最上面: 复现完立刻就能看到
                items(
                    items = entries.asReversed(),
                    key = { entry -> "${entry.label}|${entry.detail.hashCode()}" }
                ) { entry ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = entry.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = entry.detail,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
