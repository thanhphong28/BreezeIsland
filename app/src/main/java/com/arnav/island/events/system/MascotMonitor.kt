package com.arnav.island.events.system

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.arnav.island.events.EntranceAnimation
import com.arnav.island.events.EventColors
import com.arnav.island.events.EventEngine
import com.arnav.island.events.EventPriority
import com.arnav.island.events.EventSource
import com.arnav.island.events.EventType
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.MascotMood
import com.arnav.island.events.MascotPayload
import com.arnav.island.events.MediaPayload
import com.arnav.island.overlay.ForegroundAppMonitor
import com.arnav.island.permissions.OnPhoneGranter
import com.arnav.island.storage.IslandSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

/**
 * Manages the **Breeze Mascot** companion on the Dynamic Island:
 * 1. **Instant "OK Google" / Gemini Assistant Summon**:
 *    Automatically detects when the user says "OK Google" / "Hey Google" or launches Gemini
 *    using a 4-layer zero-Accessibility detector:
 *     - Layer 1: [AudioManager.AudioRecordingCallback] watching `VOICE_RECOGNITION` & hotword
 *       microphone handoff (0 permissions required, instant callback).
 *     - Layer 2: [AudioManager.AudioPlaybackCallback] watching `AudioAttributes.USAGE_ASSISTANT`
 *       chimes and Gemini voice responses (0 permissions required, instant callback).
 *     - Layer 3: [UsageStatsManager] watching Gemini (`com.google.android.apps.bard`) and Google
 *       Assistant / Robin (`com.google.android.googlequicksearchbox`) overlay/session launches.
 *     - Layer 4: Optional Shizuku/system logcat watcher for `VoiceInteractionManagerService`
 *       `showSession` & `HotwordDetectionService` events.
 * 2. **Periodic Appearance**: Pops out with cute 120Hz animations while the user is using the phone.
 * 3. **Interactive Camera Tap**: Responds immediately when the user taps the camera while idle,
 *    or taps the mascot while showing to cycle through expressions.
 */
