package com.arnav.island.settings.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsPaused
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.island.BuildConfig
import com.arnav.island.core.AppGraph
import com.arnav.island.events.test.TestEvents
import com.arnav.island.overlay.StatusBarCleanup
import com.arnav.island.overlay.SystemPopups
import com.arnav.island.permissions.Permissions
import com.arnav.island.settings.ui.Dest
import com.arnav.island.settings.ui.Ui
import com.arnav.island.settings.ui.components.Group
import com.arnav.island.settings.ui.components.InfoCard
import com.arnav.island.settings.ui.components.NavRow
import com.arnav.island.settings.ui.components.SettingRow
import com.arnav.island.settings.ui.components.SettingsPage
import com.arnav.island.settings.ui.components.SliderRow
import com.arnav.island.settings.ui.components.SwitchRow
import com.arnav.island.settings.ui.preview.IslandPreview
import com.arnav.island.settings.ui.preview.PreviewIsland
import com.arnav.island.settings.ui.theme.BadgeColors
import com.arnav.island.settings.ui.theme.LocalIslandColors
import com.arnav.island.settings.ui.tr
import com.arnav.island.storage.IslandSettings
import com.arnav.island.util.Diagnostics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

// -------------------------------------------------------------------------------------------------
// Blend with One UI
// -------------------------------------------------------------------------------------------------

/** Samsung's own notification settings (status bar icons, pop-up style). */
private fun notificationSettings() = Intent("android.settings.NOTIFICATION_SETTINGS")

@Composable
fun BlendScreen(s: IslandSettings, ui: Ui) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(StatusBarCleanup.canWrite(context)) }
    var writeSystem by remember { mutableStateOf(StatusBarCleanup.canWriteSystem(context)) }
    var notifAccess by remember { mutableStateOf(Permissions.hasNotificationAccess(context)) }
    var scale by remember { mutableStateOf(animationScale(context)) }
    LifecycleResumeEffect(Unit) {
        granted = StatusBarCleanup.canWrite(context)
        writeSystem = StatusBarCleanup.canWriteSystem(context)
        notifAccess = Permissions.hasNotificationAccess(context)
        if (writeSystem) StatusBarCleanup.sync(context, s)
        scale = animationScale(context)
        onPauseOrDispose { }
    }

    SettingsPage("Blend with One UI", onBack = ui::back) {
        item {
            InfoCard(
                title = "Make BreezeIsland sit on top cleanly",
                body = "On Galaxy Z Flip5 (One UI 9), BreezeIsland prevents status bar notifications and media pills from overlapping the Dynamic Island — without using Accessibility Service so your banking apps always work.",
                icon = Icons.Rounded.PhoneAndroid,
                color = BadgeColors.Blue,
            )
        }
        item {
            Group(
                title = "Phone-only setup (No PC required)",
                footer = "Granting \"Modify system settings\" lets BreezeIsland automatically set One UI's status bar notification icons to None (simple_status_bar = 0). Turning off Now Bar → Media player removes One UI 9's duplicate [▷ Song] pill.",
            ) {
                SettingRow(
                    "Modify system settings",
                    if (writeSystem) "Granted · One UI status bar notification icons hidden" else "Tap to grant directly on your phone",
                    Icons.Rounded.LockOpen,
                    if (writeSystem) BadgeColors.Green else BadgeColors.Blue,
                    onClick = { ui.open(Permissions.writeSettingsIntent(context)) },
                )
                NavRow(
                    "Turn off One UI 9 Live Media chip",
                    "Lock screen & AOD → Now bar → Live notifications → turn off Media player",
                    Icons.Rounded.MusicNote,
                    BadgeColors.Pink,
                ) {
                    ui.open(Permissions.samsungNowBarSettings(), Intent(Settings.ACTION_SETTINGS))
                }
                NavRow(
                    "One UI Status bar settings",
                    "Notifications → Status bar → Show notification icons → None",
                    Icons.Rounded.Notifications,
                    BadgeColors.Red,
                ) {
                    ui.open(Permissions.samsungStatusBarSettings(), notificationSettings())
                }
            }
        }
        item {
            Group(title = "Status bar") {
                SwitchRow(
                    "Seamless status bar",
                    s.seamlessStatusBar,
                    { v ->
                        ui.update { it.copy(seamlessStatusBar = v) }
                        if (v && !writeSystem) ui.open(Permissions.writeSettingsIntent(context))
                    },
                    "Hides conflicting status bar chips while the island is active, and clears the clock & icons while a card is open.",
                    Icons.Rounded.Layers,
                    BadgeColors.Indigo,
                )
                NavRow("Status bar cleanup", "Hide icons the island already shows", Icons.Rounded.VisibilityOff, BadgeColors.Graphite) { ui.go(Dest.STATUS_BAR) }
            }
        }
        item {
            val footer = "Right now One UI pop-ups are ${if (s.replaceSystemPopups && notifAccess) "off (BreezeIsland is showing them)" else "on"}. They come back by themselves whenever the island is hidden, the screen is locked or BreezeIsland stops."
            Group(title = "Notifications", footer = footer) {
                SwitchRow(
                    "Replace One UI pop-ups",
                    s.replaceSystemPopups,
                    { v ->
                        ui.update { it.copy(replaceSystemPopups = v) }
                        if (v && !notifAccess) {
                            ui.open(Permissions.notificationAccessSettings(context), Permissions.notificationAccessFallback())
                        }
                    },
                    "Each notification appears once, in the island, instead of twice",
                    Icons.Rounded.NotificationsPaused,
                    BadgeColors.Pink,
                )
            }
        }
        item {
            Group(title = "Follow the system", footer = "Haptics already follow Settings → Sounds and vibration → Vibration intensity → Touch interactions, and island sounds follow Touch sounds.") {
                SwitchRow("Match system animation speed", s.followSystemAnimationSpeed, { v -> ui.update { it.copy(followSystemAnimationSpeed = v) } },
                    "Your animation scale is ${String.format(Locale.US, "%.1f", scale)}× (Developer options). The island speeds up or slows down with it.",
                    Icons.Rounded.Speed, BadgeColors.Cyan)
                SwitchRow("Unlock bloom", s.unlockBloom, { v -> ui.update { it.copy(unlockBloom = v) } },
                    "A ring in your wallpaper's accent colour around the camera when you unlock", Icons.Rounded.AutoAwesome, BadgeColors.Violet)
            }
        }
        item {
            Group(title = "Display tips") {
                if (!s.replaceSystemPopups) {
                    NavRow("Brief pop-ups", "Notifications → Notification pop-up style → Brief, so One UI's banner stays small", Icons.Rounded.Notifications, BadgeColors.Orange) {
                        ui.open(notificationSettings(), Intent(Settings.ACTION_SETTINGS))
                    }
                }
                NavRow("Wallpaper", "A darker top edge lets the black island melt into the screen", Icons.Rounded.Wallpaper, BadgeColors.Graphite) {
                    ui.open(Intent(Intent.ACTION_SET_WALLPAPER), Intent(Settings.ACTION_DISPLAY_SETTINGS))
                }
            }
        }
    }
}

