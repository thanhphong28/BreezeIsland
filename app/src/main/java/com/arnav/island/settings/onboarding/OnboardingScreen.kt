package com.arnav.island.settings.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.island.R
import com.arnav.island.core.AppGraph
import com.arnav.island.overlay.GeometryEngine
import com.arnav.island.overlay.IslandOverlayService
import com.arnav.island.overlay.StatusBarCleanup
import com.arnav.island.permissions.Permissions
import com.arnav.island.settings.ui.Ui
import com.arnav.island.settings.ui.components.IconBadge
import com.arnav.island.settings.ui.components.OneUiSwitch
import com.arnav.island.settings.ui.preview.IslandPreview
import com.arnav.island.settings.ui.rememberPermissions
import com.arnav.island.settings.ui.theme.BadgeColors
import com.arnav.island.settings.ui.theme.LocalIslandColors
import com.arnav.island.settings.ui.theme.TitleHero
import com.arnav.island.settings.ui.tr
import com.arnav.island.storage.AppLanguage
import com.arnav.island.storage.IslandSettings
import kotlinx.coroutines.launch
import java.util.Locale

private const val PAGES = 7

@Composable
fun OnboardingScreen(graph: AppGraph, settings: IslandSettings, onFinish: () -> Unit) {
    val context = LocalContext.current
    val pager = rememberPagerState { PAGES }
    val scope = rememberCoroutineScope()
    val permissions by rememberPermissions()
    val running by IslandOverlayService.running.collectAsStateWithLifecycle()
    val ui = remember { Ui(graph, context.applicationContext, navigate = {}, goBack = {}) }
    var alertsGranted by remember(permissions.postNotifications) { mutableStateOf(permissions.postNotifications) }
    val alertsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { alertsGranted = it }

    LaunchedEffect(permissions.writeSettings) {
        if (permissions.writeSettings) {
            StatusBarCleanup.sync(context, settings)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = {
                    val next = if (settings.language == AppLanguage.VIETNAMESE) AppLanguage.ENGLISH else AppLanguage.VIETNAMESE
                    ui.update { it.copy(language = next) }
                },
                shape = RoundedCornerShape(50),
            ) {
                Text(if (settings.language == AppLanguage.VIETNAMESE) "🇻🇳 Tiếng Việt  ⇄  EN" else "🇬🇧 English  ⇄  VI")
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            when (page) {
                0 -> Page(
                    title = "Meet BreezeIsland",
                    body = "A sleek iPhone-style Dynamic Island around your camera — tuned for Galaxy Z Flip5 & One UI with physical spring motion, zero root, and zero Accessibility Service so banking apps always work.",
                    hero = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(
                                painter = painterResource(R.drawable.ic_breeze_logo),
                                contentDescription = "BreezeIsland Logo",
                                modifier = Modifier
                                    .size(84.dp)
                                    .clip(RoundedCornerShape(22.dp)),
                            )
                            Spacer(Modifier.height(14.dp))
                            IslandPreview(graph, settings, Modifier.fillMaxWidth().height(220.dp), demo = true)
                        }
                    },
                )
                1 -> Page(
                    title = "Draw around the camera",
                    body = "Android lets apps draw on top of others with \"Display over other apps\". BreezeIsland uses this instead of Accessibility Service so your banking apps never complain.",
                    hero = { Hero(Icons.Rounded.Layers, BadgeColors.Indigo) },
                ) {
                    Status("Display over other apps", permissions.overlay)
                    if (!permissions.overlay) {
                        Button(onClick = { ui.open(Permissions.overlaySettings(context)) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Allow")) }
                    }
                }
                2 -> Page(
                    title = "Know what's happening",
                    body = "Notification access shares media sessions, calls, navigation, downloads and alerts directly on-device. Nothing is ever stored or sent anywhere.",
                    hero = { Hero(Icons.Rounded.Notifications, BadgeColors.Pink) },
                ) {
                    Status("Notification access", permissions.notificationAccess)
                    if (!permissions.notificationAccess) {
                        Button(onClick = { ui.open(Permissions.notificationAccessSettings(context), Permissions.notificationAccessFallback()) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Grant access")) }
                        Note("Greyed out? On Android 13+, open App info → ⋮ → Allow restricted settings first.")
                        TextButton(onClick = { ui.open(Permissions.appDetails(context)) }) { Text(tr("Open App info")) }
                    }
                }
                3 -> Page(
                    title = "Prevent status bar overlap",
                    body = "On Galaxy Z Flip5 (One UI 9), grant \"Modify system settings\" so BreezeIsland automatically hides redundant status bar notification icons, and turn off One UI's duplicate Media Live Notification chip.",
                    hero = { Hero(Icons.Rounded.VisibilityOff, BadgeColors.Blue) },
                ) {
                    Status("Modify system settings (Auto-clean icons)", permissions.writeSettings)
                    if (!permissions.writeSettings) {
                        Button(onClick = { ui.open(Permissions.writeSettingsIntent(context)) }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr("Allow Modify system settings"))
                        }
                    }
                    FilledTonalButton(
                        onClick = { ui.open(Permissions.samsungNowBarSettings(), Permissions.settingsFallback()) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(tr("One UI 9: Turn off Now Bar / Live Media chip"))
                    }
                    Note("In Lock screen & AOD → Now bar → Live notifications, turn off \"Media player\" so One UI's [▷ Song] chip never overlaps BreezeIsland.")
                    Status("Unrestricted battery", permissions.batteryUnrestricted)
                    if (!permissions.batteryUnrestricted) {
                        FilledTonalButton(
                            onClick = { ui.open(Permissions.requestIgnoreBatteryOptimizations(context), Permissions.appDetails(context)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(tr("Allow Unrestricted battery")) }
                    }
                    Status("Timer alerts (optional)", alertsGranted)
                    if (!alertsGranted) {
                        FilledTonalButton(onClick = { alertsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) { Text(tr("Allow timer alerts")) }
                    }
                }
                4 -> CalibrationPage()
                5 -> Page(
                    title = "Try BreezeIsland",
                    body = "Turn the island on and fire a few test events. Long-press to expand, swipe up to collapse, swipe sideways to dismiss.",
                    hero = { Hero(Icons.Rounded.NotificationsActive, BadgeColors.Violet) },
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(LocalIslandColors.current.card)
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tr("Use BreezeIsland"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        OneUiSwitch(checked = settings.enabled, onCheckedChange = { ui.setEnabled(it) }, enabled = permissions.overlay)
                    }
                    if (!permissions.overlay) Note("Allow \"Display over other apps\" on step 2 first.")
                    if (running) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            TestButton("Music", Icons.Rounded.MusicNote, Modifier.weight(1f)) { graph.testEvents.music(true) }
                            TestButton("Charge", Icons.Rounded.BatteryChargingFull, Modifier.weight(1f)) { graph.testEvents.charging(settings.chargingTheme) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            TestButton("Alert", Icons.Rounded.Notifications, Modifier.weight(1f)) { graph.testEvents.notification() }
                            TestButton("Timer", Icons.Rounded.Timer, Modifier.weight(1f)) { graph.testEvents.timer(3) }
                        }
                    }
                }
                else -> Page(
                    title = "You're all set",
                    body = "BreezeIsland is ready around your camera. You can fine-tune calibration, motion, One UI status bar blending, and themes anytime.",
                    hero = { Hero(Icons.Rounded.CheckCircle, BadgeColors.Green) },
                ) {
                    Status("BreezeIsland overlay", permissions.overlay && settings.enabled)
                    Status("Media, calls & notifications", permissions.notificationAccess)
                    Status("Status bar icon cleanup", permissions.writeSettings || permissions.writeSecureSettings)
                    Status("Background reliability", permissions.batteryUnrestricted)
                }
            }
        }

        // Footer: progress + primary action.
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dots(pager.currentPage)
            Spacer(Modifier.weight(1f))
            if (pager.currentPage in 1..4) {
                TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) { Text(tr("Skip")) }
                Spacer(Modifier.width(4.dp))
            }
            Button(
                onClick = {
                    if (pager.currentPage == PAGES - 1) {
                        graph.testEvents.clear()
                        onFinish()
                    } else {
                        scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    }
                },
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(
                    tr(
                        when (pager.currentPage) {
                            0 -> "Get started"
                            PAGES - 1 -> "Start BreezeIsland"
                            else -> "Continue"
                        }
                    )
                )
            }
        }
    }
}

@Composable
private fun Page(
    title: String,
    body: String,
    hero: @Composable () -> Unit,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        hero()
        Spacer(Modifier.height(24.dp))
        Text(tr(title), style = TitleHero, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(tr(body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally, content = actions)
    }
}

@Composable
private fun Hero(icon: ImageVector, color: Color) {
    Box(Modifier.padding(top = 36.dp).size(140.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(140.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)))
        Box(Modifier.size(104.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)))
        IconBadge(icon, color, 72)
    }
}

@Composable
private fun Status(label: String, ok: Boolean) {
    val tint by animateColorAsState(if (ok) LocalIslandColors.current.positive else MaterialTheme.colorScheme.onSurfaceVariant, label = "status")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(LocalIslandColors.current.card)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = tint)
        Spacer(Modifier.width(12.dp))
        Text(tr(label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(tr(if (ok) "Ready" else "Not yet"), style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

@Composable
private fun Note(text: String) {
    Text(tr(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

@Composable
private fun TestButton(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(16.dp)) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(tr(label))
    }
}

@Composable
private fun CalibrationPage() {
    val context = LocalContext.current
    val measurement = remember { runCatching { GeometryEngine(context).measure() }.getOrNull() }
    val cutout = measurement?.detected
    Page(
        title = "Fits your camera",
        body = if (cutout != null) {
            "BreezeIsland read your camera from Android's display cutout and centred itself on it. Fine-tune to the exact pixel anytime in Settings → Camera calibration."
        } else {
            "Android didn't report a camera cutout, so BreezeIsland uses a tuned device profile. Fine-tune it in Settings → Camera calibration."
        },
        hero = { Hero(Icons.Rounded.CenterFocusStrong, BadgeColors.Cyan) },
    ) {
        Surface(shape = RoundedCornerShape(20.dp), color = LocalIslandColors.current.card, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                val screen = measurement?.screen
                Text(tr("Detected"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (cutout != null) {
                        String.format(Locale.US, "Camera at x %.0f, y %.0f px · radius %.1f px\n%s", cutout.centerX, cutout.centerY, cutout.radius, tr(cutout.source.label))
                    } else {
                        tr("No cutout reported")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (screen != null) {
                    Text(
                        "${screen.widthPx} × ${screen.heightPx} px · status bar ${screen.statusBarHeightPx} px",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Dots(current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(PAGES) { i ->
            val width by animateDpAsState(if (i == current) 22.dp else 7.dp, label = "dot")
            val color by animateColorAsState(
                if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                label = "dotColor",
            )
            Box(Modifier.height(7.dp).width(width).clip(CircleShape).background(color))
        }
    }
}
