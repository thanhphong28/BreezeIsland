package com.arnav.island.dex

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

/**
 * Translates touch gestures and navigation keys into scrcpy v4.1 control protocol messages.
 *
 * Uses scrcpy's virtual mouse model (`pointerId = -1`, hover-before-press, button state) so
 * One UI 8.5/9 freeform window caption dragging works smoothly without SystemUI NPEs:
 *  - 1 finger: mouse click & drag (window handles, sliders, buttons)
 *  - 2 fingers: scroll wheel (horizontal & vertical)
 *  - Back / Home / Recents: forwarded directly to the DeX display
 */
class DexInputController(
    private val output: OutputStream,
    private val input: InputStream,
) {
    companion object {
        private const val TAG = "DexInputController"
        private const val TYPE_INJECT_KEYCODE = 0
        private const val TYPE_INJECT_TOUCH_EVENT = 2
        private const val TYPE_INJECT_SCROLL_EVENT = 3
        private const val POINTER_ID_MOUSE = -1L
        private const val BUTTON_PRIMARY = 1
        private const val SCROLL_PX_PER_TICK = 64f

        internal fun pressureToU16FixedPoint(pressure: Float): Short {
            val clamped = pressure.coerceIn(0f, 1f)
            return if (clamped == 1f) 0xffff.toShort() else (clamped * 0x10000).toInt().toShort()
        }

        internal fun scrollToI16FixedPoint(value: Float): Short {
            val clamped = (value / 16f).coerceIn(-1f, 1f)
            return if (clamped == 1f) {
                0x7fff.toShort()
            } else {
                (clamped * 0x8000).toInt().coerceIn(-0x8000, 0x7fff).toShort()
            }
        }
    }

    private val queue = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var running = true

    private val senderThread = Thread({
        try {
            while (running) {
                val message = queue.take()
                output.write(message)
                output.flush()
            }
        } catch (_: InterruptedException) {
        } catch (e: IOException) {
            if (running) Log.w(TAG, "Control socket write failed", e)
        }
    }, "breeze-dex-ctrl-send")

    private val drainThread = Thread({
        val buffer = ByteArray(4096)
        try {
            while (running && input.read(buffer) != -1) {
                // Consume device messages so server writer never blocks.
            }
        } catch (_: IOException) {
        }
    }, "breeze-dex-ctrl-drain")

    fun start() {
        senderThread.start()
        drainThread.start()
    }

    fun stop() {
        running = false
        senderThread.interrupt()
        runCatching { output.close() }
        runCatching { input.close() }
    }

    fun sendKeyPress(keycode: Int) {
        sendKey(KeyEvent.ACTION_DOWN, keycode)
        sendKey(KeyEvent.ACTION_UP, keycode)
    }

    private fun sendKey(action: Int, keycode: Int) {
        val buffer = ByteBuffer.allocate(14)
        buffer.put(TYPE_INJECT_KEYCODE.toByte())
        buffer.put(action.toByte())
        buffer.putInt(keycode)
        buffer.putInt(0)
        buffer.putInt(0)
        queue.offer(buffer.array())
    }

    private fun sendMouse(
        action: Int,
        x: Int,
        y: Int,
        videoWidth: Int,
        videoHeight: Int,
        pressure: Float,
        actionButton: Int,
        buttons: Int,
    ) {
        val buffer = ByteBuffer.allocate(32)
        buffer.put(TYPE_INJECT_TOUCH_EVENT.toByte())
        buffer.put(action.toByte())
        buffer.putLong(POINTER_ID_MOUSE)
        buffer.putInt(x)
        buffer.putInt(y)
        buffer.putShort(videoWidth.toShort())
        buffer.putShort(videoHeight.toShort())
        buffer.putShort(pressureToU16FixedPoint(pressure))
        buffer.putInt(actionButton)
        buffer.putInt(buttons)
        queue.offer(buffer.array())
    }

    private fun sendScroll(
        x: Int,
        y: Int,
        videoWidth: Int,
        videoHeight: Int,
        hScroll: Float,
        vScroll: Float,
    ) {
        val buffer = ByteBuffer.allocate(21)
        buffer.put(TYPE_INJECT_SCROLL_EVENT.toByte())
        buffer.putInt(x)
        buffer.putInt(y)
        buffer.putShort(videoWidth.toShort())
        buffer.putShort(videoHeight.toShort())
        buffer.putShort(scrollToI16FixedPoint(hScroll))
        buffer.putShort(scrollToI16FixedPoint(vScroll))
        buffer.putInt(0)
        queue.offer(buffer.array())
    }

    private enum class Gesture { NONE, MOUSE, SCROLL, DONE }

    private var gesture = Gesture.NONE
    private var lastX = 0
    private var lastY = 0

    fun forwardMotionEvent(
        event: MotionEvent,
        viewWidth: Int,
        viewHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
    ) {
        if (viewWidth == 0 || viewHeight == 0 || videoWidth == 0 || videoHeight == 0) return

        fun videoX(pointerIndex: Int) =
            (event.getX(pointerIndex) * videoWidth / viewWidth).toInt().coerceIn(0, videoWidth - 1)

        fun videoY(pointerIndex: Int) =
            (event.getY(pointerIndex) * videoHeight / viewHeight).toInt().coerceIn(0, videoHeight - 1)

        fun centroidX(): Int {
            var sum = 0
            for (i in 0 until event.pointerCount) sum += videoX(i)
            return sum / event.pointerCount
        }

        fun centroidY(): Int {
            var sum = 0
            for (i in 0 until event.pointerCount) sum += videoY(i)
            return sum / event.pointerCount
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture = Gesture.MOUSE
                lastX = videoX(0)
                lastY = videoY(0)
                sendMouse(MotionEvent.ACTION_HOVER_MOVE, lastX, lastY, videoWidth, videoHeight, 0f, 0, 0)
                sendMouse(MotionEvent.ACTION_DOWN, lastX, lastY, videoWidth, videoHeight, 1f, BUTTON_PRIMARY, BUTTON_PRIMARY)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (gesture == Gesture.MOUSE) {
                    sendMouse(MotionEvent.ACTION_UP, lastX, lastY, videoWidth, videoHeight, 0f, BUTTON_PRIMARY, 0)
                    gesture = Gesture.SCROLL
                    lastX = centroidX()
                    lastY = centroidY()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (gesture) {
                    Gesture.MOUSE -> {
                        lastX = videoX(0)
                        lastY = videoY(0)
                        sendMouse(MotionEvent.ACTION_MOVE, lastX, lastY, videoWidth, videoHeight, 1f, 0, BUTTON_PRIMARY)
                    }
                    Gesture.SCROLL -> {
                        val cx = centroidX()
                        val cy = centroidY()
                        val dx = cx - lastX
                        val dy = cy - lastY
                        if (dx != 0 || dy != 0) {
                            sendScroll(
                                cx, cy, videoWidth, videoHeight,
                                dx / SCROLL_PX_PER_TICK,
                                dy / SCROLL_PX_PER_TICK,
                            )
                            lastX = cx
                            lastY = cy
                        }
                    }
                    else -> {}
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (gesture == Gesture.SCROLL && event.pointerCount <= 2) {
                    gesture = Gesture.DONE
                }
            }
            MotionEvent.ACTION_UP -> {
                if (gesture == Gesture.MOUSE) {
                    sendMouse(MotionEvent.ACTION_UP, videoX(0), videoY(0), videoWidth, videoHeight, 0f, BUTTON_PRIMARY, 0)
                }
                gesture = Gesture.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                if (gesture == Gesture.MOUSE) {
                    sendMouse(MotionEvent.ACTION_UP, lastX, lastY, videoWidth, videoHeight, 0f, BUTTON_PRIMARY, 0)
                }
                gesture = Gesture.NONE
            }
        }
    }
}