class MascotMonitor(
    private val context: Context,
    private val events: EventEngine,
    private val scope: CoroutineScope,
    private val settings: () -> IslandSettings,
    private val canShowPeriodically: () -> Boolean,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val usageStats = context.getSystemService(UsageStatsManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var periodicJob: Job? = null
    private var usageWatchJob: Job? = null
    private var logcatWatchJob: Job? = null
    private var assistantHoldJob: Job? = null
    private var logcatProcess: Process? = null

    private var callbacksRegistered = false
    private var screenUsable = false
    private var seedCounter = 0
    private var interactionCounter = 0
    private var lastShownAtElapsed = SystemClock.elapsedRealtime()
    private var lastAssistantTriggerElapsed = 0L

    @Volatile
    private var voiceRecMicActive = false

    @Volatile
    private var assistantAudioActive = false

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            evaluateRecordingConfigs(configs)
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            evaluatePlaybackConfigs(configs)
        }
    }

    fun setActive(usable: Boolean) {
        screenUsable = usable
        val s = settings()
        if (usable && s.enabled && s.mascotEnabled) {
            registerAudioCallbacksIfNeeded()
            if (s.mascotPeriodicEnabled) {
                startPeriodicLoop()
            } else {
                periodicJob?.cancel()
                periodicJob = null
            }
            if (s.mascotOnAssistantEnabled) {
                startAssistantWatchers()
            } else {
                stopAssistantWatchers()
            }
        } else {
            periodicJob?.cancel()
            periodicJob = null
            stopAssistantWatchers()
            unregisterAudioCallbacks()
        }
    }

    fun stop() {
        screenUsable = false
        periodicJob?.cancel()
        periodicJob = null
        assistantHoldJob?.cancel()
        assistantHoldJob = null
        stopAssistantWatchers()
        unregisterAudioCallbacks()
        isAssistantSessionActive = false
    }

    private fun registerAudioCallbacksIfNeeded() {
        if (callbacksRegistered) return
        runCatching {
            audioManager?.registerAudioRecordingCallback(recordingCallback, mainHandler)
            audioManager?.registerAudioPlaybackCallback(playbackCallback, mainHandler)
            callbacksRegistered = true
        }
    }

    private fun unregisterAudioCallbacks() {
        if (!callbacksRegistered) return
        runCatching {
            audioManager?.unregisterAudioRecordingCallback(recordingCallback)
            audioManager?.unregisterAudioPlaybackCallback(playbackCallback)
        }
        callbacksRegistered = false
        voiceRecMicActive = false
        assistantAudioActive = false
    }

    /**
     * Layer 1: Detects "OK Google" / "Hey Google" / Gemini voice recognition microphone activation.
     * When the user says "OK Google", Google's VoiceInteractionSession / Gemini opens an active
     * recording stream with [MediaRecorder.AudioSource.VOICE_RECOGNITION] (6) or hotword handoff.
     */
    private fun evaluateRecordingConfigs(configs: List<AudioRecordingConfiguration>?) {
        val s = settings()
        if (!s.enabled || !s.mascotEnabled || !s.mascotOnAssistantEnabled) {
            voiceRecMicActive = false
            return
        }

        val mode = audioManager?.mode ?: AudioManager.MODE_NORMAL
        val inPhoneCall = mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_RINGTONE ||
            events.current().live.any { it.type == EventType.CALL_INCOMING || it.type == EventType.CALL_ONGOING }

        if (inPhoneCall) {
            voiceRecMicActive = false
            return
        }

        val hasVoiceRec = configs.orEmpty().any { cfg ->
            if (Build.VERSION.SDK_INT >= 29 && cfg.isClientSilenced) return@any false
            val src = cfg.clientAudioSource
            // VOICE_RECOGNITION (6) is the standard source for "OK Google" / Gemini / Assistant.
            // Also match VOICE_COMMUNICATION (7) when Gemini Live is active.
            src == MediaRecorder.AudioSource.VOICE_RECOGNITION ||
                (src == MediaRecorder.AudioSource.VOICE_COMMUNICATION && isGeminiRecentlyActive())
        }

        val wasActive = voiceRecMicActive
        voiceRecMicActive = hasVoiceRec
        if (hasVoiceRec && !wasActive) {
            onAssistantDetected()
        }
    }

    /**
     * Layer 2: Detects `AudioAttributes.USAGE_ASSISTANT` playback (e.g. the "OK Google" activation
     * chime and Gemini's spoken responses).
     */
    private fun evaluatePlaybackConfigs(configs: List<AudioPlaybackConfiguration>?) {
        val s = settings()
        if (!s.enabled || !s.mascotEnabled || !s.mascotOnAssistantEnabled) {
            assistantAudioActive = false
            return
        }

        val hasAssistantAudio = configs.orEmpty().any { cfg ->
            cfg.audioAttributes?.usage == AudioAttributes.USAGE_ASSISTANT
        }

        val wasActive = assistantAudioActive
        assistantAudioActive = hasAssistantAudio
        if (hasAssistantAudio && !wasActive) {
            onAssistantDetected()
        }
    }

    private fun startAssistantWatchers() {
        startUsageEventsWatcher()
        startLogcatWatcher()
    }

    private fun stopAssistantWatchers() {
        usageWatchJob?.cancel()
        usageWatchJob = null
        logcatWatchJob?.cancel()
        logcatWatchJob = null
        runCatching { logcatProcess?.destroy() }
        logcatProcess = null
    }

    /**
     * Layer 3: Watches `UsageEvents` for Gemini (`com.google.android.apps.bard`) and Google
     * Assistant / Robin (`com.google.android.googlequicksearchbox` voice/assistant surfaces).
     */
    private fun startUsageEventsWatcher() {
        if (usageWatchJob?.isActive == true) return
        usageWatchJob = scope.launch(Dispatchers.Default) {
            var lastCheckedTime = System.currentTimeMillis()
            while (isActive && screenUsable) {
                delay(300L)
                val s = settings()
                if (!s.enabled || !s.mascotEnabled || !s.mascotOnAssistantEnabled) {
                    delay(1_500L)
                    continue
                }
                if (!ForegroundAppMonitor.hasUsageAccess(context)) {
                    delay(2_000L)
                    continue
                }

                val now = System.currentTimeMillis()
                val from = (now - 2_500L).coerceAtMost(lastCheckedTime)
                val iter = try {
                    usageStats?.queryEvents(from, now)
                } catch (_: Throwable) {
                    null
                } ?: continue

                val ev = UsageEvents.Event()
                var maxTime = lastCheckedTime
                var triggered = false
                while (iter.hasNextEvent()) {
                    iter.getNextEvent(ev)
                    if (ev.timeStamp <= lastCheckedTime) continue
                    if (ev.timeStamp > maxTime) maxTime = ev.timeStamp

                    val pkg = ev.packageName?.lowercase(Locale.ROOT) ?: continue
                    val cls = ev.className?.lowercase(Locale.ROOT).orEmpty()

                    val isResumeOrService = ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                        ev.eventType == UsageEvents.Event.FOREGROUND_SERVICE_START

                    if (isResumeOrService && isAssistantComponent(pkg, cls)) {
                        lastGeminiSeenWallMs = ev.timeStamp
                        triggered = true
                    }
                }
                lastCheckedTime = maxTime
                if (triggered) {
                    mainHandler.post { onAssistantDetected() }
                }
            }
        }
    }

    /**
     * Layer 4: Watches system/main/events logcat via Shizuku (if active) for instant
     * `VoiceInteractionManagerService` / `HotwordDetection` / `Robin` ("OK Google") triggers.
     */
    private fun startLogcatWatcher() {
        if (logcatWatchJob?.isActive == true) return
        logcatWatchJob = scope.launch(Dispatchers.IO) {
            val cmd = "logcat -b main,system,events -v brief -T 1 *:I"
            while (isActive && screenUsable) {
                val s = settings()
                if (!s.enabled || !s.mascotEnabled || !s.mascotOnAssistantEnabled) {
                    delay(2_500L)
                    continue
                }
                val proc = OnPhoneGranter.openShizukuProcess(cmd)
                    ?: if (Build.VERSION.SDK_INT < 33 && OnPhoneGranter.hasReadLogsPermission(context)) {
                        runCatching { Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)) }.getOrNull()
                    } else {
                        null
                    }

                if (proc == null) {
                    delay(4_000L)
                    continue
                }
                logcatProcess = proc
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        var line: String?
                        while (isActive && screenUsable) {
                            line = reader.readLine() ?: break
                            if (isAssistantInvocationLog(line!!)) {
                                mainHandler.post { onAssistantDetected() }
                            }
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    runCatching { proc.destroy() }
                    if (logcatProcess === proc) logcatProcess = null
                }
                delay(2_000L)
            }
        }
    }

    private fun isAssistantInvocationLog(line: String): Boolean {
        val lower = line.lowercase(Locale.ROOT)
        if (lower.contains("voiceinteraction") &&
            (lower.contains("showsession") || lower.contains("startvoiceactivity") || lower.contains("startlistening"))
        ) {
            return true
        }
        if ((lower.contains("hotword") || lower.contains("alwaysonhotword") || lower.contains("dsp")) &&
            (lower.contains("ondetected") || lower.contains("hotworddetected") || lower.contains("trigger"))
        ) {
            return true
        }
        if (lower.contains("assistantinvocation") || lower.contains("robininvocation")) {
            return true
        }
        return false
    }

    private fun onAssistantDetected() {
        val s = settings()
        if (!s.enabled || !s.mascotEnabled || !s.mascotOnAssistantEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAssistantTriggerElapsed < 1_200L && isAssistantSessionActive) return
        lastAssistantTriggerElapsed = now
        lastGeminiSeenWallMs = System.currentTimeMillis()
        isAssistantSessionActive = true

        // Remove any fallback voice-recorder pill so Breeze Mascot takes center stage
        events.remove(AudioRecordingMonitor.ID)

        // Summon Mascot immediately in Gemini / Assistant mode (EXCITED or user's chosen mood)
        val assistantMood = if (s.mascotMood == MascotMood.AUTO || s.mascotMood == MascotMood.SLEEPY) {
            MascotMood.EXCITED
        } else {
            s.mascotMood
        }
        summon(
            nextMood = true,
            forcedMood = assistantMood,
            forceAssistantMode = true,
        )

        // Keep Mascot alive on the Dynamic Island for as long as the user is speaking to "OK Google" / Gemini
        assistantHoldJob?.cancel()
        assistantHoldJob = scope.launch {
            var ticks = 0
            while (isActive && ticks < 40 && (voiceRecMicActive || assistantAudioActive)) {
                delay(1_500L)
                ticks++
                if (voiceRecMicActive || assistantAudioActive) {
                    val currentMood = if (ticks % 2 == 0) MascotMood.HAPPY else MascotMood.EXCITED
                    summon(
                        nextMood = false,
                        forcedMood = currentMood,
                        forceAssistantMode = true,
                    )
                }
            }
            delay(2_500L)
            isAssistantSessionActive = false
        }
    }

    private fun startPeriodicLoop() {
        if (periodicJob?.isActive == true) return
        lastShownAtElapsed = SystemClock.elapsedRealtime()
        periodicJob = scope.launch {
            while (isActive && screenUsable) {
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
        forceAssistantMode: Boolean = false,
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
        val assistantActive = forceAssistantMode || isAssistantSessionActive || voiceRecMicActive || assistantAudioActive
        val duration = if (assistantActive) {
            s.mascotDurationMs.coerceIn(4_500L, 12_000L)
        } else {
            s.mascotDurationMs.coerceIn(2_000L, 12_000L)
        }
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
            isAssistantActive = assistantActive,
            mediaTitle = mediaPayload?.title ?: mediaEvent?.title,
            mediaAccent = mediaPayload?.accent ?: mediaEvent?.colors?.accent ?: 0,
        )
        events.post(event)
        return true
    }

    companion object {
        const val MASCOT_ID = "breeze:mascot"

        @Volatile
        var isAssistantSessionActive: Boolean = false

        @Volatile
        var lastGeminiSeenWallMs: Long = 0L

        private val STANDALONE_ASSISTANT_PACKAGES = setOf(
            "com.google.android.apps.bard", // Google Gemini app
            "com.samsung.android.bixby.agent", // Samsung Bixby voice assistant
            "com.microsoft.copilot",
        )

        private val GOOGLE_ASSISTANT_CLASS_KEYWORDS = listOf(
            "gemini",
            "robin", // Google's internal class/package name for Gemini in Google Quick Search Box
            "assistant",
            "voiceinteraction",
            "voiceplate",
            "opa",
            "hotword",
            "bisto",
            "searchplate",
            "voice",
            "interactor",
        )

        fun isAssistantComponent(pkgLower: String, clsLower: String): Boolean {
            if (pkgLower in STANDALONE_ASSISTANT_PACKAGES) return true
            if (pkgLower == "com.google.android.googlequicksearchbox") {
                return GOOGLE_ASSISTANT_CLASS_KEYWORDS.any { kw -> clsLower.contains(kw) }
            }
            return false
        }

        fun isGeminiRecentlyActive(): Boolean =
            System.currentTimeMillis() - lastGeminiSeenWallMs < 15_000L || isAssistantSessionActive

        fun buildEvent(
            mood: MascotMood,
            seed: Int,
            interactiveCount: Int,
            durationMs: Long = 4_500L,
            source: EventSource = EventSource.SYSTEM,
            isMusicPlaying: Boolean = false,
            isAssistantActive: Boolean = false,
            mediaTitle: String? = null,
            mediaAccent: Int = 0,
        ): IslandEvent = IslandEvent(
            id = if (source == EventSource.TEST) "test:mascot" else MASCOT_ID,
            source = source,
            type = EventType.MASCOT,
            priority = if (isAssistantActive) EventPriority.CRITICAL_SYSTEM_EVENT else EventPriority.CHARGING,
            timestamp = System.currentTimeMillis(),
            persistent = false,
            durationMs = durationMs,
            title = if (isAssistantActive) "Breeze Mascot · Gemini" else "Breeze Mascot",
            subtitle = if (isAssistantActive) "Đang lắng nghe cùng Gemini ✦" else mood.label,
            icon = Glyph.SPARK,
            animation = EntranceAnimation.BLOOM,
            colors = EventColors(accent = 0xFF00E5FF.toInt(), secondary = 0xFF7C4DFF.toInt()),
            contentKey = "mascot:$mood:$seed:$interactiveCount:$isMusicPlaying:$isAssistantActive",
            payload = MascotPayload(
                mood = mood,
                seed = seed,
                interactiveCount = interactiveCount,
                isMusicPlaying = isMusicPlaying,
                isAssistantActive = isAssistantActive,
                mediaTitle = mediaTitle,
                mediaAccent = mediaAccent,
            ),
        )
    }
}
