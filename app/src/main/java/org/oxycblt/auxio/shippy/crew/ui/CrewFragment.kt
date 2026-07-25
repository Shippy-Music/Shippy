/*
 * Copyright (c) 2026 Shippy contributors
 * CrewFragment.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentCrewBinding
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteDecodeResult
import org.oxycblt.auxio.shippy.crew.lan.CrewLanSignalConnectFailure
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewMode
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewPresentation
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewRuntimeFailure
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewRuntimeState
import org.oxycblt.auxio.shippy.crew.runtime.CrewJoinFailure
import org.oxycblt.auxio.shippy.crew.runtime.CrewLanHostLaunchFailure
import org.oxycblt.auxio.shippy.crew.runtime.CrewLanJoinLaunchFailure
import org.oxycblt.auxio.ui.ViewBindingFragment

@AndroidEntryPoint
class CrewFragment : ViewBindingFragment<FragmentCrewBinding>() {
    private val model: CrewViewModel by viewModels()

    override fun onCreateBinding(inflater: LayoutInflater) = FragmentCrewBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentCrewBinding, savedInstanceState: Bundle?) {
        binding.crewStart.setOnClickListener { model.startHost() }
        binding.crewJoin.setOnClickListener { showJoinDialog() }
        binding.crewDismissFailure.setOnClickListener { model.dismissFailure() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect(::render)
            }
        }
    }

    private fun showJoinDialog() {
        val input = EditText(requireContext()).apply {
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
            .setPositiveButton(R.string.lbl_join) { _, _ -> model.join(input.text.toString().trim()) }
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
        }

        when (state) {
            ActiveCrewRuntimeState.Idle -> Unit
            is ActiveCrewRuntimeState.Starting -> {
                binding.crewProgressTitle.setText(
                    if (state.mode == ActiveCrewMode.HOST) {
                        R.string.lbl_starting_crew
                    } else {
                        R.string.lbl_joining_crew
                    },
                )
            }
            is ActiveCrewRuntimeState.Active -> renderActive(state.presentation, ending = false)
            is ActiveCrewRuntimeState.Ending -> renderActive(state.presentation, ending = true)
            is ActiveCrewRuntimeState.Failed -> renderFailure(state.failure)
        }
    }

    private fun renderActive(presentation: ActiveCrewPresentation, ending: Boolean) {
        val binding = requireBinding()
        binding.crewRole.setText(
            if (presentation.role == ActiveCrewMode.HOST) {
                R.string.lng_hosting_crew
            } else {
                R.string.lng_joined_crew
            },
        )
        binding.crewMemberCount.text = resources.getQuantityString(
            R.plurals.plr_crew_members,
            presentation.crewState.members.size,
            presentation.crewState.members.size,
        )
        renderMembers(presentation)

        val host = presentation.role == ActiveCrewMode.HOST
        binding.crewShare.isVisible = host
        binding.crewShare.isEnabled = !ending
        binding.crewShare.setOnClickListener {
            presentation.inviteLink?.let(::shareInvite)
        }
        binding.crewEnd.isEnabled = !ending
        binding.crewEnd.setText(if (host) R.string.lbl_end_crew else R.string.lbl_leave_crew)
        binding.crewEnd.setOnClickListener { showEndDialog(host) }
        binding.crewEndingStatus.isVisible = ending
        binding.crewEndingProgress.isVisible = ending
    }

    private fun renderMembers(presentation: ActiveCrewPresentation) {
        val members = presentation.crewState.members.sortedWith(
            compareBy<CrewMember> { it.id != presentation.localMemberId }.thenBy { it.displayName },
        )
        requireBinding().crewMembers.apply {
            removeAllViews()
            members.forEach { member ->
                addView(
                    TextView(context).apply {
                        text = if (member.id == presentation.localMemberId) {
                            getString(R.string.lbl_crew_member_local, member.displayName)
                        } else {
                            member.displayName
                        }
                        setCompoundDrawablesRelativeWithIntrinsicBounds(
                            R.drawable.ic_person_24,
                            0,
                            0,
                            0,
                        )
                        compoundDrawablePadding =
                            resources.getDimensionPixelSize(R.dimen.spacing_small)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.spacing_small) }
                    },
                )
            }
        }
    }

    private fun shareInvite(inviteLink: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, inviteLink)
                },
                getString(R.string.lbl_share_invite),
            ),
        )
    }

    private fun showEndDialog(host: Boolean) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (host) R.string.ttl_end_crew else R.string.ttl_leave_crew)
            .setMessage(if (host) R.string.lng_end_crew else R.string.lng_leave_crew)
            .setNegativeButton(R.string.lbl_cancel, null)
            .setPositiveButton(if (host) R.string.lbl_end_crew else R.string.lbl_leave_crew) { _, _ ->
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

    private fun hostFailureMessage(failure: CrewLanHostLaunchFailure) =
        when (failure) {
            CrewLanHostLaunchFailure.AdvertisementTimedOut ->
                R.string.lng_crew_connection_timed_out
            CrewLanHostLaunchFailure.AdvertisementClosed,
            is CrewLanHostLaunchFailure.AdvertisementFailed ->
                R.string.lng_crew_nearby_unavailable
            CrewLanHostLaunchFailure.Initialization,
            CrewLanHostLaunchFailure.EngineOrPersistence ->
                R.string.lng_could_not_start_crew
        }

    private fun joinFailureMessage(failure: CrewLanJoinLaunchFailure) =
        when (failure) {
            is CrewLanJoinLaunchFailure.InviteRejected ->
                if (failure.reason == CrewInviteDecodeResult.Rejected.EXPIRED) {
                    R.string.lng_crew_invite_expired
                } else {
                    R.string.lng_invalid_crew_invite
                }
            CrewLanJoinLaunchFailure.DiscoveryTimedOut ->
                R.string.lng_crew_connection_timed_out
            CrewLanJoinLaunchFailure.DiscoveryClosed,
            is CrewLanJoinLaunchFailure.DiscoveryFailed ->
                R.string.lng_crew_nearby_unavailable
            is CrewLanJoinLaunchFailure.SignalingFailed ->
                when (failure.reason) {
                    CrewLanSignalConnectFailure.INVITATION_MISMATCH,
                    CrewLanSignalConnectFailure.INVITATION_INACTIVE,
                    CrewLanSignalConnectFailure.AUTHENTICATION_FAILED ->
                        R.string.lng_crew_invite_verification_failed
                    CrewLanSignalConnectFailure.NO_REACHABLE_ADDRESS ->
                        R.string.lng_could_not_connect_crew
                    CrewLanSignalConnectFailure.PROTOCOL_ERROR ->
                        R.string.lng_crew_incompatible
                }
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
                    CrewJoinFailure.Closed ->
                        R.string.lng_could_not_connect_crew
                    CrewJoinFailure.SessionFailure -> R.string.lng_could_not_join_crew
                }
            CrewLanJoinLaunchFailure.Initialization,
            CrewLanJoinLaunchFailure.EngineOrPersistence ->
                R.string.lng_could_not_join_crew
        }
}
