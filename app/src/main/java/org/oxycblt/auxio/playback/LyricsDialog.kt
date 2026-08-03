/*
 * Copyright (c) 2026 Auxio Project
 * LyricsDialog.kt is part of Auxio.
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

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.google.android.material.R as MR
import com.google.android.material.bottomsheet.BackportBottomSheetBehavior
import com.google.android.material.bottomsheet.BackportBottomSheetDialog
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogLyricsBinding
import org.oxycblt.auxio.playback.ui.StyledSeekBar
import org.oxycblt.auxio.shippy.lyrics.PlainLyrics
import org.oxycblt.auxio.shippy.lyrics.SyncedLyrics
import org.oxycblt.auxio.shippy.lyrics.TranslatedLyrics
import org.oxycblt.auxio.shippy.provider.ui.ProviderTrackActionsSheet
import org.oxycblt.auxio.ui.ViewBindingBottomSheetDialogFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.getAttrColorCompat

/** Full-height synchronized lyrics driven by the same player state as Now Playing. */
@AndroidEntryPoint
class LyricsDialog :
    ViewBindingBottomSheetDialogFragment<DialogLyricsBinding>(), StyledSeekBar.Listener {
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val playerActionsModel: PlayerActionsViewModel by viewModels()
    private var renderedState: PlaybackLyricsState = PlaybackLyricsState.None
    private var renderedTranslationState: LyricsTranslationState = LyricsTranslationState.Hidden
    private var renderedLineIndex = Int.MIN_VALUE

    override fun onCreateBinding(inflater: LayoutInflater) = DialogLyricsBinding.inflate(inflater)

    override fun onBindingCreated(binding: DialogLyricsBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        binding.lyricsClose.setOnClickListener { dismiss() }
        binding.lyricsPlayPause.setOnClickListener { playbackModel.togglePlaying() }
        binding.lyricsDownload.setOnClickListener { playerActionsModel.performDownloadAction() }
        binding.lyricsMore.setOnClickListener {
            val track =
                playbackModel.displayItem.value?.queueItem?.track ?: return@setOnClickListener
            ProviderTrackActionsSheet.show(parentFragmentManager, track, playerContext = true)
        }
        binding.lyricsSeekBar.listener = this
        binding.lyricsBody.highlightColor = Color.TRANSPARENT
        collectImmediately(playbackModel.displayItem, ::updateTrack)
        collectImmediately(
            playbackModel.lyrics,
            playbackModel.lyricsTranslation,
            playbackModel.positionDs,
            ::updateLyrics,
        )
        collectImmediately(playbackModel.isPlaying, ::updatePlaying)
        collectImmediately(playerActionsModel.state, ::updateActions)
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BackportBottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BackportBottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onDestroyBinding(binding: DialogLyricsBinding) {
        binding.lyricsSeekBar.listener = null
        renderedState = PlaybackLyricsState.None
        renderedTranslationState = LyricsTranslationState.Hidden
        renderedLineIndex = Int.MIN_VALUE
        super.onDestroyBinding(binding)
    }

    override fun onSeekConfirmed(positionDs: Long) {
        playbackModel.seekTo(positionDs)
    }

    private fun updateTrack(item: PlaybackDisplayItem?) {
        val binding = requireBinding()
        playerActionsModel.setDisplayItem(item)
        val track = item?.queueItem?.track
        binding.lyricsTrackTitle.text = track?.title ?: getString(R.string.lbl_playback)
        binding.lyricsTrackArtist.text = track?.artists?.joinToString(", ").orEmpty()
        binding.lyricsSeekBar.durationDs = (track?.durationMs ?: 0L).msToDs()
        binding.lyricsMore.isVisible = track != null
    }

    private fun updatePlaying(isPlaying: Boolean) {
        requireBinding().apply {
            lyricsPlayPause.isChecked = isPlaying
            lyricsSeekBar.setWaveEnabled(isPlaying)
        }
    }

    private fun updateActions(state: PlayerActionsState) {
        requireBinding().lyricsDownload.apply {
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

    private fun updateLyrics(
        state: PlaybackLyricsState,
        translationState: LyricsTranslationState,
        positionDs: Long,
    ) {
        val binding = requireBinding()
        binding.lyricsSeekBar.positionDs = positionDs
        val stateChanged = state != renderedState || translationState != renderedTranslationState
        if (stateChanged) {
            renderedState = state
            renderedTranslationState = translationState
            renderedLineIndex = Int.MIN_VALUE
            binding.lyricsRetry.isVisible = false
            binding.lyricsRetry.setOnClickListener(null)
            updateTranslationAction(binding, state, translationState)
        }

        when (state) {
            PlaybackLyricsState.None,
            is PlaybackLyricsState.Unavailable -> {
                if (stateChanged) {
                    binding.lyricsBody.movementMethod = null
                    binding.lyricsBody.setText(R.string.lng_lyrics_unavailable)
                }
            }
            is PlaybackLyricsState.Loading -> {
                if (stateChanged) {
                    binding.lyricsBody.movementMethod = null
                    binding.lyricsBody.setText(R.string.lng_lyrics_loading)
                }
            }
            is PlaybackLyricsState.Ready -> {
                when (val lyrics = state.lyrics) {
                    is SyncedLyrics -> {
                        val activeIndex = activeLyricIndex(lyrics, positionDs * 100)
                        if (stateChanged || activeIndex != renderedLineIndex) {
                            renderedLineIndex = activeIndex
                            renderSyncedLyrics(binding, lyrics, translationState, activeIndex)
                        }
                    }
                    is PlainLyrics -> {
                        if (stateChanged) {
                            binding.lyricsBody.movementMethod = null
                            renderPlainLyrics(binding, lyrics, translationState)
                        }
                    }
                }
            }
            is PlaybackLyricsState.Error -> {
                if (stateChanged) {
                    binding.lyricsBody.movementMethod = null
                    binding.lyricsBody.setText(R.string.lng_lyrics_error)
                    if (state.retryable) {
                        binding.lyricsRetry.isVisible = true
                        binding.lyricsRetry.setOnClickListener { playbackModel.retryLyrics() }
                    }
                }
            }
        }
        binding.lyricsBody.contentDescription = binding.lyricsBody.text
    }

    private fun renderSyncedLyrics(
        binding: DialogLyricsBinding,
        lyrics: SyncedLyrics,
        translationState: LyricsTranslationState,
        activeIndex: Int,
    ) {
        val text = SpannableStringBuilder()
        var activeStart = -1
        val activeColor = requireContext().getAttrColorCompat(MR.attr.colorOnSurface).defaultColor
        val inactiveColor = ColorUtils.setAlphaComponent(activeColor, 0x78)
        val translatedLines =
            ((translationState as? LyricsTranslationState.Ready)?.takeIf { it.visible }?.lyrics
                    as? TranslatedLyrics.Synced)
                ?.lines
        lyrics.lines.forEachIndexed { index, line ->
            if (index > 0) text.append("\n\n")
            val start = text.length
            text.append(line.text)
            val originalEnd = text.length
            val translatedText = translatedLines?.getOrNull(index)?.translatedText.orEmpty()
            val translatedStart =
                if (translatedText.isNotBlank()) {
                    text.append("\n")
                    text.length
                } else {
                    -1
                }
            if (translatedStart >= 0) text.append(translatedText)
            val end = text.length
            text.setSpan(
                ForegroundColorSpan(if (index == activeIndex) activeColor else inactiveColor),
                start,
                originalEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            if (translatedStart >= 0) {
                text.setSpan(
                    ForegroundColorSpan(
                        ColorUtils.setAlphaComponent(
                            activeColor,
                            if (index == activeIndex) 0xB8 else 0x68,
                        )
                    ),
                    translatedStart,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                text.setSpan(
                    RelativeSizeSpan(0.78f),
                    translatedStart,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            text.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        val positionDs = line.startMs.msToDs()
                        binding.lyricsSeekBar.positionDs = positionDs
                        playbackModel.seekTo(positionDs)
                    }

                    override fun updateDrawState(drawState: TextPaint) = Unit
                },
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            if (index == activeIndex) {
                activeStart = start
                text.setSpan(
                    StyleSpan(Typeface.BOLD),
                    start,
                    originalEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                text.setSpan(
                    RelativeSizeSpan(1.22f),
                    start,
                    originalEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            } else {
                text.setSpan(
                    RelativeSizeSpan(0.92f),
                    start,
                    originalEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        binding.lyricsBody.movementMethod = LinkMovementMethod.getInstance()
        binding.lyricsBody.text = text
        if (activeStart >= 0) {
            binding.lyricsBody.post {
                val layout = binding.lyricsBody.layout ?: return@post
                val line = layout.getLineForOffset(activeStart.coerceAtMost(text.length))
                val target =
                    (layout.getLineTop(line) - binding.lyricsScroll.height / 3).coerceAtLeast(0)
                binding.lyricsScroll.smoothScrollTo(0, target)
            }
        }
    }

    private fun renderPlainLyrics(
        binding: DialogLyricsBinding,
        lyrics: PlainLyrics,
        translationState: LyricsTranslationState,
    ) {
        if (lyrics.plainText.isBlank()) {
            binding.lyricsBody.setText(R.string.lng_lyrics_instrumental)
            return
        }
        val translated =
            ((translationState as? LyricsTranslationState.Ready)?.takeIf { it.visible }?.lyrics
                    as? TranslatedLyrics.Plain)
                ?.lines
        if (translated == null) {
            binding.lyricsBody.text = lyrics.plainText
            return
        }
        val activeColor = requireContext().getAttrColorCompat(MR.attr.colorOnSurface).defaultColor
        val text = SpannableStringBuilder()
        translated.forEachIndexed { index, line ->
            if (index > 0) text.append("\n\n")
            text.append(line.originalText)
            if (line.translatedText.isNotBlank()) {
                text.append("\n")
                val start = text.length
                text.append(line.translatedText)
                text.setSpan(
                    ForegroundColorSpan(ColorUtils.setAlphaComponent(activeColor, 0x78)),
                    start,
                    text.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                text.setSpan(
                    RelativeSizeSpan(0.78f),
                    start,
                    text.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        binding.lyricsBody.text = text
    }

    private fun updateTranslationAction(
        binding: DialogLyricsBinding,
        lyricsState: PlaybackLyricsState,
        state: LyricsTranslationState,
    ) {
        val hasLyrics =
            (lyricsState as? PlaybackLyricsState.Ready)?.lyrics?.plainText?.isNotBlank() == true
        binding.lyricsTranslate.isVisible = hasLyrics
        binding.lyricsTranslationStatus.isVisible = hasLyrics
        binding.lyricsTranslate.setOnClickListener(null)
        when (state) {
            LyricsTranslationState.Hidden -> {
                binding.lyricsTranslate.isEnabled = true
                binding.lyricsTranslate.setText(R.string.lbl_translate_lyrics)
                binding.lyricsTranslate.setOnClickListener {
                    playbackModel.translateLyricsToEnglish()
                }
                binding.lyricsTranslationStatus.setText(
                    R.string.lng_translation_download_disclosure
                )
            }
            is LyricsTranslationState.Loading -> {
                binding.lyricsTranslate.isEnabled = false
                binding.lyricsTranslate.setText(R.string.lng_translation_loading)
                binding.lyricsTranslationStatus.setText(
                    R.string.lng_translation_download_disclosure
                )
            }
            is LyricsTranslationState.Ready -> {
                binding.lyricsTranslate.isEnabled = true
                binding.lyricsTranslate.setText(
                    if (state.visible) {
                        R.string.lbl_hide_translation
                    } else {
                        R.string.lbl_show_translation
                    }
                )
                binding.lyricsTranslate.setOnClickListener {
                    playbackModel.toggleLyricsTranslationVisibility()
                }
                binding.lyricsTranslationStatus.setText(R.string.lng_translation_attribution)
            }
            is LyricsTranslationState.AlreadyEnglish -> {
                binding.lyricsTranslate.isEnabled = false
                binding.lyricsTranslate.setText(R.string.lbl_translate_lyrics)
                binding.lyricsTranslationStatus.setText(
                    R.string.lng_translation_already_english
                )
            }
            is LyricsTranslationState.Unsupported -> {
                binding.lyricsTranslate.isEnabled = false
                binding.lyricsTranslate.setText(R.string.lbl_translate_lyrics)
                binding.lyricsTranslationStatus.setText(R.string.lng_translation_unsupported)
            }
            is LyricsTranslationState.Error -> {
                binding.lyricsTranslate.isEnabled = true
                binding.lyricsTranslate.setText(R.string.lbl_retry_translation)
                binding.lyricsTranslate.setOnClickListener {
                    playbackModel.translateLyricsToEnglish()
                }
                binding.lyricsTranslationStatus.setText(R.string.lng_translation_error)
            }
        }
    }

    companion object {
        private const val TAG = "lyrics_dialog"

        fun show(fragmentManager: FragmentManager) {
            if (!fragmentManager.isStateSaved && fragmentManager.findFragmentByTag(TAG) == null) {
                LyricsDialog().show(fragmentManager, TAG)
            }
        }
    }
}
