package com.arnav.island.events.system

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.arnav.island.events.AudioRecordPayload
import com.arnav.island.events.EventEngine
import com.arnav.island.events.EventSource
import com.arnav.island.events.EventType
import com.arnav.island.events.Glyph
import com.arnav.island.events.IslandAction
import com.arnav.island.events.IslandEvent
import com.arnav.island.island.render.presenters.AudioRecordPresenter
import com.arnav.island.storage.AppLanguage
import com.arnav.island.storage.IslandSettings
import com.arnav.island.util.Launch

/**
 * Detects active microphone voice recording sessions via [AudioManager.AudioRecordingCallback]
 * (public Android API, no permissions required) as a fallback when a Voice Recorder app does not
 * post a rich notification or before its notification arrives.
 *
 * Ignores voice recognition / hotword / communication calls so only genuine audio recording shows.
 */
class AudioRecordingMonitor(
    private val context: Context,
    private val engine: EventEngine,
    private val settings: () -> IslandSettings,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var since = 0L

    private val clearRunnable = Runnable {
        since = 0L
        engine.remove(ID)
    }

    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            evaluate(configs)
        }
    }

    fun start() {
        if (registered) return
        runCatching {
            audioManager.registerAudioRecordingCallback(callback, handler)
            registered = true
            evaluate(audioManager.activeRecordingConfigurations)
        }
    }

    fun refresh() {
        if (!registered) return
        runCatching { evaluate(audioManager.activeRecordingConfigurations) }
    }

    fun stop() {
        if (!registered) return
        runCatching { audioManager.unregisterAudioRecordingCallback(callback) }
        registered = false
        handler.removeCallbacks(clearRunnable)
        since = 0L
        engine.remove(ID)
    }

    private fun evaluate(configs: List<AudioRecordingConfiguration>?) {
        val s = settings()
        if (!s.enabled || !s.audioRecordEnabled) {
            handler.removeCallbacks(clearRunnable)
            since = 0L
            engine.remove(ID)
            return
        }
        // If NotificationProcessor is already showing a richer notification-backed voice recording
        // event (with Pause/Stop PendingIntents), let that one take precedence.
        val hasNotifRecording = engine.current().live.any {
            it.type == EventType.AUDIO_RECORD && it.id != ID
        }
        if (hasNotifRecording || MascotMonitor.isAssistantSessionActive || MascotMonitor.isGeminiRecentlyActive()) {
            handler.removeCallbacks(clearRunnable)
            engine.remove(ID)
            return
        }

        val active = configs.orEmpty().any { cfg ->
            if (Build.VERSION.SDK_INT >= 29 && cfg.isClientSilenced) return@any false
            val src = cfg.clientAudioSource
            // Only match standard microphone / camcorder / unprocessed audio recording sources;
            // exclude VOICE_COMMUNICATION (calls) and VOICE_RECOGNITION (assistant hotword).
            src == MediaRecorder.AudioSource.MIC ||
                src == MediaRecorder.AudioSource.DEFAULT ||
                src == MediaRecorder.AudioSource.CAMCORDER ||
                src == MediaRecorder.AudioSource.UNPROCESSED
        }

        // Also ignore if a phone/VoIP call is currently in progress.
        val mode = audioManager.mode
        val inCall = mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE

        if (active && !inCall) {
            handler.removeCallbacks(clearRunnable)
            val now = System.currentTimeMillis()
            if (since == 0L) since = now
            val isVi = s.language == AppLanguage.VIETNAMESE
            val title = if (isVi) "Đang ghi âm" else "Voice Recording"
            val appLabel = if (isVi) "Ghi âm" else "Voice Recorder"
            val openAction = IslandAction(AudioRecordPresenter.ACTION_OPEN, if (isVi) "Mở Ghi âm" else "Open Recorder", Glyph.OPEN, collapses = true) {
                openVoiceRecorderApp(context)
            }
            engine.post(
                IslandEvent(
                    id = ID,
                    source = EventSource.AUDIO_RECORD,
                    type = EventType.AUDIO_RECORD,
                    timestamp = since,
                    persistent = true,
                    title = title,
                    subtitle = appLabel,
                    icon = Glyph.MIC,
                    actions = listOf(openAction),
                    tapAction = openAction,
                    contentKey = "audiorec:active",
                    payload = AudioRecordPayload(
                        packageName = null,
                        appLabel = appLabel,
                        title = title,
                        startedAt = since,
                        elapsedWhenPausedMs = 0L,
                        isPaused = false,
                    ),
                )
            )
        } else {
            handler.removeCallbacks(clearRunnable)
            handler.postDelayed(clearRunnable, 800L)
        }
    }

    companion object {
        const val ID = "system:audio-record"

        val RECORDER_PACKAGES = listOf(
            "com.sec.android.app.voicenote",
            "com.samsung.android.app.voicenote",
            "com.google.android.apps.recorder",
            "com.android.soundrecorder",
            "com.miui.soundrecorder",
            "com.coloros.soundrecorder",
            "com.oplus.soundrecorder",
            "com.oneplus.soundrecorder",
            "com.asus.soundrecorder",
        )

        fun openVoiceRecorderApp(context: Context): Boolean {
            for (pkg in RECORDER_PACKAGES) {
                if (Launch.openApp(context, pkg)) return true
            }
            return false
        }
    }
}
