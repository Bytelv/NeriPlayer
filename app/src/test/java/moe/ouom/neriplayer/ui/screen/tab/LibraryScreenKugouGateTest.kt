package moe.ouom.neriplayer.ui.screen.tab

import moe.ouom.neriplayer.ui.screen.tab.library.LibraryTab
import moe.ouom.neriplayer.ui.screen.tab.library.libraryTabDisplayOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 酷狗歌单标签页只在登录后可见
 *
 * `/user/playlist` 需要登录态 (未登录时后端回 204 空正文), 未登录时显示入口
 * 只会让用户点进一个永远为空的页面。
 */
class LibraryScreenKugouGateTest {

    @Test
    fun `kugou tab is hidden before signing in`() {
        val tabs = libraryTabDisplayOrder(
            isInternational = false,
            youtubeEnabled = true,
            kugouLoggedIn = false
        )

        assertFalse(tabs.contains(LibraryTab.KUGOU))
        assertTrue(tabs.contains(LibraryTab.NETEASE))
        assertTrue(tabs.contains(LibraryTab.BILI))
    }

    @Test
    fun `kugou tab appears after signing in`() {
        val tabs = libraryTabDisplayOrder(
            isInternational = false,
            youtubeEnabled = true,
            kugouLoggedIn = true
        )

        assertTrue(tabs.contains(LibraryTab.KUGOU))
        // 默认(非国际化)顺序里酷狗排在 QQ 音乐之前
        assertEquals(LibraryTab.QQMUSIC, tabs.last())
        assertTrue(tabs.indexOf(LibraryTab.KUGOU) < tabs.indexOf(LibraryTab.QQMUSIC))
    }

    @Test
    fun `logging out removes the kugou tab again`() {
        val signedIn = libraryTabDisplayOrder(
            isInternational = true,
            youtubeEnabled = true,
            kugouLoggedIn = true
        )
        val signedOut = libraryTabDisplayOrder(
            isInternational = true,
            youtubeEnabled = true,
            kugouLoggedIn = false
        )

        assertEquals(signedIn.size - 1, signedOut.size)
        assertFalse(signedOut.contains(LibraryTab.KUGOU))
    }
}
