package com.arnav.island.settings.ui.screens

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arnav.island.events.MediaPayload
import com.arnav.island.permissions.PermissionSnapshot
import com.arnav.island.permissions.Permissions
import com.arnav.island.settings.ui.Ui
import com.arnav.island.settings.ui.components.Group
import com.arnav.island.settings.ui.components.InfoCard
import com.arnav.island.settings.ui.components.PermissionRow
import com.arnav.island.settings.ui.components.Reveal
import com.arnav.island.settings.ui.components.SettingRow
import com.arnav.island.settings.ui.components.SettingsPage
import com.arnav.island.settings.ui.components.SwitchRow
import com.arnav.island.settings.ui.theme.BadgeColors
import com.arnav.island.storage.IslandSettings
import com.arnav.island.util.InstalledApp
import com.arnav.island.widget.BreezeMusicWidgetProvider
import com.arnav.island.widget.BreezeMusicWidgetRenderer
import com.arnav.island.widget.LyricWindow
import com.arnav.island.widget.MusicWidgetRenderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private data class MusicAppChoice(
    val app: InstalledApp,
    val isAudioPlayer: Boolean,
)

private val KNOWN_MUSIC_PACKAGES = setOf(
    "com.spotify.music",
    "com.google.android.apps.youtube.music",
    "com.zing.mp3",
    "com.nct.nhaccuatui",
    "com.apple.android.music",
    "com.sec.android.app.music",
    "com.soundcloud.android",
    "com.aspiro.tidal",
    "deezer.android.app",
    "com.maxmpz.audioplayer",
    "com.aimp.player",
    "in.krosbits.musicolet",
    "com.piyush.music",
    "com.miui.player",
    "app.revanced.android.apps.youtube.music",
    "app.rvx.android.apps.youtube.music",
    "com.amazon.mp3",
    "org.schabi.newpipe",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicWidgetScreen(settings: IslandSettings, permissions: PermissionSnapshot, ui: Ui) {
    val context = LocalContext.current
    val schedule by ui.graph.events.schedule.collectAsStateWithLifecycle()
    var showDemoTrack by remember { mutableStateOf(true) }
    var showPicker by remember { mutableStateOf(false) }
    var pinFeedback by remember { mutableStateOf<String?>(null) }
    var tickCounter by remember { mutableStateOf(0) }

    // Refresh preview every 1.4s when live media is active or demo is playing
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1_400L)
            tickCounter++
        }
    }

    val linkedPkg = settings.musicWidgetLinkedPkg
    val linkedLabel = remember(linkedPkg) {
        if (linkedPkg.isNotBlank()) ui.graph.apps.label(linkedPkg) else "Chưa chọn (Tự động theo app đang phát)"
    }
    val linkedIcon by produceState<Bitmap?>(initialValue = null, linkedPkg) {
        value = if (linkedPkg.isNotBlank()) ui.graph.apps.icon(linkedPkg, 128) else null
    }

    val livePreviewBitmap by produceState<Bitmap?>(
        initialValue = null,
        schedule.revision,
        settings.musicWidgetLinkedPkg,
        settings.musicWidgetShowLyrics,
        settings.musicWidgetFullLyrics,
        settings.musicWidgetTransparentBg,
        showDemoTrack,
        tickCounter,
    ) {
        value = withContext(Dispatchers.Default) {
            val realState = ui.graph.widgetUpdater.buildRenderStateSnapshot(settings)
            val effectiveState = if (realState.media == null && showDemoTrack) {
                val appTitle = if (linkedPkg.isNotBlank()) ui.graph.apps.label(linkedPkg) else "Mochy"
                MusicWidgetRenderState(
                    media = MediaPayload(
                        packageName = linkedPkg.ifBlank { "com.spotify.music" },
                        appLabel = appTitle,
                        title = "Secondhand (cùng với Rema)",
                        artist = "Don Toliver",
                        album = "OCTANE",
                        durationMs = 226_000L,
                        positionMs = 69_000L,
                        positionUpdatedAt = 0L,
                        playbackSpeed = 1f,
                        isPlaying = true,
                        canSkipNext = true,
                        canSkipPrevious = true,
                        canSeek = true,
                        accent = 0xFFFFFFFF.toInt(),
                        volume = 0.55f,
                        outputName = "SOUNDPEATS T3 Pro",
                    ),
                    artwork = realState.artwork,
                    appIcon = realState.linkedAppIcon,
                    linkedPackage = linkedPkg,
                    linkedAppLabel = appTitle,
                    linkedAppIcon = realState.linkedAppIcon,
                    showLyrics = true,
                    fullLyrics = settings.musicWidgetFullLyrics,
                    transparentBg = settings.musicWidgetTransparentBg,
                    lyrics = LyricWindow(
                        prevLine = "Need each other",
                        currentLine = if (settings.musicWidgetFullLyrics) {
                            "How am I\nsupposed to let\nyou go?"
                        } else {
                            "Angel dust"
                        },
                        nextLine = "Shawty always got me trippin' like angel dust",
                        upcomingLines = listOf(
                            "Shawty always got me trippin' like angel dust",
                            "Everywhere she goes, you always see me",
                        ),
                        hasSynced = true,
                    ),
                    positionMs = 69_000L,
                )
            } else {
                realState
            }
            BreezeMusicWidgetRenderer.render(effectiveState)
        }
    }

    val widgetCount = remember(schedule.revision, tickCounter) {
        ui.graph.widgetUpdater.activeWidgetCount()
    }

    SettingsPage("Widget Nhạc 4×4 (Màn ngoài)", onBack = { ui.back() }) {
        // 1. Live 1:1 Cover Screen Preview
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    shape = RoundedCornerShape(30.dp),
                    color = Color(0xFF0A0C12),
                    shadowElevation = 10.dp,
                    modifier = Modifier
                        .fillMaxWidth(0.84f)
                        .aspectRatio(1f)
                        .border(
                            width = 1.5.dp,
                            brush = Brush.linearGradient(
                                listOf(Color(0x55FFFFFF), Color(0x2238BDF8), Color(0x44FFFFFF)),
                            ),
                            shape = RoundedCornerShape(30.dp),
                        ),
                ) {
                    val bmp = livePreviewBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Xem trước Widget Nhạc 4x4",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(30.dp)),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (widgetCount > 0) "Đang gắn $widgetCount widget" else "Xem trước bố cục 4×4 màn ngoài Z Flip5",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { showDemoTrack = !showDemoTrack }) {
                        Text(if (showDemoTrack) "Xem trạng thái chờ" else "Xem mẫu có Lyric")
                    }
                }
            }
        }

        // 2. Notification Access Gate if missing
        if (!permissions.notificationAccess) {
            item {
                Group(title = "Quyền cần thiết") {
                    PermissionRow(
                        title = "Quyền truy cập thông báo",
                        subtitle = "Bắt buộc để widget đọc tên bài hát, ảnh bìa, thanh tiến trình và đồng bộ Lyric.",
                        granted = false,
                        icon = Icons.Rounded.NotificationsActive,
                        color = BadgeColors.Red,
                        onGrant = {
                            ui.open(
                                Permissions.notificationAccessSettings(context),
                                Permissions.notificationAccessFallback(),
                            )
                        },
                        grantLabel = "Cấp quyền",
                    )
                }
            }
        }

        // 3. Permanent Linked Music App Picker
        item {
            Group(
                title = "Ứng dụng nghe nhạc liên kết",
                footer = "Khi đã chọn app tại đây, BreezeMusic 4×4 (và Đảo) sẽ ghi nhớ vĩnh viễn app đó, ưu tiên hiển thị & mở đúng app này khi chạm ▶ lúc màn ngoài đang chờ.",
            ) {
                SettingRow(
                    title = if (linkedPkg.isNotBlank()) linkedLabel else "Chọn ứng dụng nghe nhạc",
                    subtitle = if (linkedPkg.isNotBlank()) {
                        "Đang ghi nhớ vĩnh viễn · chạm để đổi"
                    } else {
                        "Chạm để liên kết Spotify, Zing MP3, YouTube Music, Apple Music…"
                    },
                    icon = if (linkedIcon == null) Icons.Rounded.MusicNote else null,
                    iconColor = BadgeColors.Pink,
                    onClick = { showPicker = true },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            linkedIcon?.let { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            if (linkedPkg.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        ui.update { it.copy(musicWidgetLinkedPkg = "") }
                                        ui.graph.widgetUpdater.pushNow()
                                    },
                                ) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = "Xóa liên kết",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }

        // 4. Lyrics & Display Modes
        item {
            Group(
                title = "Bố cục Full-Bleed & Lời bài hát",
                footer = "Mẹo: Trên màn ngoài Z Flip 5, chạm nút tròn mở rộng (hoặc dấu ^ cạnh lời bài hát) để chuyển tức thì giữa Giao diện Điều khiển nổi (Hình 3) và Giao diện Poster Full Lyric chữ lớn (Hình 2). Đặt hình nền màn ngoài màu tối/đen hoặc bật 'Nền trong suốt' để tràn viền hoàn toàn.",
            ) {
                SwitchRow(
                    title = "Chế độ Poster Full Lyric chữ lớn (Kiểu Hình 2)",
                    checked = settings.musicWidgetFullLyrics,
                    onCheckedChange = { full ->
                        ui.update { it.copy(musicWidgetFullLyrics = full, musicWidgetShowLyrics = true) }
                        ui.graph.widgetUpdater.pushNow()
                    },
                    subtitle = "Khi tắt sẽ hiện giao diện Điều khiển nổi + Lyric (Kiểu Hình 3); chạm trực tiếp trên widget để đổi qua lại",
                    icon = Icons.Rounded.Lyrics,
                    iconColor = BadgeColors.Cyan,
                )
                SwitchRow(
                    title = "Nền trong suốt hòa vào hình nền màn ngoài",
                    checked = settings.musicWidgetTransparentBg,
                    onCheckedChange = { trans ->
                        ui.update { it.copy(musicWidgetTransparentBg = trans) }
                        ui.graph.widgetUpdater.pushNow()
                    },
                    subtitle = "Làm mờ viền ảnh bìa hòa trực tiếp vào hình nền trang màn hình ngoài, xóa bỏ cảm giác khung hộp",
                    icon = Icons.Rounded.Widgets,
                    iconColor = BadgeColors.Violet,
                )
            }
        }

        // 5. Add to Z Flip 5 Cover Screen Guide + 1-Tap Home Pin
        item {
            InfoCard(
                title = "Cách thêm ra Màn hình ngoài Galaxy Z Flip 5",
                body = "1. Mở Cài đặt điện thoại → Màn hình ngoài (Cover screen) → Tiện ích (Widgets).\n" +
                    "2. Tìm \"BreezeIsland\" và bật tiện ích \"BreezeMusic 4x4 (Lyric)\".\n" +
                    "3. Gập máy lại, chạm giữ màn hình ngoài hoặc vuốt sang trang tiện ích mới để hiển thị trình phát nhạc 4×4 full trang kèm Lyric.\n\n" +
                    "Nếu muốn đặt thêm trên Màn hình chính khi mở máy, nhấn nút bên dưới:",
                icon = Icons.Rounded.PhoneAndroid,
                color = BadgeColors.Blue,
                actionLabel = "Ghim lên Màn hình chính",
                onAction = {
                    val awm = AppWidgetManager.getInstance(context)
                    if (awm != null && awm.isRequestPinAppWidgetSupported) {
                        val ok = awm.requestPinAppWidget(
                            ComponentName(context, BreezeMusicWidgetProvider::class.java),
                            null,
                            null,
                        )
                        pinFeedback = if (ok) {
                            "Đã gửi yêu cầu ghim widget lên trình khởi chạy."
                        } else {
                            "Trình khởi chạy chưa hỗ trợ ghim tự động — hãy nhấn giữ Màn hình chính để thêm."
                        }
                    } else {
                        pinFeedback = "Hãy nhấn giữ Màn hình chính hoặc vào Cài đặt → Màn hình ngoài → Tiện ích."
                    }
                },
                secondaryLabel = "Mở Cài đặt Màn ngoài",
                onSecondary = {
                    ui.open(
                        Intent("com.samsung.settings.SubScreenSettings"),
                        Intent(Settings.ACTION_SETTINGS),
                    )
                },
            )
        }

        if (pinFeedback != null) {
            item {
                Text(
                    pinFeedback.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showPicker) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var query by remember { mutableStateOf("") }
        val choices by produceState<List<MusicAppChoice>?>(initialValue = null) {
            val all = ui.graph.apps.launchableApps()
            val pm = context.packageManager
            value = withContext(Dispatchers.Default) {
                all.map { app ->
                    val isAudio = app.packageName in KNOWN_MUSIC_PACKAGES || runCatching {
                        pm.getApplicationInfo(app.packageName, 0).category == ApplicationInfo.CATEGORY_AUDIO
                    }.getOrDefault(false)
                    MusicAppChoice(app, isAudio)
                }.sortedWith(
                    compareByDescending<MusicAppChoice> { it.app.packageName == settings.musicWidgetLinkedPkg }
                        .thenByDescending { it.isAudioPlayer }
                        .thenBy { it.app.isSystem }
                        .thenBy { it.app.label.lowercase() },
                )
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = sheetState,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    "Chọn ứng dụng nghe nhạc mặc định",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "BreezeMusic 4×4 sẽ ghi nhớ app bạn chọn cho tới khi bạn thay đổi tại đây.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    placeholder = { Text("Tìm Spotify, Zing MP3, YouTube Music...") },
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))

                // Auto option
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            ui.update { it.copy(musicWidgetLinkedPkg = "") }
                            ui.graph.widgetUpdater.pushNow()
                            showPicker = false
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Tự động theo app đang phát", style = MaterialTheme.typography.titleMedium)
                        Text("Không khóa cứng 1 app cụ thể", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (settings.musicWidgetLinkedPkg.isBlank()) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Spacer(Modifier.height(6.dp))
                val filtered = remember(choices, query) {
                    val list = choices.orEmpty()
                    val q = query.trim().lowercase()
                    if (q.isEmpty()) list else list.filter {
                        it.app.label.lowercase().contains(q) || it.app.packageName.lowercase().contains(q)
                    }
                }

                LazyColumn(Modifier.height(380.dp)) {
                    items(filtered, key = { it.app.packageName }) { item ->
                        val pkg = item.app.packageName
                        val iconBmp by produceState<Bitmap?>(initialValue = ui.graph.apps.cachedIcon(pkg, 96), pkg) {
                            if (value == null) value = ui.graph.apps.icon(pkg, 96)
                        }
                        val selected = pkg == settings.musicWidgetLinkedPkg
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    ui.update { it.copy(musicWidgetLinkedPkg = pkg) }
                                    ui.graph.widgetUpdater.pushNow()
                                    showPicker = false
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (iconBmp != null) {
                                Image(
                                    bitmap = iconBmp!!.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(11.dp)),
                                )
                            } else {
                                Box(
                                    Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(11.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(item.app.label, style = MaterialTheme.typography.titleMedium)
                                    if (item.isAudioPlayer) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "Nhạc",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                Text(
                                    pkg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (selected) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
