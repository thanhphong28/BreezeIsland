package com.arnav.island.settings.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Crop169
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EmojiEmotions
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhonelinkLock
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.island.BuildConfig
import com.arnav.island.R
import com.arnav.island.overlay.IslandOverlayService
import com.arnav.island.permissions.OnPhoneGranter
import com.arnav.island.permissions.PermissionSnapshot
import com.arnav.island.permissions.Permissions
import com.arnav.island.settings.ui.Dest
import com.arnav.island.settings.ui.Ui
import com.arnav.island.settings.ui.components.Group
import com.arnav.island.settings.ui.components.InfoCard
import com.arnav.island.settings.ui.components.NavRow
import com.arnav.island.settings.ui.components.OneUiSwitch
import com.arnav.island.settings.ui.components.SegmentedRow
import com.arnav.island.settings.ui.components.SettingRow
import com.arnav.island.settings.ui.preview.IslandPreview
import com.arnav.island.settings.ui.preview.PreviewIsland
import com.arnav.island.settings.ui.theme.BadgeColors
import com.arnav.island.settings.ui.theme.LocalIslandColors
import com.arnav.island.settings.ui.theme.TitleHero
import com.arnav.island.settings.ui.tr
import com.arnav.island.settings.update.UpdateDialog
import com.arnav.island.storage.AppLanguage
import com.arnav.island.storage.IslandSettings

