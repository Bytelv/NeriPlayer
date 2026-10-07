package moe.ouom.neriplayer.data.model.music

import kotlinx.serialization.Serializable

@Serializable
data class SongSearchInfo(
    val id: String,
    val songName: String,
    val singer: String,
    val duration: String,
    val source: MusicPlatform,
    val albumName: String?,
    val coverUrl: String?,
    /**
     * 酷狗写歌单需要的专辑 id (`AlbumID`)
     *
     * 该接口的 `songs` 元素要求 `hash`/`name`/`album_id`/`mixsongid` 四项, 缺项时
     * 上游会回"歌曲列表不能为空", 因此搜索结果要把它带出来。其它平台为 null。
     */
    val albumId: String? = null,
    /** 酷狗写歌单需要的 `mixsongid` (注意: 该后端要求**字符串**) */
    val mixSongId: String? = null
)
