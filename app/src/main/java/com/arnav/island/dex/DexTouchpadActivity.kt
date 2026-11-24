package com.arnav.island.dex

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Minimize
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.arnav.island.IslandApp
import com.arnav.island.settings.ui.theme.IslandTheme
import com.arnav.island.storage.AppLanguage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Turns the phone screen into a sleek Samsung DeX Touchpad & Keyboard controller while Samsung DeX
 * is displayed fullscreen on an external monitor, TV, projector, or PC:
 *  - 1-finger drag: moves the virtual mouse cursor smoothly across the external screen
 *  - 1-finger tap: Left Click
 *  - 2-finger tap: Right Click
 *  - 2-finger drag: Scroll Wheel
 *  - Hold / Drag-Lock button: click-and-drag windows, resize edges, or select text
 *  - Keyboard dock: type on the phone and send UTF-8 text + Enter / Backspace straight into DeX
 *  - Minimize: press Home or Minimize to use your phone normally while DeX stays on the monitor!
 */
class DexTouchpadActivity : ComponentActivity() {

    private val appGraph get() = (application as IslandApp).graph
    private val session: DexSession? get() = DexSession.current

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val activeSession = session
        if (activeSession == null) {
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        lifecycleScope.launch {
            activeSession.state.collectLatest { st ->
                if (st is DexSession.State.Stopped) finish()
            }
        }

        setContent {
            val settings by appGraph.settings.state.collectAsState()
            val dexState by appGraph.localDex.state.collectAsState()
            val vi = settings.language != AppLanguage.ENGLISH
            IslandTheme(settings.appTheme, settings.amoledBlack, settings.dynamicColor) {
                DexTouchpadScreen(
                    vi = vi,
                    externalName = dexState.externalDisplayName,
                    pcUrl = dexState.pcWebUrl,
                    session = activeSession,
                    onMinimize = { moveTaskToBack(true) },
                    onStopDex = {
                        appGraph.localDex.stopDex(this@DexTouchpadActivity)
                        finish()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (session == null) finish()
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun DexTouchpadScreen(
    vi: Boolean,
    externalName: String?,
    pcUrl: String?,
    session: DexSession,
    onMinimize: () -> Unit,
    onStopDex: () -> Unit,
) {
    var cursorX by remember { mutableFloatStateOf((session.videoWidth.takeIf { it > 0 } ?: 1920) / 2f) }
    var cursorY by remember { mutableFloatStateOf((session.videoHeight.takeIf { it > 0 } ?: 1080) / 2f) }
    var dragLocked by remember { mutableStateOf(false) }
    var showKeyboard by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }

    fun videoW() = session.videoWidth.takeIf { it > 0 } ?: 1920
    fun videoH() = session.videoHeight.takeIf { it > 0 } ?: 1080

    fun setDragLock(enable: Boolean) {
        if (dragLocked == enable) return
        dragLocked = enable
        val ctrl = session.controller ?: return
        val x = cursorX.toInt().coerceIn(0, videoW() - 1)
        val y = cursorY.toInt().coerceIn(0, videoH() - 1)
        if (enable) {
            ctrl.sendMouseDown(x, y, videoW(), videoH(), DexInputController.BUTTON_PRIMARY)
        } else {
            ctrl.sendMouseUp(x, y, videoW(), videoH(), DexInputController.BUTTON_PRIMARY)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07090D))
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        // Header bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.DesktopWindows,
                contentDescription = null,
                tint = Color(0xFF38BDF8),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (vi) "Bàn rê chuột Samsung DeX" else "Samsung DeX Touchpad",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    when {
                        !externalName.isNullOrBlank() -> if (vi) "Đang xuất ra: $externalName" else "Output: $externalName"
                        !pcUrl.isNullOrBlank() -> if (vi) "DeX for PC: $pcUrl" else "DeX for PC: $pcUrl"
                        else -> if (vi) "Đang điều khiển màn hình DeX" else "Controlling DeX display"
                    },
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            FilledTonalIconButton(
                onClick = onMinimize,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = Color(0xFF171C26),
                    contentColor = Color.White,
                ),
            ) {
                Icon(Icons.Rounded.Minimize, contentDescription = if (vi) "Thu nhỏ" else "Minimize")
            }
            Spacer(Modifier.width(6.dp))
            FilledTonalIconButton(
                onClick = onStopDex,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = Color(0x33FF453A),
                    contentColor = Color(0xFFFF6961),
                ),
            ) {
                Icon(Icons.Rounded.PowerSettingsNew, contentDescription = if (vi) "Tắt DeX" else "Stop DeX")
            }
        }

        Spacer(Modifier.height(10.dp))

        // Main Touchpad surface
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(if (dragLocked) Color(0xFF102033) else Color(0xFF10141C))
                .border(
                    width = if (dragLocked) 2.dp else 1.dp,
                    color = if (dragLocked) Color(0xFF38BDF8) else Color(0x26FFFFFF),
                    shape = RoundedCornerShape(28.dp),
                ),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    View(ctx).apply {
                        val density = ctx.resources.displayMetrics.density
                        val tapSlopPx = 12f * density
                        val sensitivity = 1.65f

                        var downTime = 0L
                        var downX = 0f
                        var downY = 0f
                        var lastX = 0f
                        var lastY = 0f
                        var maxPointers = 0
                        var movedFar = false
                        var scrolling = false
                        var scrollCentroidX = 0f
                        var scrollCentroidY = 0f

                        setOnTouchListener { v, event ->
                            val ctrl = session.controller ?: return@setOnTouchListener true
                            val vw = videoW()
                            val vh = videoH()

                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    downTime = event.eventTime
                                    downX = event.x
                                    downY = event.y
                                    lastX = event.x
                                    lastY = event.y
                                    maxPointers = 1
                                    movedFar = false
                                    scrolling = false
                                }
                                MotionEvent.ACTION_POINTER_DOWN -> {
                                    maxPointers = maxOf(maxPointers, event.pointerCount)
                                    if (event.pointerCount == 2) {
                                        scrolling = true
                                        scrollCentroidX = (event.getX(0) + event.getX(1)) / 2f
                                        scrollCentroidY = (event.getY(0) + event.getY(1)) / 2f
                                    }
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    if (event.pointerCount >= 2 && scrolling) {
                                        val cx = (event.getX(0) + event.getX(1)) / 2f
                                        val cy = (event.getY(0) + event.getY(1)) / 2f
                                        val dx = cx - scrollCentroidX
                                        val dy = cy - scrollCentroidY
                                        if (hypot(dx.toDouble(), dy.toDouble()) > tapSlopPx / 2f) {
                                            movedFar = true
                                        }
                                        val step = 36f * density
                                        if (kotlin.math.abs(dx) >= 4f || kotlin.math.abs(dy) >= 4f) {
                                            ctrl.sendScrollAt(
                                                cursorX.toInt(),
                                                cursorY.toInt(),
                                                vw,
                                                vh,
                                                dx / step,
                                                dy / step,
                                            )
                                            scrollCentroidX = cx
                                            scrollCentroidY = cy
                                        }
                                    } else if (event.pointerCount == 1 && !scrolling) {
                                        val dx = event.x - lastX
                                        val dy = event.y - lastY
                                        if (hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > tapSlopPx) {
                                            movedFar = true
                                        }
                                        cursorX = (cursorX + dx * sensitivity).coerceIn(0f, (vw - 1).toFloat())
                                        cursorY = (cursorY + dy * sensitivity).coerceIn(0f, (vh - 1).toFloat())
                                        if (dragLocked) {
                                            ctrl.sendMouseDrag(cursorX.toInt(), cursorY.toInt(), vw, vh)
                                        } else {
                                            ctrl.sendHoverMove(cursorX.toInt(), cursorY.toInt(), vw, vh)
                                        }
                                        lastX = event.x
                                        lastY = event.y
                                    }
                                }
                                MotionEvent.ACTION_UP -> {
                                    val elapsed = event.eventTime - downTime
                                    if (!movedFar && elapsed < 300L) {
                                        if (maxPointers >= 2) {
                                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            ctrl.sendClick(cursorX.toInt(), cursorY.toInt(), vw, vh, DexInputController.BUTTON_SECONDARY)
                                        } else if (dragLocked) {
                                            setDragLock(false)
                                        } else {
                                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            ctrl.sendClick(cursorX.toInt(), cursorY.toInt(), vw, vh, DexInputController.BUTTON_PRIMARY)
                                        }
                                    }
                                    scrolling = false
                                    maxPointers = 0
                                }
                                MotionEvent.ACTION_CANCEL -> {
                                    scrolling = false
                                    maxPointers = 0
                                }
                            }
                            true
                        }
                    }
                },
            )

            // Subtle instructions & status centered on touchpad
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    if (dragLocked) Icons.Rounded.DragIndicator else Icons.Rounded.Mouse,
                    contentDescription = null,
                    tint = if (dragLocked) Color(0xFF38BDF8) else Color(0x38FFFFFF),
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (dragLocked) {
                        if (vi) "ĐANG GIỮ CHUỘT TRÁI (KÉO CỬA SỔ)\nChạm 1 cái hoặc bấm nút Giữ chuột để thả"
                        else "DRAG LOCK ACTIVE\nTap once or press Hold button to release"
                    } else {
                        if (vi) "1 ngón: Di chuột · Chạm: Click trái\n2 ngón: Cuộn trang · Chạm 2 ngón: Click phải"
                        else "1 finger: Move cursor · Tap: Left click\n2 fingers: Scroll · 2-finger tap: Right click"
                    },
                    color = if (dragLocked) Color(0xFFBAE6FD) else Color(0x66FFFFFF),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "(${cursorX.toInt()}, ${cursorY.toInt()})",
                    color = Color(0x40FFFFFF),
                    fontSize = 11.sp,
                )
            }
        }

        // Optional Keyboard input bar (injects text + Enter/Backspace into DeX)
        if (showKeyboard) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    singleLine = true,
                    placeholder = { Text(if (vi) "Gõ chữ gửi sang Samsung DeX…" else "Type text to send to DeX…") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            val text = inputText
                            if (text.isNotEmpty()) {
                                session.controller?.sendText(text)
                                inputText = ""
                            } else {
                                session.controller?.sendKeyPress(KeyEvent.KEYCODE_ENTER)
                            }
                        },
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { session.controller?.sendKeyPress(KeyEvent.KEYCODE_DEL) },
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = "Backspace", tint = Color.White)
                }
                FilledTonalIconButton(
                    onClick = {
                        val text = inputText
                        if (text.isNotEmpty()) {
                            session.controller?.sendText(text)
                            inputText = ""
                        } else {
                            session.controller?.sendKeyPress(KeyEvent.KEYCODE_ENTER)
                        }
                    },
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Left/Right Click + Drag-Lock row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    session.controller?.sendClick(cursorX.toInt(), cursorY.toInt(), videoW(), videoH(), DexInputController.BUTTON_PRIMARY)
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (vi) "Click trái" else "Left Click", maxLines = 1)
            }
            FilledTonalButton(
                onClick = { setDragLock(!dragLocked) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (dragLocked) (if (vi) "Thả chuột" else "Release")
                    else (if (vi) "Giữ kéo" else "Drag Lock"),
                    maxLines = 1,
                )
            }
            FilledTonalButton(
                onClick = {
                    session.controller?.sendClick(cursorX.toInt(), cursorY.toInt(), videoW(), videoH(), DexInputController.BUTTON_SECONDARY)
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (vi) "Click phải" else "Right Click", maxLines = 1)
            }
        }

        Spacer(Modifier.height(8.dp))

        // Navigation + Keyboard dock
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TouchpadNavButton(
                label = if (vi) "Quay lại" else "Back",
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                modifier = Modifier.weight(1f),
            ) { session.controller?.sendKeyPress(KeyEvent.KEYCODE_BACK) }

            TouchpadNavButton(
                label = "Home",
                icon = Icons.Rounded.Home,
                modifier = Modifier.weight(1f),
            ) { session.controller?.sendKeyPress(KeyEvent.KEYCODE_HOME) }

            TouchpadNavButton(
                label = if (vi) "Đa nhiệm" else "Recents",
                icon = Icons.Rounded.ViewCarousel,
                modifier = Modifier.weight(1f),
            ) { session.controller?.sendKeyPress(KeyEvent.KEYCODE_APP_SWITCH) }

            TouchpadNavButton(
                label = if (vi) "Bàn phím" else "Keyboard",
                icon = Icons.Rounded.Keyboard,
                highlighted = showKeyboard,
                modifier = Modifier.weight(1f),
            ) { showKeyboard = !showKeyboard }
        }
    }
}

@Composable
private fun TouchpadNavButton(
    label: String,
    icon: ImageVector,
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (highlighted) Color(0xFF1E3A5F) else Color(0xFF151922),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (highlighted) Color(0xFF38BDF8) else Color.White,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(label, color = Color(0xFFCBD5E1), fontSize = 11.sp, maxLines = 1)
        }
    }
}