@Composable
fun HomeScreen(settings: IslandSettings, permissions: PermissionSnapshot, ui: Ui) {
    val context = LocalContext.current
    val running by IslandOverlayService.running.collectAsStateWithLifecycle()
    val runtime by ui.graph.runtime.collectAsStateWithLifecycle()
    val dexState by ui.graph.localDex.state.collectAsStateWithLifecycle()
    var preview by remember { mutableStateOf<PreviewIsland?>(null) }
    var demo by remember { mutableStateOf(true) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        ui.graph.localDex.refreshStatus(forceAdbCheck = false)
        onPauseOrDispose { }
    }

    if (showUpdateDialog) {
        UpdateDialog(onDismiss = { showUpdateDialog = false })
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 40.dp),
    ) {
        item {
            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_breeze_logo),
                    contentDescription = "BreezeIsland Logo",
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(15.dp)),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("BreezeIsland", style = TitleHero)
                    Text(
                        tr(
                            when {
                                !permissions.overlay -> "Needs permission to draw over apps"
                                !settings.enabled -> "Paused"
                                running && runtime.hiddenReason != null -> "Running · hidden (${runtime.hiddenReason})"
                                running -> "Running · ${runtime.deviceProfile}"
                                else -> "Starting…"
                            }
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(
                    onClick = {
                        val next = if (settings.language == AppLanguage.VIETNAMESE) AppLanguage.ENGLISH else AppLanguage.VIETNAMESE
                        ui.update { it.copy(language = next) }
                    },
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(if (settings.language == AppLanguage.VIETNAMESE) "🇻🇳 VI" else "🇬🇧 EN")
                }
            }
            Spacer(Modifier.height(18.dp))
        }

        item {
            IslandPreview(
                graph = ui.graph,
                settings = settings,
                demo = demo,
                onReady = { preview = it },
                modifier = Modifier.fillMaxWidth().height(250.dp),
            )
            Spacer(Modifier.height(12.dp))
            DemoChips(onPick = { pick ->
                demo = false
                val p = preview ?: return@DemoChips
                when (pick) {
                    "Mascot" -> p.tests.mascot(settings.mascotMood, settings.mascotDurationMs)
                    "Music" -> p.tests.music(true, rich = false)
                    "Charging" -> p.tests.charging(settings.chargingTheme)
                    "Timer" -> p.tests.timer(5)
                    "Notification" -> p.tests.notification()
                    "Call" -> p.tests.call()
                    "Split" -> p.tests.split()
                    "Bluetooth" -> p.tests.bluetooth()
                    "Download" -> p.tests.progress()
                    "Silent" -> p.tests.ringer()
                    "Clear" -> p.tests.clear()
                }
            })
            Text(
                tr("Tap, long-press or swipe the preview island. It is the same renderer as the real one."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }

        item { MasterSwitch(settings.enabled, permissions.overlay) { ui.setEnabled(it) } }

        if (!permissions.overlay) {
            item {
                InfoCard(
                    title = "Allow BreezeIsland to appear on top",
                    body = "Android requires the \"Display over other apps\" permission for any overlay. BreezeIsland never uses Accessibility Service so banking apps work normally.",
                    icon = Icons.Rounded.Layers,
                    color = BadgeColors.Indigo,
                    actionLabel = "Open settings",
                    onAction = { ui.open(Permissions.overlaySettings(context)) },
                )
            }
        }
        if (!permissions.notificationAccess) {
            item {
                InfoCard(
                    title = "Unlock media, calls and notifications",
                    body = "Notification access lets BreezeIsland follow music players, calls, navigation, downloads and alerts. On Android 13+ sideloaded apps may first need App info → ⋮ → Allow restricted settings.",
                    icon = Icons.Rounded.NotificationsOff,
                    color = BadgeColors.Pink,
                    actionLabel = "Grant access",
                    onAction = { ui.open(Permissions.notificationAccessSettings(context), Permissions.notificationAccessFallback()) },
                    secondaryLabel = "App info",
                    onSecondary = { ui.open(Permissions.appDetails(context)) },
                )
            }
        }
        if (!permissions.writeSettings || !permissions.writeSecureSettings) {
            item {
                InfoCard(
                    title = "Prevent One UI status bar overlap",
                    body = "Allow \"Modify system settings\" to automatically hide redundant One UI status bar notification icons, and disable One UI 9's duplicate Media Live Notification chip.",
                    icon = Icons.Rounded.VisibilityOff,
                    color = BadgeColors.Blue,
                    actionLabel = "Grant on phone (1 tap)",
                    onAction = { OnPhoneGranter.grantOnPhone(context) },
                    secondaryLabel = "One UI Blend",
                    onSecondary = { ui.go(Dest.BLEND) },
                )
            }
        }

        item {
            Group(title = "Island") {
                val dexActive = dexState.running || dexState.starting
                SettingRow(
                    title = tr("Samsung DeX (Monitor / PC)", "Samsung DeX (Màn hình ngoài / PC)"),
                    subtitle = when {
                        dexState.running && !dexState.externalDisplayName.isNullOrBlank() -> tr(
                            "Outputting to ${dexState.externalDisplayName} · Tap for Touchpad",
                            "Đang xuất lên ${dexState.externalDisplayName} · Chạm mở Bàn rê chuột",
                        )
                        dexState.running && !dexState.pcWebUrl.isNullOrBlank() -> tr(
                            "PC ready: ${dexState.pcWebUrl} · Tap to manage or toggle off",
                            "Sẵn sàng cho PC: ${dexState.pcWebUrl} · Chạm để xem hoặc gạt tắt",
                        )
                        dexState.running -> tr(
                            "Running · Display #${dexState.displayId} · Tap to manage or toggle off",
                            "Đang chạy · Màn hình #${dexState.displayId} · Chạm để mở hoặc gạt tắt",
                        )
                        dexState.starting -> tr("Starting Samsung DeX…", "Đang khởi động Samsung DeX…")
                        dexState.detectedExternalDisplay != null -> tr(
                            "Detected ${dexState.detectedExternalDisplay!!.label} · Toggle on to project DeX",
                            "Đã nhận ${dexState.detectedExternalDisplay!!.label} · Gạt bật xuất DeX ngay",
                        )
                        dexState.bestReadyTransport(settings.dexConnectionMode) != null -> tr(
                            "Ready · Project Samsung DeX to HDMI monitor, projector, TV or PC",
                            "Sẵn sàng · Xuất Samsung DeX ra màn hình rời, máy chiếu, TV hoặc PC",
                        )
                        else -> tr(
                            "Samsung DeX for external monitor, projector & PC — zero root",
                            "Xuất Samsung DeX ra màn hình ngoài, máy chiếu & PC · Chạm để thiết lập",
                        )
                    },
                    icon = Icons.Rounded.DesktopWindows,
                    iconColor = if (dexActive) BadgeColors.Green else BadgeColors.Indigo,
                    onClick = { ui.go(Dest.LOCAL_DEX) },
                    trailing = {
                        OneUiSwitch(
                            checked = dexActive,
                            onCheckedChange = { on ->
                                if (!on) {
                                    ui.graph.localDex.stopDex(context)
                                } else {
                                    val started = ui.graph.localDex.startDex(context, forcePhoneViewer = false)
                                    if (!started || (dexState.detectedExternalDisplay == null && settings.dexOutputMode != com.arnav.island.dex.DexOutputMode.PHONE_SCREEN)) {
                                        ui.go(Dest.LOCAL_DEX)
                                    }
                                }
                            },
                        )
                    },
                )
                NavRow(
                    tr("Instant Search Launch", "Mở nhanh khi tìm App"),
                    if (settings.searchAutoLaunchEnabled) {
                        tr("Active · Auto-opens single matching app on Home search", "Đang bật · Gõ tìm còn 1 app tự mở ngay (không Trợ năng)")
                    } else {
                        tr("Auto-open app when Home search narrows to 1 result", "Vuốt lên tìm app còn 1 kết quả sẽ tự động mở luôn")
                    },
                    Icons.Rounded.Search,
                    BadgeColors.Blue,
                ) { ui.go(Dest.SEARCH_AUTO_LAUNCH) }
                NavRow("Breeze Mascot", "Thú cưng Aurora dễ thương · chạm camera lúc nghỉ", Icons.Rounded.EmojiEmotions, BadgeColors.Cyan) { ui.go(Dest.MASCOT) }
                NavRow("Blend with One UI 9 (Z Flip5)", "Seamless status bar, Now Bar fix & pop-up replacement", Icons.Rounded.PhoneAndroid, BadgeColors.Blue) { ui.go(Dest.BLEND) }
                NavRow("Shape & motion", "Size, corners, springs, split island", Icons.Rounded.Crop169, BadgeColors.Graphite) { ui.go(Dest.ISLAND) }
                NavRow("Camera calibration", "Auto-detect or fine-tune to the pixel", Icons.Rounded.CenterFocusStrong, BadgeColors.Cyan) { ui.go(Dest.CALIBRATION) }
                NavRow("Gestures", "Tap, long-press, swipe, haptics", Icons.Rounded.TouchApp, BadgeColors.Blue) { ui.go(Dest.GESTURES) }
                NavRow("Appearance", settings.islandTheme.label, Icons.Rounded.Palette, BadgeColors.Violet) { ui.go(Dest.APPEARANCE) }
            }
        }
        item {
            Group(title = "Activities") {
                NavRow("Events", "What the island reacts to", Icons.Rounded.AutoAwesome, BadgeColors.Indigo) { ui.go(Dest.EVENTS) }
                NavRow("Media", "Now playing, controls, artwork", Icons.Rounded.MusicNote, BadgeColors.Pink) { ui.go(Dest.MEDIA) }
                NavRow("Notifications", "Banners, privacy, duration", Icons.Rounded.Notifications, BadgeColors.Red) { ui.go(Dest.NOTIFICATIONS) }
                NavRow("Charging", settings.chargingTheme.label + " animation", Icons.Rounded.BatteryChargingFull, BadgeColors.Green) { ui.go(Dest.CHARGING) }
                NavRow("Device unlock", "Face ID, Fingerprint & Padlock", Icons.Rounded.PhonelinkLock, BadgeColors.Indigo) { ui.go(Dest.UNLOCK) }
                NavRow("Battery", "Low, full and power saving alerts", Icons.Rounded.BatteryFull, BadgeColors.Teal) { ui.go(Dest.BATTERY) }
                NavRow("Bluetooth", "Earbuds, headphones, watch, car", Icons.Rounded.Bluetooth, BadgeColors.Blue) { ui.go(Dest.BLUETOOTH) }
                NavRow("Timers & stopwatch", "Sync alarms, timers & stopwatch from phone Clock", Icons.Rounded.Timer, BadgeColors.Orange) { ui.go(Dest.TIMERS) }
                NavRow(tr("Weather Island", "Thời tiết trên Đảo"), tr("Rain alerts, morning briefing & live forecast", "Cảnh báo mưa sắp tới, bản tin sáng & dự báo trực tiếp"), Icons.Rounded.WbSunny, BadgeColors.Yellow) { ui.go(Dest.WEATHER) }
                NavRow("Apps", "Per-app rules, motion, fullscreen, Game Mode", Icons.Rounded.Apps, BadgeColors.Yellow) { ui.go(Dest.APPS) }
                NavRow("Gallery", "Every activity, live, ready to pin", Icons.Rounded.GridView, BadgeColors.Violet) { ui.go(Dest.GALLERY) }
            }
        }
        item {
            Group(title = "System") {
                SegmentedRow(
                    options = AppLanguage.entries,
                    selected = settings.language,
                    label = { it.label },
                    onSelect = { v -> ui.update { it.copy(language = v) } },
                    title = "Language",
                    subtitle = "App display language",
                )
                NavRow("Check for updates", "Update directly from GitHub Releases", Icons.Rounded.SystemUpdate, BadgeColors.Blue) { showUpdateDialog = true }
                NavRow("Performance", settings.performanceMode.label, Icons.Rounded.Speed, BadgeColors.Cyan) { ui.go(Dest.PERFORMANCE) }
                NavRow("Privacy", "Everything stays on this device", Icons.Rounded.Shield, BadgeColors.Green) { ui.go(Dest.PRIVACY) }
                NavRow("Advanced", "Debug HUD, local API, monitor", Icons.Rounded.Tune, BadgeColors.Graphite) { ui.go(Dest.ADVANCED) }
                NavRow("Developer tools", "Fire test events on the real island", Icons.Rounded.Science, BadgeColors.Violet) { ui.go(Dest.DEVELOPER) }
                NavRow("What's new", "Version ${BuildConfig.VERSION_NAME.substringBefore('-')} · play every feature", Icons.Rounded.NewReleases, BadgeColors.Pink) { ui.go(Dest.WHATS_NEW) }
                NavRow("About", "Version, updates, device, permissions", Icons.Rounded.Info, BadgeColors.Graphite) { ui.go(Dest.ABOUT) }
            }
        }
        item { Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) }
    }
}

