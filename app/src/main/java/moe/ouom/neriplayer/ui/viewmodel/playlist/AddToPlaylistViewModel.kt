package moe.ouom.neriplayer.ui.viewmodel.playlist

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
 * File: moe.ouom.neriplayer.ui.viewmodel.playlist/AddToPlaylistViewModel
 */

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.SongSourceTags
import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthState
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherChannels
import moe.ouom.neriplayer.data.model.playlist.AddToPlaylistPlatform
import moe.ouom.neriplayer.data.model.playlist.AddToPlaylistTarget
import moe.ouom.neriplayer.platform.kugou.api.KugouApiException
import moe.ouom.neriplayer.platform.kugou.repository.KugouPlaylistRepository
import moe.ouom.neriplayer.platform.netease.playlist.addNeteasePlaylistSongIdsWithCode
import moe.ouom.neriplayer.platform.netease.playlist.parseNeteaseRemotePlaylists

private const val TAG = "AddToPlaylistViewModel"

/** 网易云 `/user/playlist` 一次取完: 与既有同步逻辑一致 */
private const val NETEASE_PLAYLIST_FETCH_LIMIT = 1000

/**
 * 单个平台的远端歌单状态
 *
 * [loggedIn] 为 false 时界面整组隐藏; [loaded] 为 false 且 [loading] 为 false
 * 表示"还没查过", 与"查过但是空"区分开, 便于只打一次网络。
 */
data class AddToPlaylistRemoteState(
    val loggedIn: Boolean = false,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val playlists: List<AddToPlaylistTarget> = emptyList()
) {
    val hasContent: Boolean get() = playlists.isNotEmpty()
}

data class AddToPlaylistUiState(
    val netease: AddToPlaylistRemoteState = AddToPlaylistRemoteState(),
    val kugou: AddToPlaylistRemoteState = AddToPlaylistRemoteState(),
    /** 正在提交的目标 [AddToPlaylistTarget.key], null 表示空闲 */
    val submittingKey: String? = null,
    /** 一次性反馈, 界面弹完调用 [AddToPlaylistViewModel.consumeFeedback] */
    val feedback: AddToPlaylistFeedback? = null
)

/** 一次"加到远端歌单"的结果, 由界面映射成本地化文案 */
sealed interface AddToPlaylistFeedback {
    data class Added(val playlistName: String) : AddToPlaylistFeedback

    /** 平台侧写接口失败 (网络异常或接口返回失败) */
    data class AddFailed(
        val platform: AddToPlaylistPlatform,
        val detail: String? = null
    ) : AddToPlaylistFeedback

    /** 目标平台搜索本身出错 */
    data class SearchFailed(
        val platform: AddToPlaylistPlatform,
        val detail: String? = null
    ) : AddToPlaylistFeedback

    /** 搜索过但没有匹配: 明确提示, 不提交 */
    data class NoMatch(val platform: AddToPlaylistPlatform) : AddToPlaylistFeedback

    /** 判定阶段就失败了, 没有发起任何写请求 */
    data class Rejected(val failure: AddToPlaylistFailure) : AddToPlaylistFeedback

    /** 目标歌单没有可用的远端 id */
    data class TargetUnavailable(val platform: AddToPlaylistPlatform) : AddToPlaylistFeedback
}

/**
 * "添加到歌单"弹窗的远端歌单加载与跨平台匹配添加
 *
 * 编排都在这里, 界面只负责渲染与把点击转成 [addCurrentSongToTarget]:
 * - 远端歌单懒加载一次 (ViewModel 存活期间不重复打网络), 失败后可重试
 * - 歌曲本就属于目标平台时直接添加, 否则先在目标平台搜索匹配
 * - 匹配不到 / id 不可用 / 搜索失败都返回明确结果, 绝不拿当前 id 硬提交
 */
class AddToPlaylistViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(AddToPlaylistUiState())
    val uiState: StateFlow<AddToPlaylistUiState> = _uiState.asStateFlow()

    private var remoteLoadJob: Job? = null

    /** 添加成功后的"静默刷新"任务, 避免同一平台重复并发刷新 */
    private var neteaseReloadJob: Job? = null
    private var kugouReloadJob: Job? = null

    /**
     * 弹窗打开时调用
     *
     * 每次只做本地登录态检查: 已登录且加载过的平台不会再打网络, 未登录的平台
     * 整组隐藏 (登录后再打开就能加载), 登出后不会留下上一次会话的歌单。
     */
    fun ensureRemotePlaylists() {
        val current = _uiState.value
        val neteaseLoggedIn = isNeteaseLoggedIn()
        val kugouLoggedIn = isKugouLoggedIn()

        val reset = current.copy(
            netease = if (!neteaseLoggedIn && current.netease != AddToPlaylistRemoteState()) {
                AddToPlaylistRemoteState()
            } else {
                current.netease
            },
            kugou = if (!kugouLoggedIn && current.kugou != AddToPlaylistRemoteState()) {
                AddToPlaylistRemoteState()
            } else {
                current.kugou
            }
        )
        if (reset != current) _uiState.value = reset

        val neteaseNeedsLoad = neteaseLoggedIn && !reset.netease.loaded
        val kugouNeedsLoad = kugouLoggedIn && !reset.kugou.loaded
        if (!neteaseNeedsLoad && !kugouNeedsLoad) return
        startRemoteLoad()
    }

    /** 用户点"重试", 或者想强制刷新 */
    fun refreshRemotePlaylists() {
        startRemoteLoad()
    }

    private fun startRemoteLoad() {
        if (remoteLoadJob?.isActive == true) return
        val neteaseLoggedIn = isNeteaseLoggedIn()
        val kugouLoggedIn = isKugouLoggedIn()

        _uiState.update { current ->
            current.copy(
                netease = if (neteaseLoggedIn) {
                    current.netease.copy(loggedIn = true, loading = true, error = null)
                } else {
                    AddToPlaylistRemoteState()
                },
                kugou = if (kugouLoggedIn) {
                    current.kugou.copy(loggedIn = true, loading = true, error = null)
                } else {
                    AddToPlaylistRemoteState()
                }
            )
        }

        if (!neteaseLoggedIn && !kugouLoggedIn) {
            NPLogger.d(TAG, "网易云与酷狗都未登录, 只展示本地歌单")
            return
        }

        remoteLoadJob = viewModelScope.launch {
            val jobs = buildList {
                if (neteaseLoggedIn) add(async { loadNeteasePlaylists() })
                if (kugouLoggedIn) add(async { loadKugouPlaylists() })
            }
            jobs.awaitAll()
        }
    }

    private suspend fun loadNeteasePlaylists() {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val client = AppContainer.neteaseClient
                if (!client.hasLogin()) {
                    throw IllegalStateException("网易云未登录")
                }
                runCatching { client.ensureWeapiSession() }.onFailure {
                    NPLogger.w(TAG, "ensureWeapiSession failed: ${it.message}")
                }
                val userId = client.getCurrentUserId()
                parseNeteaseRemotePlaylists(
                    raw = client.getUserPlaylists(userId, offset = 0, limit = NETEASE_PLAYLIST_FETCH_LIMIT),
                    ownerUserId = userId
                )
            }
        }

        updateNetease { current ->
            result.fold(
                onSuccess = { playlists ->
                    current.copy(
                        loggedIn = true,
                        loading = false,
                        loaded = true,
                        error = null,
                        playlists = playlists.map { playlist ->
                            AddToPlaylistTarget(
                                platform = AddToPlaylistPlatform.NETEASE,
                                id = playlist.id.toString(),
                                name = playlist.name,
                                trackCount = playlist.trackCount
                            )
                        }
                    )
                },
                onFailure = { error ->
                    NPLogger.w(TAG, "网易云歌单加载失败: ${error.message}")
                    current.copy(
                        loading = false,
                        loaded = true,
                        error = error.message ?: error.javaClass.simpleName
                    )
                }
            )
        }
    }

    private suspend fun loadKugouPlaylists() {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                AppContainer.kugouPlaylistRepository.fetchUserPlaylists(
                    page = 1,
                    pageSize = KugouPlaylistRepository.DEFAULT_PLAYLIST_PAGE_SIZE
                ).playlists
            }
        }

        updateKugou { current ->
            result.fold(
                onSuccess = { playlists ->
                    current.copy(
                        loggedIn = true,
                        loading = false,
                        loaded = true,
                        error = null,
                        playlists = playlists.mapNotNull { playlist ->
                            // 写歌单接口认 listid, 没有它的条目无法提交
                            val listId = playlist.listId?.trim().orEmpty()
                            if (listId.isEmpty()) return@mapNotNull null
                            AddToPlaylistTarget(
                                platform = AddToPlaylistPlatform.KUGOU,
                                id = listId,
                                name = playlist.name,
                                trackCount = playlist.trackCount
                            )
                        }
                    )
                },
                onFailure = { error ->
                    if (error is KugouApiException.SessionRequired) {
                        // 本地会话看着有效但服务端不认: 按未登录处理 (整组隐藏),
                        // 保留 loaded=false, 下次打开弹窗会再确认一次
                        NPLogger.w(TAG, "酷狗歌单需要登录: ${error.message}")
                        AddToPlaylistRemoteState()
                    } else {
                        NPLogger.w(TAG, "酷狗歌单加载失败: ${error.message}")
                        current.copy(
                            loading = false,
                            loaded = true,
                            error = error.message ?: error.javaClass.simpleName
                        )
                    }
                }
            )
        }
    }

    /**
     * 把当前播放的歌加到 [target]
     *
     * [onFinished] 在主线程回调, 无论成功失败都会调用 (界面据此收起弹窗并弹提示)。
     */
    fun addCurrentSongToTarget(
        song: SongItem?,
        target: AddToPlaylistTarget,
        onFinished: () -> Unit = {}
    ) {
        if (_uiState.value.submittingKey != null) return

        val context = song?.toAddToPlaylistSong()
        val plan = planAddToRemotePlaylist(context, target.platform)
        if (plan is AddToPlaylistResolution.Failed || context == null) {
            val failure = (plan as? AddToPlaylistResolution.Failed)?.failure
                ?: AddToPlaylistFailure.SONG_UNAVAILABLE
            _uiState.update { it.copy(feedback = AddToPlaylistFeedback.Rejected(failure)) }
            onFinished()
            return
        }

        _uiState.update { it.copy(submittingKey = target.key) }
        viewModelScope.launch {
            val feedback = try {
                execute(target = target, song = context)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NPLogger.w(TAG, "加到 ${target.platform} 歌单失败: ${error.message}")
                AddToPlaylistFeedback.AddFailed(target.platform, error.message)
            }
            _uiState.update { it.copy(submittingKey = null, feedback = feedback) }
            if (feedback is AddToPlaylistFeedback.Added) {
                // 曲目数变了: 静默刷新该平台的歌单列表, 否则弹窗里的数字是旧的
                refreshTargetPlatformSilently(target.platform)
            }
            onFinished()
        }
    }

    /**
     * 只刷新某个平台的歌单列表, 保留现有内容直到新数据回来
     *
     * 与 [refreshRemotePlaylists] 的区别: 不把列表置为 loading, 因此不会闪一下白屏
     * (用户刚添加成功, 此时整屏 loading 会显得像出了错)。
     */
    private fun refreshTargetPlatformSilently(platform: AddToPlaylistPlatform) {
        val job = when (platform) {
            AddToPlaylistPlatform.LOCAL -> return
            AddToPlaylistPlatform.NETEASE -> neteaseReloadJob
            AddToPlaylistPlatform.KUGOU -> kugouReloadJob
        }
        if (job?.isActive == true) return
        val newJob = viewModelScope.launch {
            runCatching {
                when (platform) {
                    AddToPlaylistPlatform.LOCAL -> Unit
                    AddToPlaylistPlatform.NETEASE -> loadNeteasePlaylists()
                    AddToPlaylistPlatform.KUGOU -> loadKugouPlaylists()
                }
            }.onFailure { error ->
                // 刷新失败不影响已经完成的添加
                NPLogger.w(TAG, "刷新 ${platform} 歌单失败: ${error.message.orEmpty()}")
            }
        }
        when (platform) {
            AddToPlaylistPlatform.LOCAL -> Unit
            AddToPlaylistPlatform.NETEASE -> neteaseReloadJob = newJob
            AddToPlaylistPlatform.KUGOU -> kugouReloadJob = newJob
        }
    }

    fun consumeFeedback() {
        _uiState.update { it.copy(feedback = null) }
    }

    private suspend fun execute(
        target: AddToPlaylistTarget,
        song: AddToPlaylistSong
    ): AddToPlaylistFeedback {
        val resolution = resolveAddToPlaylistOutcome(
            song = song,
            platform = target.platform,
            search = { query ->
                try {
                    searchOnPlatform(target.platform, query)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    NPLogger.w(TAG, "${target.platform} 搜索失败: ${error.message}")
                    PlatformSearchResult.Failed(error.message)
                }
            }
        )

        return when (resolution) {
            is AddToPlaylistResolution.NeteaseSongId ->
                addToNeteasePlaylist(target, resolution.songId, song)

            is AddToPlaylistResolution.KugouHash -> addToKugouPlaylist(
                target = target,
                title = resolution.title.ifBlank { song.name },
                artist = resolution.artist.ifBlank { song.artist },
                hash = resolution.hash
            )

            is AddToPlaylistResolution.NoMatch ->
                AddToPlaylistFeedback.NoMatch(resolution.platform)

            is AddToPlaylistResolution.SearchFailed ->
                AddToPlaylistFeedback.SearchFailed(resolution.platform, resolution.detail)

            is AddToPlaylistResolution.Failed ->
                AddToPlaylistFeedback.Rejected(resolution.failure)

            is AddToPlaylistResolution.NeedsPlatformSearch ->
                AddToPlaylistFeedback.Rejected(AddToPlaylistFailure.UNSUPPORTED_PLATFORM)
        }
    }

    private suspend fun searchOnPlatform(
        platform: AddToPlaylistPlatform,
        song: AddToPlaylistSong
    ): PlatformSearchResult {
        val musicPlatform = platform.toMusicPlatformOrNull()
            ?: return PlatformSearchResult.Failed(null)
        val candidate = withContext(Dispatchers.IO) {
            AppContainer.searchManager.findBestCandidateOnPlatform(
                platform = musicPlatform,
                songName = song.name,
                songArtist = song.artist,
                songDurationMs = song.durationMs,
                // 检索词带歌手: 只按歌名会搜不到原版(同名翻唱占满结果页)
                searchKeyword = song.searchKeyword()
            )
        }
        return if (candidate == null) {
            PlatformSearchResult.NotFound
        } else {
            PlatformSearchResult.Found(candidate)
        }
    }

    private suspend fun addToNeteasePlaylist(
        target: AddToPlaylistTarget,
        songId: Long,
        song: AddToPlaylistSong
    ): AddToPlaylistFeedback {
        val playlistId = target.id.toLongOrNull()?.takeIf { it > 0L }
            ?: return AddToPlaylistFeedback.TargetUnavailable(AddToPlaylistPlatform.NETEASE)

        val first = withContext(Dispatchers.IO) {
            addNeteasePlaylistSongIdsWithCode(
                client = AppContainer.neteaseClient,
                playlistId = playlistId,
                songIds = listOf(songId)
            )
        }
        if (first.success) return AddToPlaylistFeedback.Added(target.name)

        /*
         * 直连用的 song.id 不一定真是网易云可用的 songId
         * (下载/换源/历史等入口可能只带占位 id), 表现为"添加失败"。
         * 这里按歌名+歌手在网易云重新搜一次, 用搜到的真实 id 再试一遍。
         */
        NPLogger.w(
            TAG,
            "网易云直连加歌失败(code=${first.code}), 尝试按歌名搜索真实 songId: " +
                "song=${song.name}, artist=${song.artist}, id=$songId"
        )
        val searched = searchOnPlatform(AddToPlaylistPlatform.NETEASE, song)
        val searchedId = (searched as? PlatformSearchResult.Found)
            ?.candidate
            ?.id
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it > 0L && it != songId }

        if (searchedId == null) {
            return AddToPlaylistFeedback.AddFailed(
                platform = AddToPlaylistPlatform.NETEASE,
                detail = first.code?.let { "code=$it" }
            )
        }

        val second = withContext(Dispatchers.IO) {
            addNeteasePlaylistSongIdsWithCode(
                client = AppContainer.neteaseClient,
                playlistId = playlistId,
                songIds = listOf(searchedId)
            )
        }
        return if (second.success) {
            AddToPlaylistFeedback.Added(target.name)
        } else {
            AddToPlaylistFeedback.AddFailed(
                platform = AddToPlaylistPlatform.NETEASE,
                detail = second.code?.let { "code=$it" } ?: first.code?.let { "code=$it" }
            )
        }
    }

    private suspend fun addToKugouPlaylist(
        target: AddToPlaylistTarget,
        title: String,
        artist: String,
        hash: String
    ): AddToPlaylistFeedback {
        val listId = target.id.trim()
        if (listId.isEmpty()) {
            return AddToPlaylistFeedback.TargetUnavailable(AddToPlaylistPlatform.KUGOU)
        }
        val added = withContext(Dispatchers.IO) {
            AppContainer.kugouPlaylistRepository.addSongsToPlaylist(
                listId = listId,
                songs = listOf(buildKugouPlaylistAddSong(title = title, artist = artist, hash = hash))
            )
        }
        return if (added > 0) {
            AddToPlaylistFeedback.Added(target.name)
        } else {
            AddToPlaylistFeedback.AddFailed(AddToPlaylistPlatform.KUGOU)
        }
    }

    private fun isNeteaseLoggedIn(): Boolean =
        AppContainer.neteaseCookieRepo.getAuthHealthOnce().state != SavedCookieAuthState.Missing

    private fun isKugouLoggedIn(): Boolean =
        AppContainer.kugouSessionRepo.currentSession().isLoggedIn()

    private fun updateNetease(
        transform: (AddToPlaylistRemoteState) -> AddToPlaylistRemoteState
    ) {
        _uiState.update { it.copy(netease = transform(it.netease)) }
    }

    private fun updateKugou(
        transform: (AddToPlaylistRemoteState) -> AddToPlaylistRemoteState
    ) {
        _uiState.update { it.copy(kugou = transform(it.kugou)) }
    }
}

/**
 * `SongItem` → 匹配/提交用的纯数据
 *
 * 平台归属沿用播放器既有判定: 网易云看 `channelId`/`album` 标签, 酷狗用
 * [PlayerManager.isKugouTrack] (与播放、下载分派同一套口径)。
 */
internal fun SongItem.toAddToPlaylistSong(): AddToPlaylistSong = AddToPlaylistSong(
    name = name,
    artist = artist,
    durationMs = durationMs,
    isNeteaseSource = isNeteaseSourceSong(),
    neteaseSongId = id,
    isKugouSource = PlayerManager.isKugouTrack(this),
    kugouHash = audioId.orEmpty()
)

private fun SongItem.isNeteaseSourceSong(): Boolean =
    channelId.equals(ListenTogetherChannels.NETEASE, ignoreCase = true) ||
        album.startsWith(SongSourceTags.NETEASE, ignoreCase = true)
