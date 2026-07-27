/*
 * Copyright (c) 2026 Auxio Project
 * LibraryCollectionLayoutStore.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.library

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.oxycblt.auxio.shippy.domain.LibraryCollectionId

/**
 * Small durable presentation authority for the mixed system/user collection list.
 *
 * Room still owns user-playlist data. This store owns only the cross-type order and the pin state
 * needed to place permanent collections beside user playlists.
 */
@Singleton
class LibraryCollectionLayoutStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableEntries = MutableStateFlow(readEntries())

    internal val entries = mutableEntries.asStateFlow()

    @Synchronized
    internal fun replace(entries: List<LibraryCollectionLayoutEntry>) {
        require(entries.map { it.id }.distinct().size == entries.size) {
            "Collection layout cannot contain duplicate IDs"
        }
        mutableEntries.value = entries
        preferences.edit().putString(KEY_LAYOUT, entries.encode()).apply()
    }

    @Synchronized
    internal fun setPinned(id: LibraryCollectionId, pinned: Boolean) {
        val current = mutableEntries.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            if (current[index].pinned == pinned) return
            current[index] = current[index].copy(pinned = pinned)
        } else {
            current += LibraryCollectionLayoutEntry(id, pinned)
        }
        replace(current)
    }

    private fun readEntries(): List<LibraryCollectionLayoutEntry> =
        preferences
            .getString(KEY_LAYOUT, null)
            ?.lineSequence()
            ?.mapNotNull { line ->
                val separator = line.lastIndexOf(SEPARATOR)
                if (separator <= 0 || separator >= line.lastIndex) return@mapNotNull null
                val id =
                    line.substring(0, separator).takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                val pinned =
                    when (line.substring(separator + 1)) {
                        "1" -> true
                        "0" -> false
                        else -> return@mapNotNull null
                    }
                runCatching { LibraryCollectionLayoutEntry(LibraryCollectionId(id), pinned) }
                    .getOrNull()
            }
            ?.distinctBy(LibraryCollectionLayoutEntry::id)
            ?.toList()
            .orEmpty()

    private fun List<LibraryCollectionLayoutEntry>.encode() =
        joinToString("\n") { entry -> "${entry.id.value}$SEPARATOR${if (entry.pinned) 1 else 0}" }

    private companion object {
        const val PREFERENCES_NAME = "shippy_library_layout"
        const val KEY_LAYOUT = "collections"
        const val SEPARATOR = '\t'
    }
}

internal data class LibraryCollectionLayoutEntry(val id: LibraryCollectionId, val pinned: Boolean)