@Composable
private fun MasterSwitch(enabled: Boolean, canEnable: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalIslandColors.current
    val container by animateColorAsState(
        if (enabled) MaterialTheme.colorScheme.primaryContainer else colors.card,
        label = "master",
    )
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = container,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onChange),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Use BreezeIsland"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    tr(
                        when {
                            !canEnable -> "Grant the overlay permission to start"
                            enabled -> "BreezeIsland is live around your camera"
                            else -> "Turn on to show the island"
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            OneUiSwitch(checked = enabled, onCheckedChange = null)
        }
    }
}

private data class DemoChip(val label: String, val icon: ImageVector)

@Composable
private fun DemoChips(onPick: (String) -> Unit) {
    val chips = listOf(
        DemoChip("Mascot", Icons.Rounded.EmojiEmotions),
        DemoChip("Music", Icons.Rounded.MusicNote),
        DemoChip("Charging", Icons.Rounded.BatteryChargingFull),
        DemoChip("Timer", Icons.Rounded.Timer),
        DemoChip("Notification", Icons.Rounded.Notifications),
        DemoChip("Call", Icons.Rounded.Call),
        DemoChip("Split", Icons.Rounded.Layers),
        DemoChip("Bluetooth", Icons.Rounded.Bluetooth),
        DemoChip("Download", Icons.Rounded.Download),
        DemoChip("Silent", Icons.Rounded.NotificationsOff),
        DemoChip("Clear", Icons.Rounded.Close),
    )
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { chip ->
            AssistChip(
                onClick = { onPick(chip.label) },
                label = { Text(tr(chip.label)) },
                leadingIcon = { Icon(chip.icon, null, modifier = Modifier.size(18.dp)) },
                shape = CircleShape,
                colors = AssistChipDefaults.assistChipColors(containerColor = LocalIslandColors.current.card),
                border = null,
            )
        }
    }
}
