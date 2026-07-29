/*
 * Copyright (c) 2026 Auxio Project
 * CompositeFS.kt is part of Auxio.
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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/**
 * A small ordered composition of file systems.
 *
 * The primary source is explored before the secondary source. Exact [Path] duplicates from the
 * secondary are suppressed, so callers retain a deterministic primary source of truth without
 * guessing that two different paths represent the same recording.
 */
class CompositeFS(private val primary: FS, private val secondary: FS) : FS {
    override suspend fun explore(files: Channel<File>): Deferred<Result<Unit>> = coroutineScope {
        async(Dispatchers.Default) {
            try {
                val seenPaths = mutableSetOf<Path>()
                forward(primary, files, seenPaths)
                forward(secondary, files, seenPaths)
                files.close()
                Result.success(Unit)
            } catch (e: CancellationException) {
                files.cancel(e)
                throw e
            } catch (e: Throwable) {
                files.close(e)
                Result.failure(e)
            }
        }
    }

    override fun track(): Flow<FSUpdate> = merge(primary.track(), secondary.track())

    private suspend fun forward(
        source: FS,
        destination: Channel<File>,
        seenPaths: MutableSet<Path>,
    ) = coroutineScope {
        val sourceFiles = Channel<File>(SOURCE_BUFFER_CAPACITY)
        val forwardTask =
            async(Dispatchers.Default) {
                for (file in sourceFiles) {
                    if (seenPaths.add(file.path)) {
                        destination.send(file)
                    }
                }
            }
        val result = source.explore(sourceFiles).await()
        result.getOrThrow()
        forwardTask.await()
    }

    private companion object {
        const val SOURCE_BUFFER_CAPACITY = 128
    }
}
