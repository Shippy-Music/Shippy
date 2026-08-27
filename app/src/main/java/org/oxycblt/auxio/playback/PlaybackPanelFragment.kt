/*
 * Copyright (c) 2021 Auxio Project
 * PlaybackPanelFragment.kt is part of Auxio.
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

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.dynamicanimation.animation.SpringForce
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.R as MR
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentPlaybackPanelBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.playback.ui.StyledSeekBar
import org.oxycblt.auxio.playback.ui.stepper.Direction
import org.oxycblt.auxio.playback.ui.stepper.StepperOverlay
import org.oxycblt.auxio.playback.ui.swiper.CarouselTransformer
import org.oxycblt.auxio.playback.ui.swiper.CoverPagerAdapter
import org.oxycblt.auxio.playback.ui.swiper.UserAwarePagerCallback
import org.oxycblt.auxio.shippy.crew.reaction.ActiveCrewReaction
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewRuntime
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewRuntimeState
import org.oxycblt.auxio.shippy.crew.settings.CrewSettings
import org.oxycblt.auxio.shippy.lastfm.LastFmScrobbleStatus
import org.oxycblt.auxio.shippy.lastfm.LastFmScrobbleStatusKind
import org.oxycblt.auxio.shippy.lastfm.LastFmScrobbleTracker
import org.oxycblt.auxio.shippy.lyrics.PlainLyrics
import org.oxycblt.auxio.shippy.lyrics.SyncedLyrics
import org.oxycblt.auxio.shippy.playback.timer.SleepTimerController
import org.oxycblt.auxio.shippy.playback.timer.SleepTimerMode
import org.oxycblt.auxio.shippy.provider.ui.ProviderTrackActionsSheet
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.dampen
import org.oxycblt.auxio.util.getAttrColorCompat
import org.oxycblt.auxio.util.recycler
import org.oxycblt.auxio.util.smoothScrollByPageTo
import org.oxycblt.auxio.util.systemBarInsetsCompat
import org.oxycblt.musikr.MusicParent
import timber.log.Timber as L

/**
 * A [ViewBindingFragment] more information about the currently playing song, alongside all
 * available controls.
 *
 * @author Alexander Capehart (OxygenCobalt)
 *
 * TODO: Improve flickering situation on play button
 */
