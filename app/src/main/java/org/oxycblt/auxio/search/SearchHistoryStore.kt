/*
 * Copyright (c) 2026 Auxio Project
 * SearchHistoryStore.kt is part of Auxio.
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
package org.oxycblt.auxio.search

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class RecentSearch(
    val query: String,
    val title: String,
    val subtitle: String? = null,
    val artwork: String? = null,
)

/** Small durable search history used only to make the empty Search screen useful. */
@Singleton
class SearchHistoryStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    fun observe(): StateFlow<List<RecentSearch>> = state.asStateFlow()

    fun record(query: String, title: String, subtitle: String?, artwork: String?) {
        val normalizedQuery = query.trim().take(MAX_QUERY_LENGTH)
        val normalizedTitle = title.trim().take(MAX_TITLE_LENGTH)
        if (normalizedQuery.isEmpty() || normalizedTitle.isEmpty()) return
        val entry =
            RecentSearch(
                query = normalizedQuery,
                title = normalizedTitle,
                subtitle = subtitle?.trim()?.take(MAX_SUBTITLE_LENGTH)?.takeIf(String::isNotEmpty),
                artwork =
                    artwork?.trim()?.take(MAX_ARTWORK_LENGTH)?.takeIf { it.startsWith("https://") },
            )
        val updated =
            (listOf(entry) +
                    state.value.filterNot { it.query.equals(normalizedQuery, ignoreCase = true) })
                .take(MAX_ENTRIES)
        persist(updated)
    }

    fun clear() {
        preferences.edit().remove(KEY_HISTORY).apply()
        state.value = emptyList()
    }

    private fun persist(entries: List<RecentSearch>) {
        val encoded =
            JSONArray().apply {
                entries.forEach { entry ->
                    put(
                        JSONObject()
                            .put("q", entry.query)
                            .put("t", entry.title)
                            .put("s", entry.subtitle)
                            .put("a", entry.artwork)
                    )
                }
            }
        preferences.edit().putString(KEY_HISTORY, encoded.toString()).apply()
        state.value = entries
    }

    private fun read(): List<RecentSearch> =
        runCatching {
                val array = JSONArray(preferences.getString(KEY_HISTORY, "[]"))
                buildList {
                    repeat(minOf(array.length(), MAX_ENTRIES)) { index ->
                        val item = array.getJSONObject(index)
                        val query = item.optString("q").trim().take(MAX_QUERY_LENGTH)
                        val title = item.optString("t").trim().take(MAX_TITLE_LENGTH)
                        if (query.isEmpty() || title.isEmpty()) return@repeat
                        add(
                            RecentSearch(
                                query = query,
                                title = title,
                                subtitle =
                                    item
                                        .takeUnless { it.isNull("s") }
                                        ?.optString("s")
                                        ?.trim()
                                        ?.take(MAX_SUBTITLE_LENGTH)
                                        ?.takeIf(String::isNotEmpty),
                                artwork =
                                    item
                                        .takeUnless { it.isNull("a") }
                                        ?.optString("a")
                                        ?.trim()
                                        ?.take(MAX_ARTWORK_LENGTH)
                                        ?.takeIf { it.startsWith("https://") },
                            )
                        )
                    }
                }
            }
            .getOrElse {
                preferences.edit().remove(KEY_HISTORY).apply()
                emptyList()
            }

    private companion object {
        const val PREFERENCES_NAME = "shippy_search_history"
        const val KEY_HISTORY = "history"
        const val MAX_ENTRIES = 12
        const val MAX_QUERY_LENGTH = 160
        const val MAX_TITLE_LENGTH = 240
        const val MAX_SUBTITLE_LENGTH = 320
        const val MAX_ARTWORK_LENGTH = 2_048
    }
}
