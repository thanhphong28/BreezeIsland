package com.arnav.island.events.notification

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.os.BundleCompat
import com.arnav.island.events.ActionStyle
import com.arnav.island.events.AudioRecordPayload
import com.arnav.island.events.CallPayload
import com.arnav.island.events.CallState
import com.arnav.island.events.EntranceAnimation
import com.arnav.island.events.EventColors
import com.arnav.island.events.EventEngine
import com.arnav.island.events.EventPriority
import com.arnav.island.events.EventSource
import com.arnav.island.events.EventType
import com.arnav.island.events.Glyph
import com.arnav.island.events.ImageRef
import com.arnav.island.events.IslandAction
import com.arnav.island.events.IslandEvent
import com.arnav.island.events.LiveUpdatePayload
import com.arnav.island.events.NavigationPayload
import com.arnav.island.events.NotificationPayload
import com.arnav.island.events.NotificationPrivacy
import com.arnav.island.events.ProgressPayload
import com.arnav.island.events.StopwatchPayload
import com.arnav.island.events.TimerPayload
import com.arnav.island.events.navigation.NavigationRegistry
import com.arnav.island.events.system.AudioRecordingMonitor
import com.arnav.island.island.render.presenters.AudioRecordPresenter
import com.arnav.island.island.render.presenters.CallPresenter
import com.arnav.island.island.render.presenters.NotificationPresenter
import com.arnav.island.island.render.presenters.TimerPresenter
import com.arnav.island.settings.reply.ReplyRequest
import com.arnav.island.storage.AppLanguage
import com.arnav.island.storage.IslandSettings
import com.arnav.island.util.AppInfoCache
import com.arnav.island.util.Bitmaps
import com.arnav.island.util.ImageStore
import com.arnav.island.util.Launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.util.Collections

/**
 * Turns posted notifications into island events: calls, navigation, progress and regular
 * alerts. Notification content is kept in memory only for as long as the event is on screen.
 */
