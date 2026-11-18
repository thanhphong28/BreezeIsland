package com.arnav.island.widget

import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class SyncedLine(val timeMs: Long, val text: String)

data class TrackLyrics(
    val synced: List<SyncedLine>,
    val plain: List<String>,
)

data class LyricWindow(
    val prevLine: String? = null,
    val currentLine: String? = null,
    val nextLine: String? = null,
    val upcomingLines: List<String> = emptyList(),
    val hasSynced: Boolean = false,
    val isLoading: Boolean = false,
)

/**
 * Lightweight, zero-dependency synced LRC lyrics engine backed by lrclib.net + in-memory cache.
 * Falls back cleanly to MediaSession live lines or plain lyrics when timestamps aren't available.
 */
object LyricsRepository {

    private val cache = LruCache<String, TrackLyrics>(64)
    private val inFlight = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val negativeUntil = ConcurrentHashMap<String, Long>()

    private val TIME_TAG_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val TITLE_NOISE_REGEX = Regex("""\s*(\([^)]*(official|lyric|audio|video|mv|hd|4k|remaster|live|feat|ft)[^)]*\)|\[[^]]*])""", RegexOption.IGNORE_CASE)

    fun resolveLines(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        positionMs: Long,
        liveLineFallback: String?,
        scope: CoroutineScope,
        onLoaded: () -> Unit,
    ): LyricWindow {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isEmpty()) {
            return LyricWindow(currentLine = liveLineFallback?.takeIf { it.isNotBlank() })
        }

        val key = cacheKey(cleanTitle, cleanArtist)
        val cached = cache.get(key)
        val now = System.currentTimeMillis()
        val retryAfter = negativeUntil[key] ?: 0L
        val loading = cached == null && inFlight.contains(key)

        if (cached == null && !inFlight.contains(key) && now >= retryAfter) {
            inFlight.add(key)
            scope.launch(Dispatchers.IO) {
                try {
                    val fetched = fetchFromLrclib(cleanTitle, cleanArtist, album.trim(), durationMs)
                    if (fetched != null && (fetched.synced.isNotEmpty() || fetched.plain.isNotEmpty())) {
                        cache.put(key, fetched)
                        negativeUntil.remove(key)
                    } else {
                        // Retry after 5 minutes for missing tracks so we don't hammer the network
                        negativeUntil[key] = System.currentTimeMillis() + 5 * 60_000L
                    }
                } finally {
                    inFlight.remove(key)
                    onLoaded()
                }
            }
        }

        if (cached != null && cached.synced.isNotEmpty()) {
            return sliceSynced(cached.synced, positionMs)
        }

        if (!liveLineFallback.isNullOrBlank()) {
            return LyricWindow(
                currentLine = liveLineFallback,
                upcomingLines = cached?.plain?.take(3).orEmpty(),
                hasSynced = false,
                isLoading = false,
            )
        }

        if (cached != null && cached.plain.isNotEmpty()) {
            return LyricWindow(
                currentLine = cached.plain.firstOrNull(),
                nextLine = cached.plain.getOrNull(1),
                upcomingLines = cached.plain.drop(1).take(3),
                hasSynced = false,
                isLoading = false,
            )
        }

