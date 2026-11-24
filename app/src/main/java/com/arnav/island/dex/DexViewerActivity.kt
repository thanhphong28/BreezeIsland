package com.arnav.island.dex

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Minimize
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.arnav.island.IslandApp
import com.arnav.island.settings.ui.theme.IslandTheme
import com.arnav.island.storage.AppLanguage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Fullscreen interactive viewer for the on-device Samsung DeX virtual display.
 *
 * Uses a native [SurfaceView] below a lightweight Compose overlay for zero-latency H.264 decoding
 * and 120Hz touch responsiveness:
 *  - 1 finger: virtual mouse click & drag (window handles, sliders, selection)
 *  - 2 fingers: scroll wheel
 *  - Back key / gesture: forwarded straight to DeX (`KEYCODE_BACK`)
 *  - Floating `✕` control pill: tap for Quick Menu (Stop DeX / Background / Rotate / DeX Home /
 *    DeX Recents), press-and-hold to drag anywhere out of the way of window controls.
 */
class DexViewerActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView

    private var surfaceReady = false
    private var surfaceGivenToDecoder = false
    private var landscapeLocked = true

    private val session: DexSession?
        get() = DexSession.current

    private val appGraph
        get() = (application as IslandApp).graph

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val activeSession = session
        if (activeSession == null) {
            finish()
            return
        }

        val settings = appGraph.settings.state.value
        landscapeLocked = settings.dexLandscapeLock
        applyOrientationLock(landscapeLocked)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        root = FrameLayout(this).apply {
            setBackgroundColor(AndroidColor.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
        }
        root.addView(surfaceView)

        val overlayView = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setContent {
                val curSettings by appGraph.settings.state.collectAsState()
                val sessionState by activeSession.state.collectAsState()
                val vi = curSettings.language != AppLanguage.ENGLISH
                IslandTheme(curSettings.appTheme, curSettings.amoledBlack, curSettings.dynamicColor) {
                    DexViewerOverlay(
                        state = sessionState,
                        vi = vi,
                        landscapeLocked = landscapeLocked,
                        onToggleOrientation = {
                            landscapeLocked = !landscapeLocked
                            applyOrientationLock(landscapeLocked)
                        },
                        onSendKey = { keyCode -> session?.controller?.sendKeyPress(keyCode) },
                        onMinimize = { moveTaskToBack(true) },
                        onStopDex = {
                            appGraph.localDex.stopDex(this@DexViewerActivity)
                            finish()
                        },
                        onDismissError = { finish() },
                    )
                }
            }
        }
        root.addView(overlayView)
        setContentView(root)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                surfaceGivenToDecoder = false
                offerSurface()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                surfaceGivenToDecoder = false
                session?.videoDecoder?.clearSurface(holder.surface)
            }
        })

        surfaceView.setOnTouchListener { view, event ->
            val s = session ?: return@setOnTouchListener false
            s.controller?.forwardMotionEvent(event, view.width, view.height, s.videoWidth, s.videoHeight)
            true
        }

        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) {
                val s = session ?: return@addOnLayoutChangeListener
                if (s.videoWidth > 0) applyAspectRatio(s.videoWidth, s.videoHeight)
            }
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    session?.controller?.sendKeyPress(KeyEvent.KEYCODE_BACK)
                }
            },
        )

        lifecycleScope.launch {
            activeSession.state.collectLatest { state ->
                when (state) {
                    is DexSession.State.Running -> {
                        applyAspectRatio(state.videoWidth, state.videoHeight)
                        offerSurface()
                    }
                    is DexSession.State.Stopped -> {
                        if (state.error == null) finish()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun applyOrientationLock(lockLandscape: Boolean) {
        requestedOrientation = if (lockLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }

    private fun offerSurface() {
        if (!surfaceReady || surfaceGivenToDecoder) return
        val decoder = session?.videoDecoder ?: return
        decoder.setSurface(surfaceView.holder.surface)
        surfaceGivenToDecoder = true
    }

    private fun applyAspectRatio(videoWidth: Int, videoHeight: Int) {
        if (videoWidth == 0 || videoHeight == 0) return
        root.post {
            val containerWidth = root.width
            val containerHeight = root.height
            if (containerWidth == 0 || containerHeight == 0) return@post

            val scale = minOf(
                containerWidth.toFloat() / videoWidth,
                containerHeight.toFloat() / videoHeight,
            )
            val params = surfaceView.layoutParams as FrameLayout.LayoutParams
            params.width = (videoWidth * scale).toInt()
            params.height = (videoHeight * scale).toInt()
            params.gravity = Gravity.CENTER
            surfaceView.layoutParams = params
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            session?.controller?.sendKeyPress(KeyEvent.KEYCODE_BACK)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        appGraph.dexViewerVisible.value = true
        hideSystemBars()
        if (session == null) finish()
    }

    override fun onPause() {
        appGraph.dexViewerVisible.value = false
        super.onPause()
    }

    override fun onDestroy() {
        appGraph.dexViewerVisible.value = false
        super.onDestroy()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

@Composable
private fun DexViewerOverlay(
    state: DexSession.State,
    vi: Boolean,
    landscapeLocked: Boolean,
    onToggleOrientation: () -> Unit,
    onSendKey: (Int) -> Unit,
    onMinimize: () -> Unit,
    onStopDex: () -> Unit,
    onDismissError: () -> Unit,
) {
    val view = LocalView.current
    var showMenu by remember { mutableStateOf(false) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        // Loading overlay while connecting / preparing display
        if (state is DexSession.State.Starting) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xD9111318))
                    .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(24.dp))
                    .padding(horizontal = 28.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Rounded.DesktopWindows,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(34.dp),
                )
                Spacer(Modifier.height(12.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 2.6.dp,
                    color = Color(0xFF38BDF8),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    state.message,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Translucent draggable control button (starts top-right; long-press & drag anywhere)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 14.dp, end = 14.dp)
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(40.dp)
                .clip(CircleShape)
                .background(if (isDragging) Color(0xCC38BDF8) else Color(0x7810141C))
                .border(1.dp, Color(0x40FFFFFF), CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { showMenu = true })
                }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            isDragging = true
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            offsetX += dragAmount.x
                            offsetY += dragAmount.y
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = if (vi) "Điều khiển DeX" else "DeX controls",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    if (showMenu) {
        val displayId = (state as? DexSession.State.Running)?.displayId ?: -1
        AlertDialog(
            onDismissRequest = { showMenu = false },
            title = {
                Text(
                    if (vi) "Điều khiển Samsung DeX" else "Samsung DeX Controls",
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (displayId >= 0) {
                        Text(
                            if (vi) "Đang chạy trên màn hình ảo #$displayId · Nhấn giữ nút ✕ để kéo sang vị trí khác."
                            else "Running on virtual display #$displayId · Long-press ✕ to drag it anywhere.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        QuickNavChip(
                            label = if (vi) "Quay lại" else "Back",
                            icon = Icons.AutoMirrored.Rounded.ArrowBack,
                            modifier = Modifier.weight(1f),
                        ) {
                            showMenu = false
                            onSendKey(KeyEvent.KEYCODE_BACK)
                        }
                        QuickNavChip(
                            label = "Home",
                            icon = Icons.Rounded.Home,
                            modifier = Modifier.weight(1f),
                        ) {
                            showMenu = false
                            onSendKey(KeyEvent.KEYCODE_HOME)
                        }
                        QuickNavChip(
                            label = if (vi) "Đa nhiệm" else "Recents",
                            icon = Icons.Rounded.ViewCarousel,
                            modifier = Modifier.weight(1f),
                        ) {
                            showMenu = false
                            onSendKey(KeyEvent.KEYCODE_APP_SWITCH)
                        }
                    }
                    FilledTonalButton(
                        onClick = {
                            showMenu = false
                            onToggleOrientation()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.ScreenRotation, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (landscapeLocked) {
                                if (vi) "Đang khóa Ngang · Chuyển sang Tự xoay" else "Locked Landscape · Switch to Auto-rotate"
                            } else {
                                if (vi) "Đang Tự xoay · Chuyển sang Khóa Ngang" else "Auto-rotate · Switch to Lock Landscape"
                            }
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            showMenu = false
                            onMinimize()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Minimize, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (vi) "Thu nhỏ (Giữ DeX chạy nền)" else "Minimize (Keep DeX in background)")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showMenu = false
                        onStopDex()
                    },
                ) {
                    Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size(18.dp), tint = Color(0xFFFF453A))
                    Spacer(Modifier.width(6.dp))
                    Text(if (vi) "Tắt Samsung DeX" else "Stop Samsung DeX", color = Color(0xFFFF453A))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMenu = false }) {
                    Text(if (vi) "Đóng" else "Cancel")
                }
            },
        )
    }

    if (state is DexSession.State.Stopped && state.error != null) {
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text(if (vi) "Phiên Samsung DeX đã dừng" else "DeX session ended") },
            text = { Text(state.error) },
            confirmButton = {
                TextButton(onClick = onDismissError) {
                    Text("OK")
                }
            },
        )
    }
}

@Composable
private fun QuickNavChip(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 12.sp, maxLines = 1)
        }
    }
}
