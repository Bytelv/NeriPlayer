package moe.ouom.neriplayer.ui.viewmodel.playlist

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
 * File: moe.ouom.neriplayer.ui.viewmodel.playlist/KugouPlaylistDetailViewModel
 */

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.kugou.KugouPlaylistSummary
import moe.ouom.neriplayer.platform.kugou.repository.toKugouQueueSong
import moe.ouom.neriplayer.ui.viewmodel.tab.KugouPlaylist

/** 酷狗歌单详情页一次拉取的曲目数量 */
private const val KUGOU_SONG_PAGE_SIZE = 60

data class KugouPlaylistDetailUiState(
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val playlist: KugouPlaylist? = null,
    val songs: List<SongItem> = emptyList(),
    val hasMore: Boolean = false,
    val total: Int = 0
)

/**
 * 酷狗歌单详情
 *
 * 曲目接口是分页的 (`/playlist/track/all`), 因此保留 [loadMore] 供列表尾部
 * 继续追加; 歌单元数据用 `/playlist/detail` 尽力补齐 (失败时退回列表页数据)。
 */
class KugouPlaylistDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppContainer.kugouPlaylistRepository

    private val _uiState = MutableStateFlow(KugouPlaylistDetailUiState())
    val uiState: StateFlow<KugouPlaylistDetailUiState> = _uiState

    private var currentPlaylist: KugouPlaylist? = null
    private var loadedPage = 1
    private var loadJob: Job? = null

    fun start(playlist: KugouPlaylist) {
        val current = currentPlaylist
        val samePlaylist = current != null &&
            current.listId == playlist.listId &&
            current.globalCollectionId == playlist.globalCollectionId
        // 同一个歌单只在失败后重新进入时才重新拉取, 避免返回时把已加载的列表清空
        if (samePlaylist && _uiState.value.error == null) return

        currentPlaylist = playlist
        loadedPage = 1
        _uiState.value = KugouPlaylistDetailUiState(loading = true, playlist = playlist)
        load(page = 1)
    }

    fun retry() {
        if (currentPlaylist == null) return
        loadedPage = 1
        _uiState.value = _uiState.value.copy(
            loading = true,
            loadingMore = false,
            error = null,
            songs = emptyList(),
            hasMore = false
        )
        load(page = 1)
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.loading || state.loadingMore || !state.hasMore) return
        _uiState.value = state.copy(loadingMore = true)
        load(page = loadedPage + 1)
    }

    private fun load(page: Int) {
        val playlist = currentPlaylist ?: return
        val reset = page <= 1
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) {
                    repository.fetchPlaylistDetail(playlist.globalCollectionId)
                }
                val songPage = withContext(Dispatchers.IO) {
                    repository.fetchPlaylistSongs(
                        globalCollectionId = playlist.globalCollectionId,
                        listId = playlist.listId,
                        page = page,
                        pageSize = KUGOU_SONG_PAGE_SIZE
                    )
                }
                loadedPage = page
                val mapped = songPage.songs.map { it.toKugouQueueSong() }
                val previous = _uiState.value
                _uiState.value = previous.copy(
                    loading = false,
                    loadingMore = false,
                    error = null,
                    playlist = mergePlaylist(previous.playlist ?: playlist, detail),
                    songs = if (reset) {
                        mapped
                    } else {
                        (previous.songs + mapped).distinctBy { it.sourceStableKey ?: it.name }
                    },
                    hasMore = songPage.hasMore,
                    total = songPage.total
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    loadingMore = false,
                    error = error.message ?: error.javaClass.simpleName
                )
            }
        }
    }

    /**
     * `/playlist/detail` 返回的元数据优先, 缺失字段保留列表页传来的值
     */
    private fun mergePlaylist(
        base: KugouPlaylist,
        detail: KugouPlaylistSummary?
    ): KugouPlaylist {
        if (detail == null) return base
        return base.copy(
            name = detail.name.ifBlank { base.name },
            coverUrl = detail.coverUrl.orEmpty().ifBlank { base.coverUrl },
            creatorName = detail.creatorName.orEmpty().ifBlank { base.creatorName },
            trackCount = detail.trackCount.takeIf { it > 0 } ?: base.trackCount,
            intro = detail.intro.orEmpty().ifBlank { base.intro },
            globalCollectionId = detail.globalCollectionId.orEmpty()
                .ifBlank { base.globalCollectionId },
            listId = detail.listId.orEmpty().ifBlank { base.listId }
        )
    }
}
