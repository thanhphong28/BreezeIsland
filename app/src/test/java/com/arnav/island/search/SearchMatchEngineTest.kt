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
        AppEntry("com.sec.android.gallery3d", "Bộ sưu tập"),
        AppEntry("com.sec.android.app.clockpackage", "Đồng hồ"),
        AppEntry("com.google.android.googlequicksearchbox", "Google"),
        AppEntry("com.android.vending", "Google Play"),
        AppEntry("com.mservice.momotransfer", "MoMo"),
        AppEntry("com.ss.android.ugc.trill", "TikTok"),
        AppEntry("com.android.settings", "Cài đặt"),
    )

    private fun box(
        index: Int,
        parent: Int = 0,
        text: String = "",
        desc: String = "",
        id: String = "",
        cls: String = "android.widget.FrameLayout",
        focused: Boolean = false,
        editable: Boolean = false,
        showingHint: Boolean = false,
        hint: String = "",
        clickable: Boolean = false,
        actionable: Boolean = clickable,
        left: Int = 0,
        top: Int = 0,
        right: Int = 1080,
        bottom: Int = 2000,
    ) = UiNodeSnapshot(
        nodeIndex = index,
        parentIndex = parent,
        text = text,
        contentDesc = desc,
        viewId = id,
        className = cls,
        isFocused = focused,
        isEditable = editable,
        isShowingHint = showingHint,
        hintText = hint,
        isClickable = clickable,
        isActionable = actionable,
        isVisible = true,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
    )

    private fun searchInput(
        index: Int,
        parent: Int,
        text: String,
        showingHint: Boolean = false,
    ) = box(
        index = index,
        parent = parent,
        text = text,
        id = "com.sec.android.app.launcher:id/search_src_text",
        cls = "androidx.appcompat.widget.SearchView\$SearchAutoComplete",
        focused = true,
        editable = true,
        showingHint = showingHint,
        hint = "Tìm kiếm",
        clickable = true,
        left = 40,
        top = 1200,
        right = 980,
        bottom = 1300,
    )

    @Test
    fun `ignores empty search box showing hint text`() {
        val nodes = listOf(
            box(0, -1),
            searchInput(1, 0, "Tìm kiếm", showingHint = true),
            box(2, 0, text = "Zalo", clickable = true, left = 50, top = 300, right = 230, bottom = 500),
        )
        assertEquals(SearchDecision.EmptyQuery, SearchMatchEngine.evaluateSearchScreen(nodes, apps))
    }

    @Test
    fun `opens single app inside Searched Apps card regardless of what user typed (typos, categories, aliases)`() {
        // User typed "ngan hang" -> Samsung Finder shows 1 app "VCB Digibank" in "Ứng dụng đã tìm kiếm"
        val nodes = listOf(
            box(0, -1),
            searchInput(1, 0, "ngan hang"),
            // Card 1: Ứng dụng đã tìm kiếm
            box(2, 0, left = 20, top = 60, right = 1060, bottom = 420),
            box(3, 2, text = "Ứng dụng đã tìm kiếm", left = 50, top = 80, right = 600, bottom = 140),
            box(4, 2, desc = "VCB Digibank", clickable = true, left = 60, top = 180, right = 240, bottom = 380),
            box(5, 4, text = "VCB Digibank", clickable = false, actionable = true, left = 70, top = 330, right = 230, bottom = 380),
            // Card 2: Settings card below
            box(6, 0, left = 20, top = 460, right = 1060, bottom = 700),
            box(7, 6, text = "Cài đặt", clickable = true, left = 60, top = 500, right = 400, bottom = 560),
        )
        val decision = SearchMatchEngine.evaluateSearchScreen(nodes, apps)
        assertTrue(decision is SearchDecision.SingleMatch)
        val match = (decision as SearchDecision.SingleMatch).match
        assertEquals("com.VCB", match.packageName)
        assertEquals(4, match.nodeIndex)
    }

    @Test
    fun `opens TikTok on One UI Finder screenshot with TikTok shortcuts card and Settings card below`() {
        val nodes = listOf(
            box(0, -1),
            searchInput(1, 0, "tikotk"), // even with typo!
            // Card 1: Ứng dụng đã tìm kiếm
            box(2, 0, left = 20, top = 60, right = 1060, bottom = 420),
            box(3, 2, text = "Ứng dụng đã tìm kiếm", left = 50, top = 80, right = 600, bottom = 140),
            box(4, 2, desc = "TikTok", clickable = true, left = 60, top = 180, right = 240, bottom = 380),
            box(5, 4, text = "TikTok", clickable = false, actionable = true, left = 70, top = 330, right = 230, bottom = 380),
            // Card 2: App shortcuts card whose header ALSO says "TikTok"
            box(6, 0, left = 20, top = 460, right = 1060, bottom = 920),
            box(7, 6, text = "TikTok", clickable = false, left = 60, top = 500, right = 400, bottom = 560),
            box(8, 6, text = "Thông báo", clickable = true, left = 60, top = 600, right = 800, bottom = 680),
            box(9, 6, text = "Bắt đầu quay", clickable = true, left = 60, top = 700, right = 800, bottom = 780),
            // Card 3: Cài đặt
            box(10, 0, left = 20, top = 960, right = 1060, bottom = 1120),
            box(11, 10, text = "Cài đặt", clickable = true, left = 60, top = 1000, right = 400, bottom = 1060),
        )
        val decision = SearchMatchEngine.evaluateSearchScreen(nodes, apps)
        assertTrue(decision is SearchDecision.SingleMatch)
        val match = (decision as SearchDecision.SingleMatch).match
        assertEquals("com.ss.android.ugc.trill", match.packageName)
        assertEquals(4, match.nodeIndex)
    }

    @Test
    fun `waits when two apps appear inside Searched Apps card`() {
        val nodes = listOf(
            box(0, -1),
            searchInput(1, 0, "za"),
            box(2, 0, left = 20, top = 60, right = 1060, bottom = 420),
            box(3, 2, text = "Ứng dụng đã tìm kiếm", left = 50, top = 80, right = 600, bottom = 140),
            box(4, 2, text = "Zalo", clickable = true, left = 60, top = 180, right = 240, bottom = 380),
            box(5, 2, text = "ZArchiver", clickable = true, left = 280, top = 180, right = 460, bottom = 380),
        )
        val decision = SearchMatchEngine.evaluateSearchScreen(nodes, apps)
        assertEquals(SearchDecision.MultipleMatches("za", 2), decision)
    }

    @Test
    fun `does not launch when zero apps matched and only Settings or Google footer is shown`() {
        val nodes = listOf(
            box(0, -1),
            searchInput(1, 0, "xyz999"),
            box(2, 0, text = "Cài đặt", clickable = true, left = 60, top = 600, right = 400, bottom = 660),
            box(3, 0, text = "Google", clickable = true, left = 60, top = 700, right = 400, bottom = 760),
            box(4, 0, text = "Google Play", clickable = true, left = 60, top = 800, right = 400, bottom = 860),
        )
        assertEquals(SearchDecision.NoMatches("xyz999"), SearchMatchEngine.evaluateSearchScreen(nodes, apps))
    }

    @Test
    fun `opens single matching app on flat launcher drawer without Searched Apps header`() {
        val nodes = listOf(
            box(0, -1),
            searchInput(1, 0, "photo"),
            box(2, 0, text = "Bộ sưu tập", clickable = true, left = 60, top = 260, right = 240, bottom = 460),
        )
        val decision = SearchMatchEngine.evaluateSearchScreen(nodes, apps)
        assertTrue(decision is SearchDecision.SingleMatch)
        assertEquals("com.sec.android.gallery3d", (decision as SearchDecision.SingleMatch).match.packageName)
    }
}