@AndroidEntryPoint
class PlaybackPanelFragment :
    ViewBindingFragment<FragmentPlaybackPanelBinding>(),
    Toolbar.OnMenuItemClickListener,
    StyledSeekBar.Listener,
    StepperOverlay.Listener {
    private val artworkToneExtractor = ArtworkToneExtractor()
    private var toneSource: android.graphics.drawable.Drawable? = null
    private var toneApplied = false
    @Inject lateinit var activeCrewRuntime: ActiveCrewRuntime
    @Inject lateinit var crewSettings: CrewSettings
    @Inject lateinit var sleepTimerController: SleepTimerController
    @Inject lateinit var lastFmScrobbleTracker: LastFmScrobbleTracker
    private val coverPagerAdapter = CoverPagerAdapter(this)
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    private val playerActionsModel: PlayerActionsViewModel by viewModels()
    private var userAwarePagerCallback: UserAwarePagerCallback? = null
    private var pagerUpdateGeneration = 0
    private var playbackPresentationGeneration = 0
    private var renderedLyricsState: PlaybackLyricsState = PlaybackLyricsState.None
    private var renderedLyricsLineIndex = Int.MIN_VALUE
    private val reactionViews = mutableSetOf<View>()
    private var peerMediaDialog: androidx.appcompat.app.AlertDialog? = null
    private var promptedForCurrentPeerBlock = false

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentPlaybackPanelBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentPlaybackPanelBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)

        // --- UI SETUP ---
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.systemBarInsetsCompat
            view.updatePadding(bottom = bars.bottom)
            insets
        }
        binding.root.onCollapseGesture = {
            // Preserve normal lyrics scrolling: collapse once the full-player scroll is at its top.
            if (binding.playbackScroll?.canScrollVertically(-1) != true) {
                playbackModel.openMain()
            }
        }

        binding.playbackToolbar.apply {
            setNavigationOnClickListener { playbackModel.openMain() }
            setOnMenuItemClickListener(this@PlaybackPanelFragment)
        }
        binding.playbackLyricsContainer?.apply {
            setOnClickListener { LyricsDialog.show(parentFragmentManager) }
            contentDescription = getString(R.string.desc_open_lyrics)
        }
        binding.playbackLyricsOpen?.setOnClickListener { LyricsDialog.show(parentFragmentManager) }
        binding.playbackLyricsHint?.setOnClickListener {
            binding.playbackScroll?.post {
                binding.playbackScroll?.smoothScrollTo(0, binding.playbackLyricsContainer?.top ?: 0)
            }
        }

        binding.playbackPager?.apply {
            adapter = coverPagerAdapter
            userAwarePagerCallback =
                UserAwarePagerCallback(this) {
                        // Resolve the page from this pager's own stable item identity. The queue
                        // sheet has an independently mapped list that can briefly lag behind.
                        val itemId =
                            playbackModel.pagerQueue.value.queue.getOrNull(it)?.queueItem?.id
                        if (itemId != null) post { playbackModel.goto(itemId) }
                    }
                    .also { it.attach() }
            setPageTransformer(CarouselTransformer())
            recycler().apply {
                // Make it possible to collapse the bottom sheet from the ViewPager's touch area.
                isNestedScrollingEnabled = false
                // Visual effect consistency
                // TODO: Custom overscroll?
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            // Make it easier to collapse the bottom sheet
            dampen()
            offscreenPageLimit = 1
        }

        // Set up fast seek overlay
        binding.playbackSong.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentSong() }
        }
        binding.playbackArtist.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentArtist() }
        }
        binding.playbackAlbum?.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentAlbum() }
        }

        binding.playbackSeekBar?.listener = this

        // Set up actions
        // TODO: Add better playback button accessibility
        binding.playbackRepeat.setOnClickListener { playbackModel.toggleRepeatMode() }
        binding.playbackSkipPrev.setOnClickListener { playbackModel.prev() }
        binding.playbackPlayPause.apply {
            @SuppressLint("RestrictedApi")
            setCornerSpringForce(
                SpringForce().apply {
                    stiffness = 700f
                    dampingRatio = 0.9f
                }
            )
            setOnClickListener { playbackModel.togglePlaying() }
        }
        binding.playbackSkipNext.setOnClickListener { playbackModel.next() }
        binding.playbackShuffle.setOnClickListener {
            val shuffle = binding.playbackShuffle
            val target = !shuffle.isChecked
            shuffle.isChecked = target
            shuffle.setIconResource(
                if (target) R.drawable.ic_shuffle_on_24 else R.drawable.ic_shuffle_off_24
            )
            // Commit playback state at the interaction boundary. The button group may continue
            // its visual spring independently, but lifecycle changes must never discard intent.
            playbackModel.setShuffled(target)
        }
        binding.playbackSave.setOnClickListener {
            val state = playerActionsModel.state.value
            if (state.liked || state.playlistIds.isNotEmpty()) {
                showSavedDestinations(state)
            } else {
                playerActionsModel.toggleLiked()
            }
        }
        binding.playbackDownload.setOnClickListener { playerActionsModel.performDownloadAction() }
        binding.playbackSleepTimer?.setOnClickListener { showSleepTimerDialog() }
        binding.playbackQueue.setOnClickListener { playbackModel.openQueue() }

        parentFragmentManager.setFragmentResultListener(
            SavedDestinationsSheet.RESULT,
            viewLifecycleOwner,
        ) { _, result ->
            val expectedId = result.getString("queue_item_id") ?: return@setFragmentResultListener
            if (playbackModel.displayItem.value?.queueItem?.id?.value != expectedId)
                return@setFragmentResultListener
            playerActionsModel.updateSavedDestinations(
                liked = result.getBoolean(SavedDestinationsSheet.KEY_LIKED),
                playlistIds =
                    result.getStringArray(SavedDestinationsSheet.KEY_PLAYLIST_IDS).orEmpty().mapTo(
                        mutableSetOf()
                    ) {
                        org.oxycblt.auxio.shippy.domain.LibraryCollectionId(it)
                    },
            )
        }

        // --- VIEWMODEL SETUP --
        collectImmediately(
            playbackModel.displayItem,
            playbackModel.pagerQueue,
            ::updatePlaybackPresentation,
        )
        collectImmediately(playbackModel.parent, ::updateParent)
        collectImmediately(playbackModel.positionDs, ::updatePosition)
        collectImmediately(playbackModel.repeatMode, ::updateRepeat)
        collectImmediately(playbackModel.isPlaying, ::updatePlaying)
        collectImmediately(playbackModel.isShuffled, ::updateShuffled)
        collectImmediately(playerActionsModel.state, ::updateActions)
        collectImmediately(lastFmScrobbleTracker.status, ::updateScrobbleStatus)
        collectImmediately(playbackModel.lyrics, playbackModel.positionDs, ::updateLyrics)
        collectImmediately(activeCrewRuntime.state, ::updateCrewActions)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                activeCrewRuntime.reactions.collect(::showCrewReaction)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                activeCrewRuntime.peerMediaBlocked.collect(::updatePeerMediaBlocked)
            }
        }
    }

    /**
     * Starts every mini-player expansion at the primary player content instead of restoring a
     * previous lyrics scroll position.
     */
    fun resetScrollPosition() {
        binding?.playbackScroll?.apply {
            stopNestedScroll()
            scrollTo(0, 0)
        }
    }

    // FIXME: Old code!! Maybe not necessary anymore?
    //    override fun onStart() {
    //        super.onStart()
    //        playbackModel.song.value?.let { requireBinding().playbackCover.bind(it) }
    //        requireBinding().root.viewTreeObserver.addOnGlobalLayoutListener(this)
    //    }

    //    override fun onStop() {
    //        super.onStop()
    //        requireBinding().root.viewTreeObserver.removeOnGlobalLayoutListener(this)
    //    }

    //    override fun onGlobalLayout() {
    //        if (binding == null || lastCoverWidth < 0) {
    //            return
    //        }
    // Hacky workaround for cover radius not being preserved in between sizing changes
    // (i.e split screen or landscape mode)
    // For some reason ConstraintLayout does several passes on 1:1 elements that causes their
    // size to radically change, so we wait until it stabilizes and then force an image
    // reload if needed. Optimistically this is a no-op from coil caching, but when the cover
    // did accidentally load the wrong image (with weird corner radius intended for bigger
    // covers) we can force it to reload.
    // If this breaks, it's fine since we also started a load as we normally did w/state
    // updates, so the cover will not break.
    //        val binding = requireBinding()
    //        val coverWidth = binding.playbackCover.width
    //        if (lastCoverWidth != coverWidth) {
    //            lastCoverWidth = coverWidth
    //        } else {
    //            playbackModel.song.value?.let { binding.playbackCover.bind(it) }
    //            lastCoverWidth = -1
    //        }
    //    }

    override fun onDestroyBinding(binding: FragmentPlaybackPanelBinding) {
        binding.root.onCollapseGesture = null
        binding.playbackLyricsHint?.setOnClickListener(null)
        binding.playbackRepeat.clearPendingIcon()
        binding.playbackShuffle.clearPendingIcon()
        binding.playbackSong.isSelected = false
        binding.playbackArtist.isSelected = false
        binding.playbackAlbum?.isSelected = false
        binding.playbackToolbar.setOnMenuItemClickListener(null)
        userAwarePagerCallback?.release()
        binding.playbackPager?.adapter = null
        renderedLyricsState = PlaybackLyricsState.None
        renderedLyricsLineIndex = Int.MIN_VALUE
        reactionViews.toList().forEach { reaction ->
            reaction.animate().cancel()
            (reaction.parent as? ViewGroup)?.removeView(reaction)
        }
        reactionViews.clear()
        peerMediaDialog?.dismiss()
        peerMediaDialog = null
        promptedForCurrentPeerBlock = false
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_crew_react) {
            showReactionPicker()
            return true
        }
        if (item.itemId == R.id.action_player_more) {
            val track = playbackModel.displayItem.value?.queueItem?.track ?: return true
            ProviderTrackActionsSheet.show(parentFragmentManager, track, playerContext = true)
            return true
        }

        return false
    }

    private fun updateCrewActions(state: ActiveCrewRuntimeState) {
        requireBinding().playbackToolbar.menu.findItem(R.id.action_crew_react)?.isVisible =
            state is ActiveCrewRuntimeState.Active
    }

    private fun showReactionPicker() {
        val reactions = activeCrewRuntime.allowedReactions
        if (reactions.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ttl_react_to_crew)
            .setItems(reactions.toTypedArray()) { _, index ->
                viewLifecycleOwner.lifecycleScope.launch {
                    activeCrewRuntime.sendReaction(reactions[index])
                }
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }

    private fun updatePeerMediaBlocked(blocked: Boolean) {
        if (!blocked || crewSettings.pushPullEnabled) {
            promptedForCurrentPeerBlock = false
            peerMediaDialog?.dismiss()
            peerMediaDialog = null
            return
        }
        if (promptedForCurrentPeerBlock || peerMediaDialog != null) return
        promptedForCurrentPeerBlock = true
        peerMediaDialog =
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ttl_crew_media_available)
                .setMessage(R.string.msg_crew_media_available)
                .setPositiveButton(R.string.lbl_enable_push_pull) { _, _ ->
                    crewSettings.setPushPullEnabled(true)
                }
                .setNegativeButton(R.string.lbl_not_now, null)
                .show()
                .also { dialog ->
                    dialog.setOnDismissListener {
                        if (peerMediaDialog === dialog) peerMediaDialog = null
                    }
                }
    }

    private fun showCrewReaction(reaction: ActiveCrewReaction) {
        val root = requireBinding().root
        val density = resources.displayMetrics.density
        val reactionView =
            android.widget.TextView(requireContext()).apply {
                text = reaction.event.emoji
                textSize = 42f
                alpha = 0f
                scaleX = 0.82f
                scaleY = 0.82f
                translationX =
                    (Math.floorMod(reaction.event.id.value.hashCode(), 81) - 40) * density
            }
        root.addView(
            reactionView,
            ConstraintLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                .apply {
                    startToStart = ConstraintSet.PARENT_ID
                    endToEnd = ConstraintSet.PARENT_ID
                    bottomToBottom = ConstraintSet.PARENT_ID
                    bottomMargin = (112 * density).toInt()
                },
        )
        reactionViews += reactionView
        reactionView
            .animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(120L)
            .withEndAction {
                reactionView
                    .animate()
                    .translationY(-180 * density)
                    .alpha(0f)
                    .setDuration(1_300L)
                    .withEndAction {
                        root.removeView(reactionView)
                        reactionViews -= reactionView
                    }
                    .start()
            }
            .start()
    }

    override fun onSeekConfirmed(positionDs: Long) {
        playbackModel.seekTo(positionDs)
    }

    private fun updateItem(item: PlaybackDisplayItem?) {
        playerActionsModel.setDisplayItem(item)
        if (item == null) {
            // Nothing to do.
            return
        }

        val binding = requireBinding()
        val context = requireContext()
        val track = item.queueItem.track
        val localSong = item.localSong
        L.d("Updating playback display: ${item.queueItem.id}")
        if (localSong != null) {
            binding.playbackInfoCover?.bind(localSong)
            binding.playbackSong.text = localSong.name.resolve(context)
            binding.playbackArtist.text = localSong.artists.resolveNames(context)
            binding.playbackAlbum?.text = localSong.album.name.resolve(context)
            binding.playbackToolbar.subtitle =
                playbackModel.parent.value?.name?.resolve(context)
                    ?: context.getString(R.string.lbl_all_songs)
        } else {
            binding.playbackInfoCover?.bindArtwork(track.artwork, track.album ?: track.title)
            binding.playbackSong.text = track.title
            binding.playbackArtist.text = track.artists.joinToString(", ")
            binding.playbackAlbum?.text = track.album.orEmpty()
            binding.playbackToolbar.subtitle =
                track.album?.takeIf(String::isNotBlank) ?: getString(R.string.lbl_search)
        }
        binding.playbackSeekBar?.durationDs = (track.durationMs ?: 0L).msToDs()
        toneSource = null
        toneApplied = false
    }

    private fun updateParent(parent: MusicParent?) {
        val displayItem = playbackModel.displayItem.value
        if (displayItem != null && displayItem.localSong == null) {
            return
        }
        val binding = requireBinding()
        val context = requireContext()
        binding.playbackToolbar.subtitle =
            parent?.run { name.resolve(context) } ?: context.getString(R.string.lbl_all_songs)
    }

    private fun updatePosition(positionDs: Long) {
        requireBinding().playbackSeekBar?.positionDs = positionDs
    }

    private fun updateRepeat(repeatMode: RepeatMode) {
        val repeatButton = requireBinding().playbackRepeat
        repeatButton.isChecked = repeatMode != RepeatMode.NONE
        repeatButton.setIconResource(repeatMode.icon)
    }

    private fun updatePlaying(isPlaying: Boolean) {
        requireBinding().playbackPlayPause.isChecked = isPlaying
        requireBinding().playbackSeekBar?.setWaveEnabled(isPlaying)
    }

    private fun updateShuffled(isShuffled: Boolean) {
        requireBinding().playbackShuffle.apply {
            isChecked = isShuffled
            setIconResource(
                if (isShuffled) R.drawable.ic_shuffle_on_24 else R.drawable.ic_shuffle_off_24
            )
        }
    }

    private fun updateActions(state: PlayerActionsState) {
        val binding = requireBinding()
        binding.playbackSave.apply {
            val isSaved = state.liked || state.playlistIds.isNotEmpty()
            isVisible = state.track != null
            isEnabled = state.track != null
            setIconResource(if (isSaved) R.drawable.ic_favorite_24 else R.drawable.ic_add_circle_24)
            contentDescription =
                getString(
                    if (isSaved) {
                        R.string.desc_edit_saved_destinations
                    } else {
                        R.string.desc_save_to_liked
                    }
                )
        }
        binding.playbackDownload.apply {
            val presentation = state.download
            isVisible = presentation !is PlayerDownloadPresentation.Hidden
            isEnabled =
                presentation !is PlayerDownloadPresentation.Working &&
                    presentation !is PlayerDownloadPresentation.Available
            when (presentation) {
                PlayerDownloadPresentation.Hidden -> Unit
                is PlayerDownloadPresentation.Ready -> {
                    setIconResource(R.drawable.ic_download_24)
                    contentDescription = getString(R.string.desc_download)
                }
                is PlayerDownloadPresentation.Working -> {
                    setIconResource(R.drawable.ic_download_24)
                    contentDescription = getString(R.string.desc_downloading)
                }
                is PlayerDownloadPresentation.Paused -> {
                    setIconResource(R.drawable.ic_play_24)
                    contentDescription = getString(R.string.desc_resume_download)
                }
                is PlayerDownloadPresentation.Retry -> {
                    setIconResource(R.drawable.ic_feature_request_24)
                    contentDescription = getString(R.string.desc_retry_download)
                }
                is PlayerDownloadPresentation.Available -> {
                    setIconResource(R.drawable.ic_check_24)
                    contentDescription = getString(R.string.desc_downloaded)
                }
            }
        }
    }

    private fun updateScrobbleStatus(status: LastFmScrobbleStatus) {
        val binding = requireBinding()
        val currentId = playbackModel.displayItem.value?.queueItem?.id
        val visible =
            status.queueItemId != null &&
                status.queueItemId == currentId &&
                (status.kind == LastFmScrobbleStatusKind.Retrying ||
                    status.kind == LastFmScrobbleStatusKind.ReauthRequired)
        binding.playbackScrobbleStatus.isVisible = visible
        if (!visible) return
        binding.playbackScrobbleStatus.text =
            status.message
                ?: getString(
                    when (status.kind) {
                        LastFmScrobbleStatusKind.Retrying -> R.string.lbl_lastfm_retrying
                        LastFmScrobbleStatusKind.ReauthRequired ->
                            R.string.lbl_lastfm_reauth_required
                        else -> return
                    }
                )
        val colorAttr =
            when (status.kind) {
                LastFmScrobbleStatusKind.Scrobbled -> androidx.appcompat.R.attr.colorPrimary
                LastFmScrobbleStatusKind.Retrying,
                LastFmScrobbleStatusKind.Ignored,
                LastFmScrobbleStatusKind.ReauthRequired -> MR.attr.colorOnErrorContainer
                else -> android.R.attr.textColorSecondary
            }
        binding.playbackScrobbleStatus.setTextColor(
            requireContext().getAttrColorCompat(colorAttr).defaultColor
        )
    }

    private fun updateLyrics(state: PlaybackLyricsState, positionDs: Long) {
        val binding = requireBinding()
        val container = binding.playbackLyricsContainer ?: return
        val title = binding.playbackLyricsTitle ?: return
        val current = binding.playbackLyricsCurrent ?: return
        val body = binding.playbackLyricsBody ?: return
        val retry = binding.playbackLyricsRetry ?: return
        val artwork = binding.playbackInfoCover?.loadedArtworkDrawable()
        if (!toneApplied || artwork !== toneSource) {
            toneSource = artwork
            toneApplied = true
            val surface =
                requireContext().getAttrColorCompat(MR.attr.colorSurfaceContainerHigh).defaultColor
            val onSurface = requireContext().getAttrColorCompat(MR.attr.colorOnSurface).defaultColor
            container.setCardBackgroundColor(
                artworkToneExtractor.mutedColor(artwork, surface, onSurface)
            )
        }
        container.isVisible = state !is PlaybackLyricsState.None
        binding.playbackLyricsHint?.isVisible = state !is PlaybackLyricsState.None
        val stateChanged = state != renderedLyricsState
        if (stateChanged) {
            renderedLyricsState = state
            renderedLyricsLineIndex = Int.MIN_VALUE
            retry.isVisible = false
            retry.setOnClickListener(null)
        }
        when (state) {
            PlaybackLyricsState.None -> Unit
            is PlaybackLyricsState.Loading -> {
                if (stateChanged) {
                    title.setText(R.string.lbl_lyrics)
                    current.text = ""
                    body.setText(R.string.lng_lyrics_loading)
                    body.contentDescription = body.text
                }
            }
            is PlaybackLyricsState.Ready -> {
                when (val lyrics = state.lyrics) {
                    is SyncedLyrics -> {
                        val positionMs = positionDs * 100
                        val activeIndex = activeLyricIndex(lyrics, positionMs)
                        if (stateChanged) {
                            title.setText(R.string.lbl_lyrics)
                        }
                        if (activeIndex != renderedLyricsLineIndex) {
                            renderedLyricsLineIndex = activeIndex
                            val activeLine = lyrics.lines.getOrNull(activeIndex)
                            current.text = activeLine?.text.orEmpty()
                            current.contentDescription =
                                activeLine
                                    ?.text
                                    ?.let { getString(R.string.desc_current_lyric, it) }
                                    .orEmpty()
                            val previewStart = (activeIndex + 1).coerceAtLeast(0)
                            body.text =
                                lyrics.lines.drop(previewStart).take(4).joinToString("\n") {
                                    it.text
                                }
                            body.contentDescription = lyrics.plainText
                        }
                    }
                    is PlainLyrics -> {
                        if (stateChanged) {
                            title.setText(R.string.lbl_lyrics)
                            current.text = ""
                            current.contentDescription = null
                            body.text =
                                lyrics.plainText.takeIf(String::isNotBlank)
                                    ?: getString(R.string.lng_lyrics_instrumental)
                            body.contentDescription = body.text
                        }
                    }
                }
            }
            is PlaybackLyricsState.Unavailable -> {
                if (stateChanged) {
                    title.setText(R.string.lbl_lyrics)
                    current.text = ""
                    body.setText(R.string.lng_lyrics_unavailable)
                    body.contentDescription = body.text
                }
            }
            is PlaybackLyricsState.Error -> {
                if (stateChanged) {
                    title.setText(R.string.lbl_lyrics)
                    current.text = ""
                    body.setText(R.string.lng_lyrics_error)
                    body.contentDescription = body.text
                    if (state.retryable) {
                        retry.isVisible = true
                        retry.setOnClickListener { playbackModel.retryLyrics() }
                    }
                }
            }
        }
    }

    private fun showSavedDestinations(state: PlayerActionsState) {
        val queueItemId = playbackModel.displayItem.value?.queueItem?.id?.value ?: return
        val labels = buildList {
            add(getString(R.string.lbl_liked))
            state.playlists.forEach { add(it.displayName) }
        }
        val checked =
            BooleanArray(labels.size) { index ->
                if (index == 0) state.liked else state.playlists[index - 1].id in state.playlistIds
            }
        SavedDestinationsSheet.show(
            parentFragmentManager,
            queueItemId,
            labels.toTypedArray(),
            checked,
            state.playlists.map { it.id.value }.toTypedArray(),
        )
    }

    private fun showSleepTimerDialog() {
        val modes = SleepTimerMode.entries
        val labels =
            modes
                .map { mode ->
                    getString(
                        when (mode) {
                            SleepTimerMode.OFF -> R.string.lbl_sleep_timer_off
                            SleepTimerMode.FINISH_CURRENT -> R.string.lbl_sleep_timer_finish_current
                            SleepTimerMode.MINUTES_15 -> R.string.lbl_sleep_timer_15_minutes
                            SleepTimerMode.MINUTES_30 -> R.string.lbl_sleep_timer_30_minutes
                            SleepTimerMode.MINUTES_45 -> R.string.lbl_sleep_timer_45_minutes
                            SleepTimerMode.MINUTES_60 -> R.string.lbl_sleep_timer_60_minutes
                        }
                    )
                }
                .toTypedArray()
        val selected = modes.indexOf(sleepTimerController.state.value.mode).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_sleep_timer)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                sleepTimerController.select(modes[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Keeps the text/actions and the large ViewPager cover on the same queue occurrence.
     *
     * The pager has its own short, bounded projection. It can arrive after the current item while a
     * transition is settling, so rendering them independently briefly paired the new title with the
     * old cover. Waiting until the projection names the same queue occurrence makes the visible
     * player one atomic presentation without widening the artwork window.
     */
    private fun updatePlaybackPresentation(item: PlaybackDisplayItem?, queue: PagerQueue) {
        val pagerItem = queue.queue.getOrNull(queue.index)
        if (item?.queueItem?.id != pagerItem?.queueItem?.id) return

        val generation = ++playbackPresentationGeneration
        updatePager(queue) { if (generation == playbackPresentationGeneration) updateItem(item) }
    }

    private fun updatePager(queue: PagerQueue, onCurrentItemApplied: () -> Unit) {
        val generation = ++pagerUpdateGeneration
        requireBinding().playbackPager.apply {
            // Let ViewPager finish its current adapter/layout work, but do not deliberately hold
            // a completed playback transition behind multiple frame callbacks.
            post { updatePagerImpl(queue, generation, onCurrentItemApplied) }
        }
    }

    private fun updatePagerImpl(
        queue: PagerQueue,
        generation: Int,
        onCurrentItemApplied: () -> Unit,
    ) {
        // Android insanity means this may be executed after view destruction
        // but only on some devices.
        val binding = binding ?: return
        if (generation != pagerUpdateGeneration) return

        val command = queue.command

        if (command.update != null) {
            // A diff may complete asynchronously. Align only after the adapter owns the same
            // canonical queue, and ignore it if a newer playback update arrived meanwhile.
            coverPagerAdapter.update(queue.queue, command.update) {
                finishPagerUpdate(queue, generation, onCurrentItemApplied)
            }
        } else {
            finishPagerUpdate(queue, generation, onCurrentItemApplied)
        }
    }

    private fun finishPagerUpdate(
        queue: PagerQueue,
        generation: Int,
        onCurrentItemApplied: () -> Unit,
    ) {
        val pager = binding?.playbackPager ?: return
        // Adapter notifications are applied by RecyclerView after the update callback returns.
        // Publish the matching title only after the corresponding cover page has had a layout pass.
        pager.post {
            if (generation != pagerUpdateGeneration) return@post
            alignPagerToCanonicalItem(queue, generation)
            pager.postOnAnimation {
                if (generation == pagerUpdateGeneration) onCurrentItemApplied()
            }
        }
    }

    private fun alignPagerToCanonicalItem(queue: PagerQueue, generation: Int) {
        val binding = binding ?: return
        if (generation != pagerUpdateGeneration || queue.queue.isEmpty()) return

        val targetId = queue.queue.getOrNull(queue.index)?.queueItem?.id ?: return
        val targetIndex = coverPagerAdapter.currentList.indexOfFirst { it.queueItem.id == targetId }
        if (targetIndex < 0) return

        val currentId =
            coverPagerAdapter.currentList
                .getOrNull(binding.playbackPager.currentItem)
                ?.queueItem
                ?.id
        if (currentId == targetId) return

        // Smooth animation is only safe for an adjacent index-only move. Queue mutations and
        // mapping updates snap silently to avoid showing an intermediate song.
        val delta = binding.playbackPager.currentItem - targetIndex
        if (queue.command.update == null && queue.command.scroll != null && abs(delta) == 1) {
            binding.playbackPager.smoothScrollByPageTo(targetIndex)
        } else {
            binding.playbackPager.setCurrentItem(targetIndex, false)
        }
    }

    private fun navigateToCurrentSong() {
        playbackModel.song.value?.let(detailModel::showAlbum)
    }

    private fun navigateToCurrentArtist() {
        playbackModel.song.value?.let(detailModel::showArtist)
    }

    private fun navigateToCurrentAlbum() {
        playbackModel.song.value?.let { detailModel.showAlbum(it.album) }
    }

    override fun seek(direction: Direction) {
        when (direction) {
            Direction.FORWARDS -> playbackModel.stepForward()
            Direction.BACKWARDS -> playbackModel.stepBackwards()
        }
    }
}

internal fun activeLyricIndex(lyrics: SyncedLyrics, positionMs: Long): Int =
    lyrics.lines.indexOfLast { it.startMs <= positionMs }
