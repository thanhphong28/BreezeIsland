package com.arnav.island.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRepositoryTest {

    @Test
    fun `parses standard and multi-tag LRC lines in sorted order`() {
        val sample = """
            [ti:Test Song]
            [00:12.50] First line
            [00:25.100][01:05.5] Chorus line
            [00:18] Second line
        """.trimIndent()

        val parsed = LyricsRepository.parseLrc(sample)
        assertEquals(4, parsed.size)
        assertEquals(12_500L, parsed[0].timeMs)
        assertEquals("First line", parsed[0].text)
        assertEquals(18_000L, parsed[1].timeMs)
        assertEquals("Second line", parsed[1].text)
        assertEquals(25_100L, parsed[2].timeMs)
        assertEquals("Chorus line", parsed[2].text)
        assertEquals(65_500L, parsed[3].timeMs)
        assertEquals("Chorus line", parsed[3].text)
    }

    @Test
    fun `slices active prev current and upcoming lines for playback timestamp`() {
        val lines = LyricsRepository.parseLrc(
            """
            [00:10.00] Line one
            [00:20.00] Line two
            [00:30.00] Line three
            [00:40.00] Line four
            [00:50.00] Line five
            """.trimIndent()
        )

        val mid = LyricsRepository.sliceSynced(lines, 21_500L)
        assertTrue(mid.hasSynced)
        assertEquals("Line one", mid.prevLine)
        assertEquals("Line two", mid.currentLine)
        assertEquals("Line three", mid.nextLine)
        assertEquals(listOf("Line three", "Line four", "Line five"), mid.upcomingLines)
    }
}
