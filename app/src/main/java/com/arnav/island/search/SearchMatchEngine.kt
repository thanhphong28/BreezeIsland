package com.arnav.island.search

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class AppEntry(
    val packageName: String,
    val label: String,
    val normLabel: String = SearchMatchEngine.normalize(label),
    val compactLabel: String = normLabel.replace(" ", ""),
    val words: List<String> = normLabel.split(' ').filter { it.isNotEmpty() },
    val initials: String = words.joinToString("") { it.first().toString() },
)

data class UiNodeSnapshot(
    val nodeIndex: Int,
    val parentIndex: Int = -1,
    val text: String,
    val contentDesc: String,
    val viewId: String,
    val className: String,
    val isFocused: Boolean,
    val isEditable: Boolean,
    val isShowingHint: Boolean,
    val hintText: String,
    val isClickable: Boolean,
    val isActionable: Boolean = isClickable,
    val isVisible: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

data class VisualAppMatch(
    val packageName: String?,
    val label: String,
    val nodeIndex: Int,
    val isClickable: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

sealed interface SearchDecision {
    data object NoSearchActive : SearchDecision
    data object EmptyQuery : SearchDecision
    data class QueryTooShort(val query: String) : SearchDecision
    data class NoMatches(val query: String) : SearchDecision
    data class MultipleMatches(val query: String, val count: Int) : SearchDecision
    data class SingleMatch(val query: String, val match: VisualAppMatch) : SearchDecision
}

/**
 * Pure, deterministic UI-tree analyzer for Samsung One UI Home / Finder and Android launchers.
 *
 * Design rule: DO NOT second-guess what the user typed (typos, category search like "ngân hàng",
 * English/Vietnamese aliases like "photo" -> "Bộ sưu tập", etc.). If the search box has a real
 * query and the launcher/Finder displays **exact 1 app result**, launch that app immediately.
 */
object SearchMatchEngine {

    private val COMBINING_MARKS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val ZERO_WIDTH_REGEX = Regex("[\\u200B-\\u200F\\uFEFF]")
    private val MULTI_SPACE_REGEX = Regex("\\s+")

    /** Exact normalized titles of Samsung One UI / Android Launcher app-result cards. */
    private val SEARCHED_APPS_HEADERS = setOf(
        "ung dung da tim kiem",
        "searched apps",
        "app results",
        "ket qua ung dung",
        "ung dung",
        "apps",
    )

    private val SUGGESTED_HEADERS = setOf(
        "ung dung de xuat",
        "suggested apps",
        "goi y",
        "suggestions",
        "tim kiem gan day",
        "recent searches",
        "gan day",
        "recents",
    )

    private val CARD_ACTION_LABELS = setOf(
        "xem them",
        "see more",
        "show more",
        "thu gon",
        "collapse",
        "show less",
        "tat ca",
        "view all",
        "more",
        "mo rong",
        "tim kiem",
        "search",
        "xoa",
        "clear",
    )

    private val EXCLUDED_VIEW_ID_KEYWORDS = listOf(
        "search_src_text",
        "search_plate",
        "search_bar",
        "search_edit",
        "search_close",
        "clear_button",
        "voice_btn",
        "more_btn",
        "history",
        "recent_search",
        "suggestion_chip",
        "autocomplete",
        "toolbar",
        "action_bar",
        "card_title",
        "section_title",
        "category_title",
        "header_title",
        "shortcut",
    )

    private val FOOTER_NOISE_PHRASES = listOf(
        "google play",
        "galaxy store",
        "tim tren",
        "tim trong",
        "search on",
        "search in",
        "search web",
        "mo trong",
        "xem them",
        "see more",
        "show more",
        "cai dat >",
        "settings >",
    )

    fun normalize(raw: String): String {
        if (raw.isBlank()) return ""
        val cleaned = ZERO_WIDTH_REGEX.replace(raw, "")
        val lower = cleaned.trim().lowercase(Locale.ROOT)
            .replace('đ', 'd')
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        val stripped = COMBINING_MARKS_REGEX.replace(decomposed, "")
        return MULTI_SPACE_REGEX.replace(stripped, " ").trim()
    }

    fun buildIndex(installedApps: List<AppEntry>): Map<String, List<AppEntry>> {
        val map = LinkedHashMap<String, MutableList<AppEntry>>(installedApps.size * 2)
        for (app in installedApps) {
            if (app.normLabel.isEmpty()) continue
            map.getOrPut(app.normLabel) { ArrayList(2) }.add(app)
        }
        return map
    }

    fun evaluateSearchScreen(
        nodes: List<UiNodeSnapshot>,
        installedApps: List<AppEntry>,
        labelIndex: Map<String, List<AppEntry>> = buildIndex(installedApps),
        minChars: Int = 1,
    ): SearchDecision {
        val searchBox = findActiveSearchInput(nodes) ?: return SearchDecision.NoSearchActive

        if (searchBox.isShowingHint) return SearchDecision.EmptyQuery
        val rawQuery = ZERO_WIDTH_REGEX.replace(searchBox.text, "").trim()
        if (rawQuery.isEmpty()) return SearchDecision.EmptyQuery
        if (searchBox.hintText.isNotBlank() && rawQuery.equals(searchBox.hintText.trim(), ignoreCase = true)) {
            return SearchDecision.EmptyQuery
        }

        val normQuery = normalize(rawQuery)
        val compactQuery = normQuery.replace(" ", "")
        if (compactQuery.length < minChars.coerceAtLeast(1)) {
            return SearchDecision.QueryTooShort(rawQuery)
        }

        // Priority 1: Samsung One UI Finder / Home "Ứng dụng đã tìm kiếm" / "Searched apps" card.
        // Completely query-agnostic: whatever 1 app Samsung Finder displays in this card gets opened!
        val searchedAppsHeader = findSearchedAppsHeader(nodes, searchBox)
        if (searchedAppsHeader != null) {
            val cardItems = collectAppItemsInSearchedCard(nodes, searchedAppsHeader, searchBox, installedApps, labelIndex)
            return when (cardItems.size) {
                0 -> SearchDecision.NoMatches(rawQuery)
                1 -> SearchDecision.SingleMatch(rawQuery, cardItems.first())
                else -> SearchDecision.MultipleMatches(rawQuery, cardItems.size)
            }
        }

        // If Finder is explicitly showing a "Suggested apps" / "Recent" card with NO "Searched apps"
        // card, then 0 apps matched the query — never auto-open a suggested app.
        if (hasOnlySuggestedHeader(nodes)) {
            return SearchDecision.NoMatches(rawQuery)
        }

        // Priority 2: Flat launcher app drawer search (no card header).
        // Match visible clickable app icons against installed apps without restricting on query text,
        // while ignoring Finder's bottom Settings/Store/Web-search cards.
        val flatMatches = collectFlatLauncherMatches(nodes, searchBox, installedApps, labelIndex)
        return when (flatMatches.size) {
            0 -> SearchDecision.NoMatches(rawQuery)
            1 -> SearchDecision.SingleMatch(rawQuery, flatMatches.first())
            else -> SearchDecision.MultipleMatches(rawQuery, flatMatches.size)
        }
    }

    /**
     * Finds the "Ứng dụng đã tìm kiếm" / "Searched apps" header node in Samsung One UI Finder.
     */
    private fun findSearchedAppsHeader(nodes: List<UiNodeSnapshot>, searchBox: UiNodeSnapshot): UiNodeSnapshot? {
        var genericCandidate: UiNodeSnapshot? = null
        for (n in nodes) {
            if (!n.isVisible || n.nodeIndex == searchBox.nodeIndex || n.isEditable) continue
            val norm = normalize(n.text.ifBlank { n.contentDesc })
            if (norm.isEmpty()) continue
            if (norm == "ung dung da tim kiem" || norm == "searched apps" || norm == "app results" || norm == "ket qua ung dung") {
                return n
            }
            if (norm in SEARCHED_APPS_HEADERS && genericCandidate == null) {
                genericCandidate = n
            }
        }
        return genericCandidate
    }

    private fun hasOnlySuggestedHeader(nodes: List<UiNodeSnapshot>): Boolean {
        for (n in nodes) {
            if (!n.isVisible || n.isEditable) continue
            val norm = normalize(n.text.ifBlank { n.contentDesc })
            if (norm in SUGGESTED_HEADERS) return true
        }
        return false
    }

    /**
     * Finds the card container holding [header] ("Ứng dụng đã tìm kiếm") and returns all distinct
     * clickable app items inside that card (below the header). Completely ignores Card 2 shortcuts,
     * Card 3 Settings, and Store/Web footers outside Card 1.
     */
    private fun collectAppItemsInSearchedCard(
        nodes: List<UiNodeSnapshot>,
        header: UiNodeSnapshot,
        searchBox: UiNodeSnapshot,
        installedApps: List<AppEntry>,
        labelIndex: Map<String, List<AppEntry>>,
    ): List<VisualAppMatch> {
        val byIndex = nodes.associateBy { it.nodeIndex }
        val childrenMap = HashMap<Int, MutableList<UiNodeSnapshot>>()
        for (n in nodes) {
            childrenMap.getOrPut(n.parentIndex) { ArrayList() }.add(n)
        }

        // Walk up from header to find the tightest ancestor card that contains clickable items
        // below header.bottom without absorbing the searchBox itself.
        var cardRootIndex = header.parentIndex
        var chosenRoot: Int = -1
        var hops = 0
        while (cardRootIndex >= 0 && hops < 5) {
            val subtree = collectSubtree(cardRootIndex, childrenMap)
            val containsSearchBox = subtree.any { it.nodeIndex == searchBox.nodeIndex }
            if (containsSearchBox) break

            val clickableBelow = subtree.any { n ->
                n.isVisible && n.isClickable && n.nodeIndex != header.nodeIndex &&
                    n.top >= header.bottom - 12 && n.width >= 40 && n.height >= 40 &&
                    !isCardActionButton(n, header)
            }
            if (clickableBelow) {
                chosenRoot = cardRootIndex
                break
            }
            cardRootIndex = byIndex[cardRootIndex]?.parentIndex ?: -1
            hops++
        }

        val scopeNodes = if (chosenRoot >= 0) {
            collectSubtree(chosenRoot, childrenMap)
        } else {
            // Flat list fallback (no parentIndex supplied): take nodes below header and above the
            // next card/section boundary or searchBox.
            val nextBoundaryY = findNextSectionTopBelow(nodes, header, searchBox)
            nodes.filter { n ->
                n.isVisible && n.nodeIndex != header.nodeIndex && n.nodeIndex != searchBox.nodeIndex &&
                    n.top >= header.bottom - 12 && n.bottom <= nextBoundaryY
            }
        }

        val scopeIndexSet = scopeNodes.map { it.nodeIndex }.toHashSet()
        val rawMatches = ArrayList<VisualAppMatch>()

        // Prefer outermost clickable items inside Card 1 (each app icon tile in One UI Finder is 1 clickable container)
        for (n in scopeNodes) {
            if (!n.isVisible || !n.isClickable) continue
            if (n.nodeIndex == header.nodeIndex || n.nodeIndex == searchBox.nodeIndex || n.isEditable) continue
            if (n.top < header.bottom - 12) continue
            if (n.width < 40 || n.height < 40) continue
            if (isCardActionButton(n, header)) continue

            // Skip nested clickable child if an ancestor inside Card 1 is already clickable
            if (hasClickableAncestorInScope(n, byIndex, scopeIndexSet)) continue

            val idLower = n.viewId.lowercase(Locale.ROOT)
            if (EXCLUDED_VIEW_ID_KEYWORDS.any { idLower.contains(it) }) continue

            val label = extractNodeOrSubtreeLabel(n, childrenMap)
            val normLabel = normalize(label)
            if (normLabel in CARD_ACTION_LABELS) continue

            val resolvedApp = resolveInstalledApp(label, installedApps, labelIndex)
            rawMatches.add(
                VisualAppMatch(
                    packageName = resolvedApp?.packageName,
                    label = resolvedApp?.label ?: label.ifBlank { "App" },
                    nodeIndex = n.nodeIndex,
                    isClickable = true,
                    left = n.left,
                    top = n.top,
                    right = n.right,
                    bottom = n.bottom,
                )
            )
        }

        // If Card 1 used non-clickable containers with clickable text/icon leaves, deduplicate by bounding box
        return deduplicateByBounds(rawMatches)
    }

    private fun collectFlatLauncherMatches(
        nodes: List<UiNodeSnapshot>,
        searchBox: UiNodeSnapshot,
        installedApps: List<AppEntry>,
        labelIndex: Map<String, List<AppEntry>>,
    ): List<VisualAppMatch> {
        val rawMatches = ArrayList<VisualAppMatch>()
        for (node in nodes) {
            if (!node.isVisible || !node.isActionable) continue
            if (node.nodeIndex == searchBox.nodeIndex || node.isEditable) continue
            if (node.width <= 0 || node.height <= 0) continue

            val idLower = node.viewId.lowercase(Locale.ROOT)
            if (EXCLUDED_VIEW_ID_KEYWORDS.any { idLower.contains(it) }) continue

            val candidates = extractCandidateLabels(node)
            if (candidates.isEmpty()) continue

            var resolved: AppEntry? = null
            for (cand in candidates) {
                val normCand = normalize(cand)
                if (normCand.isEmpty()) continue
                if (isFooterNoise(normCand)) continue
                val app = resolveInstalledApp(cand, installedApps, labelIndex)
                if (app != null) {
                    resolved = app
                    break
                }
            }
            if (resolved != null) {
                rawMatches.add(
                    VisualAppMatch(
                        packageName = resolved.packageName,
                        label = resolved.label,
                        nodeIndex = node.nodeIndex,
                        isClickable = node.isClickable,
                        left = node.left,
                        top = node.top,
                        right = node.right,
                        bottom = node.bottom,
                    )
                )
            }
        }
        return deduplicateFlatMatches(rawMatches)
    }

    private fun collectSubtree(rootIndex: Int, childrenMap: Map<Int, List<UiNodeSnapshot>>): List<UiNodeSnapshot> {
        val out = ArrayList<UiNodeSnapshot>()
        val queue = ArrayDeque<Int>()
        queue.add(rootIndex)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val children = childrenMap[cur] ?: continue
            for (c in children) {
                out.add(c)
                queue.add(c.nodeIndex)
            }
        }
        return out
    }

    private fun hasClickableAncestorInScope(
        node: UiNodeSnapshot,
        byIndex: Map<Int, UiNodeSnapshot>,
        scopeIndices: Set<Int>,
    ): Boolean {
        var p = node.parentIndex
        var hops = 0
        while (p in scopeIndices && hops < 6) {
            val parentNode = byIndex[p] ?: break
            if (parentNode.isClickable) return true
            p = parentNode.parentIndex
            hops++
        }
        return false
    }

    private fun isCardActionButton(node: UiNodeSnapshot, header: UiNodeSnapshot): Boolean {
        // Button sitting on the same horizontal line as the "Ứng dụng đã tìm kiếm" header (e.g. ">", "Xem thêm")
        if (abs(node.centerY - header.centerY) < 42 && node.bottom <= header.bottom + 24) return true
        val norm = normalize(node.text.ifBlank { node.contentDesc })
        return norm in CARD_ACTION_LABELS
    }

    private fun findNextSectionTopBelow(
        nodes: List<UiNodeSnapshot>,
        header: UiNodeSnapshot,
        searchBox: UiNodeSnapshot,
    ): Int {
        var limit = if (searchBox.top > header.bottom) searchBox.top else Int.MAX_VALUE
        for (n in nodes) {
            if (!n.isVisible || n.isClickable || n.nodeIndex == header.nodeIndex) continue
            if (n.top <= header.bottom + 120) continue
            val norm = normalize(n.text)
            if (norm.isNotEmpty() && n.left <= header.left + 80 && n.top < limit) {
                // Non-clickable left-aligned section header below Card 1
                limit = n.top
            }
        }
        return limit
    }

    private fun extractNodeOrSubtreeLabel(
        node: UiNodeSnapshot,
        childrenMap: Map<Int, List<UiNodeSnapshot>>,
    ): String {
        extractCandidateLabels(node).firstOrNull()?.let { return it }
        val queue = ArrayDeque<Int>()
        queue.add(node.nodeIndex)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (c in childrenMap[cur].orEmpty()) {
                extractCandidateLabels(c).firstOrNull()?.let { return it }
                queue.add(c.nodeIndex)
            }
        }
        return ""
    }

    private fun resolveInstalledApp(
        rawLabel: String,
        installedApps: List<AppEntry>,
        labelIndex: Map<String, List<AppEntry>>,
    ): AppEntry? {
        val norm = normalize(rawLabel)
        if (norm.isEmpty()) return null
        labelIndex[norm]?.firstOrNull()?.let { return it }

        // Support ellipsized launcher labels like "VCB Digib..." or "VCB Digib…"
        val trimmedEllipsis = norm.removeSuffix("...").removeSuffix("…").trim()
        if (trimmedEllipsis.length >= 4 && trimmedEllipsis.length < norm.length) {
            val prefixMatches = installedApps.filter { it.normLabel.startsWith(trimmedEllipsis) }
            if (prefixMatches.size == 1) return prefixMatches.first()
        }
        return null
    }

    private fun findActiveSearchInput(nodes: List<UiNodeSnapshot>): UiNodeSnapshot? {
        val editables = nodes.filter { n ->
            n.isVisible && (
                n.isEditable ||
                    n.className.contains("EditText", ignoreCase = true) ||
                    n.className.contains("SearchAutoComplete", ignoreCase = true) ||
                    n.className.contains("AutoCompleteTextView", ignoreCase = true)
                )
        }
        if (editables.isEmpty()) return null
        return editables.firstOrNull { it.isFocused }
            ?: editables.firstOrNull {
                val id = it.viewId.lowercase(Locale.ROOT)
                id.contains("search") || id.contains("query") || id.contains("finder") || id.contains("input")
            }
    }

    private fun extractCandidateLabels(node: UiNodeSnapshot): List<String> {
        val out = ArrayList<String>(2)
        val t = ZERO_WIDTH_REGEX.replace(node.text, "").trim()
        if (t.isNotEmpty() && t.length <= 64) {
            out.add(t)
        }
        val cd = ZERO_WIDTH_REGEX.replace(node.contentDesc, "").trim()
        if (cd.isNotEmpty() && cd.length <= 96) {
            val primary = cd.substringBefore(",").trim()
            if (primary.isNotEmpty() && primary != t) out.add(primary)
            if (cd != primary && cd != t) out.add(cd)
        }
        return out
    }

    private fun isFooterNoise(normCandidate: String): Boolean =
        FOOTER_NOISE_PHRASES.any { normCandidate.contains(it) } ||
            normCandidate == "cai dat" || normCandidate == "settings" ||
            normCandidate == "google"

    private fun deduplicateByBounds(matches: List<VisualAppMatch>): List<VisualAppMatch> {
        if (matches.size <= 1) return matches
        val kept = ArrayList<VisualAppMatch>()
        for (m in matches) {
            val overlap = kept.any { k -> rectsOverlapSignificantly(k, m) }
            if (!overlap) kept.add(m)
        }
        return kept
    }

    private fun deduplicateFlatMatches(matches: List<VisualAppMatch>): List<VisualAppMatch> {
        if (matches.size <= 1) return matches
        val kept = ArrayList<VisualAppMatch>()
        for (m in matches) {
            val existingIdx = kept.indexOfFirst { k ->
                k.packageName != null && k.packageName == m.packageName && !isSideBySideDualIcon(k, m)
            }
            if (existingIdx < 0) {
                kept.add(m)
            } else {
                val cur = kept[existingIdx]
                val mIsHigher = m.top < cur.top - 40
                val sameBandPreferClickable = abs(m.top - cur.top) <= 40 && !cur.isClickable && m.isClickable
                if (mIsHigher || sameBandPreferClickable) {
                    kept[existingIdx] = m
                }
            }
        }
        return kept
    }

    private fun rectsOverlapSignificantly(a: VisualAppMatch, b: VisualAppMatch): Boolean {
        val interL = max(a.left, b.left)
        val interT = max(a.top, b.top)
        val interR = min(a.right, b.right)
        val interB = min(a.bottom, b.bottom)
        if (interR <= interL || interB <= interT) return false
        val interArea = (interR - interL).toLong() * (interB - interT).toLong()
        val areaA = ((a.right - a.left).coerceAtLeast(1)).toLong() * ((a.bottom - a.top).coerceAtLeast(1)).toLong()
        val areaB = ((b.right - b.left).coerceAtLeast(1)).toLong() * ((b.bottom - b.top).coerceAtLeast(1)).toLong()
        return interArea * 4 >= min(areaA, areaB)
    }

    private fun isSideBySideDualIcon(a: VisualAppMatch, b: VisualAppMatch): Boolean {
        val dy = abs(a.centerY - b.centerY)
        val dx = abs(a.centerX - b.centerX)
        return dy < 95 && dx >= 90
    }
}
