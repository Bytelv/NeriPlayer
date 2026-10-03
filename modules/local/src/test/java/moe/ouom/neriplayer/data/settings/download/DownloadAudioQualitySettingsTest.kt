package moe.ouom.neriplayer.data.settings.download

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DownloadAudioQualitySettingsTest {

    @Test
    fun `saved Netease qualities retain all supported historical values`() {
        listOf("standard", "higher", "exhigh", "lossless", "hires", "jyeffect", "sky", "jymaster")
            .forEach { quality ->
                assertEquals(quality, normalizeDownloadNeteaseAudioQuality(quality))
                assertEquals(
                    quality,
                    normalizeDownloadNeteaseAudioQuality("  ${quality.uppercase(Locale.ROOT)}  ")
                )
            }
    }

    @Test
    fun `saved YouTube qualities retain all supported historical values`() {
        listOf("low", "medium", "high", "very_high").forEach { quality ->
            assertEquals(quality, normalizeDownloadYouTubeAudioQuality(quality))
            assertEquals(
                quality,
                normalizeDownloadYouTubeAudioQuality("  ${quality.uppercase(Locale.ROOT)}  ")
            )
        }
    }

    @Test
    fun `saved Bilibili qualities retain all supported historical values`() {
        listOf("low", "medium", "high", "lossless", "hires", "dolby").forEach { quality ->
            assertEquals(quality, normalizeDownloadBiliAudioQuality(quality))
            assertEquals(
                quality,
                normalizeDownloadBiliAudioQuality("  ${quality.uppercase(Locale.ROOT)}  ")
            )
        }
    }

    @Test
    fun `saved Kugou qualities retain all supported historical values`() {
        listOf("128", "320", "flac", "hires").forEach { quality ->
            assertEquals(quality, normalizeDownloadKugouAudioQuality(quality))
            assertEquals(
                quality,
                normalizeDownloadKugouAudioQuality("  ${quality.uppercase(Locale.ROOT)}  ")
            )
        }
    }

    @Test
    fun `missing blank and unknown qualities use each platform default`() {
        listOf(null, "", "  ", "future_quality", "very-high").forEach { value ->
            assertEquals(DEFAULT_DOWNLOAD_NETEASE_AUDIO_QUALITY, normalizeDownloadNeteaseAudioQuality(value))
            assertEquals(DEFAULT_DOWNLOAD_YOUTUBE_AUDIO_QUALITY, normalizeDownloadYouTubeAudioQuality(value))
            assertEquals(DEFAULT_DOWNLOAD_BILI_AUDIO_QUALITY, normalizeDownloadBiliAudioQuality(value))
            assertEquals(DEFAULT_DOWNLOAD_KUGOU_AUDIO_QUALITY, normalizeDownloadKugouAudioQuality(value))
        }
    }

    @Test
    fun `following playback quality ignores independent download values`() {
        val selection = resolveDownloadAudioQualitySelection(
            followsPlaybackQuality = true,
            playbackNeteaseQuality = "lossless",
            playbackYouTubeQuality = "very_high",
            playbackBiliQuality = "dolby",
            playbackKugouQuality = "flac",
            downloadNeteaseQuality = "standard",
            downloadYouTubeQuality = "low",
            downloadBiliQuality = "low",
            downloadKugouQuality = "128"
        )

        assertEquals("lossless", selection.neteaseQuality)
        assertEquals("very_high", selection.youtubeQuality)
        assertEquals("dolby", selection.biliQuality)
        assertEquals("flac", selection.kugouQuality)
    }

    @Test
    fun `independent download quality ignores playback values`() {
        val selection = resolveDownloadAudioQualitySelection(
            followsPlaybackQuality = false,
            playbackNeteaseQuality = "standard",
            playbackYouTubeQuality = "low",
            playbackBiliQuality = "low",
            playbackKugouQuality = "128",
            downloadNeteaseQuality = "hires",
            downloadYouTubeQuality = "high",
            downloadBiliQuality = "lossless",
            downloadKugouQuality = "hires"
        )

        assertEquals("hires", selection.neteaseQuality)
        assertEquals("high", selection.youtubeQuality)
        assertEquals("lossless", selection.biliQuality)
        assertEquals("hires", selection.kugouQuality)
    }

    @Test
    fun `invalid saved values fall back per platform`() {
        val selection = resolveDownloadAudioQualitySelection(
            followsPlaybackQuality = false,
            playbackNeteaseQuality = "ignored",
            playbackYouTubeQuality = "ignored",
            playbackBiliQuality = "ignored",
            playbackKugouQuality = "ignored",
            downloadNeteaseQuality = "unexpected",
            downloadYouTubeQuality = "  ",
            downloadBiliQuality = null,
            downloadKugouQuality = "unexpected"
        )

        assertEquals(DEFAULT_DOWNLOAD_NETEASE_AUDIO_QUALITY, selection.neteaseQuality)
        assertEquals(DEFAULT_DOWNLOAD_YOUTUBE_AUDIO_QUALITY, selection.youtubeQuality)
        assertEquals(DEFAULT_DOWNLOAD_BILI_AUDIO_QUALITY, selection.biliQuality)
        assertEquals(DEFAULT_DOWNLOAD_KUGOU_AUDIO_QUALITY, selection.kugouQuality)
    }
}
