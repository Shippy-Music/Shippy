/*
 * Copyright (c) 2026 Auxio Project
 * ProviderTrackActionsSheet.kt is part of Auxio.
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

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.view.SupportMenuInflater
import androidx.appcompat.view.menu.MenuBuilder
import androidx.core.view.children
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogMenuBinding
import org.oxycblt.auxio.list.ClickableListListener
import org.oxycblt.auxio.list.adapter.UpdateInstructions
import org.oxycblt.auxio.list.menu.MenuItemAdapter
import org.oxycblt.auxio.playback.LyricsDialog
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.LibraryCollection
import org.oxycblt.auxio.shippy.domain.LibraryCollectionId
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.download.DownloadRemovalResult
import org.oxycblt.auxio.shippy.download.DownloadState
import org.oxycblt.auxio.shippy.download.DownloadWorkCoordinator
import org.oxycblt.auxio.shippy.persistence.download.DownloadJobRepository
import org.oxycblt.auxio.shippy.persistence.library.LibraryRelationshipRepository
import org.oxycblt.auxio.shippy.playback.ShippyPlaybackController
import org.oxycblt.auxio.shippy.playback.timer.SleepTimerController
import org.oxycblt.auxio.shippy.playback.timer.SleepTimerMode
import org.oxycblt.auxio.shippy.provider.ProviderCapability
import org.oxycblt.auxio.shippy.provider.ProviderRegistry
import org.oxycblt.auxio.shippy.share.ProviderTrackSharing
import org.oxycblt.auxio.shippy.storage.LocalMediaDeletionCoordinator
import org.oxycblt.auxio.shippy.storage.LocalMediaDeletionResult
import org.oxycblt.auxio.ui.ViewBindingBottomSheetDialogFragment
import org.oxycblt.auxio.util.showToast

/** One consistent bottom-sheet action surface for provider tracks everywhere in Shippy. */
@AndroidEntryPoint
class ProviderTrackActionsSheet :
    ViewBindingBottomSheetDialogFragment<DialogMenuBinding>(), ClickableListListener<MenuItem> {
    @Inject lateinit var playback: ShippyPlaybackController
    @Inject lateinit var relationships: LibraryRelationshipRepository
    @Inject lateinit var downloads: DownloadJobRepository
    @Inject lateinit var downloadCoordinator: DownloadWorkCoordinator
    @Inject lateinit var providers: ProviderRegistry
    @Inject lateinit var sleepTimerController: SleepTimerController
    @Inject lateinit var localMediaDeletion: LocalMediaDeletionCoordinator

    private val localDeleteConsent =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                androidx.lifecycle
                    .ViewModelProvider(requireActivity())[
                        org.oxycblt.auxio.music.MusicViewModel::class.java]
                    .rescan()
                requireContext().showToast(R.string.msg_delete_local_complete)
                dismiss()
            } else {
                requireContext().showToast(R.string.msg_delete_local_failed)
            }
        }

    private val adapter = MenuItemAdapter(this)
    private lateinit var track: Track
    private var trackToken: String? = null
    private var playerContext = false
    private var liked = false
    private var playlists = emptyList<LibraryCollection.Playlist>()
    private var playlistIds = emptySet<LibraryCollectionId>()
    private var downloaded =
        null as org.oxycblt.auxio.shippy.persistence.download.PersistedDownload?

    override fun onCreateBinding(inflater: LayoutInflater) = DialogMenuBinding.inflate(inflater)

    override fun onBindingCreated(binding: DialogMenuBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        val token = requireArguments().getString(ARG_TRACK_TOKEN)
        val selectedTrack = token?.let(ProviderTrackActionRegistry::get)
        if (selectedTrack == null) {
            dismiss()
            return
        }
        trackToken = token
        track = selectedTrack
        playerContext = requireArguments().getBoolean(ARG_PLAYER_CONTEXT)
        binding.menuType.setText(R.string.lbl_song)
        binding.menuName.text = track.title
        binding.menuInfo.text = track.artists.joinToString(", ")
        binding.menuName.isSelected = true
        binding.menuInfo.isSelected = true
        binding.menuCover.bindArtwork(track.artwork, track.title)
        binding.menuOptionRecycler.apply {
            adapter = this@ProviderTrackActionsSheet.adapter
            itemAnimator = null
        }
        renderMenu()
        viewLifecycleOwner.lifecycleScope.launch {
            val relationship = relationships.observe(track.id).first()
            liked = relationship.liked
            playlistIds = relationship.playlistIds
            playlists = relationships.observeUserPlaylists().first()
            downloaded =
                downloads
                    .observeAll()
                    .first()
                    .filter { it.track.id == track.id }
                    .maxByOrNull { it.updatedAtEpochMs }
            renderMenu()
        }
    }

    override fun onDestroyBinding(binding: DialogMenuBinding) {
        binding.menuOptionRecycler.adapter = null
        trackToken?.let(ProviderTrackActionRegistry::remove)
        trackToken = null
        super.onDestroyBinding(binding)
    }

    @Suppress("RestrictedApi")
    private fun renderMenu() {
        if (!this::track.isInitialized || binding == null) return
        val menu = MenuBuilder(requireContext())
        SupportMenuInflater(requireContext()).inflate(R.menu.provider_track_actions, menu)
        menu.findItem(R.id.action_provider_save).apply {
            title =
                getString(
                    if (liked) R.string.lbl_remove_from_liked else R.string.desc_save_to_liked
                )
            setIcon(if (liked) R.drawable.ic_check_24 else R.drawable.ic_add_24)
        }
        menu.findItem(R.id.action_provider_playlists).isEnabled = playlists.isNotEmpty()
        val downloadable = downloadableCandidate(track) != null
        menu.findItem(R.id.action_provider_download).apply {
            val state = downloaded?.job?.state
            val active =
                state in
                    setOf(
                        DownloadState.REQUESTED,
                        DownloadState.RESOLVING,
                        DownloadState.QUEUED,
                        DownloadState.TRANSFERRING,
                        DownloadState.VERIFYING,
                        DownloadState.FINALIZING,
                    )
            isVisible = downloadable || state == DownloadState.AVAILABLE || active
            isEnabled = !active
            title =
                getString(
                    when {
                        state == DownloadState.AVAILABLE -> R.string.lbl_remove_download
                        active -> R.string.desc_downloading
                        else -> R.string.desc_download
                    }
                )
            setIcon(
                if (state == DownloadState.AVAILABLE) {
                    R.drawable.ic_delete_24
                } else {
                    R.drawable.ic_download_24
                }
            )
        }
        menu.findItem(R.id.action_delete_local_media).isVisible =
            track.candidates.any { it.kind == CandidateKind.LOCAL && it.locator != null }
        menu.findItem(R.id.action_share_original_link).isVisible =
            ProviderTrackSharing.originalLink(track) != null
        menu.findItem(R.id.action_share_with_shippy).isVisible =
            ProviderTrackSharing.shippyLink(track) != null
        menu.findItem(R.id.action_open_lyrics).isVisible = playerContext
        menu.findItem(R.id.action_sleep_timer).isVisible = playerContext
        menu.findItem(R.id.action_open_equalizer).isVisible = playerContext
        adapter.update(menu.children.filter(MenuItem::isVisible).toList(), UpdateInstructions.Diff)
    }

    override fun onClick(
        item: MenuItem,
        viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
    ) {
        val activity = requireActivity()
        when (item.itemId) {
            R.id.action_play -> activity.lifecycleScope.launch { playback.play(track, "actions") }
            R.id.action_play_next ->
                activity.lifecycleScope.launch {
                    playback.playNext(listOf(track), "actions")
                    activity.showToast(R.string.lng_play_next)
                }
            R.id.action_queue_add ->
                activity.lifecycleScope.launch {
                    playback.addToQueue(listOf(track), "actions")
                    activity.showToast(R.string.lng_queue_added)
                }
            R.id.action_provider_save ->
                activity.lifecycleScope.launch { relationships.setLiked(track, !liked) }
            R.id.action_provider_playlists -> showPlaylistMemberships()
            R.id.action_provider_download ->
                activity.lifecycleScope.launch {
                    val existing = downloaded
                    if (existing?.job?.state == DownloadState.AVAILABLE) {
                        if (
                            downloadCoordinator.remove(existing.job.id) !=
                                DownloadRemovalResult.Removed
                        ) {
                            activity.showToast(R.string.msg_download_remove_failed)
                        }
                    } else {
                        downloadableCandidate(track)?.let {
                            downloadCoordinator.request(track, it.id)
                        }
                    }
                }
            R.id.action_delete_local_media -> {
                showLocalDeleteConfirmation()
                return
            }
            R.id.action_open_queue ->
                activity.lifecycleScope.launch {
                    // Queue is already a player-owned surface; opening it through the active
                    // PlaybackViewModel keeps the bottom-sheet hierarchy correct.
                    androidx.lifecycle
                        .ViewModelProvider(activity)[
                            org.oxycblt.auxio.playback.PlaybackViewModel::class.java]
                        .openQueue()
                }
            R.id.action_open_lyrics -> LyricsDialog.show(parentFragmentManager)
            R.id.action_sleep_timer -> showSleepTimerDialog()
            R.id.action_open_equalizer -> openEqualizer()
            R.id.action_provider_track_info -> showTrackInfo()
            R.id.action_share_original_link ->
                ProviderTrackSharing.originalLink(track)?.let(::share)
            R.id.action_share_with_shippy -> ProviderTrackSharing.shippyLink(track)?.let(::share)
        }
        dismiss()
    }

    private fun showLocalDeleteConfirmation() {
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_delete_from_device)
            .setMessage(getString(R.string.msg_delete_local_confirm, track.title))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.lbl_delete) { _, _ ->
                when (val result = localMediaDeletion.delete(track)) {
                    LocalMediaDeletionResult.Deleted -> {
                        androidx.lifecycle
                            .ViewModelProvider(requireActivity())[
                                org.oxycblt.auxio.music.MusicViewModel::class.java]
                            .rescan()
                        requireContext().showToast(R.string.msg_delete_local_complete)
                        dismiss()
                    }
                    is LocalMediaDeletionResult.ConsentRequired ->
                        localDeleteConsent.launch(result.request)
                    LocalMediaDeletionResult.Failed,
                    LocalMediaDeletionResult.MissingLocalObject ->
                        requireContext().showToast(R.string.msg_delete_local_failed)
                }
            }
            .show()
    }

    private fun showPlaylistMemberships() {
        if (playlists.isEmpty()) return
        val hostActivity = requireActivity()
        val selected = playlists.map { it.id in playlistIds }.toBooleanArray()
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_edit_playlists)
            .setMultiChoiceItems(
                playlists.map(LibraryCollection.Playlist::displayName).toTypedArray(),
                selected,
            ) { _, index, checked ->
                selected[index] = checked
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selectedIds =
                    playlists
                        .filterIndexed { index, _ -> selected[index] }
                        .mapTo(mutableSetOf(), LibraryCollection.Playlist::id)
                hostActivity.lifecycleScope.launch {
                    relationships.replacePlaylistMemberships(track, selectedIds)
                }
            }
            .show()
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
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_sleep_timer)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                sleepTimerController.select(modes[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openEqualizer() {
        val playbackModel =
            androidx.lifecycle.ViewModelProvider(requireActivity())[PlaybackViewModel::class.java]
        val intent =
            Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, playbackModel.currentAudioSessionId)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            requireContext().showToast(R.string.err_no_app)
        }
    }

    private fun downloadableCandidate(track: Track) =
        track.candidates.firstOrNull {
            it.kind == CandidateKind.PROVIDER &&
                it.availability != CandidateAvailability.UNAVAILABLE &&
                it.providerId in downloadableProviderIds()
        }

    private fun downloadableProviderIds(): Set<ProviderId> =
        providers.supporting(ProviderCapability.DOWNLOAD).mapTo(mutableSetOf()) { it.descriptor.id }

    private fun showTrackInfo() {
        val sources =
            track.candidates.mapNotNull { it.providerId?.value }.distinct().joinToString(", ")
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(requireContext())
            .setTitle(track.title)
            .setMessage(
                buildList {
                        add(track.artists.joinToString(", "))
                        track.album?.takeIf(String::isNotBlank)?.let(::add)
                        sources.takeIf(String::isNotBlank)?.let(::add)
                    }
                    .joinToString("\n")
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun share(text: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                getString(R.string.lbl_share),
            )
        )
    }

    companion object {
        private const val TAG = "provider_track_actions"
        private const val ARG_TRACK_TOKEN = "track_token"
        private const val ARG_PLAYER_CONTEXT = "player_context"

        fun show(fragmentManager: FragmentManager, track: Track, playerContext: Boolean = false) {
            if (fragmentManager.isStateSaved || fragmentManager.findFragmentByTag(TAG) != null)
                return
            val token = ProviderTrackActionRegistry.put(track)
            ProviderTrackActionsSheet()
                .apply {
                    arguments =
                        Bundle().apply {
                            putString(ARG_TRACK_TOKEN, token)
                            putBoolean(ARG_PLAYER_CONTEXT, playerContext)
                        }
                }
                .show(fragmentManager, TAG)
        }
    }
}

/**
 * Process-local handoff for a transient action sheet.
 *
 * A Track can contain exact provider, download, Crew, or local candidates that the public Shippy
 * link codec deliberately strips. Keeping a tiny bounded handoff means every app surface opens the
 * same menu without weakening the share-link privacy contract. If Android recreates the process
 * while the sheet is open, the stale sheet simply dismisses.
 */
private object ProviderTrackActionRegistry {
    private const val MAX_PENDING_TRACKS = 8
    private val tracks = LinkedHashMap<String, Track>()

    @Synchronized
    fun put(track: Track): String {
        while (tracks.size >= MAX_PENDING_TRACKS) {
            tracks.remove(tracks.keys.first())
        }
        return UUID.randomUUID().toString().also { tracks[it] = track }
    }

    @Synchronized fun get(token: String): Track? = tracks[token]

    @Synchronized
    fun remove(token: String) {
        tracks.remove(token)
    }
}
