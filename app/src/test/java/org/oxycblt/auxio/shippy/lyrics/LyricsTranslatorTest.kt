/*
 * Copyright (c) 2026 Auxio Project
 * LyricsTranslatorTest.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.oxycblt.auxio.shippy.lyrics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTranslatorTest {
    @Test
    fun `synced translation preserves timestamps order and duplicate lines`() = runBlocking {
        val calls = mutableListOf<String>()
        val original =
            SyncedLyrics(
                lines =
                    listOf(
                        SyncedLyricLine(1_000, "तुम ही हो"),
                        SyncedLyricLine(2_500, "अब तुम ही हो"),
                        SyncedLyricLine(4_000, "तुम ही हो"),
                    ),
                plainText = "तुम ही हो\nअब तुम ही हो",
            )

        val translated =
            translateLyricsWith(original) { line ->
                calls += line
                mapOf("तुम ही हो" to "You are the one", "अब तुम ही हो" to "Now you are the one")
                    .getValue(line)
            } as TranslatedLyrics.Synced

        assertEquals(listOf("तुम ही हो", "अब तुम ही हो"), calls)
        assertEquals(listOf(1_000L, 2_500L, 4_000L), translated.lines.map { it.startMs })
        assertEquals(
            listOf("You are the one", "Now you are the one", "You are the one"),
            translated.lines.map { it.translatedText },
        )
    }

    @Test
    fun `plain translation retains original lines and blanks`() = runBlocking {
        val translated =
            translateLyricsWith(PlainLyrics("पहली पंक्ति\n\nदूसरी पंक्ति")) { "English: $it" }
                as TranslatedLyrics.Plain

        assertEquals(
            listOf("पहली पंक्ति", "", "दूसरी पंक्ति"),
            translated.lines.map { it.originalText },
        )
        assertEquals(
            listOf("English: पहली पंक्ति", "", "English: दूसरी पंक्ति"),
            translated.lines.map { it.translatedText },
        )
    }
}
