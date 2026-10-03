package moe.ouom.neriplayer.ui.screen.tab.library

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
 * File: moe.ouom.neriplayer.ui.screen.tab.library/LibraryScreenKugouContent
 */

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.tab.KugouPlaylist
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

/**
 * 媒体库的「酷狗」标签页
 *
 * 结构与 [YouTubeMusicPlaylistList] / [BiliPlaylistList] 一致: 首次加载显示
 * 进度, 失败显示错误与重试, 空列表给出登录提示; 有下一页时在列表尾部提供
 * "加载更多" (该接口是分页的, 没有一次拉全量的写法)。
 */
@Composable
internal fun KugouPlaylistList(
    playlists: List<KugouPlaylist>,
    error: String?,
    loading: Boolean,
    loadingMore: Boolean,
    hasMore: Boolean,
    listState: LazyListState,
    onClick: (KugouPlaylist) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    offlineMode: Boolean
) {
    val context = LocalContext.current
    val miniPlayerHeight = LocalMiniPlayerHeight.current

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(
            start = 8.dp,
            end = 8.dp,
            top = 8.dp,
            bottom = 8.dp + miniPlayerHeight
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        val cardShape = RoundedCornerShape(12.dp)

        if (playlists.isEmpty()) {
            item(key = "kugou_playlist_state") {
                Card(
                    shape = cardShape,
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clip(cardShape)
                ) {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = when {
                                    error != null -> error
                                    loading -> stringResource(CoreCommonR.string.library_kugou_loading)
                                    else -> stringResource(CoreCommonR.string.library_kugou_empty)
                                },
                                color = if (error != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    Color.Unspecified
                                }
                            )
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = stringResource(CoreCommonR.string.library_kugou_hint),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (error != null) {
                                    HapticTextButton(onClick = onRetry) {
                                        Text(text = stringResource(CoreCommonR.string.action_retry))
                                    }
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            if (loading && error == null) {
                                Box(
                                    modifier = Modifier.size(56.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                }
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(56.dp)
                                )
                            }
                        }
                    )
                }
            }
        }

        items(
            items = playlists,
            key = { "${it.globalCollectionId}:${it.listId}" }
        ) { playlist ->
            val kindLabel = when {
                playlist.isDefaultPlaylist -> stringResource(CoreCommonR.string.library_kugou_liked)
                playlist.isCollected -> stringResource(CoreCommonR.string.library_kugou_collected)
                else -> stringResource(CoreCommonR.string.library_kugou_created)
            }
            Card(
                shape = cardShape,
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .animateItem()
                    .clip(cardShape)
                    .clickable { onClick(playlist) }
            ) {
                ListItem(
                    headlineContent = { Text(playlist.name) },
                    supportingContent = {
                        val countText = playlist.trackCount
                            .takeIf { it > 0 }
                            ?.let { count ->
                                pluralStringResource(
                                    CoreCommonR.plurals.library_song_count,
                                    count,
                                    count
                                )
                            }
                        Text(
                            text = listOfNotNull(
                                kindLabel,
                                playlist.creatorName.takeIf { it.isNotBlank() },
                                countText
                            ).distinct().joinToString(" · "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        if (playlist.coverUrl.isNotBlank()) {
                            AsyncImage(
                                model = offlineCachedImageRequest(
                                    context = context,
                                    data = playlist.coverUrl,
                                    sizePx = 192,
                                    allowHardware = false,
                                    offlineMode = offlineMode
                                ),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    }
                )
            }
        }

        if (playlists.isNotEmpty() && (hasMore || loadingMore)) {
            item(key = "kugou_playlist_load_more") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (loadingMore) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        HapticTextButton(onClick = onLoadMore) {
                            Text(text = stringResource(CoreCommonR.string.library_kugou_load_more))
                        }
                    }
                }
            }
        }
    }
}
