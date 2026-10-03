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
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.SongSourceTags
import moe.ouom.neriplayer.data.model.kugou.KugouPlaylistSummary
import moe.ouom.neriplayer.data.model.kugou.KugouSong
import moe.ouom.neriplayer.platform.kugou.repository.toKugouQueueSong
import moe.ouom.neriplayer.ui.viewmodel.tab.KugouPlaylist

/** 酷狗歌单详情页一次拉取的曲目数量 */
private const val KUGOU_SONG_PAGE_SIZE = 60

private const val TAG = "KugouPlaylistDetailViewModel"

/** 这些提示是内部常量而非文案资源: 界面层会按字符串映射到本地化文案 */
internal const val KUGOU_REMOVE_SUCCESS_MESSAGE = "kugou_remove_success"
internal const val KUGOU_REMOVE_FAILED_MESSAGE = "kugou_remove_failed"
internal const val KUGOU_REMOVE_MISSING_ID_MESSAGE = "kugou_remove_missing_id"

/** 分页拼接时用于去重: 优先稳定身份, 退回 hash */
private fun KugouSong.sourceStableKeyOrHash(): String = "${SongSourceTags.KUGOU}:$hash"

private fun SongItem.stableKeyOrName(): String = sourceStableKey ?: name

data class KugouPlaylistDetailUiState(
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val playlist: KugouPlaylist? = null,
    val songs: List<SongItem> = emptyList(),
    /**
     * 与 [songs] 一一对应的原始酷狗曲目
     *
     * 界面展示用 [songs], 但"移出歌单"需要 `fileid`, 而 `SongItem` 不带这个字段,
     * 因此在这里保留原始模型 (顺序与 [songs] 严格一致)。
     */
    val rawSongs: List<KugouSong> = emptyList(),
    val hasMore: Boolean = false,
    val total: Int = 0,
    /** 正在移出的曲目名, 用于禁用菜单并给出进行中提示 */
    val removingSongName: String? = null,
    /** 一次性提示 (移出成功/失败) */
    val message: String? = null
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
                    rawSongs = if (reset) {
                        songPage.songs
                    } else {
                        (previous.rawSongs + songPage.songs)
                            .distinctBy { it.sourceStableKeyOrHash() }
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
     * 把曲目移出当前酷狗歌单
     *
     * `fileid` 由歌单接口下发, 只能通过 [KugouPlaylistDetailUiState.rawSongs] 取到;
     * 成功后本地先移除, 再重新拉第一页把 `total` 刷新成服务端的真实值
     * (否则"移出后曲目数不变")。
     */
    fun removeSong(song: SongItem) {
        val state = _uiState.value
        if (state.removingSongName != null) return
        val index = state.songs.indexOfFirst { it.stableKeyOrName() == song.stableKeyOrName() }
        if (index < 0) return
        val raw = state.rawSongs.getOrNull(index)
        val fileId = raw?.fileId?.trim().orEmpty()
        val listId = currentPlaylist?.listId.orEmpty()

        if (fileId.isEmpty() || listId.isEmpty()) {
            _uiState.value = state.copy(
                message = KUGOU_REMOVE_MISSING_ID_MESSAGE
            )
            return
        }

        _uiState.value = state.copy(removingSongName = song.name, message = null)
        viewModelScope.launch {
            val removed = try {
                withContext(Dispatchers.IO) {
                    repository.removeSongsFromPlaylist(listId = listId, fileIds = listOf(fileId))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NPLogger.w(TAG, "酷狗移出歌单异常: ${error.message.orEmpty()}")
                false
            }

            val current = _uiState.value
            if (!removed) {
                _uiState.value = current.copy(
                    removingSongName = null,
                    message = KUGOU_REMOVE_FAILED_MESSAGE
                )
                return@launch
            }

            // 本地立即移除, 界面不用等服务端
            val remainingSongs = current.songs.filterIndexed { i, _ -> i != index }
            val remainingRaw = current.rawSongs.filterIndexed { i, _ -> i != index }
            _uiState.value = current.copy(
                removingSongName = null,
                songs = remainingSongs,
                rawSongs = remainingRaw,
                total = (current.total - 1).coerceAtLeast(0),
                message = KUGOU_REMOVE_SUCCESS_MESSAGE
            )
            // 重新拉第一页, 让曲目数与服务端对齐, 并补上因移除而空出的位置
            reloadFirstPage()
        }
    }

    fun consumeMessage() {
        if (_uiState.value.message != null) {
            _uiState.value = _uiState.value.copy(message = null)
        }
    }

    /** 静默刷新第一页: 不清空列表, 只把首屏数据与总数替换成最新的 */
    private fun reloadFirstPage() {
        val playlist = currentPlaylist ?: return
        viewModelScope.launch {
            try {
                val songPage = withContext(Dispatchers.IO) {
                    repository.fetchPlaylistSongs(
                        globalCollectionId = playlist.globalCollectionId,
                        listId = playlist.listId,
                        page = 1,
                        pageSize = KUGOU_SONG_PAGE_SIZE
                    )
                }
                val previous = _uiState.value
                _uiState.value = previous.copy(
                    songs = songPage.songs.map { it.toKugouQueueSong() },
                    rawSongs = songPage.songs,
                    hasMore = songPage.hasMore,
                    total = songPage.total
                )
                loadedPage = 1
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 刷新失败不影响已经完成的移除
                NPLogger.w(TAG, "酷狗歌单刷新失败: ${error.message.orEmpty()}")
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
