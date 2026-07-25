/*
 * Copyright (c) 2026 Shippy contributors
 * ShippyHomeFragment.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.home

import android.os.Bundle
import android.view.LayoutInflater
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentShippyHomeBinding
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.musikr.Song

@AndroidEntryPoint
class ShippyHomeFragment : ViewBindingFragment<FragmentShippyHomeBinding>() {
    private val musicModel: MusicViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentShippyHomeBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentShippyHomeBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)
        binding.homeCurrent.setOnClickListener { playbackModel.openPlayback() }
        binding.homeOpenSearch.setOnClickListener {
            findNavController().navigate(R.id.search_fragment)
        }
        binding.homeOpenLibrary.setOnClickListener {
            findNavController().navigate(R.id.library_fragment)
        }

        collectImmediately(playbackModel.song, ::updateCurrentSong)
        collectImmediately(musicModel.statistics, ::updateLibrarySummary)
    }

    private fun updateCurrentSong(song: Song?) {
        val binding = requireBinding()
        binding.homeCurrent.isVisible = song != null
        binding.homeNoCurrent.isVisible = song == null
        if (song == null) {
            return
        }

        val context = requireContext()
        binding.homeCurrentCover.bind(song)
        binding.homeCurrentTitle.text = song.name.resolve(context)
        binding.homeCurrentArtist.text = song.artists.resolveNames(context)
    }

    private fun updateLibrarySummary(statistics: MusicViewModel.Statistics?) {
        requireBinding().homeLibrarySummary.text =
            getString(
                R.string.fmt_shippy_library_summary,
                statistics?.songs ?: 0,
                statistics?.albums ?: 0,
                statistics?.artists ?: 0,
            )
    }
}
