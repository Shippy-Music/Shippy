/*
 * Copyright (c) 2026 Shippy contributors
 * LrcParserTest.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {
    @Test
    fun `plain lyrics preserve meaningful lines and normalize newlines`() {
        val lyrics = LrcParser.parse("First line\r\n\r\nSecond line\r")

        assertEquals(PlainLyrics("First line\nSecond line"), lyrics)
    }

    @Test
    fun `synced lyrics accept common timestamp precisions and sort chronologically`() {
        val lyrics =
            LrcParser.parse(
                """
                [ar:Artist]
                [00:02.5] Half second
                [00:01.050] Milliseconds
                [00:03] Whole second
                """.trimIndent()
            ) as SyncedLyrics

        assertEquals(
            listOf(
                SyncedLyricLine(1_050, "Milliseconds"),
                SyncedLyricLine(2_500, "Half second"),
                SyncedLyricLine(3_000, "Whole second"),
            ),
            lyrics.lines,
        )
        assertEquals("Half second\nMilliseconds\nWhole second", lyrics.plainText)
    }

    @Test
    fun `multiple timestamps and offset produce one entry per timestamp`() {
        val lyrics =
            LrcParser.parse(
                """
                [offset:-250]
                [00:00.10] Starts at zero
                [00:02.00][00:04.25] Chorus
                """.trimIndent()
            ) as SyncedLyrics

        assertEquals(
            listOf(
                SyncedLyricLine(0, "Starts at zero"),
                SyncedLyricLine(1_750, "Chorus"),
                SyncedLyricLine(4_000, "Chorus"),
            ),
            lyrics.lines,
        )
        assertEquals("Starts at zero\nChorus", lyrics.plainText)
    }

    @Test
    fun `invalid timestamps remain plain instead of producing corrupt timing`() {
        val lyrics = LrcParser.parse("[00:99.10] Impossible")

        assertTrue(lyrics is PlainLyrics)
        assertEquals("[00:99.10] Impossible", lyrics.plainText)
    }
}
