package com.arnav.island.dex

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class LocalDexSpecTest {

    @Test
    fun allBuiltInPresetsAreValid() {
        for (preset in DexDisplaySpec.PRESETS) {
            assertTrue("Preset ${preset.spec} must be valid", DexDisplaySpec.isValid(preset.spec))
            val parsed = DexDisplaySpec.parse(preset.spec)
            assertNotNull(parsed)
        }
    }

    @Test
    fun parseRejectsMalformedAndOutOfRangeSpecs() {
        assertNull(DexDisplaySpec.parse(""))
        assertNull(DexDisplaySpec.parse("1920x1080"))
        assertNull(DexDisplaySpec.parse("100x100/240"))
        assertNull(DexDisplaySpec.parse("1920x1080/999"))
        assertNull(DexDisplaySpec.parse("abcxdef/ghi"))
        assertFalse(DexDisplaySpec.isValid("9999x9999/240"))
        assertEquals(DexDisplaySpec.DEFAULT_SPEC, DexDisplaySpec.normalize("invalid"))
        assertEquals("1920x1080/160", DexDisplaySpec.normalize(" 1920x1080/160 "))
    }

    @Test
    fun parseDisplayIdExtractsVirtualDisplayNumberFromServerLog() {
        val log = "[server] INFO: New display: 1920x1080/160 (id=7)\n[server] INFO: Ready"
        assertEquals(7, DexDisplaySpec.parseDisplayId(log))
        assertNull(DexDisplaySpec.parseDisplayId("[server] INFO: Starting scrcpy"))
    }

    @Test
    fun fixedPointEncodingsStayWithinBounds() {
        assertEquals(0.toShort(), DexInputController.pressureToU16FixedPoint(0f))
        assertEquals(0xffff.toShort(), DexInputController.pressureToU16FixedPoint(1f))
        assertEquals(0xffff.toShort(), DexInputController.pressureToU16FixedPoint(2.5f))
        assertEquals(0.toShort(), DexInputController.scrollToI16FixedPoint(0f))
        assertEquals(0x7fff.toShort(), DexInputController.scrollToI16FixedPoint(16f))
        assertEquals((-0x8000).toShort(), DexInputController.scrollToI16FixedPoint(-16f))
    }

    @Test
    fun injectTextEncodesUtf8LengthAndPayload() {
        val text = "Xin chào DeX"
        val utf8 = text.toByteArray(Charsets.UTF_8)
        val packet = DexInputController.encodeInjectText(text)
        val buf = ByteBuffer.wrap(packet)
        assertEquals(1.toByte(), buf.get())
        assertEquals(utf8.size, buf.getInt())
        val payload = ByteArray(utf8.size)
        buf.get(payload)
        assertEquals(text, String(payload, Charsets.UTF_8))
    }

    @Test
    fun extractAvcCodecStringParsesSpsProfileCompatLevel() {
        // Annex-B SPS: 00 00 00 01 67 64 00 2a ... (High Profile, level 4.2)
        val sps = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x64, 0x00, 0x2a, 0x00)
        assertEquals("avc1.64002a", DexVideoDecoder.extractAvcCodecString(sps))
        val baseline = byteArrayOf(0x00, 0x00, 0x01, 0x67, 0x42, 0xe0.toByte(), 0x1f, 0x00)
        assertEquals("avc1.42e01f", DexVideoDecoder.extractAvcCodecString(baseline))
    }

    @Test
    fun externalDisplayFilterAcceptsHdmiAndRejectsZFlipCoverAndScrcpy() {
        // Valid external HDMI / Smart View display
        assertTrue(
            ExternalDisplayFinder.isExternalDisplayCandidate(
                displayId = 2,
                name = "HDMI Screen",
                flags = Display.FLAG_PRESENTATION,
                state = Display.STATE_ON,
                ignoreVirtualDisplayId = 7,
            )
        )
        // Reject default screen
        assertFalse(
            ExternalDisplayFinder.isExternalDisplayCandidate(
                displayId = Display.DEFAULT_DISPLAY,
                name = "Built-in Screen",
                flags = 0,
                state = Display.STATE_ON,
                ignoreVirtualDisplayId = 7,
            )
        )
        // Reject Z Flip built-in Cover Screen / Flex Window
        assertFalse(
            ExternalDisplayFinder.isExternalDisplayCandidate(
                displayId = 1,
                name = "Built-in Cover Screen",
                flags = Display.FLAG_PRESENTATION,
                state = Display.STATE_ON,
                ignoreVirtualDisplayId = 7,
            )
        )
        // Reject our own virtual scrcpy display
        assertFalse(
            ExternalDisplayFinder.isExternalDisplayCandidate(
                displayId = 7,
                name = "scrcpy",
                flags = Display.FLAG_PRESENTATION,
                state = Display.STATE_ON,
                ignoreVirtualDisplayId = 7,
            )
        )
        // Reject private displays
        assertFalse(
            ExternalDisplayFinder.isExternalDisplayCandidate(
                displayId = 3,
                name = "Private Virtual",
                flags = Display.FLAG_PRIVATE,
                state = Display.STATE_ON,
                ignoreVirtualDisplayId = 7,
            )
        )
    }

    @Test
    fun transportSelectionRespectsConnectionMode() {
        val bothReady = LocalDexState(shizukuPermitted = true, adbConnected = true)
        assertEquals(DexTransport.SHIZUKU, bothReady.bestReadyTransport(DexConnectionMode.AUTO))
        assertEquals(DexTransport.SHIZUKU, bothReady.bestReadyTransport(DexConnectionMode.SHIZUKU))
        assertEquals(DexTransport.WIRELESS_ADB, bothReady.bestReadyTransport(DexConnectionMode.WIRELESS_ADB))

        val adbOnly = LocalDexState(shizukuPermitted = false, adbConnected = true)
        assertEquals(DexTransport.WIRELESS_ADB, adbOnly.bestReadyTransport(DexConnectionMode.AUTO))
        assertNull(adbOnly.bestReadyTransport(DexConnectionMode.SHIZUKU))
        assertEquals(DexTransport.WIRELESS_ADB, adbOnly.bestReadyTransport(DexConnectionMode.WIRELESS_ADB))
    }
}
