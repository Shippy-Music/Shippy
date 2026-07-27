/*
 * Copyright (c) 2026 Auxio Project
 * CrewTemporaryDownloadStaging.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.download

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.Track

/** Owns the durable, private snapshot used by permanent downloads of Crew media. */
@Singleton
class CrewTemporaryDownloadStaging internal constructor(private val directory: File) {
    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(File(context.noBackupFilesDir, DIRECTORY_NAME))

    suspend fun stage(track: Track, candidateId: CandidateId, jobId: DownloadJobId): Track {
        val candidate = track.candidates.firstOrNull { it.id == candidateId } ?: return track
        if (candidate.kind != CandidateKind.CREW_TEMPORARY) return track

        val staged = snapshot(candidate.locator, candidate.media?.contentLength, jobId)
        return track.copy(
            candidates =
                track.candidates.map {
                    if (it.id == candidateId) it.copy(locator = staged.toURI().toString()) else it
                }
        )
    }

    suspend fun cleanup(jobId: DownloadJobId) =
        withContext(Dispatchers.IO + NonCancellable) {
            stageFile(jobId).delete()
            partialFile(jobId).delete()
        }

    private suspend fun snapshot(
        locator: String?,
        expectedLength: Long?,
        jobId: DownloadJobId,
    ): File =
        withContext(Dispatchers.IO) {
            cleanupStalePartials()
            val source =
                locator.toRegularFileOrNull()
                    ?: throw CrewTemporaryStagingException("Crew download source is unavailable")
            val sourceLength = source.length()
            if (sourceLength !in 1L..MAX_STAGING_BYTES) {
                throw CrewTemporaryStagingException(
                    "Crew download source is outside staging bounds"
                )
            }
            if (expectedLength != null && sourceLength != expectedLength) {
                throw CrewTemporaryStagingException("Crew download source length changed")
            }
            if (!directory.exists() && !directory.mkdirs()) {
                throw CrewTemporaryStagingException("Could not prepare private download storage")
            }

            val partial = partialFile(jobId)
            val target = stageFile(jobId)
            partial.delete()
            target.delete()
            try {
                FileInputStream(source).use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (copied > sourceLength) {
                                throw CrewTemporaryStagingException(
                                    "Crew download source length changed"
                                )
                            }
                        }
                        output.fd.sync()
                        if (
                            copied != sourceLength ||
                                (expectedLength != null && copied != expectedLength)
                        ) {
                            throw CrewTemporaryStagingException(
                                "Crew download source length changed"
                            )
                        }
                    }
                }
                if (!partial.renameTo(target)) {
                    throw IOException("Could not finalize private download staging")
                }
                target
            } catch (error: Throwable) {
                partial.delete()
                target.delete()
                if (error is CancellationException) throw error
                if (error is CrewTemporaryStagingException) throw error
                throw CrewTemporaryStagingException(
                    "Could not snapshot Crew download source",
                    error,
                )
            }
        }

    private fun cleanupStalePartials() {
        val cutoff = System.currentTimeMillis() - STALE_PARTIAL_AGE_MS
        directory
            .listFiles { file -> file.name.endsWith(PARTIAL_SUFFIX) }
            ?.asSequence()
            ?.filter { it.lastModified() < cutoff }
            ?.sortedBy(File::lastModified)
            ?.take(MAX_PARTIALS_PER_PASS)
            ?.forEach(File::delete)
    }

    private fun stageFile(jobId: DownloadJobId) = File(directory, "${jobId.value}$STAGE_SUFFIX")

    private fun partialFile(jobId: DownloadJobId) = File(directory, "${jobId.value}$PARTIAL_SUFFIX")

    private companion object {
        const val DIRECTORY_NAME = "crew-download-staging"
        const val STAGE_SUFFIX = ".stage"
        const val PARTIAL_SUFFIX = ".partial"
        const val BUFFER_SIZE = 64 * 1024
        const val STALE_PARTIAL_AGE_MS = 24L * 60L * 60L * 1000L
        const val MAX_PARTIALS_PER_PASS = 32
        const val MAX_STAGING_BYTES = 2L * 1024L * 1024L * 1024L
    }
}

class CrewTemporaryStagingException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

private fun String?.toRegularFileOrNull(): File? =
    try {
        val uri = this?.let(::URI) ?: return null
        if (uri.scheme?.lowercase() != "file") return null
        File(uri).takeIf(File::isFile)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: java.net.URISyntaxException) {
        null
    }
