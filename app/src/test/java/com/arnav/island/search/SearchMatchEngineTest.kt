package com.arnav.island.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatchEngineTest {

    private val apps = listOf(
        AppEntry("com.zing.zalo", "Zalo"),
        AppEntry("ru.zdevs.zarchiver", "ZArchiver"),
        AppEntry("com.facebook.katana", "Facebook"),
        AppEntry("com.VCB", "VCB Digibank"),
        AppEntry("com.sec.android.app.clockpackage", "Đồng hồ"),
        AppEntry("com.google.android.googlequicksearchbox", "Google"),
        AppEntry("com.android.vending", "Google Play"),
        AppEntry("com.mservice.momotransfer", "MoMo"),
    )

    private fun searchBox(
        text: String,
        showingHint: Boolean = false,
        hint: String = "Tìm kiếm",
    ) = UiNodeSnapshot(
        nodeIndex = 0,
        text = text,
        contentDesc = "",
        viewId = "com.sec.android.app.launcher:id/search_src_text",
        className = "androidx.appcompat.widget.SearchView\$SearchAutoComplete",
        isFocused = true,
        isEditable = true,
        isShowingHint = showingHint,
        hintText = hint,
        isClickable = true,
        isVisible = true,
        left = 40,
        top = 120,
        right = 980,
        bottom = 220,
    )

    private fun appNode(
        index: Int,
        label: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        contentDesc: String = label,
        clickable: Boolean = true,
        viewId: String = "com.sec.android.app.launcher:id/icon",
    ) = UiNodeSnapshot(
        nodeIndex = index,
        text = label,
        contentDesc = contentDesc,
        viewId = viewId,
        className = "android.widget.TextView",
        isFocused = false,
        isEditable = false,
        isShowingHint = false,
        hintText = "",
        isClickable = clickable,
        isVisible = true,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
    )

    @Test
    fun `ignores empty search box showing hint text`() {
        val nodes = listOf(
            searchBox(text = "Tìm kiếm", showingHint = true),
            appNode(1, "Zalo", 50, 300, 230, 500),
        )
        assertEquals(SearchDecision.EmptyQuery, SearchMatchEngine.evaluateSearchScreen(nodes, apps))
    }

    @Test
    fun `waits when two apps match query`() {
        val nodes = listOf(
            searchBox("za"),
            appNode(1, "Zalo", 50, 300, 230, 500),
            appNode(2, "ZArchiver", 280, 300, 460, 500),
        )
        val decision = SearchMatchEngine.evaluateSearchScreen(nodes, apps)
        assertEquals(SearchDecision.MultipleMatches("za", 2), decision)
    }

    @Test
    fun `triggers single match while ignoring non-matching Finder footer items`() {
        val nodes = listOf(
            searchBox("zal"),
            // Container + child TextView for the same Zalo icon
            UiNodeSnapshot(1, "", "Zalo, 3 thông báo", "com.sec.android.app.launcher:id/app_item", "android.widget.FrameLayout", false, false, false, "", true, true, 40, 300, 240, 520),
            appNode(2, "Zalo", 50, 440, 230, 510, clickable = false),
            // Finder footer suggestions that do not match "zal"
            appNode(3, "Google", 50, 900, 230, 1000),
            appNode(4, "Google Play", 280, 900, 460, 1000),
            appNode(5, "Thông báo Zalo", 50, 620, 800, 700),
        )
        val decision = SearchMatchEngine.evaluateSearchScreen(nodes, apps)
        assertTrue(decision is SearchDecision.SingleMatch)
        val match = (decision as SearchDecision.SingleMatch).match
        assertEquals("com.zing.zalo", match.app.packageName)
        assertEquals(1, match.nodeIndex) // prefers clickable container
    }

    @Test
    fun `supports Vietnamese diacritic-free and acronym queries`() {
        val vnNodes = listOf(
            searchBox("dong ho"),
            appNode(1, "Đồng hồ", 50, 300, 230, 500),
        )
        val vnDecision = SearchMatchEngine.evaluateSearchScreen(vnNodes, apps)
        assertTrue(vnDecision is SearchDecision.SingleMatch)
        assertEquals("com.sec.android.app.clockpackage", (vnDecision as SearchDecision.SingleMatch).match.app.packageName)

        val fbNodes = listOf(
            searchBox("fb"),
            appNode(1, "Facebook", 50, 300, 230, 500),
        )
        val fbDecision = SearchMatchEngine.evaluateSearchScreen(fbNodes, apps)
        assertTrue(fbDecision is SearchDecision.SingleMatch)
        assertEquals("com.facebook.katana", (fbDecision as SearchDecision.SingleMatch).match.app.packageName)
    }

    @Test
    fun `does not auto-launch when Dual Messenger shows two separate Zalo icons`() {
        val nodes = listOf(
            searchBox("zalo"),
            appNode(1, "Zalo", 50, 300, 230, 500),
            appNode(2, "Zalo", 300, 300, 480, 500),
        )
        val decision = SearchMatchEngine.evaluateSearchScreen(nodes, apps)
        assertEquals(SearchDecision.MultipleMatches("zalo", 2), decision)
    }
}
