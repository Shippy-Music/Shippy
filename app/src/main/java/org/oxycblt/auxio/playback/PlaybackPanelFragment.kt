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
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentPlaybackPanelBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.queue.QueueViewModel
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.playback.ui.StyledSeekBar
import org.oxycblt.auxio.playback.ui.stepper.Direction
import org.oxycblt.auxio.playback.ui.stepper.StepperOverlay
import org.oxycblt.auxio.playback.ui.swiper.CarouselTransformer
import org.oxycblt.auxio.playback.ui.swiper.CoverPagerAdapter
import org.oxycblt.auxio.playback.ui.swiper.UserAwarePagerCallback
import org.oxycblt.auxio.shippy.lyrics.PlainLyrics
import org.oxycblt.auxio.shippy.lyrics.SyncedLyrics
import org.oxycblt.auxio.shippy.crew.reaction.ActiveCrewReaction
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewRuntime
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewRuntimeState
import org.oxycblt.auxio.shippy.crew.settings.CrewSettings
import org.oxycblt.auxio.shippy.playback.timer.SleepTimerController
import org.oxycblt.auxio.shippy.playback.timer.SleepTimerMode
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.download.DownloadJobId
import org.oxycblt.auxio.shippy.share.ProviderTrackSharing
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.dampen
import org.oxycblt.auxio.util.recycler
import org.oxycblt.auxio.util.showToast
import org.oxycblt.auxio.util.smoothScrollByPageTo
import org.oxycblt.auxio.util.systemBarInsetsCompat
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
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
    @Inject lateinit var sleepTimerController: SleepTimerController
    @Inject lateinit var activeCrewRuntime: ActiveCrewRuntime
    @Inject lateinit var crewSettings: CrewSettings
    private val coverPagerAdapter = CoverPagerAdapter(this)
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    private val listModel: ListViewModel by activityViewModels()
    private val queueModel: QueueViewModel by viewModels()
    private val playerActionsModel: PlayerActionsViewModel by viewModels()
    private var equalizerLauncher: ActivityResultLauncher<Intent>? = null
    private var userAwarePagerCallback: UserAwarePagerCallback? = null
    private var currentPagerPosition = 0
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

        // AudioEffect expects you to use startActivityForResult with the panel intent. There is no
        // contract analogue for this intent, so the generic contract is used instead.
        equalizerLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                // Nothing to do
            }

        // --- UI SETUP ---
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.systemBarInsetsCompat
            view.updatePadding(bottom = bars.bottom)
            insets
        }

        binding.playbackToolbar.apply {
            setNavigationOnClickListener { playbackModel.openMain() }
            setOnMenuItemClickListener(this@PlaybackPanelFragment)
            menu.findItem(R.id.action_open_lyrics)?.isVisible =
                binding.playbackLyricsContainer == null
        }

        binding.playbackPager?.apply {
            adapter = coverPagerAdapter
            userAwarePagerCallback =
                UserAwarePagerCallback(this) {
                        // Posting the queue goto command prevents the seekbar pos from desyncing
                        // from the song's duration, which creates a visual flicker in the seekbar.
                        post { queueModel.goto(it) }
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
        binding.playbackShuffle.setOnClickListener { playbackModel.toggleShuffled() }
        binding.playbackSave.setOnClickListener {
            val state = playerActionsModel.state.value
            if (state.liked || state.playlistIds.isNotEmpty()) {
                showSavedDestinations(state)
            } else {
                playerActionsModel.toggleLiked()
            }
        }
        binding.playbackDownload.setOnClickListener { playerActionsModel.performDownloadAction() }
        binding.playbackQueue.setOnClickListener { playbackModel.openQueue() }
        binding.playbackMore?.setOnClickListener { anchor ->
            val displayItem = playbackModel.displayItem.value ?: return@setOnClickListener
            if (displayItem.localSong != null) {
                listModel.openMenu(
                    R.menu.playback_song,
                    displayItem.localSong,
                    PlaySong.ByItself,
                )
            } else {
                showProviderOverflow(anchor, displayItem.queueItem.track)
            }
        }

        // --- VIEWMODEL SETUP --
        collectImmediately(playbackModel.displayItem, ::updateItem)
        collectImmediately(playbackModel.parent, ::updateParent)
        collectImmediately(playbackModel.positionDs, ::updatePosition)
        collectImmediately(playbackModel.repeatMode, ::updateRepeat)
        collectImmediately(playbackModel.isPlaying, ::updatePlaying)
        collectImmediately(playbackModel.isShuffled, ::updateShuffled)
        collectImmediately(playbackModel.pagerQueue, ::updatePager)
        collectImmediately(playerActionsModel.state, ::updateActions)
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
        equalizerLauncher = null
        binding.playbackRepeat.clearPendingIcon()
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
        if (item.itemId == R.id.action_open_lyrics) {
            LyricsDialog.show(parentFragmentManager)
            return true
        }
        if (item.itemId == R.id.action_crew_react) {
            showReactionPicker()
            return true
        }
        if (item.itemId == R.id.action_sleep_timer) {
            showSleepTimerDialog()
            return true
        }
        if (item.itemId == R.id.action_open_equalizer) {
            // Launch the system equalizer app, if possible.
            L.d("Launching equalizer")
            val equalizerIntent =
                Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                    // Provide audio session ID so the equalizer can show options for this app
                    // in particular.
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, playbackModel.currentAudioSessionId)
                    // Signal music type so that the equalizer settings are appropriate for
                    // music playback.
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            try {
                requireNotNull(equalizerLauncher) { "Equalizer panel launcher was not available" }
                    .launch(equalizerIntent)
            } catch (e: ActivityNotFoundException) {
                requireContext().showToast(R.string.err_no_app)
            }
            return true
        }

        return false
    }

    private fun updateCrewActions(state: ActiveCrewRuntimeState) {
        requireBinding()
            .playbackToolbar
            .menu
            .findItem(R.id.action_crew_react)
            ?.isVisible = state is ActiveCrewRuntimeState.Active
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
            ).apply {
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
            binding.playbackSong.text = localSong.name.resolve(context)
            binding.playbackArtist.text = localSong.artists.resolveNames(context)
            binding.playbackAlbum?.text = localSong.album.name.resolve(context)
        } else {
            binding.playbackSong.text = track.title
            binding.playbackArtist.text = track.artists.joinToString(", ")
            binding.playbackAlbum?.text = track.album.orEmpty()
            binding.playbackToolbar.subtitle =
                track.album?.takeIf(String::isNotBlank) ?: getString(R.string.lbl_search)
        }
        binding.playbackMore?.isVisible = true
        binding.playbackSeekBar?.durationDs = (track.durationMs ?: 0L).msToDs()
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
        requireBinding().playbackShuffle.isChecked = isShuffled
    }

    private fun updateActions(state: PlayerActionsState) {
        val binding = requireBinding()
        binding.playbackSave.apply {
            val isSaved = state.liked || state.playlistIds.isNotEmpty()
            isVisible = state.track != null
            isEnabled = state.track != null
            setIconResource(if (isSaved) R.drawable.ic_check_24 else R.drawable.ic_add_24)
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
                    setIconResource(R.drawable.ic_down_24)
                    contentDescription = getString(R.string.desc_download)
                }
                is PlayerDownloadPresentation.Working -> {
                    setIconResource(R.drawable.ic_down_24)
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

    private fun updateLyrics(state: PlaybackLyricsState, positionDs: Long) {
        val binding = requireBinding()
        val container = binding.playbackLyricsContainer ?: return
        val title = binding.playbackLyricsTitle ?: return
        val current = binding.playbackLyricsCurrent ?: return
        val body = binding.playbackLyricsBody ?: return
        val retry = binding.playbackLyricsRetry ?: return
        container.isVisible = state !is PlaybackLyricsState.None
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
                            body.text = lyrics.plainText
                            body.contentDescription = lyrics.plainText
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

    private fun showProviderOverflow(anchor: View, track: Track) {
        val availableJobId =
            (playerActionsModel.state.value.download as? PlayerDownloadPresentation.Available)
                ?.jobId
        PopupMenu(requireContext(), anchor).apply {
            inflate(R.menu.playback_provider)
            val originalLink = ProviderTrackSharing.originalLink(track)
            val shippyLink = ProviderTrackSharing.shippyLink(track)
            menu.findItem(R.id.action_share_original_link).isVisible = originalLink != null
            menu.findItem(R.id.action_share_with_shippy).isVisible = shippyLink != null
            menu.findItem(R.id.action_remove_download).isVisible = availableJobId != null
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_open_queue -> {
                        playbackModel.openQueue()
                        true
                    }
                    R.id.action_provider_track_info -> {
                        showProviderTrackInfo(track)
                        true
                    }
                    R.id.action_share_original_link -> {
                        originalLink?.let(::shareProviderText)
                        true
                    }
                    R.id.action_share_with_shippy -> {
                        shippyLink?.let(::shareProviderText)
                        true
                    }
                    R.id.action_remove_download -> {
                        availableJobId?.let(::confirmRemoveDownload)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun showSleepTimerDialog() {
        val modes = SleepTimerMode.entries
        val labels = modes.map(::sleepTimerLabel).toTypedArray()
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

    private fun sleepTimerLabel(mode: SleepTimerMode): String =
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

    private fun showSavedDestinations(state: PlayerActionsState) {
        val labels =
            buildList {
                add(getString(R.string.lbl_liked))
                state.playlists.forEach { add(it.displayName) }
            }
        val checked =
            BooleanArray(labels.size) { index ->
                if (index == 0) {
                    state.liked
                } else {
                    state.playlists[index - 1].id in state.playlistIds
                }
            }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_saved_destinations)
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, selected ->
                checked[which] = selected
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                playerActionsModel.updateSavedDestinations(
                    liked = checked.first(),
                    playlistIds =
                        state.playlists
                            .filterIndexed { index, _ -> checked[index + 1] }
                            .mapTo(mutableSetOf()) { it.id },
                )
            }
            .show()
    }

    private fun confirmRemoveDownload(jobId: DownloadJobId) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_remove_download)
            .setMessage(R.string.lng_remove_download_confirmation)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.lbl_remove_download) { _, _ ->
                playerActionsModel.removeDownload(jobId)
            }
            .show()
    }

    private fun showProviderTrackInfo(track: Track) {
        val sourceNames =
            track.candidates
                .mapNotNull { candidate -> candidate.providerId?.value ?: candidate.sourceId }
                .distinct()
                .joinToString(", ")
        val details =
            buildList {
                    add(track.artists.joinToString(", "))
                    track.album?.takeIf(String::isNotBlank)?.let(::add)
                    if (sourceNames.isNotBlank()) {
                        add(getString(R.string.fmt_provider_sources, sourceNames))
                    }
                }
                .joinToString("\n")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(track.title)
            .setMessage(details)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun shareProviderText(text: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text),
                getString(R.string.lbl_share),
            )
        )
    }

    private fun updatePager(queue: PagerQueue) {
        // Right now there's easily 140ms of frame skipping when going next/prev. This is primarily
        // the fault of specifically the nested bottom sheet UI setup, which is intractable to
        // optimize. If I don't do multiple remeasures/relayouts on every slightest state
        // instability
        // I will suddenly encounter insane issues where the sheet fails to measure, appears below
        // the sidebar, flies away, not changing with ui scale, etc, often only on third-party OEM
        // ROMs that randomly mangle  SDK APIs and the SystemUI chrome for no reason.
        //
        // Historically this was not an issue, as I did not animate next/prev. Now I do, and it's
        // highly noticeable. So at least for plain next/prev I have to hack around it, do not
        // execute any transition until the state has fully adjudicated and laid out the UI. It's
        // not effective for swiping but there's nothing I can do there.
        //
        // Eventually one day Claude Fable 6.7 will probably be able to figure out that you need to
        // reflect into System.FoobaCrumbo::beegieConnector(GoolaUtils.PlubBud) and call it
        // specifically with 0x189B31FA alongside disabling the AndroidX Helpo SuperCharge by
        // manually clobbering `BottomSheetM2InternalBoogieCompat::scrimbloManager` to null for it
        // to not actually randomly mangle the sheets and do it in 1 clean layout, but for now I
        // must do this to keep my sanity.
        //
        // Actual snippet here was codex, just cleaned & adapted it / cognitive ownership
        requireBinding().playbackPager.apply {
            if (!isAttachedToWindow) {
                post { updatePagerImpl(queue) }
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isHardwareAccelerated) {
                // New version using post-Q frame hooks
                viewTreeObserver.registerFrameCommitCallback {
                    post { postOnAnimation { updatePagerImpl(queue) } }
                }
                postInvalidateOnAnimation()
            } else {
                // Let current layout happen, then wait for the next to conclude
                postOnAnimation { postOnAnimation { updatePagerImpl(queue) } }
            }
        }
    }

    private fun updatePagerImpl(queue: PagerQueue) {
        // Android insanity means this may be executed after view destruction
        // but only on some devices.
        val binding = binding ?: return

        val command = playbackModel.pagerCommand.consume()
        if (command == null) {
            // This probably shouldn't happen in practice, as QueueViewModel directly
            // attaches to PlaybackStateManager and will basically always initialize
            // with a command as a result.
            //
            // If it does happen we should just make sure the UI state is aligned. Don't
            // want broken UI.
            coverPagerAdapter.update(queue.queue, null)
            binding.playbackPager.setCurrentItem(queue.index, false)
            return
        }

        if (command.update != null) {
            // queue needs to be updated.
            coverPagerAdapter.update(queue.queue, command.update)
        }

        if (command.scroll != null) {
            // we need to scroll, however the smooth scroll only really looks best
            // when we are only doing next/prev due to various factors. better to
            // just not animate on outright gotos or queue updates
            val delta = binding.playbackPager.currentItem - command.scroll
            if (delta == 0) {
                // user scroll, carry on
                return
            }
            if (command.update == null && abs(delta) == 1) {
                binding.playbackPager.smoothScrollByPageTo(command.scroll)
            } else {
                binding.playbackPager.setCurrentItem(command.scroll, false)
            }
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

    private companion object {}
}

internal fun activeLyricIndex(
    lyrics: SyncedLyrics,
    positionMs: Long,
): Int = lyrics.lines.indexOfLast { it.startMs <= positionMs }
