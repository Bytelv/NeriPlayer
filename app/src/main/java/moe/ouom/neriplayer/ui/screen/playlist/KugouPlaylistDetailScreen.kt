package moe.ouom.neriplayer.ui.screen.playlist

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
 * File: moe.ouom.neriplayer.ui.screen.playlist/KugouPlaylistDetailScreen
 */

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import moe.ouom.neriplayer.ui.haptic.HapticFilledIconButton
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.viewmodel.playlist.KugouPlaylistDetailViewModel
import moe.ouom.neriplayer.ui.viewmodel.tab.KugouPlaylist
import moe.ouom.neriplayer.util.format.formatDuration
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

private const val KUGOU_LOADING_KEY = "kugou_playlist_loading"
private const val KUGOU_ERROR_KEY = "kugou_playlist_error"
private const val KUGOU_EMPTY_KEY = "kugou_playlist_empty"
private const val KUGOU_LOAD_MORE_KEY = "kugou_playlist_load_more"

/**
 * 酷狗歌单详情
 *
 * 结构与 `YouTubeMusicPlaylistDetailScreen` / `BiliPlaylistDetailScreen` 一致:
 * 顶部 hero + 操作条, 下面是曲目列表。曲目接口分页, 列表尾部可以继续加载;
 * 「全部播放」把当前已加载的曲目交给宿主的播放回调, 「加入队列」逐首插到队尾。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun KugouPlaylistDetailScreen(
    playlist: KugouPlaylist,
    onBack: () -> Unit = {},
    onSongClick: (List<SongItem>, Int) -> Unit = { _, _ -> },
    offlineMode: Boolean = false
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val viewModel: KugouPlaylistDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                KugouPlaylistDetailViewModel(context.applicationContext as Application)
            }
        }
    )
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val resolvedPlaylist = ui.playlist ?: playlist
    val songs = ui.songs
    val currentSong by PlayerManager.currentSongFlow.collectAsStateWithLifecycle()
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val listState = rememberLazyListState()

    LaunchedEffect(playlist) {
        viewModel.start(playlist)
    }

    BackHandler { onBack() }

    val songCount = ui.total.takeIf { it > 0 } ?: resolvedPlaylist.trackCount
    val countText = songCount.takeIf { it > 0 }?.let { count ->
        pluralStringResource(CoreCommonR.plurals.library_song_count, count, count)
    }
    val heroSubtitle = listOfNotNull(
        resolvedPlaylist.creatorName.takeIf { it.isNotBlank() },
        countText
    ).joinToString(" · ")

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = resolvedPlaylist.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    HapticIconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(CoreCommonR.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = miniPlayerHeight + 24.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            item(key = PLAYLIST_HEADER_KEY) {
                PlaylistModernHeroHeader(
                    displayName = resolvedPlaylist.name,
                    coverUrl = resolvedPlaylist.coverUrl.ifBlank { null },
                    subtitle = heroSubtitle,
                    offlineMode = offlineMode,
                    height = PlaylistModernHeroHeight,
                    coverContentDescription = resolvedPlaylist.name
                )
            }

            item(key = PLAYLIST_ACTIONS_KEY) {
                PlaylistModernActionSheet(
                    coverUrl = resolvedPlaylist.coverUrl.ifBlank { null },
                    offlineMode = offlineMode
                ) {
                    KugouPlaylistActionsRow(
                        songCount = songs.size,
                        onPlayAll = {
                            if (songs.isNotEmpty()) onSongClick(songs, 0)
                        },
                        onAddAllToQueue = {
                            songs.forEach { PlayerManager.addToQueueEnd(it) }
                            AppFeedback.show(
                                context = context,
                                message = resources.getString(CoreCommonR.string.kugou_added_to_queue)
                            )
                        }
                    )
                }
            }

            when {
                ui.loading && songs.isEmpty() -> {
                    item(key = KUGOU_LOADING_KEY) {
                        KugouPlaylistStateSurface(
                            coverUrl = resolvedPlaylist.coverUrl,
                            offlineMode = offlineMode
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }

                ui.error != null && songs.isEmpty() -> {
                    item(key = KUGOU_ERROR_KEY) {
                        KugouPlaylistStateSurface(
                            coverUrl = resolvedPlaylist.coverUrl,
                            offlineMode = offlineMode
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = ui.error.orEmpty(),
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center
                                )
                                HapticTextButton(onClick = viewModel::retry) {
                                    Text(text = stringResource(CoreCommonR.string.action_retry))
                                }
                            }
                        }
                    }
                }

                songs.isEmpty() -> {
                    item(key = KUGOU_EMPTY_KEY) {
                        KugouPlaylistStateSurface(
                            coverUrl = resolvedPlaylist.coverUrl,
                            offlineMode = offlineMode
                        ) {
                            Text(
                                text = stringResource(CoreCommonR.string.kugou_playlist_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                else -> {
                    itemsIndexed(
                        items = songs,
                        key = { index, song ->
                            "${song.sourceStableKey ?: song.name}#$index"
                        }
                    ) { index, song ->
                        PlaylistModernListItemSurface(
                            coverUrl = resolvedPlaylist.coverUrl,
                            offlineMode = offlineMode
                        ) {
                            KugouSongRow(
                                index = index + 1,
                                song = song,
                                isCurrentSong = currentSong?.sourceStableKey == song.sourceStableKey,
                                offlineMode = offlineMode,
                                onClick = { onSongClick(songs, index) },
                                onPlayNext = { PlayerManager.addToQueueNext(song) },
                                onAddToQueueEnd = { PlayerManager.addToQueueEnd(song) }
                            )
                        }
                    }
                }
            }

            if (songs.isNotEmpty() && (ui.hasMore || ui.loadingMore)) {
                item(key = KUGOU_LOAD_MORE_KEY) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (ui.loadingMore) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            HapticTextButton(onClick = viewModel::loadMore) {
                                Text(text = stringResource(CoreCommonR.string.library_kugou_load_more))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KugouPlaylistActionsRow(
    songCount: Int,
    onPlayAll: () -> Unit,
    onAddAllToQueue: () -> Unit
) {
    val canPlay = songCount > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, top = 8.dp, end = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HapticFilledIconButton(
            onClick = onPlayAll,
            enabled = canPlay,
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = stringResource(CoreCommonR.string.player_play_all)
            )
        }
        Text(
            text = stringResource(CoreCommonR.string.player_play_all),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        HapticTextButton(
            onClick = onAddAllToQueue,
            enabled = canPlay
        ) {
            Text(text = stringResource(CoreCommonR.string.kugou_add_to_queue))
        }
    }
}

/** 加载 / 错误 / 空状态共用的一块面板, 背景色跟随封面 */
@Composable
private fun KugouPlaylistStateSurface(
    coverUrl: String,
    offlineMode: Boolean,
    content: @Composable () -> Unit
) {
    PlaylistModernListItemSurface(
        coverUrl = coverUrl,
        offlineMode = offlineMode
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KugouSongRow(
    index: Int,
    song: SongItem,
    isCurrentSong: Boolean,
    offlineMode: Boolean,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueueEnd: () -> Unit
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    val rowColor = if (isCurrentSong) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(rowColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuExpanded = true }
            )
            .padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = index.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(24.dp)
        )
        AsyncImage(
            model = offlineCachedImageRequest(
                context = context,
                data = song.coverUrl,
                sizePx = 128,
                allowHardware = false,
                offlineMode = offlineMode
            ),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.displayName(),
                color = if (isCurrentSong) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Unspecified
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.displayArtist(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = formatDuration(song.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            HapticIconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(CoreCommonR.string.cd_more)
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(CoreCommonR.string.local_playlist_play_next)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onPlayNext()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(CoreCommonR.string.playlist_add_to_end)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.PlaylistAdd,
                            contentDescription = null
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onAddToQueueEnd()
                    }
                )
            }
        }
    }
}
