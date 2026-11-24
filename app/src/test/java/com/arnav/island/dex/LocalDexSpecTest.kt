package com.arnav.island.dex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals("1920x1440/240", DexDisplaySpec.normalize(" 1920x1440/240 "))
    }

    @Test
    fun parseDisplayIdExtractsVirtualDisplayNumberFromServerLog() {
        val log = "[server] INFO: New display: 2200x1080/240 (id=7)\n[server] INFO: Ready"
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
