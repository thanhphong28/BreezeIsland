package com.arnav.island.search

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

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
    val app: AppEntry,
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
 * Given a flattened snapshot of visible UI nodes and the list of installed launchable apps,
 * determines whether an active search query currently narrows down to exactly ONE visible app.
 */
object SearchMatchEngine {

    private val COMBINING_MARKS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val ZERO_WIDTH_REGEX = Regex("[\\u200B-\\u200F\\uFEFF]")
    private val MULTI_SPACE_REGEX = Regex("\\s+")

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

    private val STORE_OR_WEB_NOISE_PHRASES = listOf(
        "google play",
        "galaxy store",
        "tìm trên",
        "tìm trong",
        "search on",
        "search in",
        "search web",
        "mở trong",
        "xem thêm",
        "see more",
        "show more",
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
        minChars: Int = 2,
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

        val rawMatches = ArrayList<VisualAppMatch>()
        for (node in nodes) {
            if (!node.isVisible) continue
            if (!node.isActionable) continue
            if (node.nodeIndex == searchBox.nodeIndex) continue
            if (node.isEditable) continue
            if (node.width <= 0 || node.height <= 0) continue

            val idLower = node.viewId.lowercase(Locale.ROOT)
            if (EXCLUDED_VIEW_ID_KEYWORDS.any { idLower.contains(it) }) continue

            val labelCandidates = extractCandidateLabels(node)
            if (labelCandidates.isEmpty()) continue

            var matchedApp: AppEntry? = null
            for (cand in labelCandidates) {
                val normCand = normalize(cand)
                if (normCand.isEmpty()) continue
                if (isNoisePhrase(normCand, normQuery)) continue
                val entries = labelIndex[normCand] ?: continue
                val matching = entries.firstOrNull { matchesQuery(it, normQuery, compactQuery) }
                if (matching != null) {
                    matchedApp = matching
                    break
                }
            }
            if (matchedApp != null) {
                rawMatches.add(
                    VisualAppMatch(
                        app = matchedApp,
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

        val distinct = deduplicateVisualMatches(rawMatches)
        return when (distinct.size) {
            0 -> SearchDecision.NoMatches(rawQuery)
            1 -> SearchDecision.SingleMatch(rawQuery, distinct.first())
            else -> SearchDecision.MultipleMatches(rawQuery, distinct.size)
        }
    }

    internal fun matchesQuery(app: AppEntry, normQuery: String, compactQuery: String = normQuery.replace(" ", "")): Boolean {
        if (compactQuery.isEmpty()) return false
        if (app.normLabel.contains(normQuery)) return true
        if (app.compactLabel.contains(compactQuery)) return true
        if (app.initials.isNotEmpty() && app.initials.startsWith(compactQuery)) return true

        val qWords = normQuery.split(' ').filter { it.isNotEmpty() }
        if (qWords.size > 1 && app.words.size >= qWords.size) {
            var wi = 0
            var allMatched = true
            for (qw in qWords) {
                while (wi < app.words.size && !app.words[wi].startsWith(qw)) wi++
                if (wi >= app.words.size) {
                    allMatched = false
                    break
                }
                wi++
            }
            if (allMatched) return true
        }

        if (compactQuery.length >= 3 && app.packageName.lowercase(Locale.ROOT).contains(compactQuery)) {
            return true
        }

        if (compactQuery.length >= 2) {
            val firstChar = compactQuery[0]
            val startsAnyWord = app.compactLabel.startsWith(firstChar) || app.words.any { it.startsWith(firstChar) }
            if (startsAnyWord && isSubsequence(compactQuery, app.compactLabel)) {
                return true
            }
        }

        return false
    }

    private fun isSubsequence(needle: String, haystack: String): Boolean {
        var ni = 0
        var hi = 0
        while (ni < needle.length && hi < haystack.length) {
            if (needle[ni] == haystack[hi]) ni++
            hi++
        }
        return ni == needle.length
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

    private fun isNoisePhrase(normCandidate: String, normQuery: String): Boolean {
        for (phrase in STORE_OR_WEB_NOISE_PHRASES) {
            val normPhrase = normalize(phrase)
            if (normCandidate.contains(normPhrase) && !normPhrase.contains(normQuery)) {
                return true
            }
        }
        return false
    }

    /**
     * Merges duplicate nodes for the same package (e.g., parent container + child TextView, OR
     * Card 1 "Searched apps" icon + Card 2 "App Shortcuts" section header below it), while
     * keeping side-by-side Dual Messenger icons in the same row separate so the user can choose.
     */
    private fun deduplicateVisualMatches(matches: List<VisualAppMatch>): List<VisualAppMatch> {
        if (matches.size <= 1) return matches
        val kept = ArrayList<VisualAppMatch>()
        for (m in matches) {
            val existingIdx = kept.indexOfFirst { k ->
                k.app.packageName == m.app.packageName && !isSideBySideDualIcon(k, m)
            }
            if (existingIdx < 0) {
                kept.add(m)
            } else {
                // Keep the topmost app icon (Card 1 "Searched apps"), preferring clickable container
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

    /**
     * Dual Messenger icons of the exact same app appear side-by-side in the same grid row
     * (similar centerY, separated horizontally).
     */
    private fun isSideBySideDualIcon(a: VisualAppMatch, b: VisualAppMatch): Boolean {
        val dy = abs(a.centerY - b.centerY)
        val dx = abs(a.centerX - b.centerX)
        return dy < 95 && dx >= 90
    }
}
