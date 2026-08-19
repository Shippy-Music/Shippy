/*
 * Copyright (c) 2026 Auxio Project
 * ManagedDownloadFilteringFS.kt is part of Auxio.
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
package org.oxycblt.auxio.music

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import org.oxycblt.auxio.shippy.download.DownloadState
import org.oxycblt.auxio.shippy.persistence.download.PersistedDownload
import org.oxycblt.musikr.fs.FS
import org.oxycblt.musikr.fs.FSUpdate
import org.oxycblt.musikr.fs.File
import org.oxycblt.musikr.fs.Path
import org.oxycblt.musikr.fs.path.resolveDocumentPath

/** Exact persisted evidence used to keep Shippy-owned files out of Local ingestion. */
internal data class ManagedDownloadFileIdentity(
    val contentUri: String,
    val contentLength: Long?,
    val path: Path?,
)

/** Immutable registry snapshot for one Musikr indexing pass. */
internal class ManagedDownloadFileIndex(identities: Collection<ManagedDownloadFileIdentity>) {
    private val exactUris =
        identities.filter { it.contentLength == null }.mapTo(mutableSetOf()) { it.contentUri }
    private val exactPaths =
        identities.filter { it.contentLength == null }.mapNotNullTo(mutableSetOf()) { it.path }
    private val verifiedUris =
        identities.mapNotNullTo(mutableSetOf()) { identity ->
            identity.contentLength?.let { identity.contentUri to it }
        }
    private val verifiedPaths =
        identities.mapNotNullTo(mutableSetOf()) { identity ->
            identity.path?.let { path -> identity.contentLength?.let { length -> path to length } }
        }

    val isEmpty: Boolean
        get() =
            exactUris.isEmpty() &&
                exactPaths.isEmpty() &&
                verifiedUris.isEmpty() &&
                verifiedPaths.isEmpty()

    fun contains(file: File): Boolean = contains(file.uri.toString(), file.path, file.size)

    internal fun contains(contentUri: String, path: Path?, contentLength: Long): Boolean =
        contentUri in exactUris ||
            (path != null && path in exactPaths) ||
            (contentUri to contentLength) in verifiedUris ||
            (path != null && (path to contentLength) in verifiedPaths)

    companion object {
        val EMPTY = ManagedDownloadFileIndex(emptyList())

        fun from(context: Context, downloads: List<PersistedDownload>): ManagedDownloadFileIndex =
            ManagedDownloadFileIndex(
                buildList {
                    downloads.forEach { download ->
                        download.pendingDocument?.let { pending ->
                            add(identity(context, pending.contentUri, contentLength = null))
                        }
                        if (download.job.state == DownloadState.AVAILABLE) {
                            download.job.artifact?.let { artifact ->
                                add(identity(context, artifact.contentUri, artifact.contentLength))
                            }
                        }
                    }
                }
            )

        private fun identity(context: Context, contentUri: String, contentLength: Long?) =
            ManagedDownloadFileIdentity(
                contentUri = contentUri,
                contentLength = contentLength,
                path =
                    runCatching { resolveDocumentPath(context, Uri.parse(contentUri)) }.getOrNull(),
            )
    }
}

/** Filters only exact registered Shippy files while preserving every unrelated user file. */
internal class ManagedDownloadFilteringFS(
    private val delegate: FS,
    private val managedDownloads: ManagedDownloadFileIndex,
) : FS {
    override suspend fun explore(files: Channel<File>): Deferred<Result<Unit>> = coroutineScope {
        async(Dispatchers.Default) {
            try {
                val sourceFiles = Channel<File>(SOURCE_BUFFER_CAPACITY)
                val forwardTask =
                    async(Dispatchers.Default) {
                        for (file in sourceFiles) {
                            if (!managedDownloads.contains(file)) files.send(file)
                        }
                    }
                val result = delegate.explore(sourceFiles).await()
                sourceFiles.close(result.exceptionOrNull())
                result.getOrThrow()
                forwardTask.await()
                files.close()
                Result.success(Unit)
            } catch (error: CancellationException) {
                files.cancel(error)
                throw error
            } catch (error: Throwable) {
                files.close(error)
                Result.failure(error)
            }
        }
    }

    override fun track(): Flow<FSUpdate> = delegate.track()

    private companion object {
        const val SOURCE_BUFFER_CAPACITY = 128
    }
}
