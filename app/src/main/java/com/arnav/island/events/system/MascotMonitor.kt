package com.arnav.island.events.system

import android.os.SystemClock
import com.arnav.island.events.EntranceAnimation
import com.arnav.island.events.EventColors
import com.arnav.island.events.EventEngine
import com.arnav.island.events.EventSource
import com.arnav.island.events.EventType
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.MascotMood
import com.arnav.island.events.MascotPayload
import com.arnav.island.events.MediaPayload
import com.arnav.island.storage.IslandSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages the **Breeze Mascot** companion on the Dynamic Island:
 * - Periodically pops out with cute animations while the user is actively using the phone (both
 *   when the island is idle and when compact live activities like Music are playing).
 * - Responds immediately when the user taps the camera while the island is idle, or taps the
 *   mascot while it is already showing to cycle through expressions.
 */
class MascotMonitor(
    private val events: EventEngine,
    private val scope: CoroutineScope,
    private val settings: () -> IslandSettings,
    private val canShowPeriodically: () -> Boolean,
) {
    private var periodicJob: Job? = null
    private var active = false
    private var seedCounter = 0
    private var interactionCounter = 0
    private var lastShownAtElapsed = SystemClock.elapsedRealtime()

    fun setActive(usable: Boolean) {
        val s = settings()
        val shouldRun = usable && s.mascotEnabled && s.mascotPeriodicEnabled
        if (shouldRun == active) return
        active = shouldRun
        if (shouldRun) {
            startPeriodicLoop()
        } else {
            periodicJob?.cancel()
            periodicJob = null
        }
    }

    fun stop() {
        active = false
        periodicJob?.cancel()
        periodicJob = null
    }

    private fun startPeriodicLoop() {
        periodicJob?.cancel()
        lastShownAtElapsed = SystemClock.elapsedRealtime()
        periodicJob = scope.launch {
            while (isActive && active) {
                delay(10_000L)
                val s = settings()
                if (!s.mascotEnabled || !s.mascotPeriodicEnabled) continue
                val intervalMs = s.mascotIntervalMinutes.coerceIn(1, 120) * 60_000L
                val now = SystemClock.elapsedRealtime()
                if (now - lastShownAtElapsed >= intervalMs && canShowPeriodically()) {
                    summon(nextMood = true)
                }
            }
        }
    }

    /**
     * Summons the Breeze Mascot onto the island or cycles its mood when tapped.
     */
    fun summon(
        nextMood: Boolean = false,
        forcedMood: MascotMood? = null,
        forceMusicGroove: Boolean = false,
    ): Boolean {
        val s = settings()
        if (!s.mascotEnabled && forcedMood == null) return false
        // Always purge any lingering developer/preview test events (e.g. "Test Track 1") on the real island
        events.removeWhere { it.source == EventSource.TEST }
        lastShownAtElapsed = SystemClock.elapsedRealtime()
        if (nextMood) {
            seedCounter++
            interactionCounter++
        }
        val chosenMood = forcedMood ?: s.mascotMood
        val duration = s.mascotDurationMs.coerceIn(2_000L, 12_000L)
        val mediaEvent = events.current().live.firstOrNull { it.type == EventType.MEDIA && it.source != EventSource.TEST }
        val mediaPayload = mediaEvent?.payload as? MediaPayload
        val musicPlaying = forceMusicGroove || mediaPayload?.isPlaying == true || mediaEvent != null
        val event = buildEvent(
            mood = chosenMood,
            seed = seedCounter,
            interactiveCount = interactionCounter,
            durationMs = duration,
            source = EventSource.SYSTEM,
            isMusicPlaying = musicPlaying,
            mediaTitle = mediaPayload?.title ?: mediaEvent?.title,
            mediaAccent = mediaPayload?.accent ?: mediaEvent?.colors?.accent ?: 0,
        )
        events.post(event)
        return true
    }

    companion object {
        const val MASCOT_ID = "breeze:mascot"

        fun buildEvent(
            mood: MascotMood,
            seed: Int,
            interactiveCount: Int,
            durationMs: Long = 4_500L,
            source: EventSource = EventSource.SYSTEM,
            isMusicPlaying: Boolean = false,
            mediaTitle: String? = null,
            mediaAccent: Int = 0,
        ): IslandEvent = IslandEvent(
            id = if (source == EventSource.TEST) "test:mascot" else MASCOT_ID,
            source = source,
            type = EventType.MASCOT,
            timestamp = System.currentTimeMillis(),
            persistent = false,
            durationMs = durationMs,
            title = "Breeze Mascot",
            subtitle = mood.label,
            icon = Glyph.SPARK,
            animation = EntranceAnimation.BLOOM,
            colors = EventColors(accent = 0xFF00E5FF.toInt(), secondary = 0xFF7C4DFF.toInt()),
            contentKey = "mascot:$mood:$seed:$interactiveCount:$isMusicPlaying",
            payload = MascotPayload(
                mood = mood,
                seed = seed,
                interactiveCount = interactiveCount,
                isMusicPlaying = isMusicPlaying,
                mediaTitle = mediaTitle,
                mediaAccent = mediaAccent,
            ),
        )
    }
}
