/*
 * Copyright (c) 2026 Auxio Project
 * Media3PlaybackSmokeTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real device Media3 pipeline with a deterministic generated local WAV fixture. */
@RunWith(AndroidJUnit4::class)
class Media3PlaybackSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val fixture = File(instrumentation.targetContext.cacheDir, "shippy-media3-smoke.wav")
    private val player = AtomicReference<ExoPlayer?>()

    @After
    fun cleanUp() {
        instrumentation.runOnMainSync { player.getAndSet(null)?.release() }
        fixture.delete()
    }

    @Test
    fun generatedLocalAudioReachesReadyAndEndedThroughRealMedia3() {
        writeSilentWav(fixture, durationMs = 300)
        val ready = CountDownLatch(1)
        val ended = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val exo = ExoPlayer.Builder(instrumentation.targetContext).build()
            player.set(exo)
            exo.addListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) ready.countDown()
                        if (playbackState == Player.STATE_ENDED) ended.countDown()
                    }
                }
            )
            exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(fixture)))
            exo.prepare()
            exo.play()
        }

        assertTrue("generated WAV never reached Media3 READY", ready.await(10, TimeUnit.SECONDS))
        assertTrue("generated WAV never reached Media3 ENDED", ended.await(10, TimeUnit.SECONDS))
    }

    private fun writeSilentWav(file: File, durationMs: Int) {
        val sampleRate = 8_000
        val channels = 1
        val bitsPerSample = 16
        val sampleCount = sampleRate * durationMs / 1_000
        val dataBytes = sampleCount * channels * bitsPerSample / 8
        FileOutputStream(file).use { output ->
            output.write("RIFF".toByteArray())
            output.writeIntLe(36 + dataBytes)
            output.write("WAVEfmt ".toByteArray())
            output.writeIntLe(16)
            output.writeShortLe(1)
            output.writeShortLe(channels)
            output.writeIntLe(sampleRate)
            output.writeIntLe(sampleRate * channels * bitsPerSample / 8)
            output.writeShortLe(channels * bitsPerSample / 8)
            output.writeShortLe(bitsPerSample)
            output.write("data".toByteArray())
            output.writeIntLe(dataBytes)
            output.write(ByteArray(dataBytes))
        }
    }

    private fun FileOutputStream.writeIntLe(value: Int) {
        repeat(4) { write(value ushr (it * 8) and 0xFF) }
    }

    private fun FileOutputStream.writeShortLe(value: Int) {
        repeat(2) { write(value ushr (it * 8) and 0xFF) }
    }
}
