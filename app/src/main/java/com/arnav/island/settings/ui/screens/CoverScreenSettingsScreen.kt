package com.arnav.island.settings.ui.screens

import android.hardware.display.DisplayManager
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.arnav.island.coverscreen.CoverPageType
import com.arnav.island.coverscreen.CoverPetBreed
import com.arnav.island.coverscreen.CoverScreenController
import com.arnav.island.coverscreen.encodeCoverPages
import com.arnav.island.coverscreen.parseCoverPages
import com.arnav.island.coverscreen.render.CoverScreenView
import com.arnav.island.settings.ui.Ui
import com.arnav.island.settings.ui.components.ChipsRow
import com.arnav.island.settings.ui.components.Group
import com.arnav.island.settings.ui.components.InfoCard
import com.arnav.island.settings.ui.components.SettingsPage
import com.arnav.island.settings.ui.components.SwitchRow
import com.arnav.island.settings.ui.theme.BadgeColors
import com.arnav.island.settings.ui.theme.LocalIslandColors
import com.arnav.island.storage.AppLanguage
import com.arnav.island.storage.IslandSettings
import com.arnav.island.util.InstalledApp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoverScreenSettingsScreen(s: IslandSettings, ui: Ui) {
    val context = LocalContext.current
    val isVi = s.language == AppLanguage.VIETNAMESE
    val dm = remember { context.getSystemService(DisplayManager::class.java) }
    val coverDisplay = remember(dm) { CoverScreenController.findCoverDisplay(dm) }

    var previewView by remember { mutableStateOf<CoverScreenView?>(null) }
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }

    LaunchedEffect(Unit) {
        installedApps = ui.graph.apps.launchableApps()
    }

    val activePages = remember(s.coverPagesCsv) { parseCoverPages(s.coverPagesCsv) }
    val availableToAdd = remember(activePages) { CoverPageType.entries.filter { it !in activePages } }
    val selectedAppPkgs = remember(s.coverAppsCsv) {
        s.coverAppsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun updatePages(next: List<CoverPageType>) {
        ui.update { it.copy(coverPagesCsv = encodeCoverPages(next)) }
    }

    SettingsPage("Màn hình ngoài Z Flip (3D Pet)", onBack = ui::back) {
        // 1. Live Interactive Flex Window Preview
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(315.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .border(2.dp, Color(0x33FFFFFF), RoundedCornerShape(30.dp)),
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            CoverScreenView(ctx, ui.graph, isOverlayMode = false).also { v ->
                                v.settings = s
                                previewView = v
                            }
                        },
                        update = { view ->
                            view.settings = s
                            previewView = view
                        },
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(
                        onClick = { previewView?.feedPet() },
                        label = { Text("🦴 Cho ăn") },
                        shape = CircleShape,
                        colors = AssistChipDefaults.assistChipColors(containerColor = LocalIslandColors.current.card),
                        border = null,
                    )
                    AssistChip(
                        onClick = { previewView?.playBallWithPet() },
                        label = { Text("🎾 Ném bóng") },
                        shape = CircleShape,
                        colors = AssistChipDefaults.assistChipColors(containerColor = LocalIslandColors.current.card),
                        border = null,
                    )
                    AssistChip(
                        onClick = { previewView?.triggerShake() },
                        label = { Text("📳 Lắc máy") },
                        shape = CircleShape,
                        colors = AssistChipDefaults.assistChipColors(containerColor = LocalIslandColors.current.card),
                        border = null,
                    )
                    AssistChip(
                        onClick = { previewView?.previousPage() },
                        label = { Text("◀ Trang trước") },
                        shape = CircleShape,
                        colors = AssistChipDefaults.assistChipColors(containerColor = LocalIslandColors.current.card),
                        border = null,
                    )
                    AssistChip(
                        onClick = { previewView?.nextPage() },
                        label = { Text("Trang sau ▶") },
                        shape = CircleShape,
                        colors = AssistChipDefaults.assistChipColors(containerColor = LocalIslandColors.current.card),
                        border = null,
                    )
                }
                Text(
                    "Chạm hoặc vuốt trực tiếp lên chú chó 3D để vuốt ve, hoặc vuốt từ rìa màn hình để lật trang!",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                )
            }
        }

        // 2. Master Toggle & Device Status
        item {
            Group(
                title = "Kích hoạt Màn hình ngoài",
                footer = if (coverDisplay != null) {
                    "Đã phát hiện màn hình ngoài Z Flip: Display #${coverDisplay.displayId} (${coverDisplay.name}). Khi bạn gập máy và bật màn hình ngoài, giao diện tùy chỉnh này sẽ tự động hiện lên!"
                } else {
                    "Chưa phát hiện màn hình phụ đang gập. Khi bạn gập Z Flip 5 và bật BreezeIsland, màn hình ngoài sẽ tự kích hoạt."
                },
            ) {
                SwitchRow(
                    "Bật Màn hình ngoài tùy chỉnh (Z Flip 5)",
                    s.coverScreenEnabled,
                    { v ->
                        ui.update { it.copy(coverScreenEnabled = v) }
                        if (v && !s.enabled) ui.setEnabled(true)
                    },
                    "Thay thế Flex Window khi gập máy bằng thú cưng 3D & các trang tự thiết lập",
                    Icons.Rounded.PhoneAndroid,
                    BadgeColors.Orange,
                )
                SwitchRow(
                    "Lắc điện thoại để tương tác chó 3D",
                    s.coverShakeEnabled,
                    { v -> ui.update { it.copy(coverShakeEnabled = v) } },
                    "Lắc nhẹ điện thoại khi ở màn hình ngoài để chú chó phản ứng xoay mòng",
                    Icons.Rounded.Vibration,
                    BadgeColors.Cyan,
                )
                SwitchRow(
                    "Hiện giờ & pin trên thanh trên cùng",
                    s.coverShowStatusBar,
                    { v -> ui.update { it.copy(coverShowStatusBar = v) } },
                    null,
                    Icons.Rounded.Info,
                    BadgeColors.Blue,
                )
            }
        }

        // 3. Custom Page Order Builder
        item {
            Group(
                title = "Sắp xếp & Tùy chỉnh các trang (${activePages.size} trang)",
                footer = "Bạn có thể tự chọn trang nào xuất hiện, sắp xếp thứ tự trang 1, trang 2, trang 3... bằng nút mũi tên hoặc ẩn bớt trang không dùng.",
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    activePages.forEachIndexed { idx, page ->
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            "${idx + 1}",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (isVi) page.labelVi else page.labelEn,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        if (isVi) page.descVi else page.descEn,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (idx > 0) {
                                            val copy = activePages.toMutableList()
                                            val item = copy.removeAt(idx)
                                            copy.add(idx - 1, item)
                                            updatePages(copy)
                                        }
                                    },
                                    enabled = idx > 0,
                                ) {
                                    Icon(Icons.Rounded.ArrowUpward, contentDescription = "Lên")
                                }
                                IconButton(
                                    onClick = {
                                        if (idx < activePages.lastIndex) {
                                            val copy = activePages.toMutableList()
                                            val item = copy.removeAt(idx)
                                            copy.add(idx + 1, item)
                                            updatePages(copy)
                                        }
                                    },
                                    enabled = idx < activePages.lastIndex,
                                ) {
                                    Icon(Icons.Rounded.ArrowDownward, contentDescription = "Xuống")
                                }
                                if (activePages.size > 1) {
                                    IconButton(
                                        onClick = {
                                            val copy = activePages.toMutableList()
                                            copy.removeAt(idx)
                                            updatePages(copy)
                                        },
                                    ) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Xóa trang")
                                    }
                                }
                            }
                        }
                    }

                    if (availableToAdd.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Thêm trang vào màn hình ngoài:",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            availableToAdd.forEach { page ->
                                AssistChip(
                                    onClick = { updatePages(activePages + page) },
                                    label = { Text(if (isVi) page.labelVi else page.labelEn) },
                                    leadingIcon = { Icon(Icons.Rounded.Add, null, modifier = Modifier.size(16.dp)) },
                                    shape = CircleShape,
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. 3D Puppy Customization
        item {
            Group(
                title = "Chọn giống Chó 3D tương tác",
                footer = "Giống chú chó sẽ được render 3D trực tiếp theo màu lông, tai và vòng cổ riêng.",
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Pets, contentDescription = null, tint = BadgeColors.Orange)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Giống cún cưng 3D",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                ChipsRow(
                    options = CoverPetBreed.entries,
                    selected = s.coverPetBreed,
                    label = { if (isVi) it.labelVi else it.labelEn },
                    onSelect = { breed -> ui.update { it.copy(coverPetBreed = breed) } },
                )
            }
        }

        // 5. App Launcher Picker for APPS Page
        item {
            Group(
                title = "Chọn Ứng dụng trên trang Khởi chạy App",
                footer = "Chọn tối đa 8 ứng dụng hiển thị ở trang Khởi chạy App. Nếu không chọn, hệ thống tự hiển thị 6 ứng dụng thường dùng.",
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Apps, contentDescription = null, tint = BadgeColors.Blue)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (selectedAppPkgs.isEmpty()) "Tự động (hoặc chạm để chọn riêng)"
                            else "Đã chọn ${selectedAppPkgs.size}/8 ứng dụng",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        installedApps.take(36).forEach { app ->
                            val isSelected = app.packageName in selectedAppPkgs
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    val next = selectedAppPkgs.toMutableSet()
                                    if (isSelected) {
                                        next.remove(app.packageName)
                                    } else if (next.size < 8) {
                                        next.add(app.packageName)
                                    }
                                    ui.update { it.copy(coverAppsCsv = next.joinToString(",")) }
                                },
                                label = { Text(app.label) },
                            )
                        }
                    }
                }
            }
        }

        // 6. Z Flip Usage Guide
        item {
            InfoCard(
                title = "Mẹo dùng mượt trên Galaxy Z Flip 5",
                body = "• Khi đang dùng màn ngoài, bấm nút [—] ở góc trên phải để thu gọn thành nút [🐾] nếu muốn xem màn gốc của Samsung.\n• Khi mở App từ trang Khởi chạy App, bật MultiStar trong Good Lock (Samsung) để mở mọi ứng dụng trực tiếp trên màn ngoài không cần mở gập máy.",
                icon = Icons.Rounded.Pets,
                color = BadgeColors.Cyan,
            )
        }
    }
}
