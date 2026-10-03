package moe.ouom.neriplayer.ui.screen.nowplaying

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
 * File: moe.ouom.neriplayer.ui.screen.nowplaying/AddToPlaylistSheet
 */

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.playlist.AddToPlaylistPlatform
import moe.ouom.neriplayer.data.model.playlist.AddToPlaylistTarget
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet as ModalBottomSheet
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.viewmodel.playlist.AddToPlaylistFailure
import moe.ouom.neriplayer.ui.viewmodel.playlist.AddToPlaylistFeedback
import moe.ouom.neriplayer.ui.viewmodel.playlist.AddToPlaylistRemoteState
import moe.ouom.neriplayer.ui.viewmodel.playlist.AddToPlaylistUiState

/**
 * 播放页"添加到歌单"弹窗
 *
 * 分组展示本地 / 网易云 / 酷狗歌单: 远端两组只在该账号已登录时出现, 各自带
 * 加载中 / 失败可重试 / 空状态。本地歌单的点击语义完全交给调用方
 * (仍然是 `launchWithLocalSyncWarning` + `PlayerManager.addCurrentToPlaylist`)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToPlaylistSheet(
    localPlaylists: List<LocalPlaylist>,
    uiState: AddToPlaylistUiState,
    sheetState: SheetState,
    onDismissRequest: () -> Unit,
    onSelectLocalPlaylist: (LocalPlaylist) -> Unit,
    onSelectRemoteTarget: (AddToPlaylistTarget) -> Unit,
    onRetryRemoteLoad: () -> Unit
) {
    val submitting = uiState.submittingKey != null

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        sheetGesturesEnabled = false
    ) {
        LazyColumn(modifier = Modifier.bottomSheetScrollGuard()) {
            // 上一次提交的结果: 让"未找到匹配 / 添加失败"在弹窗内也看得见,
            // 不依赖弹窗收起之后才出现的 Snackbar
            uiState.feedback?.let { feedback ->
                item(key = "add-to-playlist-result", contentType = "result") {
                    AddToPlaylistResultRow(
                        message = addToPlaylistFeedbackMessage(LocalResources.current, feedback)
                    )
                }
            }

            item(key = "add-to-playlist-local-header", contentType = "header") {
                AddToPlaylistSectionHeader(
                    title = stringResource(CoreCommonR.string.playlist_add_to_group_local)
                )
            }
            itemsIndexed(
                items = localPlaylists,
                key = { _, playlist -> "local:${playlist.id}" },
                contentType = { _, _ -> "local" }
            ) { _, playlist ->
                AddToPlaylistRow(
                    name = playlist.name,
                    trackCount = playlist.songs.size,
                    enabled = !submitting,
                    submitting = false,
                    onClick = { onSelectLocalPlaylist(playlist) }
                )
            }

            addToPlaylistRemoteSection(
                platform = AddToPlaylistPlatform.NETEASE,
                state = uiState.netease,
                submittingKey = uiState.submittingKey,
                onSelect = onSelectRemoteTarget,
                onRetry = onRetryRemoteLoad
            )
            addToPlaylistRemoteSection(
                platform = AddToPlaylistPlatform.KUGOU,
                state = uiState.kugou,
                submittingKey = uiState.submittingKey,
                onSelect = onSelectRemoteTarget,
                onRetry = onRetryRemoteLoad
            )

            item(key = "add-to-playlist-footer", contentType = "footer") {
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/**
 * 弹窗内的结果提示行
 *
 * 成功与失败都显示: 添加入口在播放页, 若只靠 Snackbar, 弹窗收起动画期间很容易
 * 被用户错过。
 */
