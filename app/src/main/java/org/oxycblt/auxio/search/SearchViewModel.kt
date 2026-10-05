/*
 * Copyright (c) 2021 Auxio Project
 * SearchViewModel.kt is part of Auxio.
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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.list.BasicHeader
import org.oxycblt.auxio.list.Item
import org.oxycblt.auxio.list.PlainDivider
import org.oxycblt.auxio.list.sort.Sort
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.MusicType
import org.oxycblt.auxio.playback.PlaySong
import org.oxycblt.auxio.playback.PlaybackSettings
import org.oxycblt.auxio.shippy.domain.PlaybackPreparation
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.playback.PlaybackStartResult
import org.oxycblt.auxio.shippy.playback.ShippyPlaybackController
import org.oxycblt.auxio.shippy.provider.ProviderDescriptor
import org.oxycblt.auxio.shippy.provider.ProviderEntity
import org.oxycblt.auxio.shippy.provider.ProviderEntityType
import org.oxycblt.auxio.shippy.search.ProviderSearchSnapshot
import org.oxycblt.auxio.shippy.search.UnifiedSearchRepository
import org.oxycblt.auxio.util.Event
import org.oxycblt.auxio.util.MutableEvent
import org.oxycblt.musikr.Library
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * An [ViewModel] that keeps performs search operations and tracks their results.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@HiltViewModel
class SearchViewModel
@Inject
constructor(
    private val musicRepository: MusicRepository,
    private val searchEngine: SearchEngine,
    private val searchSettings: SearchSettings,
    private val playbackSettings: PlaybackSettings,
    private val unifiedSearchRepository: UnifiedSearchRepository,
    private val shippyPlaybackController: ShippyPlaybackController,
    private val searchHistory: SearchHistoryStore,
) : ViewModel(), MusicRepository.UpdateListener {
    private var lastQuery: String? = null
    private var currentSearchJob: Job? = null
    private var currentProviderPlaybackJob: Job? = null
    private var localOnly = false
    val providerOptions: List<ProviderDescriptor> = unifiedSearchRepository.providers()
    private val _selectedProvider =
        MutableStateFlow(
            unifiedSearchRepository.preferredProvider() ?: providerOptions.firstOrNull()
        )
    val selectedProvider: StateFlow<ProviderDescriptor?>
        get() = _selectedProvider

    private val _searchResults = MutableStateFlow(listOf<Item>())
    /** The results of the last [search] call, if any. */
    val searchResults: StateFlow<List<Item>>
        get() = _searchResults

    private val _providerPlaybackFailure = MutableEvent<PlaybackPreparation.Failed>()
    val providerPlaybackFailure: Event<PlaybackPreparation.Failed>
        get() = _providerPlaybackFailure

    /** The [PlaySong] instructions to use when playing a [Song]. */
    val playWith
        get() = playbackSettings.playInListWith

    init {
        musicRepository.addUpdateListener(this)
        viewModelScope.launch {
            searchHistory.observe().collect { history ->
                if (lastQuery.isNullOrBlank()) {
                    _searchResults.value = history.asSearchItems()
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        musicRepository.removeUpdateListener(this)
    }

    override fun onMusicChanges(changes: MusicRepository.Changes) {
        if (changes.deviceLibrary || changes.userLibrary) {
            L.d("Music changed, re-searching library")
            search(lastQuery)
        }
    }

    /**
     * Asynchronously search the music library. Results will be pushed to [searchResults]. Will
     * cancel any previous search operations started prior.
     *
     * @param query The query to search the music library for.
     */
    fun search(query: String?) {
        // Cancel the previous background search.
        currentSearchJob?.cancel()
        lastQuery = query

        val normalizedQuery = query?.trim().orEmpty()
        if (normalizedQuery.isEmpty()) {
            L.d("Cannot search for the current query, aborting")
            _searchResults.value = searchHistory.observe().value.asSearchItems()
            return
        }

        // Local and provider work are independent. Publish the local result first, then append
        // provider sections without making one failed provider erase the useful result set.
        L.d("Searching Shippy for $normalizedQuery")
        currentSearchJob =
            viewModelScope.launch {
                // Coalesce keyboard bursts before scanning large device libraries.
                delay(90)
                val searchProviders = !localOnly
                val providerId = _selectedProvider.value?.id
                var localItems = emptyList<Item>()
                var providers: ProviderSearchSnapshot? = null
                var providersLoading = searchProviders
                fun publish() {
                    _searchResults.value =
                        combineSearchResults(
                            providers = providers,
                            localItems = localItems,
                            providersLoading = providersLoading,
                            filters = searchSettings.filters,
                        )
                }
                publish()
                launch {
                    localItems =
                        withContext(Dispatchers.Default) {
                            musicRepository.library
                                ?.let { searchImpl(it, normalizedQuery) }
                                .orEmpty()
                        }
                    ensureActive()
                    publish()
                }
                if (searchProviders)
                    launch {
                        providers = unifiedSearchRepository.search(normalizedQuery, providerId)
                        ensureActive()
                        providersLoading = false
                        publish()
                    }
            }
    }

    fun playProviderTrack(track: Track) {
        currentProviderPlaybackJob?.cancel()
        currentProviderPlaybackJob =
            viewModelScope.launch {
                when (val result = shippyPlaybackController.play(track, contextId = "search")) {
                    is PlaybackStartResult.Started ->
                        recordSearchSelection(
                            title = track.title,
                            subtitle = track.artists.joinToString(", ").ifBlank { track.album },
                            artwork = track.artwork,
                        )
                    is PlaybackStartResult.Failed -> _providerPlaybackFailure.put(result.failure)
                }
            }
    }

    fun selectProvider(provider: ProviderDescriptor) {
        if (provider !in providerOptions || provider == _selectedProvider.value) return
        _selectedProvider.value = provider
        search(lastQuery)
    }

    fun setLocalOnly(enabled: Boolean) {
        if (localOnly == enabled) return
        localOnly = enabled
        search(lastQuery)
    }

    fun recordSearchSelection(title: String, subtitle: String? = null, artwork: String? = null) {
        val query = lastQuery?.takeIf(String::isNotBlank) ?: return
        searchHistory.record(query, title, subtitle, artwork)
    }

    fun clearSearchHistory() {
        searchHistory.clear()
    }

    private fun combineSearchResults(
        providers: ProviderSearchSnapshot?,
        localItems: List<Item>,
        providersLoading: Boolean,
        filters: Set<MusicType>,
    ): List<Item> = buildList {
        // Device matches are the fastest and cheapest answer. Keep them visibly separate, but
        // surface them before remote provider sections so an already-owned song is never buried.
        if (localItems.isNotEmpty()) {
            add(BasicHeader(R.string.lbl_on_this_device))
            addAll(localItems)
        }

        val providerTypesEnabled =
            filters.isEmpty() ||
                filters.any {
                    it == MusicType.SONGS ||
                        it == MusicType.ALBUMS ||
                        it == MusicType.ARTISTS ||
                        it == MusicType.PLAYLISTS
                }
        if (providersLoading && providerTypesEnabled) {
            if (isNotEmpty()) add(PlainDivider(null))
            add(BasicHeader(R.string.lbl_searching_providers))
        } else if (providerTypesEnabled) {
            providers?.sections.orEmpty().forEach { section ->
                val entities = section.entities.filter { entity -> entity.allowedBy(filters) }
                val tracks =
                    section.tracks
                        .takeIf { filters.isEmpty() || MusicType.SONGS in filters }
                        .orEmpty()
                if (section.failure == null && entities.isEmpty() && tracks.isEmpty()) {
                    return@forEach
                }
                if (isNotEmpty()) add(PlainDivider(null))
                add(SearchTextHeader(section.provider.displayName))
                val failure = section.failure
                if (failure != null) {
                    add(
                        ProviderSearchFailureItem(
                            providerName = section.provider.displayName,
                            retryable = failure.retryable,
                        )
                    )
                } else {
                    ProviderEntityType.entries.forEach { type ->
                        val typedEntities = entities.filter { it.type == type }
                        if (typedEntities.isNotEmpty()) {
                            add(BasicHeader(type.headerLabel))
                            addAll(
                                typedEntities.map { entity ->
                                    ProviderEntityItem(section.provider.displayName, entity)
                                }
                            )
                        }
                    }
                    if (tracks.isNotEmpty()) {
                        add(BasicHeader(R.string.lbl_songs))
                        addAll(
                            tracks.map { track ->
                                ProviderTrackItem(section.provider.displayName, track)
                            }
                        )
                    }
                }
            }
        }
    }

    private fun ProviderEntity.allowedBy(filters: Set<MusicType>) =
        filters.isEmpty() ||
            when (type) {
                ProviderEntityType.ALBUM -> MusicType.ALBUMS in filters
                ProviderEntityType.ARTIST -> MusicType.ARTISTS in filters
                ProviderEntityType.PLAYLIST -> MusicType.PLAYLISTS in filters
            }

    private val ProviderEntityType.headerLabel
        get() =
            when (this) {
                ProviderEntityType.ALBUM -> R.string.lbl_albums
                ProviderEntityType.ARTIST -> R.string.lbl_artists
                ProviderEntityType.PLAYLIST -> R.string.lbl_playlists
            }

    private suspend fun searchImpl(library: Library, query: String): List<Item> {
        val filters = searchSettings.filters

        val items =
            if (!filters.isEmpty()) {
                SearchEngine.Items(
                    songs = if (MusicType.SONGS in filters) library.songs else null,
                    albums = if (MusicType.ALBUMS in filters) library.albums else null,
                    artists = if (MusicType.ARTISTS in filters) library.artists else null,
                    genres = if (MusicType.GENRES in filters) library.genres else null,
                    playlists = if (MusicType.PLAYLISTS in filters) library.playlists else null,
                )
            } else {
                SearchEngine.Items(
                    songs = library.songs,
                    albums = library.albums,
                    artists = library.artists,
                    genres = library.genres,
                    playlists = library.playlists,
                )
            }

        val results = searchEngine.search(items, query)

        return buildList {
            results.artists?.let {
                L.d("Adding ${it.size} artists to search results")
                val header = BasicHeader(R.string.lbl_artists)
                add(header)
                addAll(SORT.artists(it))
            }
            results.albums?.let {
                L.d("Adding ${it.size} albums to search results")
                val header = BasicHeader(R.string.lbl_albums)
                if (isNotEmpty()) {
                    add(PlainDivider(header))
                }

                add(header)
                addAll(SORT.albums(it))
            }
            results.playlists?.let {
                L.d("Adding ${it.size} playlists to search results")
                val header = BasicHeader(R.string.lbl_playlists)
                if (isNotEmpty()) {
                    add(PlainDivider(header))
                }

                add(header)
                addAll(SORT.playlists(it))
            }
            results.genres?.let {
                L.d("Adding ${it.size} genres to search results")
                val header = BasicHeader(R.string.lbl_genres)
                if (isNotEmpty()) {
                    add(PlainDivider(header))
                }

                add(header)
                addAll(SORT.genres(it))
            }
            results.songs?.let {
                L.d("Adding ${it.size} songs to search results")
                val header = BasicHeader(R.string.lbl_songs)
                if (isNotEmpty()) {
                    add(PlainDivider(header))
                }

                add(header)
                addAll(SORT.songs(it))
            }
        }
    }

    /** The current filters used for search. */
    val filters: Set<MusicType>
        get() = searchSettings.filters

    /**
     * Update the filters used by search. Will trigger a research.
     *
     * @param filters The new filters to use.
     */
    fun updateFilters(filters: Set<MusicType>) {
        searchSettings.filters = filters
        search(lastQuery)
    }

    private companion object {
        val SORT = Sort(Sort.Mode.ByName, Sort.Direction.ASCENDING)
    }
}

private fun List<RecentSearch>.asSearchItems(): List<Item> =
    if (isEmpty()) {
        emptyList()
    } else {
        buildList {
            add(BasicHeader(R.string.lbl_recent_searches))
            addAll(map(::RecentSearchItem))
        }
    }
