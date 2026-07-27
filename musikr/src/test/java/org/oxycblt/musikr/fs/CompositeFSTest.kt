/*
 * Copyright (c) 2026 Auxio Project
 * CompositeFSTest.kt is part of Auxio.
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
package org.oxycblt.musikr.fs

import android.net.Uri
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeFSTest {
    private val testUri = mockk<Uri>()

    @Test
    fun explore_primaryWinsExactPathDuplicates() = runTest {
        val duplicate = file("duplicate.mp3")
        val primaryOnly = file("primary.mp3")
        val secondaryOnly = file("secondary.mp3")
        val output = Channel<File>(Channel.UNLIMITED)
        val fs =
            CompositeFS(
                FakeFS(files = listOf(duplicate, primaryOnly)),
                FakeFS(files = listOf(duplicate, secondaryOnly)),
            )

        val result = fs.explore(output).await()

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(duplicate.path, primaryOnly.path, secondaryOnly.path),
            output.drain().map { it.path },
        )
    }

    @Test
    fun explore_secondaryFailurePropagatesAndClosesOutput() = runTest {
        val output = Channel<File>(Channel.UNLIMITED)
        val fs = CompositeFS(FakeFS(), FakeFS(failure = IllegalStateException("secondary failed")))

        val result = fs.explore(output).await()

        assertTrue(result.isFailure)
        assertTrue(output.isClosedForReceive)
    }

    @Test
    fun track_mergesBothSources() = runTest {
        val primaryUpdate = FSUpdate.LocationChanged(null)
        val secondaryUpdate = FSUpdate.LocationChanged(null)
        val fs =
            CompositeFS(
                FakeFS(updates = flowOf(primaryUpdate)),
                FakeFS(updates = flowOf(secondaryUpdate)),
            )

        assertEquals(2, fs.track().toList().size)
    }

    private fun file(name: String) =
        File(
            uri = testUri,
            path = Path(Volume.ThirdParty(testUri), Components.parseUnix(name)),
            addedMs = NullAddedMs,
            modifiedMs = 0,
            mimeType = "audio/mpeg",
            size = 1,
            parent = null,
        )

    private class FakeFS(
        private val files: List<File> = emptyList(),
        private val failure: Throwable? = null,
        private val updates: Flow<FSUpdate> = emptyFlow(),
    ) : FS {
        override suspend fun explore(files: Channel<File>): Deferred<Result<Unit>> {
            failure?.let {
                files.close(it)
                return CompletableDeferred(Result.failure(it))
            }
            this.files.forEach { files.send(it) }
            files.close()
            return CompletableDeferred(Result.success(Unit))
        }

        override fun track(): Flow<FSUpdate> = updates
    }

    private object NullAddedMs : AddedMs {
        override suspend fun resolve(): Long? = null
    }

    private suspend fun Channel<File>.drain(): List<File> {
        val files = mutableListOf<File>()
        for (file in this) {
            files += file
        }
        return files
    }
}
