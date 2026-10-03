package moe.ouom.neriplayer.ui.viewmodel.tab

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
 * File: moe.ouom.neriplayer.ui.viewmodel.tab/LibraryViewModel
 * Created: 2025/8/11
 */

import moe.ouom.neriplayer.platform.youtube.api.auth.hasSavedAuthMaterial
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.bilibili.collection.FavFolder
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicLibraryPlaylist
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.platform.youtube.auth.buildRefreshObserverFingerprint
import moe.ouom.neriplayer.platform.youtube.config.YouTubeFeatureGate
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylistDeleteResult
import moe.ouom.neriplayer.data.local.playlist.runLocalPlaylistMutationSafely
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.common.logging.NPLogger
import org.json.JSONObject
import java.io.IOException

private const val BILI_DETAIL_BATCH_SIZE = 6
private const val BILI_RESOURCE_TYPE_COLLECTION = 21

/** 酷狗歌单分页大小, 与仓库默认值一致 */
private const val KUGOU_PLAYLIST_PAGE_SIZE = 30

/** 媒体库页面 UI 状态 */
data class LibraryUiState(
    val localPlaylists: List<LocalPlaylist> = emptyList(),
    val neteasePlaylists: List<PlaylistSummary> = emptyList(),
    val neteaseAlbums: List<AlbumSummary> = emptyList(),
    val neteaseError: String? = null,
    val youtubeMusicPlaylists: List<YouTubeMusicPlaylist> = emptyList(),
    val youtubeMusicError: String? = null,
    val biliPlaylists: List<BiliPlaylist> = emptyList(),
    val biliError: String? = null,
    val kugouPlaylists: List<KugouPlaylist> = emptyList(),
    val kugouError: String? = null,
    val kugouLoading: Boolean = false,
    val kugouLoadingMore: Boolean = false,
    val kugouHasMore: Boolean = false,
    /** 酷狗歌单只在登录后可见, 媒体库据此决定是否显示该标签页 */
    val kugouLoggedIn: Boolean = false
)