@Composable
private fun AddToPlaylistResultRow(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * 一个远端平台的分组
 *
 * 未登录时整组不出现; 没加载完 / 加载失败 / 空列表各有自己的状态行,
 * 网络错误不会被静默吞掉。
 */
private fun LazyListScope.addToPlaylistRemoteSection(
    platform: AddToPlaylistPlatform,
    state: AddToPlaylistRemoteState,
    submittingKey: String?,
    onSelect: (AddToPlaylistTarget) -> Unit,
    onRetry: () -> Unit
) {
    if (!state.loggedIn) return

    item(key = "add-to-playlist-$platform-header", contentType = "header") {
        AddToPlaylistSectionHeader(title = addToPlaylistPlatformGroupTitle(platform))
    }

    when {
        state.loading && !state.hasContent -> item(
            key = "add-to-playlist-$platform-loading",
            contentType = "status"
        ) {
            AddToPlaylistStatusRow(
                text = stringResource(CoreCommonR.string.playlist_add_to_remote_loading),
                showProgress = true
            )
        }

        state.error != null && !state.hasContent -> item(
            key = "add-to-playlist-$platform-error",
            contentType = "status"
        ) {
            AddToPlaylistErrorRow(error = state.error, onRetry = onRetry)
        }

        !state.hasContent -> item(
            key = "add-to-playlist-$platform-empty",
            contentType = "status"
        ) {
            AddToPlaylistStatusRow(
                text = stringResource(CoreCommonR.string.playlist_add_to_remote_empty),
                showProgress = false
            )
        }

        else -> itemsIndexed(
            items = state.playlists,
            key = { _, target -> target.key },
            contentType = { _, _ -> "remote" }
        ) { _, target ->
            val isSubmitting = submittingKey == target.key
            AddToPlaylistRow(
                name = target.name,
                trackCount = target.trackCount,
                // 提交中时禁掉其它行, 避免重复点击
                enabled = submittingKey == null,
                submitting = isSubmitting,
                onClick = { onSelect(target) }
            )
        }
    }
}

@Composable
private fun AddToPlaylistSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun AddToPlaylistRow(
    name: String,
    trackCount: Int,
    enabled: Boolean,
    submitting: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled || submitting) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(modifier = Modifier.weight(1f))
        if (submitting) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = stringResource(CoreCommonR.string.playlist_add_to_submitting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = pluralStringResource(
                    CoreCommonR.plurals.nowplaying_song_count_format,
                    trackCount,
                    trackCount
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AddToPlaylistStatusRow(text: String, showProgress: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.size(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AddToPlaylistErrorRow(error: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(CoreCommonR.string.playlist_add_to_remote_failed_format, error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        TextButton(onClick = onRetry) {
            Text(text = stringResource(CoreCommonR.string.playlist_add_to_retry))
        }
    }
}

@Composable
private fun addToPlaylistPlatformGroupTitle(platform: AddToPlaylistPlatform): String =
    when (platform) {
        AddToPlaylistPlatform.LOCAL ->
            stringResource(CoreCommonR.string.playlist_add_to_group_local)

        AddToPlaylistPlatform.NETEASE ->
            stringResource(CoreCommonR.string.playlist_add_to_group_netease)

        AddToPlaylistPlatform.KUGOU ->
            stringResource(CoreCommonR.string.playlist_add_to_group_kugou)
    }

internal fun addToPlaylistPlatformName(
    resources: Resources,
    platform: AddToPlaylistPlatform
): String = when (platform) {
    AddToPlaylistPlatform.LOCAL ->
        resources.getString(CoreCommonR.string.playlist_add_to_platform_local)

    AddToPlaylistPlatform.NETEASE ->
        resources.getString(CoreCommonR.string.playlist_add_to_platform_netease)

    AddToPlaylistPlatform.KUGOU ->
        resources.getString(CoreCommonR.string.playlist_add_to_platform_kugou)
}

internal fun addToPlaylistFailureMessage(
    resources: Resources,
    failure: AddToPlaylistFailure
): String = when (failure) {
    AddToPlaylistFailure.SONG_UNAVAILABLE ->
        resources.getString(CoreCommonR.string.playlist_add_to_reason_song_unavailable)

    AddToPlaylistFailure.INVALID_NETEASE_SONG_ID ->
        resources.getString(CoreCommonR.string.playlist_add_to_reason_invalid_netease_id)

    AddToPlaylistFailure.MISSING_KUGOU_HASH ->
        resources.getString(CoreCommonR.string.playlist_add_to_reason_missing_kugou_hash)

    AddToPlaylistFailure.UNSUPPORTED_PLATFORM ->
        resources.getString(CoreCommonR.string.playlist_add_to_reason_unsupported_platform)
}

/**
 * 把一次提交的结果映射成用户可见的一句话
 *
 * "匹配不到" 与 "添加失败" 分开表述: 前者没有发出任何写请求, 不能让用户以为
 * 只是网络抖动。
 */
internal fun addToPlaylistFeedbackMessage(
    resources: Resources,
    feedback: AddToPlaylistFeedback
): String = when (feedback) {
    is AddToPlaylistFeedback.Added ->
        resources.getString(CoreCommonR.string.playlist_add_to_success_format, feedback.playlistName)

    is AddToPlaylistFeedback.AddFailed -> resources.getString(
        CoreCommonR.string.playlist_add_to_failed_format,
        feedback.detail?.takeIf { it.isNotBlank() }
            ?: resources.getString(CoreCommonR.string.playlist_add_to_failed_generic)
    )

    is AddToPlaylistFeedback.SearchFailed -> resources.getString(
        CoreCommonR.string.playlist_add_to_search_failed_format,
        addToPlaylistPlatformName(resources, feedback.platform),
        feedback.detail?.takeIf { it.isNotBlank() }
            ?: resources.getString(CoreCommonR.string.playlist_add_to_failed_generic)
    )

    is AddToPlaylistFeedback.NoMatch -> resources.getString(
        CoreCommonR.string.playlist_add_to_no_match_format,
        addToPlaylistPlatformName(resources, feedback.platform)
    )

    is AddToPlaylistFeedback.Rejected ->
        addToPlaylistFailureMessage(resources, feedback.failure)

    is AddToPlaylistFeedback.TargetUnavailable -> resources.getString(
        CoreCommonR.string.playlist_add_to_target_unavailable_format,
        addToPlaylistPlatformName(resources, feedback.platform)
    )
}
