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
        AppEntry("com.ss.android.ugc.trill", "TikTok"),
        AppEntry("com.android.settings", "Cài đặt"),
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
        isActionable = true,
        isVisible = true,
        left = 40,
        top = 1200,
        right = 980,
        bottom = 1300,
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
        actionable: Boolean = clickable,
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
        isActionable = actionable,
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
    fun `triggers single match on One UI Finder screen with app shortcuts card repeating app name`() {
        val nodes = listOf(
            searchBox("tiktok"),
            // Card 1: "Ứng dụng đã tìm kiếm" -> clickable TikTok icon + inner label
            appNode(1, "Ứng dụng đã tìm kiếm", 50, 80, 600, 140, clickable = false, actionable = false),
            UiNodeSnapshot(2, "", "TikTok", "com.sec.android.app.launcher:id/app_icon", "android.widget.FrameLayout", false, false, false, "", true, true, true, 60, 180, 240, 380),
            appNode(3, "TikTok", 70, 330, 230, 380, clickable = false, actionable = true),
            // Card 2: App shortcuts card whose header ALSO says "TikTok"
            appNode(4, "TikTok", 60, 520, 400, 580, clickable = false, actionable = false),
            appNode(5, "Thông báo", 60, 600, 800, 680, clickable = true, actionable = true),
            appNode(6, "Bắt đầu quay", 60, 700, 800, 780, clickable = true, actionable = true),
            appNode(7, "Xóa bộ nhớ đệm", 60, 800, 800, 880, clickable = true, actionable = true),
            // Card 3: Settings card ("Cài đặt")
            appNode(8, "Cài đặt", 60, 980, 400, 1040, clickable = true, actionable = true),
        )
        val decision = SearchMatchEngine.evaluateSearchScreen(nodes, apps)
        assertTrue(decision is SearchDecision.SingleMatch)
        val match = (decision as SearchDecision.SingleMatch).match
        assertEquals("com.ss.android.ugc.trill", match.app.packageName)
        assertEquals(2, match.nodeIndex)
    }

    @Test
    fun `merges Card 2 clickable header repeating same app vertically below Card 1`() {
        val nodes = listOf(
            searchBox("tiktok"),
            appNode(1, "TikTok", 60, 180, 240, 380, clickable = true, actionable = true),
            // Even if Card 2 header were clickable, it's vertically stacked below Card 1
            appNode(2, "TikTok", 60, 540, 400, 600, clickable = true, actionable = true),
        )
        val decision = SearchMatchEngine.evaluateSearchScreen(nodes, apps)
        assertTrue(decision is SearchDecision.SingleMatch)
        assertEquals(1, (decision as SearchDecision.SingleMatch).match.nodeIndex)
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
    fun `does not auto-launch when Dual Messenger shows two side-by-side Zalo icons`() {
        val nodes = listOf(
            searchBox("zalo"),
            appNode(1, "Zalo", 50, 300, 230, 500),
            appNode(2, "Zalo", 300, 300, 480, 500),
        )
        val decision = SearchMatchEngine.evaluateSearchScreen(nodes, apps)
        assertEquals(SearchDecision.MultipleMatches("zalo", 2), decision)
    }
}