@Suppress("unused")
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val localRepo = LocalPlaylistRepository.getInstance(application)

    private val neteaseCookieRepo = AppContainer.neteaseCookieRepo
    private val neteaseClient = AppContainer.neteaseClient

    private val biliCookieRepo = AppContainer.biliCookieRepo
    private val biliClient = AppContainer.biliClient
    private val youtubeAuthRepo = AppContainer.youtubeAuthRepo

    private val kugouSessionRepo = AppContainer.kugouSessionRepo
    private val kugouPlaylistRepo = AppContainer.kugouPlaylistRepository


    private val _uiState = MutableStateFlow(
        LibraryUiState(localPlaylists = localRepo.playlists.value)
    )
    val uiState: StateFlow<LibraryUiState> = _uiState
    private var lastYouTubeAuthFingerprint: String? = null
    private var youtubeMusicPlaylistsJob: Job? = null
    private var youtubeMusicPlaylistsPending = false
    private var youtubeEnabled = YouTubeFeatureGate.isEnabled()
    private var lastObservedYouTubeEnabled: Boolean? = null
    private var kugouPlaylistsJob: Job? = null
    private var kugouPage = 1
    private var lastKugouLoggedIn: Boolean? = null

    init {
        // 本地歌单
        viewModelScope.launch {
            if (!localRepo.awaitInitialized()) return@launch
            localRepo.playlists.collect { list ->
                _uiState.value = _uiState.value.copy(localPlaylists = list)
            }
        }

        // 网易云 歌单
        viewModelScope.launch {
            neteaseCookieRepo.cookieFlow.collect { cookies ->
                val mutable = cookies.toMutableMap()
                mutable.putIfAbsent("os", "pc")
                if (!cookies["MUSIC_U"].isNullOrBlank()) {
                    refreshNeteasePlaylists()
                } else {
                    _uiState.value = _uiState.value.copy(
                        neteasePlaylists = emptyList(),
                        neteaseError = null
                    )
                }
            }
        }
        // 网易云 专辑
        viewModelScope.launch {
            neteaseCookieRepo.cookieFlow.collect { cookies ->
                val mutable = cookies.toMutableMap()
                mutable.putIfAbsent("os", "pc")
                if (!cookies["MUSIC_U"].isNullOrBlank()) {
                    refreshNeteaseAlbums()
                } else {
                    _uiState.value = _uiState.value.copy(
                        neteaseAlbums = emptyList(),
                        neteaseError = null
                    )
                }
            }
        }

        // YouTube Music
        viewModelScope.launch {
            combine(
                youtubeAuthRepo.authFlow,
                AppContainer.settingsRepo.youtubeEnabledFlow
            ) { bundle, enabled ->
                bundle to enabled
            }.collect { (bundle, enabled) ->
                youtubeEnabled = enabled
                val nextFingerprint = bundle.buildRefreshObserverFingerprint()
                val authChanged = nextFingerprint != lastYouTubeAuthFingerprint
                val enabledChanged = enabled != lastObservedYouTubeEnabled
                if (!authChanged && !enabledChanged) {
                    return@collect
                }
                lastYouTubeAuthFingerprint = nextFingerprint
                lastObservedYouTubeEnabled = enabled
                if (!enabled || !bundle.hasYouTubeMusicCookieContext()) {
                    youtubeMusicPlaylistsJob?.cancel()
                    youtubeMusicPlaylistsJob = null
                    youtubeMusicPlaylistsPending = false
                    _uiState.value = _uiState.value.copy(
                        youtubeMusicPlaylists = emptyList(),
                        youtubeMusicError = null
                    )
                } else {
                    refreshYouTubeMusicPlaylists()
                }
            }
        }

        // Bilibili
        viewModelScope.launch {
            biliCookieRepo.cookieFlow.collect { cookies ->
                if (!cookies["SESSDATA"].isNullOrBlank()) {
                    refreshBilibili()
                } else {
                    _uiState.value = _uiState.value.copy(
                        biliPlaylists = emptyList(),
                        biliError = null
                    )
                }
            }
        }

        // 酷狗: 只有登录后才有歌单, 登出时清空, 避免残留上一个账号的数据
        viewModelScope.launch {
            kugouSessionRepo.sessionFlow.collect { session ->
                val loggedIn = session.isLoggedIn()
                val loggedInChanged = loggedIn != lastKugouLoggedIn
                lastKugouLoggedIn = loggedIn
                _uiState.value = _uiState.value.copy(kugouLoggedIn = loggedIn)
                if (!loggedIn) {
                    kugouPlaylistsJob?.cancel()
                    kugouPlaylistsJob = null
                    kugouPage = 1
                    _uiState.value = _uiState.value.copy(
                        kugouPlaylists = emptyList(),
                        kugouError = null,
                        kugouLoading = false,
                        kugouLoadingMore = false,
                        kugouHasMore = false
                    )
                } else if (loggedInChanged) {
                    refreshKugouPlaylists()
                }
            }
        }
    }

    fun refreshBilibili() {
        viewModelScope.launch {
            try {
                val mid = biliCookieRepo.getCookiesOnce()["DedeUserID"]?.toLongOrNull() ?: 0L
                if (mid == 0L) {
                    _uiState.value = _uiState.value.copy(biliError = getApplication<Application>().getString(CoreCommonR.string.error_get_user_id))
                    return@launch
                }
                val mapped = withContext(Dispatchers.IO) {
                    val created = async { biliClient.getUserCreatedFavFolders(mid) }
                    val collected = async {
                        runCatching { biliClient.getUserCollectedFavFolders(mid) }
                            .onFailure { error ->
                                NPLogger.w(
                                    "LibraryViewModel-Bili",
                                    "Failed to fetch collected fav folders",
                                    error
                                )
                            }
                            .getOrDefault(emptyList())
                    }

                    val createdPlaylists = mapBiliFolders(
                        folders = created.await(),
                        kind = BiliPlaylistKind.CREATED_FAVORITE,
                        currentMid = mid
                    )
                    val collectedPlaylists = mapBiliFolders(
                        folders = collected.await(),
                        kind = BiliPlaylistKind.COLLECTED_FAVORITE,
                        currentMid = mid
                    )

                    (createdPlaylists + collectedPlaylists)
                        .filter { it.mediaId != 0L && it.title.isNotBlank() }
                        .distinctBy { "${it.kind}:${it.mediaId}" }
                }

                NPLogger.d("LibraryViewModel-Bili", mapped)

                _uiState.value = _uiState.value.copy(biliPlaylists = mapped, biliError = null)
            } catch (e: IOException) {
                _uiState.value = _uiState.value.copy(biliError = e.message)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(biliError = e.message)
            }
        }
    }

    private suspend fun mapBiliFolders(
        folders: List<FavFolder>,
        kind: BiliPlaylistKind,
        currentMid: Long
    ): List<BiliPlaylist> = coroutineScope {
        folders
            .filter { it.state == 0 && it.mediaId != 0L }
            .chunked(BILI_DETAIL_BATCH_SIZE)
            .flatMap { batch ->
                batch.map { folder ->
                    async(Dispatchers.IO) {
                        mapBiliFolder(folder, kind, currentMid)
                    }
                }.awaitAll()
            }
            .filterNotNull()
    }

    private suspend fun mapBiliFolder(
        folder: FavFolder,
        kind: BiliPlaylistKind,
        currentMid: Long
    ): BiliPlaylist? {
        val resolvedKind = when {
            folder.itemType == BILI_RESOURCE_TYPE_COLLECTION -> BiliPlaylistKind.COLLECTION
            else -> kind
        }

        val detail = if (resolvedKind == BiliPlaylistKind.COLLECTION) {
            null
        } else {
            runCatching { biliClient.getFavFolderInfo(folder.mediaId) }
                .onFailure { error ->
                    NPLogger.e(
                        "LibraryViewModel-Bili",
                        getApplication<Application>().getString(CoreCommonR.string.music_get_detail_failed),
                        error
                    )
                }
                .getOrNull()
        }
        val source = detail ?: folder
        val ownerLabel = source.upperName.ifBlank {
            if (source.mid != 0L && source.mid != currentMid) source.mid.toString() else ""
        }

        return BiliPlaylist(
            mediaId = source.mediaId,
            fid = source.fid,
            mid = source.mid,
            title = source.title.takeIf { it.isNotBlank() } ?: return null,
            count = source.count,
            coverUrl = source.coverUrl.replace("http://", "https://"),
            kind = resolvedKind,
            subtitle = ownerLabel
        )
    }


    fun refreshNeteasePlaylists() {
        viewModelScope.launch {
            try {
                val uid = withContext(Dispatchers.IO) { neteaseClient.getCurrentUserId() }
                val raw = withContext(Dispatchers.IO) { neteaseClient.getUserPlaylists(uid) }
                val mapped = parseNeteasePlaylists(raw)
                _uiState.value = _uiState.value.copy(
                    neteasePlaylists = mapped,
                    neteaseError = null
                )
            } catch (e: IOException) {
                _uiState.value = _uiState.value.copy(neteaseError = e.message)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(neteaseError = e.message)
            }
        }
    }
    
    fun refreshNeteaseAlbums() {
        viewModelScope.launch {
            try {
                val uid = withContext(Dispatchers.IO) { neteaseClient.getCurrentUserId() }
                val raw = withContext(Dispatchers.IO) { neteaseClient.getUserStaredAlbums(uid) }
                val mapped = parseNeteaseAlbums(raw)
                _uiState.value = _uiState.value.copy(
                    neteaseAlbums = mapped,
                    neteaseError = null
                )
            } catch (e: IOException) {
                _uiState.value = _uiState.value.copy(neteaseError = e.message)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(neteaseError = e.message)
            }
        }
    }

    fun refreshYouTubeMusicPlaylists() {
        if (!youtubeEnabled) return
        val runningJob = youtubeMusicPlaylistsJob
        if (runningJob?.isActive == true) {
            youtubeMusicPlaylistsPending = true
            NPLogger.d("LibraryViewModel", "refreshYouTubeMusicPlaylists coalesced while loading")
            return
        }
        youtubeMusicPlaylistsPending = false
        youtubeMusicPlaylistsJob = viewModelScope.launch {
            try {
                val playlists = withContext(Dispatchers.IO) {
                    AppContainer.youtubeMusicClient.getLibraryPlaylists(
                        resolveMissingTrackCounts = false
                    )
                }
                _uiState.value = _uiState.value.copy(
                    youtubeMusicPlaylists = playlists.map(::mapYouTubeMusicPlaylist),
                    youtubeMusicError = null
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                _uiState.value = _uiState.value.copy(
                    youtubeMusicPlaylists = emptyList(),
                    youtubeMusicError = e.message
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    youtubeMusicPlaylists = emptyList(),
                    youtubeMusicError = e.message
                )
            } finally {
                val completedJob = coroutineContext[Job]
                if (youtubeMusicPlaylistsJob === completedJob) {
                    youtubeMusicPlaylistsJob = null
                    if (youtubeMusicPlaylistsPending && youtubeEnabled) {
                        youtubeMusicPlaylistsPending = false
                        refreshYouTubeMusicPlaylists()
                    }
                }
            }
        }
    }

    /**
     * 拉取酷狗"我的歌单"第一页
     *
     * 未登录时仓库会抛 `SessionRequired`, 这里把消息交给界面展示; 标签页本身
     * 只在登录后出现, 所以这条分支主要用于登录态失效的场景。
     */
    fun refreshKugouPlaylists() {
        if (!_uiState.value.kugouLoggedIn) return
        kugouPlaylistsJob?.cancel()
        kugouPage = 1
        _uiState.value = _uiState.value.copy(
            kugouLoading = true,
            kugouLoadingMore = false,
            kugouError = null
        )
        kugouPlaylistsJob = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    kugouPlaylistRepo.fetchUserPlaylists(
                        page = 1,
                        pageSize = KUGOU_PLAYLIST_PAGE_SIZE
                    )
                }
                kugouPage = 1
                _uiState.value = _uiState.value.copy(
                    kugouPlaylists = page.playlists
                        .filter { it.isPlayable() }
                        .map { it.toKugouPlaylist() },
                    kugouHasMore = page.hasMore,
                    kugouLoading = false,
                    kugouError = null
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    kugouPlaylists = emptyList(),
                    kugouHasMore = false,
                    kugouLoading = false,
                    kugouError = e.message
                )
            }
        }
    }

    /** 追加下一页歌单; 正在加载或没有更多时是空操作 */
    fun loadMoreKugouPlaylists() {
        val state = _uiState.value
        if (!state.kugouLoggedIn || state.kugouLoading || state.kugouLoadingMore) return
        if (!state.kugouHasMore) return
        val nextPage = kugouPage + 1
        _uiState.value = state.copy(kugouLoadingMore = true)
        kugouPlaylistsJob = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    kugouPlaylistRepo.fetchUserPlaylists(
                        page = nextPage,
                        pageSize = KUGOU_PLAYLIST_PAGE_SIZE
                    )
                }
                kugouPage = nextPage
                val existing = _uiState.value.kugouPlaylists
                val appended = page.playlists
                    .filter { it.isPlayable() }
                    .map { it.toKugouPlaylist() }
                _uiState.value = _uiState.value.copy(
                    kugouPlaylists = (existing + appended).distinctBy {
                        "${it.globalCollectionId}:${it.listId}"
                    },
                    kugouHasMore = page.hasMore,
                    kugouLoadingMore = false,
                    kugouError = null
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    kugouLoadingMore = false,
                    kugouError = e.message
                )
            }
        }
    }

    fun createLocalPlaylist(name: String) {
        launchPlaylistMutation("createLocalPlaylist") { localRepo.createPlaylist(name) }
    }

    fun addSongToFavorites(song: SongItem) {
        launchPlaylistMutation("addSongToFavorites") { localRepo.addToFavorites(song) }
    }

    fun renameLocalPlaylist(playlistId: Long, newName: String) {
        launchPlaylistMutation("renameLocalPlaylist") {
            localRepo.renamePlaylist(playlistId, newName)
        }
    }

    fun deleteLocalPlaylist(
        playlistId: Long,
        onResult: (Result<List<LocalPlaylistDeleteResult>>) -> Unit = {}
    ) {
        deleteLocalPlaylists(listOf(playlistId), onResult)
    }

    fun deleteLocalPlaylists(
        playlistIds: List<Long>,
        onResult: (Result<List<LocalPlaylistDeleteResult>>) -> Unit = {}
    ) {
        viewModelScope.launch {
            onResult(
                runLocalPlaylistMutationSafely("deleteLocalPlaylists") {
                    localRepo.deletePlaylistsWithResult(playlistIds)
                }
            )
        }
    }

    fun reorderLocalPlaylists(order: List<Long>) {
        launchPlaylistMutation("reorderLocalPlaylists") { localRepo.reorderPlaylists(order) }
    }

    private fun launchPlaylistMutation(
        operation: String,
        mutation: suspend () -> Unit
    ) {
        viewModelScope.launch {
            runLocalPlaylistMutationSafely(operation, mutation)
        }
    }

    private fun parseNeteasePlaylists(raw: String): List<PlaylistSummary> {
        val result = mutableListOf<PlaylistSummary>()
        val root = JSONObject(raw)
        if (root.optInt("code", -1) != 200) return emptyList()
        val arr = root.optJSONArray("playlist") ?: return emptyList()
        val size = arr.length()
        for (i in 0 until size) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optLong("id", 0L)
            val name = obj.optString("name", "")
            val cover = obj.optString("coverImgUrl", "").replaceFirst("http://", "https://")
            val playCount = obj.optLong("playCount", 0L)
            val trackCount = obj.optInt("trackCount", 0)
            if (id != 0L && name.isNotBlank()) {
                result.add(PlaylistSummary(id, name, cover, playCount, trackCount))
            }
        }
        return result
    }
    
    private fun parseNeteaseAlbums(raw: String): List<AlbumSummary> {
        val result = mutableListOf<AlbumSummary>()
        val root = JSONObject(raw)
        if (root.optInt("code", -1) != 200) return emptyList()
        val arr = root.optJSONArray("playlist") ?: return emptyList()
        val size = arr.length()
        for (i in 0 until size) {
            val obj = arr.optJSONObject(i)?.optJSONObject("dataInfo")?.optJSONObject("data") ?: continue
            val id = obj.optLong("id", 0L)
            val name = obj.optString("name", "")
            val cover = arr.optJSONObject(i)?.optJSONObject("dataInfo")?.optString("picUrl", "")?.replaceFirst("http://", "https://") ?: continue
            val songSize = obj.optInt("size", 0)
            if (id != 0L && name.isNotBlank()) {
                result.add(AlbumSummary(id, name, cover, songSize))
            }
        }
        return result
    }

    private fun moe.ouom.neriplayer.data.model.youtube.auth.YouTubeAuthBundle.hasYouTubeMusicCookieContext(): Boolean {
        return hasSavedAuthMaterial()
    }

    private fun mapYouTubeMusicPlaylist(
        playlist: YouTubeMusicLibraryPlaylist
    ): YouTubeMusicPlaylist {
        return YouTubeMusicPlaylist(
            browseId = playlist.browseId,
            playlistId = playlist.playlistId,
            title = playlist.title,
            subtitle = playlist.subtitle,
            coverUrl = playlist.coverUrl,
            trackCount = playlist.trackCount ?: 0
        )
    }
}
