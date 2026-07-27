/*
 * Copyright (c) 2026 Auxio Project
 * ShippyCollectionHeaderAdapter.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.library.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemShippyCollectionHeaderBinding

/** The single artwork-led header shared by permanent collections and user playlists. */
internal class ShippyCollectionHeaderAdapter(
    private val onPlay: () -> Unit,
    private val onShuffle: () -> Unit,
    private val onDownload: () -> Unit,
) : RecyclerView.Adapter<ShippyCollectionHeaderAdapter.ViewHolder>() {
    private var model: ShippyCollectionHeaderModel? = null

    override fun getItemCount() = if (model == null) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(
            ItemShippyCollectionHeaderBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            ),
            onPlay,
            onShuffle,
            onDownload,
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(requireNotNull(model))

    fun update(model: ShippyCollectionHeaderModel) {
        val wasEmpty = this.model == null
        this.model = model
        if (wasEmpty) notifyItemInserted(0) else notifyItemChanged(0)
    }

    internal class ViewHolder(
        private val binding: ItemShippyCollectionHeaderBinding,
        onPlay: () -> Unit,
        onShuffle: () -> Unit,
        onDownload: () -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.collectionPlay.setOnClickListener { onPlay() }
            binding.collectionShuffle.setOnClickListener { onShuffle() }
            binding.collectionDownload.setOnClickListener { onDownload() }
        }

        fun bind(model: ShippyCollectionHeaderModel) {
            binding.collectionTitle.text = model.title
            binding.collectionSubtitle.text = model.subtitle
            binding.collectionMetadata.apply {
                text = model.metadata
                isGone = model.metadata.isBlank()
            }
            binding.collectionMessage.apply {
                text = model.message
                isVisible = !model.message.isNullOrBlank()
            }

            val collage = model.collageArtwork.distinct().take(4)
            binding.collectionArtworkCollage.isVisible = collage.size >= 2
            binding.collectionArtworkPrimary.isVisible = collage.size < 2
            if (collage.size >= 2) {
                val tiles =
                    listOf(
                        binding.collectionArtworkOne,
                        binding.collectionArtworkTwo,
                        binding.collectionArtworkThree,
                        binding.collectionArtworkFour,
                    )
                tiles.forEachIndexed { index, cover ->
                    cover.bindArtwork(collage[index % collage.size], model.title)
                }
            } else {
                binding.collectionArtworkPrimary.bindArtwork(
                    collage.firstOrNull() ?: model.primaryArtwork ?: R.drawable.ic_playlist_48,
                    model.title,
                )
            }

            val controlsEnabled = model.canPlay && !model.playbackStarting
            binding.collectionPlay.isEnabled = controlsEnabled
            binding.collectionShuffle.isEnabled = controlsEnabled
            binding.collectionPlay.alpha = if (controlsEnabled) 1f else 0.5f
            binding.collectionShuffle.alpha = if (controlsEnabled) 1f else 0.5f

            binding.collectionDownloadContainer.isGone =
                model.download == CollectionHeaderDownloadPresentation.Hidden
            binding.collectionDownloadProgress.isVisible =
                model.download == CollectionHeaderDownloadPresentation.Working
            binding.collectionDownload.isInvisible =
                model.download == CollectionHeaderDownloadPresentation.Working
            binding.collectionDownload.apply {
                when (model.download) {
                    CollectionHeaderDownloadPresentation.Hidden -> Unit
                    CollectionHeaderDownloadPresentation.Ready -> {
                        isEnabled = true
                        setIconResource(R.drawable.ic_download_24)
                        contentDescription = context.getString(R.string.desc_download_collection)
                    }
                    CollectionHeaderDownloadPresentation.Working -> {
                        isEnabled = false
                        contentDescription =
                            context.getString(R.string.desc_collection_download_working)
                    }
                    CollectionHeaderDownloadPresentation.Complete -> {
                        isEnabled = false
                        setIconResource(R.drawable.ic_check_24)
                        contentDescription =
                            context.getString(R.string.desc_collection_download_complete)
                    }
                }
            }
        }
    }
}

internal data class ShippyCollectionHeaderModel(
    val title: String,
    val subtitle: String,
    val metadata: String,
    val primaryArtwork: Any?,
    val collageArtwork: List<String>,
    val message: CharSequence?,
    val canPlay: Boolean,
    val playbackStarting: Boolean,
    val download: CollectionHeaderDownloadPresentation,
)

internal enum class CollectionHeaderDownloadPresentation {
    Hidden,
    Ready,
    Working,
    Complete,
}
