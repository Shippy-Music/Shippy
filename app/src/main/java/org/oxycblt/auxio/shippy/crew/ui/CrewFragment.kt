/*
 * Copyright (c) 2026 Auxio Project
 * CrewFragment.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentCrewBinding
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.shippy.crew.core.CrewAction
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewPlaybackMode
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteDecodeResult
import org.oxycblt.auxio.shippy.crew.lan.CrewLanSignalConnectFailure
import org.oxycblt.auxio.shippy.crew.reaction.ActiveCrewReaction
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewActivity
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewMode
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewPresentation
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewRuntimeFailure
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewRuntimeState
import org.oxycblt.auxio.shippy.crew.runtime.CrewConnectivityPresentation
import org.oxycblt.auxio.shippy.crew.runtime.CrewJoinFailure
import org.oxycblt.auxio.shippy.crew.runtime.CrewJoinReconnectState
import org.oxycblt.auxio.shippy.crew.runtime.CrewLanHostLaunchFailure
import org.oxycblt.auxio.shippy.crew.runtime.CrewLanJoinLaunchFailure
import org.oxycblt.auxio.shippy.crew.settings.CrewSettings
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.applyBottomContentInset
import org.oxycblt.auxio.util.showToast

@AndroidEntryPoint
class CrewFragment : ViewBindingFragment<FragmentCrewBinding>() {
    private val model: CrewViewModel by viewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    @Inject lateinit var crewSettings: CrewSettings
    private var qrDialog: androidx.appcompat.app.AlertDialog? = null
    private var reactionDialog: androidx.appcompat.app.AlertDialog? = null
    private val reactionViews = mutableSetOf<View>()
    private var pendingNetworkAction: PendingNetworkAction? = null
    private var pendingInviteLink: String? = null
    private val nearbyPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val action = pendingNetworkAction
            val invite = pendingInviteLink
            pendingNetworkAction = null
            pendingInviteLink = null
            if (grants.any { !it.value } && isAdded) {
                if (isAdded)
                    requireContext().showToast(R.string.lng_crew_nearby_permission_required)
            }
            // LAN sockets or a configured relay may still work when proximity permissions are
            // denied, so do not turn the permission choice into an artificial hard failure.
            when (action) {
                PendingNetworkAction.START_HOST -> model.startHost()
                PendingNetworkAction.JOIN -> invite?.let(model::join)
                null -> Unit
            }
        }

    override fun onCreateBinding(inflater: LayoutInflater) = FragmentCrewBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentCrewBinding, savedInstanceState: Bundle?) {
        binding.crewScroll.applyBottomContentInset()
        binding.crewStart.setOnClickListener {
            runCrewNetworkAction(PendingNetworkAction.START_HOST)
        }
        binding.crewJoin.setOnClickListener { showJoinDialog() }
        binding.crewScanQr.setOnClickListener { startQrScan() }
        binding.crewDismissFailure.setOnClickListener { model.dismissFailure() }
        binding.crewOpenQueue.setOnClickListener { playbackModel.openQueue() }
        binding.crewReact.setOnClickListener { showReactionPicker() }
        binding.crewEnablePushPull.setOnClickListener {
            crewSettings.setPushPullEnabled(true)
            renderPeerMediaBlocked(model.peerMediaBlocked.value)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect(::render)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.reactions.collect(::showCrewReaction)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.peerMediaBlocked.collect(::renderPeerMediaBlocked)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.activity.collect(::renderActivity)
            }
        }
    }

    override fun onDestroyBinding(binding: FragmentCrewBinding) {
        qrDialog?.dismiss()
        qrDialog = null
        reactionDialog?.dismiss()
        reactionDialog = null
        reactionViews.toList().forEach { reaction ->
            reaction.animate().cancel()
            (reaction.parent as? ViewGroup)?.removeView(reaction)
        }
        reactionViews.clear()
    }

    private fun showJoinDialog() {
        val input =
            EditText(requireContext()).apply {
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_URI or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                hint = getString(R.string.lng_join_crew)
                setSingleLine()
            }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ttl_join_crew)
            .setMessage(R.string.lng_join_crew)
            .setView(input)
            .setNegativeButton(R.string.lbl_cancel, null)
            .setPositiveButton(R.string.lbl_join) { _, _ ->
                runCrewNetworkAction(PendingNetworkAction.JOIN, input.text.toString().trim())
            }
            .show()
    }

    private fun render(state: ActiveCrewRuntimeState) {
        val binding = requireBinding()
        binding.crewEmpty.isVisible = state is ActiveCrewRuntimeState.Idle
        binding.crewProgress.isVisible = state is ActiveCrewRuntimeState.Starting
        binding.crewActive.isVisible =
            state is ActiveCrewRuntimeState.Active || state is ActiveCrewRuntimeState.Ending
        binding.crewFailure.isVisible = state is ActiveCrewRuntimeState.Failed
        if (state !is ActiveCrewRuntimeState.Active && state !is ActiveCrewRuntimeState.Ending) {
            // Do not retain a short-lived invitation in a hidden view listener after teardown.
            binding.crewShare.setOnClickListener(null)
            binding.crewShowQr.setOnClickListener(null)
            qrDialog?.dismiss()
            qrDialog = null
        }

        when (state) {
            ActiveCrewRuntimeState.Idle -> Unit
            is ActiveCrewRuntimeState.Starting -> {
                binding.crewProgressTitle.setText(
                    if (state.mode == ActiveCrewMode.HOST) {
                        R.string.lbl_starting_crew
                    } else {
                        R.string.lbl_joining_crew
                    }
                )
            }
            is ActiveCrewRuntimeState.Active -> renderActive(state.presentation, ending = false)
            is ActiveCrewRuntimeState.Ending -> renderActive(state.presentation, ending = true)
            is ActiveCrewRuntimeState.Failed -> renderFailure(state.failure)
        }
    }

    private fun renderActive(presentation: ActiveCrewPresentation, ending: Boolean) {
        val binding = requireBinding()
        binding.crewRole.setText(roleCopy(presentation))
        binding.crewMemberCount.text =
            resources.getQuantityString(
                R.plurals.plr_crew_members,
                presentation.crewState.members.size,
                presentation.crewState.members.size,
            )
        renderQueue(presentation)
        renderMembers(presentation)

        val host = presentation.role == ActiveCrewMode.HOST
        val inviteLink = presentation.inviteLink?.takeIf(String::isNotBlank)
        val inviteAvailable = host && !ending && inviteLink != null
        binding.crewShowQr.isVisible = host
        binding.crewShowQr.isEnabled = inviteAvailable
        binding.crewShare.isVisible = host
        binding.crewShare.isEnabled = inviteAvailable
        if (host && !ending && inviteLink != null) {
            binding.crewShowQr.setOnClickListener { showQrDialog(inviteLink) }
            binding.crewShare.setOnClickListener { shareInvite(inviteLink) }
        } else {
            binding.crewShowQr.setOnClickListener(null)
            binding.crewShare.setOnClickListener(null)
            if (ending) qrDialog?.dismiss()
        }
        binding.crewEnd.isEnabled = !ending
        binding.crewEnd.setText(if (host) R.string.lbl_end_crew else R.string.lbl_leave_crew)
        binding.crewEnd.setOnClickListener { showEndDialog(host) }
        binding.crewEndingStatus.isVisible = ending
        binding.crewEndingProgress.isVisible = ending
        binding.crewReact.isEnabled = !ending && model.allowedReactions.isNotEmpty()
    }

    private fun roleCopy(presentation: ActiveCrewPresentation) =
        when (presentation.role) {
            ActiveCrewMode.HOST ->
                when (presentation.connectivity) {
                    CrewConnectivityPresentation.NEARBY -> R.string.lng_hosting_crew
                    CrewConnectivityPresentation.NEARBY_AND_REMOTE ->
                        R.string.lng_hosting_crew_remote
                    CrewConnectivityPresentation.NEARBY_RELAY_UNAVAILABLE ->
                        R.string.lng_hosting_crew_relay_unavailable
                }
            ActiveCrewMode.JOIN ->
                when (presentation.reconnectState) {
                    CrewJoinReconnectState.Connected ->
                        if (
                            presentation.connectivity ==
                                CrewConnectivityPresentation.NEARBY_AND_REMOTE
                        ) {
                            R.string.lng_joined_crew_remote
                        } else {
                            R.string.lng_joined_crew
                        }
                    is CrewJoinReconnectState.Reconnecting,
                    is CrewJoinReconnectState.Waiting -> R.string.lng_reconnecting_crew
                    CrewJoinReconnectState.Expired -> R.string.lng_reconnect_crew_expired
                    CrewJoinReconnectState.Closed -> R.string.lng_crew_connection_closed
                }
        }

    private fun startQrScan() {
        val options =
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
        GmsBarcodeScanning.getClient(requireContext(), options)
            .startScan()
            .addOnSuccessListener { barcode ->
                barcode.rawValue?.trim()?.takeIf(String::isNotBlank)?.let { invite ->
                    runCrewNetworkAction(PendingNetworkAction.JOIN, invite)
                }
            }
            .addOnFailureListener { context?.showToast(R.string.err_crew_qr_scan) }
    }

    private fun showQrDialog(inviteLink: String) {
        val size = (resources.displayMetrics.density * 280).toInt()
        val bitmap = CrewInviteQr.createBitmap(inviteLink, size) ?: return
        val image =
            ImageView(requireContext()).apply {
                setImageBitmap(bitmap)
                setBackgroundColor(android.graphics.Color.WHITE)
                adjustViewBounds = true
                layoutParams =
                    LinearLayout.LayoutParams(size, size).apply {
                        marginStart = resources.getDimensionPixelSize(R.dimen.spacing_medium)
                        marginEnd = resources.getDimensionPixelSize(R.dimen.spacing_medium)
                    }
            }
        qrDialog?.dismiss()
        qrDialog =
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ttl_crew_qr_code)
                .setMessage(R.string.lng_crew_qr_code)
                .setView(image)
                .setNegativeButton(R.string.lbl_cancel, null)
                .setPositiveButton(R.string.lbl_share_invite) { _, _ -> shareInvite(inviteLink) }
                .create()
                .also { dialog ->
                    dialog.setOnDismissListener {
                        image.setImageBitmap(null)
                        bitmap.recycle()
                        if (qrDialog === dialog) qrDialog = null
                    }
                    dialog.show()
                }
    }

    private fun renderMembers(presentation: ActiveCrewPresentation) {
        val members =
            presentation.crewState.members.sortedWith(
                compareBy<CrewMember> { it.id != presentation.localMemberId }
                    .thenBy { it.displayName }
            )
        requireBinding().crewMembers.apply {
            removeAllViews()
            members.forEach { member ->
                addView(
                    LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams =
                            LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                )
                                .apply {
                                    topMargin =
                                        resources.getDimensionPixelSize(R.dimen.spacing_small)
                                }
                        addView(crewAvatarView(member))
                        addView(
                            TextView(context).apply {
                                text =
                                    if (member.id == presentation.localMemberId) {
                                        getString(
                                            R.string.lbl_crew_member_local,
                                            member.displayName,
                                        )
                                    } else {
                                        member.displayName
                                    }
                                layoutParams =
                                    LinearLayout.LayoutParams(
                                            0,
                                            LinearLayout.LayoutParams.WRAP_CONTENT,
                                            1f,
                                        )
                                        .apply {
                                            marginStart =
                                                resources.getDimensionPixelSize(
                                                    R.dimen.spacing_small
                                                )
                                        }
                            }
                        )
                    }
                )
            }
        }
    }

    private fun crewAvatarView(member: CrewMember): TextView {
        val size = (36 * resources.displayMetrics.density).toInt()
        val descriptor = member.avatar.value
        val red = descriptor.substring(0, 2).toInt(16)
        val green = descriptor.substring(2, 4).toInt(16)
        val blue = descriptor.substring(4, 6).toInt(16)
        val backgroundColor = Color.rgb((red + 255) / 2, (green + 255) / 2, (blue + 255) / 2)
        return TextView(requireContext()).apply {
            text = member.displayName.trim().take(1).uppercase()
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(backgroundColor)
                }
            layoutParams = LinearLayout.LayoutParams(size, size)
            contentDescription = member.displayName
        }
    }

    private fun renderQueue(presentation: ActiveCrewPresentation) {
        val crewState = presentation.crewState
        val current = crewState.queue.firstOrNull { it.id == crewState.playback.currentQueueItemId }
        binding?.let { binding ->
            binding.crewQueueSummary.text =
                resources.getQuantityString(
                    R.plurals.plr_crew_queue_items,
                    crewState.queue.size,
                    crewState.queue.size,
                )
            binding.crewNowPlayingCover.bindArtwork(
                current?.track?.artwork,
                current?.track?.album
                    ?: current?.track?.title
                    ?: getString(R.string.lbl_no_track_playing),
            )
            binding.crewNowPlayingTitle.text =
                current?.track?.title ?: getString(R.string.lbl_no_track_playing)
            binding.crewNowPlayingArtists.text = current?.track?.artists?.joinToString(", ") ?: ""
            binding.crewNowPlayingArtists.isVisible = current != null
            val contributor =
                current?.contributorId?.let { id ->
                    crewState.members.firstOrNull { it.id.value == id }?.displayName
                }
            binding.crewNowPlayingContributor.isVisible = contributor != null
            binding.crewNowPlayingContributor.text =
                contributor?.let { getString(R.string.lbl_added_by_crew_member, it) }
            binding.crewPlaybackMode.setText(playbackModeCopy(crewState.playback.mode))
            binding.crewQueuePreview.text =
                crewState.queue
                    .filter { it.id != crewState.playback.currentQueueItemId }
                    .take(2)
                    .joinToString(separator = " · ") { it.track.title }
                    .ifBlank { getString(R.string.lng_crew_queue_preview_empty) }
        }
    }

    private fun playbackModeCopy(mode: CrewPlaybackMode) =
        when (mode) {
            CrewPlaybackMode.IDLE -> R.string.lbl_crew_playback_idle
            CrewPlaybackMode.PREPARING -> R.string.lbl_crew_playback_preparing
            CrewPlaybackMode.PLAYING -> R.string.lbl_crew_playback_playing
            CrewPlaybackMode.PAUSED -> R.string.lbl_crew_playback_paused
            CrewPlaybackMode.BUFFERING -> R.string.lbl_crew_playback_buffering
            CrewPlaybackMode.ENDED -> R.string.lbl_crew_playback_ended
        }

    private fun renderPeerMediaBlocked(blocked: Boolean) {
        binding?.crewPushPullPrompt?.isVisible = blocked && !crewSettings.pushPullEnabled
    }

    private fun renderActivity(activity: List<ActiveCrewActivity>) {
        val binding = binding ?: return
        val presentation =
            (model.state.value as? ActiveCrewRuntimeState.Active)?.presentation
                ?: (model.state.value as? ActiveCrewRuntimeState.Ending)?.presentation
        val visible = activity.isNotEmpty() && presentation != null
        binding.crewActivityTitle.isVisible = visible
        binding.crewActivity.isVisible = visible
        if (!visible || presentation == null) {
            binding.crewActivity.text = ""
            return
        }
        val crewState = presentation.crewState
        binding.crewActivity.text =
            activity.joinToString("\n") { item ->
                val actor =
                    crewState.members.firstOrNull { it.id == item.issuingMemberId }?.displayName
                        ?: getString(R.string.lbl_crew_member_unknown)
                when (val action = item.action) {
                    is CrewAction.Play -> getString(R.string.lbl_crew_activity_play, actor)
                    is CrewAction.Pause -> getString(R.string.lbl_crew_activity_pause, actor)
                    is CrewAction.CurrentItemChanged ->
                        getString(
                            R.string.lbl_crew_activity_track,
                            actor,
                            crewState.queue.firstOrNull { it.id == action.itemId }?.track?.title
                                ?: getString(R.string.lbl_no_track_playing),
                        )
                    is CrewAction.QueueItemInserted ->
                        getString(R.string.lbl_crew_activity_add, actor, action.item.track.title)
                    is CrewAction.QueueItemRemoved ->
                        getString(R.string.lbl_crew_activity_remove, actor)
                    is CrewAction.QueueItemMoved ->
                        getString(R.string.lbl_crew_activity_move, actor)
                    is CrewAction.QueueReplaced ->
                        getString(R.string.lbl_crew_activity_queue, actor)
                    is CrewAction.ShuffleChanged ->
                        getString(R.string.lbl_crew_activity_shuffle, actor)
                    is CrewAction.RepeatChanged ->
                        getString(R.string.lbl_crew_activity_repeat, actor)
                    is CrewAction.MemberJoined ->
                        getString(R.string.lbl_crew_activity_join, action.member.displayName)
                    is CrewAction.MemberLeft -> getString(R.string.lbl_crew_activity_leave, actor)
                    is CrewAction.CoordinatorTransferred ->
                        getString(R.string.lbl_crew_activity_coordinator, actor)
                    CrewAction.SessionEnded -> getString(R.string.lbl_crew_activity_end, actor)
                    is CrewAction.MemberUpdated,
                    is CrewAction.Seek -> ""
                }
            }
    }

    private fun showReactionPicker() {
        val reactions = model.allowedReactions
        if (reactions.isEmpty()) return
        reactionDialog?.dismiss()
        reactionDialog =
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ttl_react_to_crew)
                .setItems(reactions.toTypedArray()) { _, index ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        model.sendReaction(reactions[index])
                    }
                }
                .setNegativeButton(R.string.lbl_cancel, null)
                .show()
                .also { dialog ->
                    dialog.setOnDismissListener {
                        if (reactionDialog === dialog) reactionDialog = null
                    }
                }
    }

    private fun showCrewReaction(reaction: ActiveCrewReaction) {
        val root = binding?.root ?: return
        val density = resources.displayMetrics.density
        val reactionView =
            TextView(requireContext()).apply {
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
            CoordinatorLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                .apply {
                    gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
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
                        (reactionView.parent as? ViewGroup)?.removeView(reactionView)
                        reactionViews -= reactionView
                    }
                    .start()
            }
            .start()
    }

    private fun shareInvite(inviteLink: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, inviteLink)
                },
                getString(R.string.lbl_share_invite),
            )
        )
    }

    private fun showEndDialog(host: Boolean) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (host) R.string.ttl_end_crew else R.string.ttl_leave_crew)
            .setMessage(if (host) R.string.lng_end_crew else R.string.lng_leave_crew)
            .setNegativeButton(R.string.lbl_cancel, null)
            .setPositiveButton(if (host) R.string.lbl_end_crew else R.string.lbl_leave_crew) { _, _
                ->
                model.end()
            }
            .show()
    }

    private fun renderFailure(failure: ActiveCrewRuntimeFailure) {
        val binding = requireBinding()
        when (failure) {
            ActiveCrewRuntimeFailure.BlankInviteLink -> {
                binding.crewFailureTitle.setText(R.string.ttl_could_not_join_crew)
                binding.crewFailureBody.setText(R.string.lng_invalid_crew_invite)
            }
            is ActiveCrewRuntimeFailure.Host -> {
                binding.crewFailureTitle.setText(R.string.ttl_could_not_start_crew)
                binding.crewFailureBody.setText(hostFailureMessage(failure.launchFailure))
            }
            is ActiveCrewRuntimeFailure.Join -> {
                binding.crewFailureTitle.setText(R.string.ttl_could_not_join_crew)
                binding.crewFailureBody.setText(joinFailureMessage(failure.launchFailure))
            }
            ActiveCrewRuntimeFailure.Internal -> {
                binding.crewFailureTitle.setText(R.string.ttl_could_not_connect_crew)
                binding.crewFailureBody.setText(R.string.lng_could_not_connect_crew)
            }
        }
    }

    private fun runCrewNetworkAction(action: PendingNetworkAction, inviteLink: String? = null) {
        val missingPermissions = missingCrewPermissions()
        if (missingPermissions.isEmpty()) {
            when (action) {
                PendingNetworkAction.START_HOST -> model.startHost()
                PendingNetworkAction.JOIN -> inviteLink?.let(model::join)
            }
            return
        }
        pendingNetworkAction = action
        pendingInviteLink = inviteLink
        nearbyPermissionLauncher.launch(missingPermissions.toTypedArray())
    }

    private fun missingCrewPermissions(): List<String> {
        return crewRuntimePermissions(Build.VERSION.SDK_INT).filter {
            ContextCompat.checkSelfPermission(requireContext(), it) !=
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hostFailureMessage(failure: CrewLanHostLaunchFailure) =
        when (failure) {
            CrewLanHostLaunchFailure.AdvertisementTimedOut -> R.string.lng_crew_connection_timed_out
            CrewLanHostLaunchFailure.AdvertisementClosed,
            is CrewLanHostLaunchFailure.AdvertisementFailed -> R.string.lng_crew_nearby_unavailable
            CrewLanHostLaunchFailure.Initialization,
            CrewLanHostLaunchFailure.EngineOrPersistence -> R.string.lng_could_not_start_crew
        }

    private fun joinFailureMessage(failure: CrewLanJoinLaunchFailure) =
        when (failure) {
            is CrewLanJoinLaunchFailure.InviteRejected ->
                if (failure.reason == CrewInviteDecodeResult.Rejected.EXPIRED) {
                    R.string.lng_crew_invite_expired
                } else {
                    R.string.lng_invalid_crew_invite
                }
            CrewLanJoinLaunchFailure.DiscoveryTimedOut -> R.string.lng_crew_connection_timed_out
            CrewLanJoinLaunchFailure.DiscoveryClosed,
            is CrewLanJoinLaunchFailure.DiscoveryFailed -> R.string.lng_crew_nearby_unavailable
            is CrewLanJoinLaunchFailure.SignalingFailed ->
                when (failure.reason) {
                    CrewLanSignalConnectFailure.INVITATION_MISMATCH,
                    CrewLanSignalConnectFailure.INVITATION_INACTIVE,
                    CrewLanSignalConnectFailure.AUTHENTICATION_FAILED ->
                        R.string.lng_crew_invite_verification_failed
                    CrewLanSignalConnectFailure.NO_REACHABLE_ADDRESS ->
                        R.string.lng_could_not_connect_crew
                    CrewLanSignalConnectFailure.PROTOCOL_ERROR -> R.string.lng_crew_incompatible
                }
            CrewLanJoinLaunchFailure.RemoteSignalingFailed -> R.string.lng_crew_remote_route_failed
            is CrewLanJoinLaunchFailure.JoinRejected ->
                when (failure.reason) {
                    CrewJoinFailure.ProtocolMismatch -> R.string.lng_crew_incompatible
                    CrewJoinFailure.SessionMismatch,
                    CrewJoinFailure.ClaimedLocalMember,
                    CrewJoinFailure.MemberIdMismatch,
                    is CrewJoinFailure.BootstrapRejected ->
                        R.string.lng_crew_invite_verification_failed
                    CrewJoinFailure.TimedOut -> R.string.lng_crew_connection_timed_out
                    is CrewJoinFailure.ConnectionFailed,
                    CrewJoinFailure.ConnectionClosed,
                    CrewJoinFailure.ConnectionSetupFailed,
                    CrewJoinFailure.Closed -> R.string.lng_could_not_connect_crew
                    CrewJoinFailure.SessionFailure -> R.string.lng_could_not_join_crew
                }
            CrewLanJoinLaunchFailure.Initialization,
            CrewLanJoinLaunchFailure.EngineOrPersistence -> R.string.lng_could_not_join_crew
        }
}

private enum class PendingNetworkAction {
    START_HOST,
    JOIN,
}
