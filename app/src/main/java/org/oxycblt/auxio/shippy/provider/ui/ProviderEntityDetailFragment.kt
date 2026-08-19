/*
 * Copyright (c) 2026 Auxio Project
 * ProviderEntityDetailFragment.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.provider.ui

import android.os.Bundle
import android.view.LayoutInflater
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentProviderEntityDetailBinding
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.library.ui.ShippyCollectionTrackAdapter
import org.oxycblt.auxio.shippy.persistence.library.SavedProviderEntity
import org.oxycblt.auxio.shippy.provider.ProviderEntity
import org.oxycblt.auxio.shippy.provider.ProviderEntityType
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.applyBottomContentInset
import org.oxycblt.auxio.util.collect
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.showToast

/** Auxio-native provider album/artist/playlist detail with one canonical queue action path. */
@AndroidEntryPoint
class ProviderEntityDetailFragment : ViewBindingFragment<FragmentProviderEntityDetailBinding>() {
    private val model: ProviderEntityDetailViewModel by viewModels()
    private lateinit var entity: ProviderEntity
    private lateinit var tracksAdapter: ShippyCollectionTrackAdapter
    private var currentState: ProviderEntityDetailState.Content? = null

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentProviderEntityDetailBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentProviderEntityDetailBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)
        binding.providerEntityScroll.applyBottomContentInset()
        entity = requireArguments().toProviderEntity()
        tracksAdapter =
            ShippyCollectionTrackAdapter(
                onClick = { row ->
                    val index = currentState?.rows?.indexOf(row) ?: -1
                    if (index >= 0) model.play(index, shuffled = false)
                },
                onMenu = { row ->
                    ProviderTrackActionsSheet.show(parentFragmentManager, row.track)
                },
                onLongClick = { row ->
                    ProviderTrackActionsSheet.show(parentFragmentManager, row.track)
                },
            )

        binding.providerEntityToolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        binding.providerEntityToolbar.inflateMenu(R.menu.provider_entity_detail)
        binding.providerEntityToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_play_next -> {
                    model.playNext()
                    true
                }
                R.id.action_queue_add -> {
                    model.addToQueue()
                    true
                }
                R.id.action_shippy_pin -> {
                    model.togglePinned()
                    true
                }
                else -> false
            }
        }
        binding.providerEntityTracks.adapter = tracksAdapter
        binding.providerEntitySave.setOnClickListener { model.toggleSaved() }
        binding.providerEntityPlay.setOnClickListener { model.play(0, shuffled = false) }
        binding.providerEntityShuffle.setOnClickListener { model.play(0, shuffled = true) }
        binding.providerEntityRetry.setOnClickListener { model.retry() }
        binding.providerEntityLoadMore.setOnClickListener { model.loadMore() }

        collectImmediately(model.state, ::render)
        collect(model.playbackFailure.flow) { failure ->
            if (failure != null) {
                requireContext().showToast(R.string.msg_provider_playback_unavailable)
                model.playbackFailure.consume()
            }
        }
        collect(model.queueActionCompleted.flow) { action ->
            if (action != null) {
                requireContext()
                    .showToast(
                        when (action) {
                            ProviderCollectionQueueAction.PLAY_NEXT -> R.string.lng_play_next
                            ProviderCollectionQueueAction.ADD_TO_QUEUE -> R.string.lng_queue_added
                        }
                    )
                model.queueActionCompleted.consume()
            }
        }
        collectImmediately(model.savedEntity, ::renderSavedEntity)
        model.load(entity)
    }

    override fun onDestroyBinding(binding: FragmentProviderEntityDetailBinding) {
        binding.providerEntityToolbar.setOnMenuItemClickListener(null)
        binding.providerEntityTracks.adapter = null
        super.onDestroyBinding(binding)
    }

    private fun render(state: ProviderEntityDetailState?) {
        val binding = requireBinding()
        val displayEntity = state?.entity ?: entity
        binding.providerEntityToolbar.title = displayEntity.title
        binding.providerEntityArtwork.bindArtwork(displayEntity.artwork, displayEntity.title)
        binding.providerEntityTitle.text = displayEntity.title
        binding.providerEntitySubtitle.text = displayEntity.subtitle.orEmpty()
        binding.providerEntitySubtitle.isGone = displayEntity.subtitle.isNullOrBlank()

        binding.providerEntityProgress.isVisible =
            state is ProviderEntityDetailState.Loading ||
                (state as? ProviderEntityDetailState.Content)?.playbackStarting == true
        binding.providerEntityRetry.isVisible =
            state is ProviderEntityDetailState.Error && state.retryable
        val message =
            when {
                state is ProviderEntityDetailState.Error ->
                    R.string.lng_provider_catalog_unavailable
                state is ProviderEntityDetailState.Content && state.page.tracks.isEmpty() ->
                    R.string.lng_collection_empty
                else -> null
            }
        binding.providerEntityMessage.isVisible = message != null
        message?.let(binding.providerEntityMessage::setText)

        val content = state as? ProviderEntityDetailState.Content
        val page = content?.page
        currentState = content
        tracksAdapter.submitList(content?.rows.orEmpty())
        val hasTracks = !page?.tracks.isNullOrEmpty()
        binding.providerEntityPlay.isEnabled = hasTracks && content?.playbackStarting != true
        binding.providerEntityShuffle.isEnabled = hasTracks && content?.playbackStarting != true
        binding.providerEntityCount.isVisible = state is ProviderEntityDetailState.Content
        binding.providerEntityCount.text =
            resources.getQuantityString(
                R.plurals.fmt_song_count,
                page?.tracks?.size ?: 0,
                page?.tracks?.size ?: 0,
            )
        binding.providerEntityLoadMore.isVisible = page?.continuation != null
        binding.providerEntityLoadMore.isEnabled = content?.loadingMore == false
        binding.providerEntityLoadMore.setText(
            when {
                content?.loadingMore == true -> R.string.lbl_loading
                content?.pagingFailed == true -> R.string.lbl_retry
                else -> R.string.lbl_load_more
            }
        )
    }

    private fun renderSavedEntity(saved: SavedProviderEntity?) {
        val binding = requireBinding()
        binding.providerEntitySave.setIconResource(
            if (saved == null) R.drawable.ic_add_24 else R.drawable.ic_check_24
        )
        binding.providerEntitySave.contentDescription =
            getString(
                if (saved == null) {
                    R.string.desc_save_provider_entity
                } else {
                    R.string.desc_remove_provider_entity
                }
            )
        binding.providerEntityToolbar.menu.findItem(R.id.action_shippy_pin)?.apply {
            isVisible = saved != null
            title = getString(if (saved?.isPinned == true) R.string.lbl_unpin else R.string.lbl_pin)
        }
    }

    private fun Bundle.toProviderEntity() =
        ProviderEntity(
            providerId = ProviderId(requireNotNull(getString(ARG_PROVIDER_ID))),
            sourceItemId = requireNotNull(getString(ARG_SOURCE_ITEM_ID)),
            type = ProviderEntityType.valueOf(requireNotNull(getString(ARG_ENTITY_TYPE))),
            title = requireNotNull(getString(ARG_TITLE)),
            subtitle = getString(ARG_SUBTITLE),
            artwork = getString(ARG_ARTWORK),
            originalUrl = getString(ARG_ORIGINAL_URL),
        )

    companion object {
        const val ARG_PROVIDER_ID = "providerId"
        const val ARG_SOURCE_ITEM_ID = "sourceItemId"
        const val ARG_ENTITY_TYPE = "entityType"
        const val ARG_TITLE = "title"
        const val ARG_SUBTITLE = "subtitle"
        const val ARG_ARTWORK = "artwork"
        const val ARG_ORIGINAL_URL = "originalUrl"
    }
}
