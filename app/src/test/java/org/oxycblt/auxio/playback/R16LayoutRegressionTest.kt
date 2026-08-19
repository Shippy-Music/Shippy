/*
 * Copyright (c) 2026 Auxio Project
 * R16LayoutRegressionTest.kt is part of Auxio.
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
package org.oxycblt.auxio.playback

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R16LayoutRegressionTest {
    @Test
    fun `seek bar never inherits the tall lyrics panel height`() {
        listOf(
                "layout-h360dp/fragment_playback_panel.xml",
                "layout-h520dp/fragment_playback_panel.xml",
            )
            .forEach { relativePath ->
                val seekBar = viewTag(readResource(relativePath), "playback_seek_bar")
                assertTrue(
                    "$relativePath must keep a content-height seek bar",
                    seekBar.contains("android:layout_height=\"wrap_content\""),
                )
            }

        val lyrics =
            viewTag(
                readResource("layout-h520dp/fragment_playback_panel.xml"),
                "playback_lyrics_container",
            )
        assertTrue(
            "The 304dp preview height belongs to the lyrics container",
            lyrics.contains("android:layout_height=\"304dp\""),
        )
    }

    @Test
    fun `edit order controls belong only to the playlist layout`() {
        assertFalse(readResource("layout/fragment_home_list.xml").contains("home_collection_edit"))

        val playlistLayout = readResource("layout/fragment_playlist_list.xml")
        listOf(
                "home_collection_edit_start",
                "home_collection_edit_actions",
                "home_collection_edit_cancel",
                "home_collection_edit_done",
            )
            .forEach { id ->
                assertTrue("Playlist layout is missing $id", playlistLayout.contains("@+id/$id"))
            }
    }

    private fun readResource(relativePath: String): String {
        val roots = listOf(Path.of("src/main/res"), Path.of("app/src/main/res"))
        val root = roots.firstOrNull { Files.isDirectory(it) }
        requireNotNull(root) {
            "Unable to locate app/src/main/res from ${Path.of("").toAbsolutePath()}"
        }
        return String(Files.readAllBytes(root.resolve(relativePath)), Charsets.UTF_8)
    }

    private fun viewTag(xml: String, id: String): String {
        val idIndex = xml.indexOf("android:id=\"@+id/$id\"")
        require(idIndex >= 0) { "Missing view ID $id" }
        val start = xml.lastIndexOf('<', idIndex)
        val end = xml.indexOf('>', idIndex)
        require(start >= 0 && end > idIndex) { "Malformed view tag for $id" }
        return xml.substring(start, end + 1)
    }
}