class NotificationProcessor(
    private val context: Context,
    private val engine: EventEngine,
    private val images: ImageStore,
    private val apps: AppInfoCache,
    private val scope: CoroutineScope,
    private val settings: () -> IslandSettings,
) {
    private var listener: NotificationListenerService? = null
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    /** Keys cancelled/absorbed by BreezeIsland to prevent duplicate system pop-up banners. */
    private val absorbedKeys: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** Content hash per notification key, to ignore silent re-posts (LRU, bounded). */
    private val seen = object : LinkedHashMap<String, Int>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 96
    }
    private val lastProgress = HashMap<String, Float>()
    private val clockTotalMs = HashMap<String, Long>()
    private val clockLaps = HashMap<String, MutableList<Long>>()
    var onWeatherNotification: ((title: String, text: String) -> Unit)? = null

    val isConnected: Boolean get() = listener != null

    fun attach(service: NotificationListenerService) {
        listener = service
        runCatching { service.requestListenerHints(0) }
    }

    fun detach() {
        listener = null
        engine.removeWhere {
            it.source == EventSource.CALL ||
                it.source == EventSource.NAVIGATION ||
                it.source == EventSource.PROGRESS ||
                it.id.startsWith("clock-") ||
                it.id.startsWith("audiorec:")
        }
    }

    fun onPosted(sbn: StatusBarNotification, rankingMap: RankingMap?, initialScan: Boolean) {
        val s = settings()
        if (!s.enabled) return
        if (sbn.packageName == context.packageName) return
        val n = sbn.notification ?: return
        val selectedSlots = parseCsv(s.statusBarIcons)
        if (isMedia(n)) {
            // Never snooze or cancel MediaStyle notifications: Android SystemUI's Media Player
            // in the Notification Shade & Quick Settings Panel depends on this notification!
            return
        }
        if (isClockAppNotification(sbn)) {
            handleClockNotification(sbn, s)
            return
        }
        if (isVoiceRecorderNotification(sbn)) {
            handleVoiceRecorderNotification(sbn, s)
            return
        }
        if (!initialScan && isWeatherNotification(sbn)) {
            handleWeatherNotification(sbn, s)
            return
        }
        if (isFlashlightNotification(sbn)) {
            if (s.torchEnabled) {
                suppressStatusBarChip(sbn)
            }
            return
        }
        if (s.statusBarCleanup && matchesStatusBarSlot(sbn, selectedSlots)) {
            suppressStatusBarChip(sbn)
            return
        }
        val rule = s.ruleFor(sbn.packageName)

        when {
            isCall(n) -> if (s.callsEnabled) handleCall(sbn)
            NavigationRegistry.providerFor(sbn) != null -> if (s.navigationEnabled && rule.notifications) handleNavigation(sbn)
            isLiveUpdate(sbn) -> if (s.liveUpdatesEnabled && rule.notifications) handleLiveUpdate(sbn, s)
            hasProgress(n) -> if (s.progressEnabled && rule.notifications) handleProgress(sbn)
            !initialScan -> if (s.notificationsEnabled) handleAlert(sbn, ranking(rankingMap, sbn.key), s)
        }
    }

    /**
     * Immediately scans active status bar notifications and suppresses One UI media chips or
     * selected status bar icon notifications without requiring Root or ADB.
     */
    fun syncStatusBarNotifications(s: IslandSettings = settings()) {
        if (!s.enabled) return
        val svc = listener ?: return
        val active = try {
            svc.activeNotifications
        } catch (_: Exception) {
            null
        } ?: return
        val selectedSlots = parseCsv(s.statusBarIcons)
        // Restore any MediaStyle notifications that were previously snoozed so the Quick Settings
        // & Notification Shade media player is always visible.
        runCatching {
            svc.snoozedNotifications?.forEach { snoozed ->
                val n = snoozed.notification
                if (n != null && (isMedia(n) || isClockAppNotification(snoozed) || isVoiceRecorderNotification(snoozed))) {
                    svc.snoozeNotification(snoozed.key, 1L)
                }
            }
        }
        for (sbn in active) {
            if (sbn.packageName == context.packageName) continue
            val n = sbn.notification ?: continue
            if (isMedia(n)) {
                continue
            }
            if (isClockAppNotification(sbn)) {
                handleClockNotification(sbn, s)
                continue
            }
            if (isVoiceRecorderNotification(sbn)) {
                handleVoiceRecorderNotification(sbn, s)
                continue
            }
            if (isFlashlightNotification(sbn)) {
                if (s.torchEnabled) {
                    suppressStatusBarChip(sbn)
                }
                continue
            }
            if (s.statusBarCleanup && matchesStatusBarSlot(sbn, selectedSlots)) {
                suppressStatusBarChip(sbn)
            }
        }
    }

    fun onRemoved(sbn: StatusBarNotification, reason: Int) {
        val key = sbn.key
        engine.remove(callId(key))
        engine.remove(navId(key))
        engine.remove(liveId(key))
        engine.remove(clockTimerId(key))
        engine.remove(clockStopwatchId(key))
        engine.remove(clockAlarmId(key))
        engine.remove(audioRecordId(key))
        clockTotalMs.remove(key)
        clockLaps.remove(key)
        lastProgress.remove(key)?.let { last ->
            engine.remove(progressId(key))
            val finished = last >= 0.97f && reason != NotificationListenerService.REASON_CANCEL && reason != NotificationListenerService.REASON_USER_STOPPED
            if (finished) postProgressDone(sbn)
        }
        // If BreezeIsland absorbed/cancelled the system notification to prevent duplicate One UI pop-ups,
        // keep the notification alive inside the Dynamic Island for its full duration.
        if (absorbedKeys.remove(key) ||
            reason == NotificationListenerService.REASON_LISTENER_CANCEL ||
            reason == NotificationListenerService.REASON_SNOOZED
        ) {
            return
        }
        // Read, cleared or withdrawn elsewhere: stop showing it (and drop any queued copy).
        engine.remove(alertId(key))
        seen.remove(key)
    }

    // ---------------------------------------------------------------------------------------------
    // Classification
    // ---------------------------------------------------------------------------------------------

    private fun template(n: Notification): String = n.extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()

    private fun isMedia(n: Notification) =
        n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) || template(n).contains("MediaStyle")

    private fun isCall(n: Notification) =
        n.category == Notification.CATEGORY_CALL || template(n).endsWith("\$CallStyle")

    private fun hasProgress(n: Notification): Boolean {
        val ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0
        if (!ongoing) return false
        return n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 || n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
    }

    /**
     * Android 16 Live Updates (promoted ongoing or ProgressStyle notifications) from any app, and
     * the ongoing tracking notifications of common delivery and ride apps.
     */
    private fun isLiveUpdate(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (Build.VERSION.SDK_INT >= 36 && n.flags and Notification.FLAG_PROMOTED_ONGOING != 0) return true
        if (template(n).endsWith("\$ProgressStyle")) return true
        val ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0
        return ongoing && (sbn.packageName in DELIVERY_APPS || sbn.packageName in RIDE_APPS)
    }

    private fun ranking(map: RankingMap?, key: String): Ranking? {
        map ?: return null
        val r = Ranking()
        return if (map.getRanking(key, r)) r else null
    }

    // ---------------------------------------------------------------------------------------------
    // Regular alerts
    // ---------------------------------------------------------------------------------------------

    private fun handleAlert(sbn: StatusBarNotification, ranking: Ranking?, s: IslandSettings) {
        val n = sbn.notification
        if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return
        val rule = s.ruleFor(sbn.packageName)
        if (!rule.notifications) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            if (s.replaceSystemPopups && sbn.isClearable) {
                absorbSystemNotification(sbn)
            }
            return
        }
        if (ranking != null) {
            if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return
            if (!ranking.matchesInterruptionFilter()) return
            if (ranking.isSuspended) return
        }

        val content = extractContent(n)
        if (content.title.isBlank() && content.text.isBlank()) return
        val hash = (content.title + '\u0000' + content.text).hashCode()
        val previous = seen[sbn.key]
        if (previous == hash) return
        seen[sbn.key] = hash
        val id = alertId(sbn.key)
        val isUpdate = previous != null
        if (isUpdate && n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0 && engine.current().toast?.id != id) return

        // The user's per-channel lock screen choice overrides what the app declared.
        val visibility = ranking?.lockscreenVisibilityOverride
            ?.takeIf { it != Ranking.VISIBILITY_NO_OVERRIDE }
            ?: n.visibility
        val isPrivate = visibility != Notification.VISIBILITY_PUBLIC
        var privacy = rule.privacy ?: s.notificationPrivacy
        val reduce = isPrivate && (s.hideSensitiveContent || (keyguard.isKeyguardLocked && !s.showOnLockScreen))
        if (reduce && privacy == NotificationPrivacy.FULL) privacy = NotificationPrivacy.APP_NAME

        val priority = when (rule.priority) {
            1 -> EventPriority.CHARGING
            -1 -> EventPriority.BACKGROUND_ACTIVITY
            else -> EventPriority.NOTIFICATION
        }
        val duration = rule.durationMs.takeIf { it > 0 } ?: s.notificationDurationMs
        val pkg = sbn.packageName
        val appLabel = apps.label(pkg)
        val key = sbn.key
        val clearable = sbn.isClearable
        val autoCancel = n.flags and Notification.FLAG_AUTO_CANCEL != 0

        val replyWith = replyAction(n)
        val otherActions = n.actions.orEmpty()
            .filter { it.remoteInputs.isNullOrEmpty() && it.actionIntent != null && !it.title.isNullOrBlank() }
            .take(if (replyWith != null) 2 else 3)
            .mapIndexed { i, action ->
                IslandAction(NotificationPresenter.ACTION_PREFIX + i, action.title.toString(), collapses = true) {
                    Launch.send(context, action.actionIntent)
                }
            }
        val open = IslandAction("notif.open", "Open") {
            Launch.send(context, n.contentIntent) || Launch.openApp(context, pkg)
            if (autoCancel && clearable) cancel(key)
        }
        val onDismiss = IslandAction("notif.dismiss", "Dismiss") { if (clearable) cancel(key) }

        if (s.replaceSystemPopups) {
            absorbSystemNotification(sbn)
        }

        scope.launch {
            val (largeRef, appRef) = withContext(Dispatchers.Default) {
                loadLargeIcon(n, content.personIcon, "notif:$key") to loadAppIcon(pkg)
            }
            val senderRefs = if (content.senders.size >= 2 && privacy == NotificationPrivacy.FULL) {
                withContext(Dispatchers.Default) { loadSenderAvatars(content.senders, key) }
            } else {
                emptyList()
            }
            // The user's per-app colour wins, then the app's declared notification colour,
            // then the colour of its launcher icon.
            val accent = rule.accent.takeIf { it != 0 }
                ?: n.color.takeIf { it != 0 && it != Notification.COLOR_DEFAULT }
                ?: withContext(Dispatchers.Default) { apps.accentBlocking(pkg) }

            // Quick reply reuses the app's own reply action (RemoteInput): the text goes straight
            // back to the app that asked for it, exactly as the notification shade would send it.
            val reply = replyWith?.let { action ->
                val request = ReplyRequest(
                    key = key,
                    title = if (privacy == NotificationPrivacy.FULL) content.title.ifBlank { appLabel } else appLabel,
                    appLabel = appLabel,
                    packageName = pkg,
                    message = if (privacy == NotificationPrivacy.FULL && rule.showText) content.text else "",
                    label = action.title?.toString()?.takeIf { it.isNotBlank() } ?: "Reply",
                    accent = accent,
                    action = action,
                )
                IslandAction(NotificationPresenter.ACTION_REPLY, request.label, Glyph.REPLY, collapses = true) {
                    // The conversation moves into the reply sheet; the banner steps aside.
                    ReplyRequest.launch(context, request)
                    engine.remove(id)
                }
            }
            val actions = listOfNotNull(reply) + otherActions
            engine.post(
                IslandEvent(
                    id = id,
                    source = EventSource.NOTIFICATION,
                    type = EventType.NOTIFICATION,
                    priority = priority,
                    timestamp = System.currentTimeMillis(),
                    persistent = false,
                    durationMs = duration,
                    title = content.title,
                    subtitle = content.text,
                    icon = if (content.isConversation) Glyph.MESSAGE else Glyph.BELL,
                    iconImage = appRef,
                    artwork = largeRef,
                    actions = actions,
                    colors = EventColors(accent = accent),
                    mergeKey = "app:$pkg:${content.conversationKey}",
                    contentKey = hash.toString(),
                    tapAction = open,
                    onDismiss = onDismiss,
                    animation = EntranceAnimation.MORPH,
                    payload = NotificationPayload(
                        packageName = pkg,
                        appLabel = appLabel,
                        title = content.title,
                        text = content.text,
                        extraLines = content.extraLines,
                        privacy = privacy,
                        showText = rule.showText,
                        postedAt = sbn.postTime,
                        hasLargeIcon = largeRef != null,
                        isConversation = content.isConversation,
                        senderAvatars = senderRefs,
                        senderCount = content.senderCount,
                        replyLabel = reply?.label,
                    ),
                )
            )
        }
    }

    private data class Content(
        val title: String,
        val text: String,
        val extraLines: List<String>,
        val isConversation: Boolean,
        val conversationKey: String,
        val personIcon: Icon?,
        /** Most recent distinct senders of a group conversation, newest first. */
        val senders: List<Sender> = emptyList(),
        val senderCount: Int = 0,
    )

    private class Sender(val name: String, val icon: Icon?)

    /** The app's own free-form reply action, preferring one marked as a reply. */
    private fun replyAction(n: Notification): Notification.Action? {
        val candidates = n.actions.orEmpty().filter { a ->
            a.actionIntent != null && a.remoteInputs.orEmpty().any { it.allowFreeFormInput }
        }
        return candidates.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY } ?: candidates.firstOrNull()
    }

    private fun loadSenderAvatars(senders: List<Sender>, key: String): List<ImageRef> =
        senders.take(MAX_SENDERS).mapIndexed { i, sender ->
            val cacheKey = "sender:$key:$i"
            val bmp = sender.icon?.let { icon ->
                try {
                    icon.loadDrawable(context)?.let { Bitmaps.fromDrawable(it, AVATAR_PX) }
                } catch (_: Exception) {
                    null
                }
            } ?: Bitmaps.monogram(sender.name, AVATAR_PX)
            images.put(cacheKey, bmp)
        }

    private fun extractContent(n: Notification): Content {
        val extras = n.extras
        var title = (extras.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()
        var text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val extra = ArrayList<String>()
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.mapTo(extra) { it.toString() }

        var isConversation = false
        var conversationKey = n.shortcutId.orEmpty()
        var personIcon: Icon? = null
        var senders = emptyList<Sender>()
        var senderCount = 0
        val messaging = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val messages = messaging?.messages.orEmpty()
        if (messaging != null && messages.isNotEmpty()) {
            isConversation = true
            val last = messages.last()
            val sender = last.person?.name?.toString()
            val group = messaging.isGroupConversation
            text = if (group && !sender.isNullOrBlank()) "$sender: ${last.text}" else last.text?.toString().orEmpty()
            title = messaging.conversationTitle?.toString() ?: sender ?: title
            conversationKey = conversationKey.ifEmpty { title }
            extra.clear()
            messages.dropLast(1).takeLast(4).forEach { m ->
                val who = m.person?.name?.toString()
                extra += if (group && !who.isNullOrBlank()) "$who: ${m.text}" else m.text?.toString().orEmpty()
            }
            personIcon = last.person?.icon?.toIcon(context)
            if (group) {
                // Messages with no person are the user's own; everyone else is a sender.
                val distinct = messages.asReversed()
                    .mapNotNull { m -> m.person?.takeIf { !it.name.isNullOrBlank() } }
                    .distinctBy { it.key ?: it.name.toString() }
                senderCount = distinct.size
                senders = distinct.take(MAX_SENDERS).map { Sender(it.name.toString(), it.icon?.toIcon(context)) }
            }
        }
        return Content(title.trim(), text.trim(), extra.filter { it.isNotBlank() }, isConversation, conversationKey, personIcon, senders, senderCount)
    }

    // ---------------------------------------------------------------------------------------------
    // Calls
    // ---------------------------------------------------------------------------------------------

    private fun handleCall(sbn: StatusBarNotification) {
        val n = sbn.notification
        val extras = n.extras
        val key = sbn.key

        var answer: PendingIntent? = null
        var decline: PendingIntent? = null
        var hangUp: PendingIntent? = null
        var person: Person? = null
        var callType = 0
        var isVideo = false
        if (Build.VERSION.SDK_INT >= 31) {
            answer = BundleCompat.getParcelable(extras, Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java)
            decline = BundleCompat.getParcelable(extras, Notification.EXTRA_DECLINE_INTENT, PendingIntent::class.java)
            hangUp = BundleCompat.getParcelable(extras, Notification.EXTRA_HANG_UP_INTENT, PendingIntent::class.java)
            person = BundleCompat.getParcelable(extras, Notification.EXTRA_CALL_PERSON, Person::class.java)
            callType = extras.getInt(Notification.EXTRA_CALL_TYPE, 0)
            isVideo = extras.getBoolean(Notification.EXTRA_CALL_IS_VIDEO)
        }
        // Dialers that don't use CallStyle: match their own action labels.
        val actions = n.actions.orEmpty()
        fun find(vararg words: String) = actions.firstOrNull { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            // Whole words only: "end" must not match "Send message".
            words.any { w -> Regex("\\b${Regex.escape(w)}\\b").containsMatchIn(t) }
        }?.actionIntent
        answer = answer ?: find("answer", "accept", "pick up")
        decline = decline ?: find("decline", "reject")
        hangUp = hangUp ?: find("hang up", "end call", "hangup", "end")

        val usesChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val incoming = when (callType) {
            CALL_TYPE_INCOMING, CALL_TYPE_SCREENING -> true
            CALL_TYPE_ONGOING -> false
            else -> !usesChronometer && (answer != null || n.fullScreenIntent != null)
        }
        val name = person?.name?.toString() ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val detail = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val pkg = sbn.packageName
        val open = n.contentIntent ?: n.fullScreenIntent

        val eventActions = buildList {
            if (incoming) {
                decline?.let { pi -> add(IslandAction(CallPresenter.ACTION_DECLINE, "Decline", Glyph.PHONE_DOWN, ActionStyle.DESTRUCTIVE, collapses = true) { Launch.send(context, pi) }) }
                answer?.let { pi -> add(IslandAction(CallPresenter.ACTION_ANSWER, "Answer", Glyph.PHONE, ActionStyle.POSITIVE, collapses = true) { Launch.send(context, pi) }) }
            } else {
                open?.let { pi -> add(IslandAction(CallPresenter.ACTION_OPEN, "Return to call", Glyph.OPEN, collapses = true) { Launch.send(context, pi) }) }
                hangUp?.let { pi -> add(IslandAction(CallPresenter.ACTION_HANGUP, "Hang up", Glyph.PHONE_DOWN, ActionStyle.DESTRUCTIVE, collapses = true) { Launch.send(context, pi) }) }
            }
        }
        val tap = IslandAction("call.tap", "Open call") { Launch.send(context, open) || Launch.openApp(context, pkg) }

        scope.launch {
            val avatar = withContext(Dispatchers.Default) {
                val icon = if (Build.VERSION.SDK_INT >= 31) person?.icon else null
                loadLargeIcon(n, icon, "call:$key")
            }
            engine.post(
                IslandEvent(
                    id = callId(key),
                    source = EventSource.CALL,
                    type = if (incoming) EventType.CALL_INCOMING else EventType.CALL_ONGOING,
                    timestamp = System.currentTimeMillis(),
                    persistent = true,
                    title = name.ifBlank { apps.label(pkg) },
                    subtitle = detail,
                    icon = Glyph.PHONE,
                    artwork = avatar,
                    actions = eventActions,
                    autoExpand = incoming,
                    blocksToasts = incoming,
                    tapAction = tap,
                    contentKey = "$incoming",
                    animation = EntranceAnimation.BLOOM,
                    payload = CallPayload(
                        callerName = name,
                        detail = detail,
                        appLabel = apps.label(pkg),
                        state = if (incoming) CallState.INCOMING else CallState.ONGOING,
                        chronometerBase = if (usesChronometer && n.`when` > 0) n.`when` else null,
                        isVideo = isVideo,
                        hasAvatar = avatar != null,
                    ),
                )
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Navigation & progress
    // ---------------------------------------------------------------------------------------------

    private fun handleNavigation(sbn: StatusBarNotification) {
        val provider = NavigationRegistry.providerFor(sbn) ?: return
        val info = provider.parse(sbn) ?: return
        val n = sbn.notification
        val pkg = sbn.packageName
        val key = sbn.key
        scope.launch {
            val maneuver = withContext(Dispatchers.Default) { loadLargeIcon(n, null, "nav:$key") }
            engine.post(
                IslandEvent(
                    id = navId(key),
                    source = EventSource.NAVIGATION,
                    type = EventType.NAVIGATION,
                    timestamp = System.currentTimeMillis(),
                    persistent = true,
                    title = info.distance,
                    subtitle = info.instruction,
                    icon = Glyph.NAV_ARROW,
                    artwork = maneuver,
                    colors = EventColors(accent = n.color),
                    tapAction = IslandAction("nav.open", "Open navigation") { Launch.send(context, n.contentIntent) || Launch.openApp(context, pkg) },
                    contentKey = "${info.distance}|${info.instruction}",
                    payload = NavigationPayload(apps.label(pkg), info.distance, info.instruction, info.eta, maneuver != null),
                )
            )
        }
    }

    private class Route(
        val progress: Float?,
        val points: List<Float>,
        val segments: List<Pair<Float, Int>>,
        val tracker: Icon?,
    )

    /** Progress, stops, segments and tracker icon from Notification.ProgressStyle (Android 16). */
    private fun route(sbn: StatusBarNotification): Route {
        val n = sbn.notification
        if (Build.VERSION.SDK_INT >= 36) {
            val style = try {
                Notification.Builder.recoverBuilder(context, n).style as? Notification.ProgressStyle
            } catch (e: RuntimeException) {
                null
            }
            if (style != null) {
                val max = style.progressMax.coerceAtLeast(1).toFloat()
                return Route(
                    progress = if (style.isProgressIndeterminate) null else (style.progress / max).coerceIn(0f, 1f),
                    points = style.progressPoints.map { (it.position / max).coerceIn(0f, 1f) },
                    segments = style.progressSegments.map { it.length.toFloat() to it.color },
                    tracker = style.progressTrackerIcon,
                )
            }
        }
        val max = n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val progress = if (max > 0) (n.extras.getInt(Notification.EXTRA_PROGRESS, 0).toFloat() / max).coerceIn(0f, 1f) else null
        return Route(progress, emptyList(), emptyList(), null)
    }

    /** The app's own chip text on Android 16, else an ETA found in the title or text. */
    private fun shortStatus(n: Notification, title: String, text: String): String? {
        if (Build.VERSION.SDK_INT >= 36) n.shortCriticalText?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        for (source in listOf(title, text)) {
            ETA_MINUTES.find(source)?.let { m ->
                val range = m.groupValues[2].takeIf { it.isNotEmpty() }
                return if (range != null) "${m.groupValues[1]}–$range min" else "${m.groupValues[1]} min"
            }
            ETA_TIME.find(source)?.let { return it.groupValues[1].trim() }
        }
        return null
    }

    private fun handleLiveUpdate(sbn: StatusBarNotification, s: IslandSettings) {
        val n = sbn.notification
        val extras = n.extras
        val pkg = sbn.packageName
        val key = sbn.key
        val rule = s.ruleFor(pkg)
        val showText = (rule.privacy ?: s.notificationPrivacy) == NotificationPrivacy.FULL && rule.showText
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val rawText = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val appLabel = apps.label(pkg)
        val short = shortStatus(n, rawTitle, rawText)
        val route = route(sbn)
        val open = IslandAction("live.open", "Open") { Launch.send(context, n.contentIntent) || Launch.openApp(context, pkg) }
        scope.launch {
            val (appRef, trackerRef) = withContext(Dispatchers.Default) {
                val tracker = route.tracker?.let { icon ->
                    try {
                        icon.loadDrawable(context)?.let { images.put("tracker:$key", Bitmaps.fromDrawable(it, AVATAR_PX)) }
                    } catch (e: Exception) {
                        null
                    }
                }
                loadAppIcon(pkg) to tracker
            }
            val accent = rule.accent.takeIf { it != 0 }
                ?: n.color.takeIf { it != 0 && it != Notification.COLOR_DEFAULT }
                ?: withContext(Dispatchers.Default) { apps.accentBlocking(pkg) }
            val title = if (showText) rawTitle.ifBlank { appLabel } else appLabel
            val text = if (showText) rawText else ""
            engine.post(
                IslandEvent(
                    id = liveId(key),
                    source = EventSource.PROGRESS,
                    type = EventType.LIVE_UPDATE,
                    timestamp = System.currentTimeMillis(),
                    persistent = true,
                    title = title,
                    subtitle = short ?: text,
                    icon = if (pkg in RIDE_APPS) Glyph.CAR else Glyph.SCOOTER,
                    iconImage = appRef,
                    artwork = trackerRef,
                    colors = EventColors(accent = accent),
                    tapAction = open,
                    contentKey = "live",
                    payload = LiveUpdatePayload(
                        appLabel = appLabel,
                        packageName = pkg,
                        title = title,
                        text = text,
                        shortText = short,
                        progress = route.progress,
                        points = route.points,
                        segments = route.segments,
                        hasTrackerIcon = trackerRef != null,
                        vehicle = if (pkg in RIDE_APPS) Glyph.CAR else Glyph.SCOOTER,
                    ),
                )
            )
        }
    }

    private fun handleProgress(sbn: StatusBarNotification) {
        val n = sbn.notification
        val extras = n.extras
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val current = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        val fraction = if (max > 0) (current.toFloat() / max).coerceIn(0f, 1f) else 0f
        val key = sbn.key
        val pkg = sbn.packageName
        lastProgress[key] = if (indeterminate) 0f else fraction
        if (!indeterminate && fraction >= 1f) {
            engine.remove(progressId(key))
            postProgressDone(sbn)
            lastProgress.remove(key)
            return
        }
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().ifBlank { apps.label(pkg) }
        scope.launch {
            val icon = withContext(Dispatchers.Default) { loadAppIcon(pkg) }
            engine.post(
                IslandEvent(
                    id = progressId(key),
                    source = EventSource.PROGRESS,
                    type = EventType.PROGRESS,
                    timestamp = System.currentTimeMillis(),
                    persistent = true,
                    title = title,
                    icon = Glyph.DOWNLOAD,
                    iconImage = icon,
                    progress = fraction,
                    tapAction = IslandAction("progress.open", "Open") { Launch.send(context, n.contentIntent) || Launch.openApp(context, pkg) },
                    contentKey = "progress",
                    payload = ProgressPayload(apps.label(pkg), pkg, title, fraction, indeterminate, done = false),
                )
            )
        }
    }

    private fun postProgressDone(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().ifBlank { apps.label(pkg) }
        engine.post(
            IslandEvent(
                id = "progress-done:${sbn.key}",
                source = EventSource.PROGRESS,
                type = EventType.PROGRESS_DONE,
                timestamp = System.currentTimeMillis(),
                persistent = false,
                durationMs = 2_400,
                title = title,
                subtitle = "Done",
                icon = Glyph.CHECK,
                iconImage = "app:$pkg@96".takeIf(images::contains)?.let(::ImageRef),
                progress = 1f,
                animation = EntranceAnimation.POP,
                payload = ProgressPayload(apps.label(pkg), pkg, title, 1f, indeterminate = false, done = true),
            )
        )
    }

    // ---------------------------------------------------------------------------------------------
    // System Clock: Timers, Stopwatch & Alarms
    // ---------------------------------------------------------------------------------------------

    fun isClockAppNotification(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName.lowercase()
        return pkg in CLOCK_PACKAGES || pkg.endsWith(".clockpackage") || pkg.endsWith(".deskclock") || pkg.endsWith(".alarmclock")
    }

    private fun handleClockNotification(sbn: StatusBarNotification, s: IslandSettings) {
        val n = sbn.notification ?: return
        val key = sbn.key
        val pkg = sbn.packageName
        val extras = n.extras
        val channel = n.channelId.orEmpty().lowercase()
        val tag = sbn.tag.orEmpty().lowercase()
        val rawTitle = (extras.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()
        val rawText = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val actionTitles = n.actions.orEmpty().mapNotNull { it.title?.toString()?.lowercase() }.joinToString(" ")
        val meta = "$channel $tag ${rawTitle.lowercase()} ${rawText.lowercase()} ${subText.lowercase()} $actionTitles"

        val isCountDownChrono = extras.getBoolean("android.chronometerCountDown", false)
        val usesChrono = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)

        val isStopwatch = meta.contains("stopwatch") ||
            meta.contains("bấm giờ") ||
            actionTitles.contains("lap") ||
            actionTitles.contains("vòng") ||
            (usesChrono && !isCountDownChrono && n.`when` in 1..System.currentTimeMillis() && !meta.contains("timer") && !meta.contains("hẹn giờ") && !meta.contains("alarm") && !meta.contains("báo thức"))

        val isTimer = !isStopwatch && (
            isCountDownChrono ||
                meta.contains("timer") ||
                meta.contains("hẹn giờ") ||
                meta.contains("đếm ngược") ||
                meta.contains("countdown")
            )

        when {
            isStopwatch -> {
                if (s.stopwatchEnabled) handleClockStopwatch(sbn, rawTitle, rawText, actionTitles)
                else engine.remove(clockStopwatchId(key))
            }
            isTimer -> {
                if (s.timersEnabled) handleClockTimer(sbn, rawTitle, rawText, subText, actionTitles, isCountDownChrono, usesChrono)
                else engine.remove(clockTimerId(key))
            }
            else -> {
                if (s.alarmsEnabled) handleClockAlarm(sbn, rawTitle, rawText, s)
                else engine.remove(clockAlarmId(key))
            }
        }
    }

    private fun handleClockTimer(
        sbn: StatusBarNotification,
        rawTitle: String,
        rawText: String,
        subText: String,
        actionTitles: String,
        isCountDownChrono: Boolean,
        usesChrono: Boolean,
    ) {
        val n = sbn.notification
        val key = sbn.key
        val pkg = sbn.packageName
        val now = System.currentTimeMillis()
        val actions = n.actions.orEmpty()

        fun findAction(vararg words: String): Notification.Action? = actions.firstOrNull { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            words.any { w -> t.contains(w) }
        }

        val pauseResumeAction = findAction("pause", "tạm dừng", "resume", "tiếp tục", "start", "bắt đầu")
        val cancelStopAction = findAction("cancel", "hủy", "delete", "xóa", "dismiss", "bỏ qua", "stop", "dừng", "reset", "đặt lại")
        val addAction = findAction("+1", "add", "thêm", "1:00", "1 min", "1 phút", "restart", "khởi động lại")

        val isPaused = actionTitles.contains("resume") ||
            actionTitles.contains("tiếp tục") ||
            rawTitle.lowercase().contains("paused") ||
            rawText.lowercase().contains("paused") ||
            rawTitle.lowercase().contains("tạm dừng") ||
            rawText.lowercase().contains("tạm dừng")

        val parsedMs = parseDurationMs(rawTitle) ?: parseDurationMs(rawText) ?: parseDurationMs(subText)
        val chronoRemainingMs = if ((usesChrono || isCountDownChrono) && n.`when` > now) (n.`when` - now) else null
        val remainingMs = chronoRemainingMs ?: parsedMs ?: 0L

        val isRinging = !isPaused && (
            n.fullScreenIntent != null ||
                n.category == Notification.CATEGORY_ALARM ||
                (remainingMs <= 0L && (parsedMs == 0L || actionTitles.contains("dismiss") || actionTitles.contains("bỏ qua") || actionTitles.contains("stop") || actionTitles.contains("dừng"))) ||
                rawTitle.lowercase().contains("time's up") ||
                rawText.lowercase().contains("time's up") ||
                rawTitle.lowercase().contains("hết giờ") ||
                rawText.lowercase().contains("hết giờ")
            )

        val prevTotal = clockTotalMs[key] ?: 0L
        val totalMs = maxOf(prevTotal, remainingMs, 1_000L)
        clockTotalMs[key] = totalMs

        val endsAt = if (chronoRemainingMs != null) n.`when` else now + remainingMs
        val cleanLabel = listOf(rawTitle, rawText, subText)
            .firstOrNull { it.isNotBlank() && parseDurationMs(it) == null }
            ?.takeIf { it.length <= 32 }
            ?: if (settings().language == AppLanguage.VIETNAMESE) "Hẹn giờ" else "Timer"

        val openAction = IslandAction("clock.timer.open", "Open Clock") {
            Launch.send(context, n.contentIntent ?: n.fullScreenIntent) || Launch.openApp(context, pkg)
        }

        val islandActions = if (isRinging) {
            buildList {
                addAction?.actionIntent?.let { pi ->
                    add(IslandAction(TimerPresenter.ACTION_ADD, "+1 min", Glyph.PLUS) { Launch.send(context, pi) })
                }
                val stopPi = (cancelStopAction ?: actions.firstOrNull())?.actionIntent
                add(
                    IslandAction(TimerPresenter.ACTION_STOP, "Stop", Glyph.STOP, ActionStyle.PRIMARY, collapses = true) {
                        if (stopPi != null) Launch.send(context, stopPi) else openAction.invoke()
                        cancel(key)
                        engine.remove(clockTimerId(key))
                    }
                )
            }
        } else {
            buildList {
                val togglePi = pauseResumeAction?.actionIntent
                add(
                    IslandAction(
                        TimerPresenter.ACTION_TOGGLE,
                        if (isPaused) "Resume" else "Pause",
                        if (isPaused) Glyph.PLAY else Glyph.PAUSE,
                    ) {
                        if (togglePi != null) Launch.send(context, togglePi) else openAction.invoke()
                    }
                )
                val cancelPi = cancelStopAction?.actionIntent
                add(
                    IslandAction(TimerPresenter.ACTION_SECONDARY, "Cancel", Glyph.CLOSE, collapses = true) {
                        if (cancelPi != null) Launch.send(context, cancelPi) else cancel(key)
                        engine.remove(clockTimerId(key))
                    }
                )
                addAction?.actionIntent?.let { pi ->
                    add(IslandAction(TimerPresenter.ACTION_ADD, "+1 min", Glyph.PLUS) { Launch.send(context, pi) })
                }
            }
        }

        val timerId = key.hashCode().toLong()
        engine.post(
            IslandEvent(
                id = clockTimerId(key),
                source = EventSource.TIMER,
                type = if (isRinging) EventType.TIMER_DONE else EventType.TIMER,
                timestamp = now,
                persistent = true,
                title = cleanLabel,
                icon = Glyph.TIMER,
                actions = islandActions,
                autoExpand = isRinging,
                blocksToasts = isRinging,
                dismissible = true,
                tapAction = openAction,
                contentKey = "clock-timer:$isPaused:$isRinging",
                payload = TimerPayload(
                    timerId = timerId,
                    label = cleanLabel,
                    totalMs = totalMs,
                    endsAt = endsAt,
                    remainingWhenPausedMs = remainingMs,
                    isPaused = isPaused,
                    isRinging = isRinging,
                    ringingSince = if (isRinging) now else 0L,
                ),
            )
        )
    }

    private fun handleClockStopwatch(
        sbn: StatusBarNotification,
        rawTitle: String,
        rawText: String,
        actionTitles: String,
    ) {
        val n = sbn.notification
        val key = sbn.key
        val pkg = sbn.packageName
        val now = System.currentTimeMillis()
        val extras = n.extras
        val usesChrono = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        val actions = n.actions.orEmpty()

        fun findAction(vararg words: String): Notification.Action? = actions.firstOrNull { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            words.any { w -> t.contains(w) }
        }

        val isRunning = !(
            actionTitles.contains("resume") ||
                actionTitles.contains("tiếp tục") ||
                actionTitles.contains("start") ||
                actionTitles.contains("bắt đầu") ||
                rawTitle.lowercase().contains("paused") ||
                rawText.lowercase().contains("paused") ||
                rawTitle.lowercase().contains("tạm dừng") ||
                rawText.lowercase().contains("tạm dừng")
            )

        val parsedMs = parseDurationMs(rawTitle) ?: parseDurationMs(rawText)
        val chronoElapsedMs = if (usesChrono && n.`when` in 1..now) (now - n.`when`) else null
        val elapsedMs = chronoElapsedMs ?: parsedMs ?: 0L

        val runningSince = if (isRunning) (now - elapsedMs) else now
        val accumulatedMs = if (isRunning) 0L else elapsedMs

        val laps = clockLaps.getOrPut(key) { ArrayList() }
        val openAction = IslandAction("clock.sw.open", "Open Clock") {
            Launch.send(context, n.contentIntent) || Launch.openApp(context, pkg)
        }

        val toggleAction = findAction("pause", "tạm dừng", "stop", "dừng", "resume", "tiếp tục", "start", "bắt đầu")
        val secondaryAction = if (isRunning) {
            findAction("lap", "vòng", "reset", "đặt lại")
        } else {
            findAction("reset", "đặt lại", "clear", "xóa", "lap", "vòng")
        }

        val islandActions = listOf(
            IslandAction(
                TimerPresenter.ACTION_TOGGLE,
                if (isRunning) "Stop" else "Start",
                if (isRunning) Glyph.STOP else Glyph.PLAY,
            ) {
                val pi = toggleAction?.actionIntent
                if (pi != null) Launch.send(context, pi) else openAction.invoke()
            },
            if (isRunning) {
                IslandAction(TimerPresenter.ACTION_SECONDARY, "Lap", Glyph.FLAG) {
                    val pi = secondaryAction?.actionIntent
                    if (pi != null) {
                        laps.add(elapsedMs)
                        Launch.send(context, pi)
                    } else {
                        openAction.invoke()
                    }
                }
            } else {
                IslandAction(TimerPresenter.ACTION_SECONDARY, "Reset", Glyph.CLOSE, collapses = true) {
                    val pi = secondaryAction?.actionIntent
                    laps.clear()
                    if (pi != null) Launch.send(context, pi) else cancel(key)
                    engine.remove(clockStopwatchId(key))
                }
            },
        )

        engine.post(
            IslandEvent(
                id = clockStopwatchId(key),
                source = EventSource.STOPWATCH,
                type = EventType.STOPWATCH,
                timestamp = now,
                persistent = true,
                title = if (settings().language == AppLanguage.VIETNAMESE) "Bấm giờ" else "Stopwatch",
                icon = Glyph.STOPWATCH,
                actions = islandActions,
                tapAction = openAction,
                contentKey = "clock-sw:$isRunning",
                payload = StopwatchPayload(
                    runningSince = runningSince,
                    accumulatedMs = accumulatedMs,
                    isRunning = isRunning,
                    laps = laps.toList(),
                ),
            )
        )
    }

    private fun handleClockAlarm(
        sbn: StatusBarNotification,
        rawTitle: String,
        rawText: String,
        s: IslandSettings,
    ) {
        val n = sbn.notification
        val key = sbn.key
        val pkg = sbn.packageName
        val now = System.currentTimeMillis()
        if (rawTitle.isBlank() && rawText.isBlank()) return

        val hash = (rawTitle + '\u0000' + rawText).hashCode()
        if (seen[key] == hash) return
        seen[key] = hash

        val isRinging = n.fullScreenIntent != null || n.category == Notification.CATEGORY_ALARM
        val isVi = s.language == AppLanguage.VIETNAMESE
        val title = rawTitle.ifBlank { if (isVi) "Báo thức" else "Alarm" }
        val subtitle = rawText.ifBlank { if (isVi) "Đồng hồ" else "Clock" }

        val actions = n.actions.orEmpty()
            .filter { it.actionIntent != null && !it.title.isNullOrBlank() }
            .take(2)
            .mapIndexed { i, action ->
                IslandAction(NotificationPresenter.ACTION_PREFIX + i, action.title.toString(), collapses = true) {
                    Launch.send(context, action.actionIntent)
                    engine.remove(clockAlarmId(key))
                }
            }

        val open = IslandAction("clock.alarm.open", "Open Clock") {
            Launch.send(context, n.contentIntent ?: n.fullScreenIntent) || Launch.openApp(context, pkg)
        }

        scope.launch {
            val appRef = withContext(Dispatchers.Default) { loadAppIcon(pkg) }
            engine.post(
                IslandEvent(
                    id = clockAlarmId(key),
                    source = EventSource.NOTIFICATION,
                    type = EventType.NOTIFICATION,
                    priority = if (isRinging) EventPriority.CRITICAL_SYSTEM_EVENT else EventPriority.CHARGING,
                    timestamp = now,
                    persistent = isRinging,
                    durationMs = 4_500L,
                    title = title,
                    subtitle = subtitle,
                    icon = Glyph.ALARM,
                    iconImage = appRef,
                    actions = actions,
                    autoExpand = isRinging,
                    blocksToasts = isRinging,
                    tapAction = open,
                    colors = EventColors(accent = 0xFFFFA23A.toInt()),
                    animation = EntranceAnimation.POP,
                    contentKey = hash.toString(),
                    payload = NotificationPayload(
                        packageName = pkg,
                        appLabel = apps.label(pkg),
                        title = title,
                        text = subtitle,
                        extraLines = emptyList(),
                        privacy = NotificationPrivacy.FULL,
                        showText = true,
                        postedAt = sbn.postTime,
                        hasLargeIcon = false,
                        isConversation = false,
                    ),
                )
            )
        }
    }

    private fun parseDurationMs(text: String): Long? {
        if (text.isBlank()) return null
        CLOCK_HMS_REGEX.find(text)?.let { m ->
            val p1 = m.groupValues[1].toLongOrNull() ?: return null
            val p2 = m.groupValues[2].toLongOrNull() ?: return null
            val p3 = m.groupValues[3].takeIf { it.isNotEmpty() }?.toLongOrNull()
            val hundredths = m.groupValues[4].takeIf { it.isNotEmpty() }?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0L
            val totalSeconds = if (p3 != null) {
                p1 * 3600L + p2 * 60L + p3
            } else {
                p1 * 60L + p2
            }
            return totalSeconds * 1_000L + hundredths * 10L
        }
        return null
    }

    // ---------------------------------------------------------------------------------------------
    // Voice Recorder & Weather app notifications
    // ---------------------------------------------------------------------------------------------

    fun isVoiceRecorderNotification(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName.lowercase()
        if (pkg in VOICE_RECORDER_PACKAGES) return true
        if (pkg.contains("voicenote") || pkg.contains("soundrecorder") || pkg.contains("audiorecorder")) return true
        return false
    }

    private fun handleVoiceRecorderNotification(sbn: StatusBarNotification, s: IslandSettings) {
        if (!s.audioRecordEnabled) {
            engine.remove(audioRecordId(sbn.key))
            return
        }
        val n = sbn.notification ?: return
        val ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0
        val rawTitle = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val rawText = (
            n.extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
            )?.toString()?.trim().orEmpty()
        val actionTitles = n.actions.orEmpty().mapNotNull { it.title?.toString()?.lowercase() }.joinToString(" ")
        val meta = "${rawTitle.lowercase()} ${rawText.lowercase()} $actionTitles"

        val hasRecordControls = meta.contains("stop") ||
            meta.contains("pause") ||
            meta.contains("resume") ||
            meta.contains("dừng") ||
            meta.contains("tạm dừng") ||
            meta.contains("tiếp tục") ||
            meta.contains("recording") ||
            meta.contains("đang ghi") ||
            meta.contains("ghi âm")

        if (!ongoing && !hasRecordControls) {
            engine.remove(audioRecordId(sbn.key))
            return
        }

        // Remove fallback AudioRecordingMonitor event since we have the rich notification with actions
        engine.remove(AudioRecordingMonitor.ID)

        val now = System.currentTimeMillis()
        val parsedMs = parseDurationMs(rawTitle) ?: parseDurationMs(rawText)
        val isPaused = actionTitles.contains("resume") ||
            actionTitles.contains("tiếp tục") ||
            rawTitle.lowercase().contains("paused") ||
            rawText.lowercase().contains("paused") ||
            rawTitle.lowercase().contains("tạm dừng") ||
            rawText.lowercase().contains("tạm dừng")

        val chronometerBase = if (n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) && n.`when` > 0L && n.`when` <= now) {
            n.`when`
        } else {
            null
        }
        val elapsedMs = when {
            parsedMs != null -> parsedMs
            chronometerBase != null -> (now - chronometerBase).coerceAtLeast(0L)
            sbn.postTime in 1..now -> (now - sbn.postTime).coerceAtLeast(0L)
            else -> 0L
        }
        val startedAt = (now - elapsedMs).coerceAtMost(now)

        val pkg = sbn.packageName
        val appLabel = apps.label(pkg).ifBlank {
            if (s.language == AppLanguage.VIETNAMESE) "Ghi âm" else "Voice Recorder"
        }
        val displayTitle = rawTitle
            .takeIf { it.isNotBlank() && parseDurationMs(it) == null }
            ?: rawText.takeIf { it.isNotBlank() && parseDurationMs(it) == null }
            ?: if (s.language == AppLanguage.VIETNAMESE) "Bản ghi âm" else "Voice Memo"

        val actions = ArrayList<IslandAction>(3)
        for (a in n.actions.orEmpty()) {
            val intent = a.actionIntent ?: continue
            val t = a.title?.toString()?.trim().orEmpty()
            val tl = t.lowercase()
            when {
                tl.contains("pause") || tl.contains("resume") || tl.contains("tạm dừng") || tl.contains("tiếp tục") -> {
                    if (actions.none { it.id == AudioRecordPresenter.ACTION_TOGGLE }) {
                        actions += IslandAction(
                            id = AudioRecordPresenter.ACTION_TOGGLE,
                            label = t,
                            glyph = if (isPaused) Glyph.PLAY else Glyph.PAUSE,
                        ) {
                            Launch.send(context, intent)
                        }
                    }
                }
                tl.contains("stop") || tl.contains("save") || tl.contains("dừng") || tl.contains("lưu") || tl.contains("hoàn tất") -> {
                    if (actions.none { it.id == AudioRecordPresenter.ACTION_STOP }) {
                        actions += IslandAction(
                            id = AudioRecordPresenter.ACTION_STOP,
                            label = t,
                            glyph = Glyph.STOP,
                            style = ActionStyle.DESTRUCTIVE,
                            collapses = true,
                        ) {
                            Launch.send(context, intent)
                            engine.remove(audioRecordId(sbn.key))
                        }
                    }
                }
            }
        }

        val openAction = IslandAction(
            id = AudioRecordPresenter.ACTION_OPEN,
            label = if (s.language == AppLanguage.VIETNAMESE) "Mở Ghi âm" else "Open Recorder",
            glyph = Glyph.OPEN,
            collapses = true,
        ) {
            Launch.send(context, n.contentIntent) || Launch.openApp(context, pkg)
        }
        if (actions.isEmpty()) {
            actions += openAction
        }

        engine.post(
            IslandEvent(
                id = audioRecordId(sbn.key),
                source = EventSource.AUDIO_RECORD,
                type = EventType.AUDIO_RECORD,
                priority = EventPriority.TIMER,
                timestamp = now,
                persistent = true,
                title = displayTitle,
                subtitle = appLabel,
                icon = Glyph.MIC,
                actions = actions,
                tapAction = openAction,
                contentKey = "$isPaused",
                payload = AudioRecordPayload(
                    packageName = pkg,
                    appLabel = appLabel,
                    title = displayTitle,
                    startedAt = startedAt,
                    elapsedWhenPausedMs = elapsedMs,
                    isPaused = isPaused,
                ),
            )
        )
    }

    fun isWeatherNotification(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName.lowercase()
        return pkg in WEATHER_PACKAGES || pkg.contains(".weather")
    }

    private fun handleWeatherNotification(sbn: StatusBarNotification, s: IslandSettings) {
        if (!s.weatherEnabled) return
        val n = sbn.notification ?: return
        val content = extractContent(n)
        if (content.title.isBlank() && content.text.isBlank()) return
        val hash = (content.title + '\u0000' + content.text).hashCode()
        if (seen[sbn.key] == hash) return
        seen[sbn.key] = hash
        onWeatherNotification?.invoke(content.title, content.text)
    }

    // ---------------------------------------------------------------------------------------------

    private fun cancel(key: String) {
        try {
            listener?.cancelNotification(key)
        } catch (_: SecurityException) {
        }
    }

    /**
     * Prevents duplicate system pop-ups safely without cancelling or destroying the system notification.
     * We MUST NEVER call cancelNotification() or snoozeNotification() here, as doing so immediately
     * commands Android's NotificationManager to abort the notification's ringtone sound and vibration!
     */
    private fun absorbSystemNotification(sbn: StatusBarNotification) {
        // Preserved empty: System heads-up pop-up banners are managed cleanly via
        // SystemPopups (heads_up_notifications_enabled = 0), allowing full native sound & vibration!
    }

    override fun toString(): String = "NotificationProcessor"

    /**
     * Snoozes or cancels a status bar notification so One UI removes its status bar chip/icon
     * (e.g., the `[▷ Song]` media chip or system status icons) without Root or ADB.
     * MediaSessionManager continues reporting active media playback to [com.arnav.island.events.media.MediaSessionMonitor].
     */
    private fun suppressStatusBarChip(sbn: StatusBarNotification) {
        val svc = listener ?: return
        val key = sbn.key
        absorbedKeys.add(key)
        try {
            svc.snoozeNotification(key, CHIP_SNOOZE_MS)
        } catch (_: Exception) {
            runCatching { if (sbn.isClearable) svc.cancelNotification(key) }
        }
    }

    /**
     * Matches system status bar notifications corresponding to the user's selected
     * `StatusBarCleanup.ICONS` slots (`vpn`, `hotspot`, `data_saver`, `bluetooth`, `zen`,
     * `volume`, `rotate`, `headset`, `alarm_clock`, `nfc`) so toggling those switches in
     * BreezeIsland suppresses their status bar notifications without Root or ADB.
     */
    private fun matchesStatusBarSlot(sbn: StatusBarNotification, selectedSlots: Set<String>): Boolean {
        if (selectedSlots.isEmpty()) return false
        val n = sbn.notification ?: return false
        if (isCall(n) || NavigationRegistry.providerFor(sbn) != null || isLiveUpdate(sbn) || hasProgress(n)) {
            return false
        }
        val pkg = sbn.packageName.lowercase()
        val isSystemPkg = pkg == "android" ||
            pkg.startsWith("com.android.") ||
            pkg.startsWith("com.samsung.") ||
            pkg.startsWith("com.sec.") ||
            pkg.startsWith("com.qualcomm.")
        if (!isSystemPkg) return false

        val channel = n.channelId.orEmpty().lowercase()
        val tag = sbn.tag.orEmpty().lowercase()
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.lowercase().orEmpty()
        val meta = "$pkg $channel $tag $title"

        if ("vpn" in selectedSlots && (pkg.contains("vpndialogs") || channel.contains("vpn") || tag.contains("vpn"))) return true
        if ("hotspot" in selectedSlots && (meta.contains("hotspot") || meta.contains("tether") || meta.contains("wifi_ap"))) return true
        if ("data_saver" in selectedSlots && (meta.contains("data_saver") || meta.contains("datasaver") || meta.contains("restrict_background"))) return true
        if ("bluetooth" in selectedSlots && (pkg.contains("bluetooth") || channel.contains("bluetooth") || tag.contains("bluetooth"))) return true
        if ("zen" in selectedSlots && (channel.contains("zen") || channel.contains("dnd") || tag.contains("zen") || tag.contains("dnd"))) return true
        if ("volume" in selectedSlots && (channel.contains("volume") || channel.contains("ringer") || tag.contains("volume"))) return true
        if ("rotate" in selectedSlots && (channel.contains("rotate") || channel.contains("rotation") || tag.contains("rotate"))) return true
        if ("headset" in selectedSlots && (channel.contains("headset") || channel.contains("usb_audio") || tag.contains("headset"))) return true
        if ("nfc" in selectedSlots && (pkg.contains("nfc") || channel.contains("nfc") || tag.contains("nfc"))) return true
        if (("flashlight" in selectedSlots || "torch" in selectedSlots) && isFlashlightNotification(sbn)) return true
        return false
    }

    fun isFlashlightNotification(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName.lowercase()
        val isSystemPkg = pkg == "android" ||
            pkg.startsWith("com.android.") ||
            pkg.startsWith("com.samsung.") ||
            pkg.startsWith("com.sec.") ||
            pkg.startsWith("com.qualcomm.")
        if (!isSystemPkg) return false
        val n = sbn.notification ?: return false
        val channel = n.channelId.orEmpty().lowercase()
        val tag = sbn.tag.orEmpty().lowercase()
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.lowercase().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.lowercase().orEmpty()
        val subText = n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.lowercase().orEmpty()
        val actions = n.actions.orEmpty().mapNotNull { it.title?.toString()?.lowercase() }.joinToString(" ")
        val meta = "$channel $tag $title $text $subText $actions"
        return meta.contains("flashlight") ||
            meta.contains("torch") ||
            meta.contains("đèn pin") ||
            meta.contains("đèn flash")
    }

    private fun parseCsv(csv: String): Set<String> =
        csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** Loads the large icon (or a person icon) off the main thread. */
    private fun loadLargeIcon(n: Notification, preferred: Icon?, key: String): ImageRef? {
        val icon = preferred ?: n.getLargeIcon() ?: return null
        return try {
            val drawable = icon.loadDrawable(context) ?: return null
            images.put("large:$key", Bitmaps.fromDrawable(drawable, ICON_PX))
        } catch (_: Exception) {
            null
        }
    }

    private fun loadAppIcon(pkg: String): ImageRef? {
        val key = "app:$pkg@96"
        if (images.contains(key)) return ImageRef(key)
        val bmp = apps.iconBlocking(pkg, 96) ?: return null
        return images.put(key, bmp)
    }

    private fun alertId(key: String) = "notif:$key"
    private fun callId(key: String) = "call:$key"
    private fun navId(key: String) = "nav:$key"
    private fun liveId(key: String) = "live:$key"
    private fun progressId(key: String) = "progress:$key"
    private fun clockTimerId(key: String) = "clock-timer:$key"
    private fun clockStopwatchId(key: String) = "clock-stopwatch:$key"
    private fun clockAlarmId(key: String) = "clock-alarm:$key"
    private fun audioRecordId(key: String) = "audiorec:$key"

    companion object {
        private const val ICON_PX = 144
        private const val AVATAR_PX = 96
        private const val MAX_SENDERS = 3
        private const val CHIP_SNOOZE_MS = 3_600_000L

        private val CLOCK_PACKAGES = setOf(
            "com.sec.android.app.clockpackage",
            "com.samsung.android.app.clockpackage",
            "com.google.android.deskclock",
            "com.android.deskclock",
            "com.coloros.alarmclock",
            "com.oplus.alarmclock",
            "com.oneplus.deskclock",
            "com.android.bbkclock",
            "com.asus.deskclock",
            "com.lge.clock",
        )
        private val VOICE_RECORDER_PACKAGES = setOf(
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
        private val WEATHER_PACKAGES = setOf(
            "com.sec.android.daemonapp",
            "com.samsung.android.weather",
            "com.miui.weather2",
            "com.coloros.weather2",
            "com.huawei.android.totemweather",
        )
        private val CLOCK_HMS_REGEX = Regex("""\b(\d{1,2}):(\d{2})(?::(\d{2}))?(?:[.,](\d{1,2}))?\b""")

        private val DELIVERY_APPS = setOf(
            "in.swiggy.android", "com.application.zomato", "com.ubercab.eats", "com.grofers.customerapp",
            "com.zeptoconsumerapp", "com.bigbasket.mobileapp", "com.dunzo.user", "in.amazon.mShop.android.shopping",
            "com.flipkart.android", "com.dominos", "com.deliveroo.orderapp", "com.dd.doordash", "com.grubhub.android",
            "com.instacart.client", "com.global.foodpanda.android", "com.talabat",
        )
        private val RIDE_APPS = setOf(
            "com.ubercab", "com.olacabs.customer", "com.rapido.passenger", "me.lyft.android", "ee.mtakso.client",
            "com.grabtaxi.passenger", "com.bolt.client",
        )
        private val ETA_MINUTES = Regex("""(\d{1,3})\s*(?:(?:-|–|to)\s*(\d{1,3}))?\s*(?:min|mins|minutes)\b""", RegexOption.IGNORE_CASE)
        private val ETA_TIME = Regex("""(?:arriv\w*|reach\w*|deliver\w*|by|at|ETA)\s*(?:by|at|in)?\s*(\d{1,2}:\d{2}\s*(?:am|pm)?)""", RegexOption.IGNORE_CASE)

        // Notification.CALL_TYPE_* (API 31).
        private const val CALL_TYPE_INCOMING = 1
        private const val CALL_TYPE_ONGOING = 2
        private const val CALL_TYPE_SCREENING = 3
    }
}