private fun animationScale(context: Context): Float =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

// -------------------------------------------------------------------------------------------------
// Gallery
// -------------------------------------------------------------------------------------------------

private class Tile(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val color: Color,
    /** Seconds between replays for moments that end by themselves; 0 plays once. */
    val loopSeconds: Int,
    val play: (TestEvents) -> Unit,
    val expand: String? = null,
)

/** Every kind of activity, each playing live in its own small preview, ready to pin on the island. */
@Composable
fun GalleryScreen(s: IslandSettings, ui: Ui) {
    val context = LocalContext.current
    val tiles = remember {
        listOf(
            Tile("Music", "Artwork, equalizer, controls", Icons.Rounded.MusicNote, BadgeColors.Pink, 0, { it.music(true, rich = false) }),
            Tile("Delivery", "Live route with the courier", Icons.Rounded.Download, BadgeColors.Orange, 0, { it.delivery() }, expand = "test:live"),
            Tile("Meeting", "Countdown to your next event", Icons.Rounded.Event, BadgeColors.Blue, 0, { it.meeting(5) }),
            Tile("Flashlight", "Brightness in the island", Icons.Rounded.FlashlightOn, BadgeColors.Yellow, 0, { it.torch() }),
            Tile("Timer", "Final countdown", Icons.Rounded.HourglassBottom, BadgeColors.Orange, 14, { it.finalCountdown() }),
            Tile("Stopwatch", "Lap dial and ripple", Icons.Rounded.Timelapse, BadgeColors.Orange, 0, { it.laps() }),
            Tile("Charging", "Your charging theme", Icons.Rounded.BatteryChargingFull, BadgeColors.Green, 6, { it.charging(s.chargingTheme) }),
            Tile("Group chat", "Stacked avatars and reply", Icons.Rounded.Forum, BadgeColors.Green, 7, { it.groupChat(testReplyAction(context)) }),
            Tile("Notification", "Banner with icon flight", Icons.Rounded.Notifications, BadgeColors.Red, 6, { it.notification() }),
            Tile("Call", "Incoming call", Icons.Rounded.Call, BadgeColors.Green, 0, { it.call() }),
            Tile("Reply sent", "Dots, then a check", Icons.Rounded.Forum, BadgeColors.Teal, 4, { it.sent() }),
            Tile("Split island", "Two activities at once", Icons.Rounded.Layers, BadgeColors.Indigo, 0, { it.split() }),
        )
    }
    SettingsPage("Gallery", onBack = ui::back, actions = {
        FilledTonalButton(onClick = { ui.graph.testEvents.clear() }, modifier = Modifier.padding(end = 8.dp)) { Text(tr("Clear island")) }
    }) {
        item {
            Text(
                tr("Every kind of activity, live. Pin one to try it on the real island; test activities are clearly marked and never pretend to be real."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        tiles.forEach { tile -> item(key = tile.title) { GalleryTile(tile, s, ui) } }
    }
}

@Composable
private fun GalleryTile(tile: Tile, s: IslandSettings, ui: Ui) {
    var preview by remember { mutableStateOf<PreviewIsland?>(null) }
    LaunchedEffect(preview) {
        val p = preview ?: return@LaunchedEffect
        delay(400)
        do {
            tile.play(p.tests)
            if (tile.loopSeconds <= 0) break
            delay(tile.loopSeconds * 1000L)
        } while (true)
    }
    Surface(shape = RoundedCornerShape(26.dp), color = LocalIslandColors.current.card, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column {
            IslandPreview(ui.graph, s, Modifier.fillMaxWidth().height(150.dp).padding(8.dp), onReady = { preview = it })
            Row(Modifier.padding(start = 16.dp, end = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(tile.color), contentAlignment = Alignment.Center) {
                    Icon(tile.icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr(tile.title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(tr(tile.subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(onClick = { tile.play(ui.graph.testEvents) }) {
                    Icon(Icons.Rounded.PushPin, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Pin"))
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Diagnostics
// -------------------------------------------------------------------------------------------------

@Composable
fun DiagnosticsScreen(s: IslandSettings, ui: Ui) {
    val context = LocalContext.current
    val runtime by ui.graph.runtime.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    val report by produceState("", runtime, s, refresh) { value = buildReport(context, ui.graph, s) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_600)
            copied = false
        }
    }

    SettingsPage("Diagnostics", onBack = ui::back) {
        item {
            InfoCard(
                title = "Stays on this phone",
                body = "This report is built on the phone and never sent anywhere. Copy or share it yourself when reporting a problem. It contains your device model, Island's settings and recent Island warnings, but no notification content.",
                icon = Icons.Rounded.BugReport,
                color = BadgeColors.Graphite,
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Island diagnostics", report))
                    copied = true
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr(if (copied) "Copied" else "Copy"))
                }
                FilledTonalButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                    ui.open(Intent.createChooser(send, "Share diagnostics"))
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Share"))
                }
                FilledTonalButton(onClick = {
                    Diagnostics.clearCrash(context)
                    refresh++
                }) {
                    Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(18.dp))
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF0D0F16), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(
                    report,
                    color = Color(0xFFE6E8F0),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(16.dp),
                )
            }
        }
    }
}

private fun buildReport(context: Context, graph: AppGraph, s: IslandSettings): String {
    val r = graph.runtime.value
    val p = Permissions.snapshot(context)
    return buildString {
        appendLine("BreezeIsland ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Device  ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.DISPLAY}")
        appendLine("Profile ${r.deviceProfile}")
        appendLine("Display ${r.screenWidth}×${r.screenHeight} px · density ${r.density} · status bar ${r.statusBarHeight} px")
        appendLine("Camera  ${String.format(Locale.US, "x %.1f y %.1f r %.1f", r.cameraX, r.cameraY, r.cameraRadius)} · ${r.cutoutSource?.label ?: "not measured"} · cutout ${r.cutoutWidth.toInt()}×${r.cutoutHeight.toInt()}")
        appendLine("Island  ${if (r.serviceRunning) "running" else "stopped"} · ${r.stateLabel} · hidden: ${r.hiddenReason ?: "no"} · compact ${r.compactWidthDp.toInt()}×${r.compactHeightDp.toInt()} dp")
        appendLine("Perms   overlay=${p.overlay} notifications=${p.notificationAccess} usage=${p.usageAccess} bluetooth=${p.bluetooth} alarms=${p.exactAlarms} battery=${p.batteryUnrestricted} secure=${StatusBarCleanup.canWrite(context)}")
        appendLine("System  pop-ups=${SystemPopups.current(context)} icon_blacklist=${StatusBarCleanup.current(context) ?: "-"} animation=${animationScale(context)}")
        appendLine("Setup   theme=${s.islandTheme} motion=${s.motionPreset} speed=${s.animationSpeed} intensity=${s.animationIntensity} performance=${s.performanceMode} seamless=${s.seamlessStatusBar} replacePopups=${s.replaceSystemPopups}")
        appendLine()
        appendLine("Recent warnings")
        val recent = Diagnostics.recent()
        if (recent.isEmpty()) appendLine("  none") else recent.takeLast(40).forEach { appendLine("  $it") }
        appendLine()
        appendLine("Last crash")
        append(Diagnostics.lastCrash(context)?.prependIndent("  ") ?: "  none")
    }
}

// -------------------------------------------------------------------------------------------------
// Custom island theme
// -------------------------------------------------------------------------------------------------

private val ThemePalette = listOf(
    0xFF7CF7FF, 0xFF4C9BFF, 0xFF7B4DFF, 0xFFB18CFF, 0xFFFF5C9A, 0xFFFF5147, 0xFFFFA23A, 0xFFFFD54A, 0xFF3DDC84, 0xFFFFFFFF,
).map { it.toInt() }

@Composable
fun CustomThemeEditor(s: IslandSettings, ui: Ui) {
    Group(title = "Your theme", footer = "The preview above shows it live. A rim width of 0 hides the rim.") {
        ColorRow("Rim", s.customRimColor) { c -> ui.update { it.copy(customRimColor = c) } }
        ColorRow("Glow", s.customGlowColor, allowNone = true) { c -> ui.update { it.copy(customGlowColor = c) } }
        SliderRow("Rim width", s.customRimWidthDp, 0f..3f, { v -> ui.update { it.copy(customRimWidthDp = v) } }, { String.format(Locale.US, "%.1f dp", it) }, live = true)
        SliderRow("Highlight", s.customHighlight, 0f..1f, { v -> ui.update { it.copy(customHighlight = v) } }, { "${(it * 100).toInt()}%" }, live = true)
    }
}

@Composable
private fun ColorRow(title: String, selected: Int, allowNone: Boolean = false, onPick: (Int) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(tr(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (allowNone) {
                val none = selected ushr 24 == 0
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .border(if (none) 3.dp else 1.dp, if (none) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { onPick(0) },
                    contentAlignment = Alignment.Center,
                ) { Text(tr("Off"), style = MaterialTheme.typography.labelSmall) }
            }
            ThemePalette.forEach { c ->
                val isSelected = c == selected
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .border(if (isSelected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        .padding(if (isSelected) 5.dp else 0.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .clickable { onPick(c) },
                )
            }
        }
    }
}

/** Dev-panel rows for 1.2 features, shared by Developer tools. */
@Composable
fun NewTestRows(ui: Ui) {
    val tests = ui.graph.testEvents
    Group(title = "New in 1.2", footer = "Test activities are labelled as tests. The real flashlight, calendar and deliveries appear by themselves.") {
        TestButtonRow("Delivery on the way", Icons.Rounded.Download, BadgeColors.Orange) { tests.delivery() }
        TestButtonRow("Meeting in 5 minutes", Icons.Rounded.Event, BadgeColors.Blue) { tests.meeting(5) }
        TestButtonRow("Flashlight", Icons.Rounded.FlashlightOn, BadgeColors.Yellow) { tests.torch() }
        TestButtonRow("Stopwatch laps", Icons.Rounded.Timelapse, BadgeColors.Orange) { tests.laps() }
        TestButtonRow("Reply sent", Icons.Rounded.Forum, BadgeColors.Teal) { tests.sent() }
    }
}

@Composable
private fun TestButtonRow(title: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    SettingRow(title, null, icon, color, onClick = onClick) {
        FilledTonalButton(onClick = onClick) { Text(tr("Run")) }
    }
}