        return LyricWindow(isLoading = loading || (!inFlight.contains(key) && now < retryAfter && false))
    }

    internal fun parseLrc(lrc: String): List<SyncedLine> {
        val out = ArrayList<SyncedLine>()
        lrc.lineSequence().forEach { rawLine ->
            val matches = TIME_TAG_REGEX.findAll(rawLine).toList()
            if (matches.isEmpty()) return@forEach
            val text = rawLine.replace(TIME_TAG_REGEX, "").trim()
            if (text.isEmpty()) return@forEach
            for (m in matches) {
                val mins = m.groupValues[1].toLongOrNull() ?: continue
                val secs = m.groupValues[2].toLongOrNull() ?: continue
                val fracRaw = m.groupValues[3]
                val msPart = when (fracRaw.length) {
                    0 -> 0L
                    1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
                    else -> fracRaw.take(3).toLongOrNull() ?: 0L
                }
                out.add(SyncedLine(mins * 60_000L + secs * 1_000L + msPart, text))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    internal fun sliceSynced(lines: List<SyncedLine>, positionMs: Long): LyricWindow {
        if (lines.isEmpty()) return LyricWindow()
        // Find last line whose timestamp <= positionMs + small 250ms visual lead
        val targetMs = (positionMs + 250L).coerceAtLeast(0L)
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= targetMs) idx = i else break
        }
        if (idx < 0) {
            return LyricWindow(
                currentLine = "♪ …",
                nextLine = lines.firstOrNull()?.text,
                upcomingLines = lines.take(3).map { it.text },
                hasSynced = true,
            )
        }
        return LyricWindow(
            prevLine = lines.getOrNull(idx - 1)?.text,
            currentLine = lines[idx].text,
            nextLine = lines.getOrNull(idx + 1)?.text,
            upcomingLines = listOfNotNull(
                lines.getOrNull(idx + 1)?.text,
                lines.getOrNull(idx + 2)?.text,
                lines.getOrNull(idx + 3)?.text,
            ),
            hasSynced = true,
        )
    }

    private fun fetchFromLrclib(title: String, artist: String, album: String, durationMs: Long): TrackLyrics? {
        val normTitle = normalizeTitle(title)
        val normArtist = artist.substringBefore(",").substringBefore(" - ").trim()

        // 1) Exact match via /api/get
        if (normArtist.isNotEmpty()) {
            val sb = StringBuilder("https://lrclib.net/api/get?track_name=")
                .append(encode(normTitle))
                .append("&artist_name=")
                .append(encode(normArtist))
            if (album.isNotEmpty()) {
                sb.append("&album_name=").append(encode(album))
            }
            if (durationMs > 5_000L) {
                sb.append("&duration=").append(durationMs / 1_000L)
            }
            val exactJson = httpGet(sb.toString())
            if (exactJson != null) {
                parseTrackJson(JSONObject(exactJson))?.let { return it }
            }
        }

        // 2) Fuzzy search fallback via /api/search
        val query = if (normArtist.isNotEmpty()) "$normTitle $normArtist" else normTitle
        val searchJson = httpGet("https://lrclib.net/api/search?q=${encode(query)}") ?: return null
        val arr = JSONArray(searchJson)
        if (arr.length() == 0) return null

        val targetSec = durationMs / 1_000.0
        var bestSynced: TrackLyrics? = null
        var bestPlain: TrackLyrics? = null
        var bestScore = Double.MAX_VALUE

        for (i in 0 until minOf(arr.length(), 8)) {
            val item = arr.optJSONObject(i) ?: continue
            val parsed = parseTrackJson(item) ?: continue
            val itemDur = item.optDouble("duration", -1.0)
            val diff = if (targetSec > 5 && itemDur > 5) abs(itemDur - targetSec) else 0.0
            if (parsed.synced.isNotEmpty() && diff < bestScore) {
                bestScore = diff
                bestSynced = parsed
            } else if (bestPlain == null && parsed.plain.isNotEmpty()) {
                bestPlain = parsed
            }
        }
        return bestSynced ?: bestPlain
    }

    private fun parseTrackJson(obj: JSONObject): TrackLyrics? {
        val syncedRaw = obj.optString("syncedLyrics", "").takeIf { it != "null" && it.isNotBlank() }
        val plainRaw = obj.optString("plainLyrics", "").takeIf { it != "null" && it.isNotBlank() }
        val synced = syncedRaw?.let { parseLrc(it) }.orEmpty()
        val plain = plainRaw
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toList()
            .orEmpty()
        if (synced.isEmpty() && plain.isEmpty()) return null
        return TrackLyrics(synced = synced, plain = plain)
    }

    private fun httpGet(urlString: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4_500
                readTimeout = 4_500
                setRequestProperty("User-Agent", "BreezeIsland/2.4.0 (Android Cover Widget)")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode !in 200..299) return null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun normalizeTitle(raw: String): String =
        raw.replace(TITLE_NOISE_REGEX, "").trim().ifEmpty { raw.trim() }

    private fun cacheKey(title: String, artist: String): String =
        "${normalizeTitle(title).lowercase()}|${artist.lowercase()}"

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
