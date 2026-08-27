/*
 * Copyright (c) 2026 Auxio Project
 * SafDownloadStorage.kt is part of Auxio.
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

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.music.MusicSettings
import org.oxycblt.musikr.fs.Location

sealed interface DownloadDestinationState {
    data object NotSelected : DownloadDestinationState

    data class Ready(
        val destination: DownloadDestination,
        val existingAudio: List<StoredAudioDocument>,
    ) : DownloadDestinationState

    data class Unavailable(
        val destination: DownloadDestination,
        val reason: DownloadStorageFailure,
    ) : DownloadDestinationState
}

enum class DownloadStorageFailure {
    PERMISSION_REVOKED,
    NOT_A_TREE,
    NOT_READABLE,
    NOT_WRITABLE,
    CREATE_FAILED,
    OPEN_FAILED,
    VERIFY_FAILED,
}

data class StoredAudioDocument(
    val contentUri: String,
    val displayName: String,
    val mimeType: String?,
    val contentLength: Long?,
)

data class PendingDownloadDocument(
    val contentUri: String,
    val displayName: String,
    val mimeType: String,
)

sealed interface StorageResult<out T> {
    data class Success<T>(val value: T) : StorageResult<T>

    data class Failure(val reason: DownloadStorageFailure) : StorageResult<Nothing>
}

/**
 * The outcome of deleting a document that Shippy already owns.
 *
 * This is intentionally more precise than the legacy boolean delete API: a missing document is safe
 * to reconcile, while a provider failure must leave the durable download available.
 */
enum class SafManagedDownloadDeletionResult {
    DELETED,
    ALREADY_MISSING,
    FAILED,
}

/** Exact-URI probe result used by the R16 managed-download deletion seam. */
internal enum class SafManagedDocumentPresence {
    PRESENT,
    ABSENT,
    FAILED,
}

/**
 * Applies the deletion truth policy after an exact URI presence probe.
 *
 * A zero-row delete is not evidence of absence by itself: the URI is probed again to distinguish a
 * concurrent removal from a provider that refused or ignored the delete.
 */
internal suspend fun deleteSafManagedDocument(
    uri: Uri,
    probe: suspend (Uri) -> SafManagedDocumentPresence,
    delete: suspend (Uri) -> Int,
): SafManagedDownloadDeletionResult {
    return when (probe(uri)) {
        SafManagedDocumentPresence.ABSENT -> SafManagedDownloadDeletionResult.ALREADY_MISSING
        SafManagedDocumentPresence.FAILED -> SafManagedDownloadDeletionResult.FAILED
        SafManagedDocumentPresence.PRESENT -> {
            if (delete(uri) > 0) {
                SafManagedDownloadDeletionResult.DELETED
            } else {
                when (probe(uri)) {
                    SafManagedDocumentPresence.ABSENT ->
                        SafManagedDownloadDeletionResult.ALREADY_MISSING
                    SafManagedDocumentPresence.PRESENT,
                    SafManagedDocumentPresence.FAILED -> SafManagedDownloadDeletionResult.FAILED
                }
            }
        }
    }
}

/**
 * Deletes one exact SAF document without consulting destination or music-source settings.
 *
 * R16 storage owners use this narrow seam for cleanup of a job-owned pending document. The existing
 * legacy storage delegates to the same probe/delete policy so a provider that returns a zero-row
 * delete is never mistaken for a successful removal.
 */
internal suspend fun deleteSafManagedContentUri(
    resolver: ContentResolver,
    uri: Uri,
): SafManagedDownloadDeletionResult =
    withContext(Dispatchers.IO) {
        try {
            deleteSafManagedDocument(
                uri = uri,
                probe = { candidate -> probeSafManagedContentUri(resolver, candidate) },
                delete = { candidate -> resolver.delete(candidate, null, null) },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            SafManagedDownloadDeletionResult.FAILED
        }
    }

private fun probeSafManagedContentUri(
    resolver: ContentResolver,
    uri: Uri,
): SafManagedDocumentPresence =
    try {
        resolver
            .query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    SafManagedDocumentPresence.PRESENT
                } else {
                    SafManagedDocumentPresence.ABSENT
                }
            } ?: SafManagedDocumentPresence.FAILED
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        SafManagedDocumentPresence.FAILED
    }

@Singleton
class SafDownloadStorage
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val settings: DownloadDestinationSettings,
    private val musicSettings: MusicSettings,
) {
    private val resolver
        get() = context.contentResolver

    suspend fun selectDestination(
        treeUri: Uri,
        displayName: String?,
        grantFlags: Int =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    ): StorageResult<DownloadDestination> =
        withContext(Dispatchers.IO) {
            val previous = settings.destination
            val previousAutoAddedSourceUri = settings.autoAddedLocalSourceUri
            val existingSourceUris = musicSettings.safQuery.source.map { it.uri.toString() }
            val hadPersistedGrant = hasPersistedReadWriteGrant(treeUri)
            try {
                resolver.takePersistableUriPermission(
                    treeUri,
                    grantFlags and
                        (Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
                )
            } catch (_: SecurityException) {
                return@withContext StorageResult.Failure(DownloadStorageFailure.PERMISSION_REVOKED)
            }
            val root =
                DocumentFile.fromTreeUri(context, treeUri)
                    ?: run {
                        releaseNewGrantIfUnused(treeUri, hadPersistedGrant, existingSourceUris)
                        return@withContext StorageResult.Failure(DownloadStorageFailure.NOT_A_TREE)
                    }
            if (!root.canRead()) {
                releaseNewGrantIfUnused(treeUri, hadPersistedGrant, existingSourceUris)
                return@withContext StorageResult.Failure(DownloadStorageFailure.NOT_READABLE)
            }
            if (!root.canWrite()) {
                releaseNewGrantIfUnused(treeUri, hadPersistedGrant, existingSourceUris)
                return@withContext StorageResult.Failure(DownloadStorageFailure.NOT_WRITABLE)
            }
            val openedLocation =
                runCatching { Location.Unopened.from(context, treeUri).open(context) }.getOrNull()
                    ?: run {
                        releaseNewGrantIfUnused(treeUri, hadPersistedGrant, existingSourceUris)
                        return@withContext StorageResult.Failure(
                            DownloadStorageFailure.PERMISSION_REVOKED
                        )
                    }
            val destination =
                DownloadDestination(
                    treeUri = treeUri.toString(),
                    displayName =
                        displayName?.takeIf(String::isNotBlank)
                            ?: root.name?.takeIf(String::isNotBlank)
                            ?: "Downloads",
                )
            val sourcePlan =
                DownloadDestinationLocalSourcePlan.create(
                    existingSourceUris = existingSourceUris,
                    previousDestinationUri = previous?.treeUri,
                    autoAddedDestinationUri = previousAutoAddedSourceUri,
                    newDestinationUri = destination.treeUri,
                )
            if (sourcePlan.sourceChanged) {
                val existingSources = musicSettings.safQuery.source
                musicSettings.safQuery =
                    musicSettings.safQuery.copy(
                        source =
                            sourcePlan.sourceUris.map { sourceUri ->
                                existingSources.firstOrNull { it.uri.toString() == sourceUri }
                                    ?: openedLocation.takeIf { it.uri.toString() == sourceUri }
                                    ?: error("Missing Local source for $sourceUri")
                            }
                    )
                musicSettings.forceLocationUpdate()
            }
            settings.setDestination(destination, sourcePlan.autoAddedDestinationUri)
            previous
                ?.treeUri
                ?.takeIf { it != destination.treeUri }
                ?.let(Uri::parse)
                ?.let { previousUri ->
                    if (sourcePlan.sourceUris.none { it == previousUri.toString() }) {
                        releasePersistedGrant(previousUri)
                    }
                }
            StorageResult.Success(destination)
        }

    suspend fun inspectDestination(): DownloadDestinationState =
        withContext(Dispatchers.IO) {
            val destination =
                settings.destination ?: return@withContext DownloadDestinationState.NotSelected
            try {
                val treeUri = Uri.parse(destination.treeUri)
                if (!hasPersistedReadWriteGrant(treeUri)) {
                    return@withContext DownloadDestinationState.Unavailable(
                        destination,
                        DownloadStorageFailure.PERMISSION_REVOKED,
                    )
                }
                val root =
                    DocumentFile.fromTreeUri(context, treeUri)
                        ?: return@withContext DownloadDestinationState.Unavailable(
                            destination,
                            DownloadStorageFailure.NOT_A_TREE,
                        )
                when {
                    !root.canRead() ->
                        DownloadDestinationState.Unavailable(
                            destination,
                            DownloadStorageFailure.NOT_READABLE,
                        )
                    !root.canWrite() ->
                        DownloadDestinationState.Unavailable(
                            destination,
                            DownloadStorageFailure.NOT_WRITABLE,
                        )
                    else -> DownloadDestinationState.Ready(destination, scan(root))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                DownloadDestinationState.Unavailable(
                    destination,
                    DownloadStorageFailure.PERMISSION_REVOKED,
                )
            }
        }

    suspend fun createPendingDocument(
        jobId: DownloadJobId,
        title: String,
        mimeType: String?,
    ): StorageResult<PendingDownloadDocument> =
        withContext(Dispatchers.IO) {
            val destination =
                settings.destination
                    ?: return@withContext StorageResult.Failure(
                        DownloadStorageFailure.PERMISSION_REVOKED
                    )
            val treeUri = Uri.parse(destination.treeUri)
            if (!hasPersistedReadWriteGrant(treeUri)) {
                return@withContext StorageResult.Failure(DownloadStorageFailure.PERMISSION_REVOKED)
            }
            val root =
                DocumentFile.fromTreeUri(context, treeUri)
                    ?: return@withContext StorageResult.Failure(DownloadStorageFailure.NOT_A_TREE)
            if (!root.canRead()) {
                return@withContext StorageResult.Failure(DownloadStorageFailure.NOT_READABLE)
            }
            if (!root.canWrite()) {
                return@withContext StorageResult.Failure(DownloadStorageFailure.NOT_WRITABLE)
            }
            val normalizedMime = mimeType?.takeIf(String::isNotBlank) ?: DEFAULT_MIME
            val fileName = uniqueFileName(root, title, jobId, normalizedMime)
            val document =
                root.createFile(normalizedMime, fileName)
                    ?: return@withContext StorageResult.Failure(
                        DownloadStorageFailure.CREATE_FAILED
                    )
            StorageResult.Success(
                PendingDownloadDocument(
                    contentUri = document.uri.toString(),
                    displayName = document.name ?: fileName,
                    mimeType = normalizedMime,
                )
            )
        }

    suspend fun openOutput(document: PendingDownloadDocument): StorageResult<OutputStream> =
        withContext(Dispatchers.IO) {
            try {
                resolver.openOutputStream(Uri.parse(document.contentUri), "w")?.let {
                    StorageResult.Success(it)
                } ?: StorageResult.Failure(DownloadStorageFailure.OPEN_FAILED)
            } catch (_: Exception) {
                StorageResult.Failure(DownloadStorageFailure.OPEN_FAILED)
            }
        }

    suspend fun verify(
        document: PendingDownloadDocument,
        expectedBytes: Long?,
        verifiedAtEpochMs: Long,
    ): StorageResult<DownloadArtifact> =
        withContext(Dispatchers.IO) {
            val file =
                DocumentFile.fromSingleUri(context, Uri.parse(document.contentUri))
                    ?: return@withContext StorageResult.Failure(
                        DownloadStorageFailure.VERIFY_FAILED
                    )
            val length = file.length()
            if (
                !file.exists() || length <= 0L || (expectedBytes != null && length != expectedBytes)
            ) {
                return@withContext StorageResult.Failure(DownloadStorageFailure.VERIFY_FAILED)
            }
            StorageResult.Success(
                DownloadArtifact(
                    contentUri = document.contentUri,
                    contentLength = length,
                    mimeType = file.type ?: document.mimeType,
                    verifiedAtEpochMs = verifiedAtEpochMs,
                )
            )
        }

    suspend fun delete(contentUri: String): Boolean =
        deleteManagedDownload(contentUri) == SafManagedDownloadDeletionResult.DELETED

    /**
     * Deletes one already-identified SAF document without touching destination settings or local
     * source configuration.
     *
     * A provider can report a false delete even though the document disappeared concurrently, so
     * the URI is re-queried before returning [SafManagedDownloadDeletionResult.FAILED].
     */
    suspend fun deleteManagedDownload(contentUri: String): SafManagedDownloadDeletionResult =
        withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(contentUri)
                deleteSafManagedContentUri(resolver, uri)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                SafManagedDownloadDeletionResult.FAILED
            }
        }

    private fun hasPersistedReadWriteGrant(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }

    private fun releaseNewGrantIfUnused(
        uri: Uri,
        hadPersistedGrant: Boolean,
        sourceUris: List<String>,
    ) {
        if (!hadPersistedGrant && uri.toString() !in sourceUris) {
            releasePersistedGrant(uri)
        }
    }

    private fun releasePersistedGrant(uri: Uri) {
        try {
            resolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // The grant may already be revoked.
        }
    }

    private fun scan(root: DocumentFile): List<StoredAudioDocument> {
        val pending = ArrayDeque<DocumentFile>().apply { add(root) }
        val found = mutableListOf<StoredAudioDocument>()
        while (pending.isNotEmpty()) {
            val document = pending.removeFirst()
            for (child in document.listFiles()) {
                when {
                    child.isDirectory -> pending.add(child)
                    child.isFile && child.isSupportedAudio() ->
                        found +=
                            StoredAudioDocument(
                                contentUri = child.uri.toString(),
                                displayName = child.name ?: "Audio",
                                mimeType = child.type,
                                contentLength = child.length().takeIf { it >= 0L },
                            )
                }
            }
        }
        return found.sortedBy { it.displayName.lowercase() }
    }

    private fun uniqueFileName(
        root: DocumentFile,
        title: String,
        jobId: DownloadJobId,
        mimeType: String,
    ): String {
        val base =
            title.replace(UNSAFE_FILE_NAME, "_").trim(' ', '.', '_').take(MAX_BASE_LENGTH).ifBlank {
                "Track"
            }
        val extension = extensionFor(mimeType)
        val suffix = jobId.value.filter(Char::isLetterOrDigit).take(8).ifBlank { "download" }
        var candidate = "$base.$extension"
        if (root.findFile(candidate) == null) return candidate
        candidate = "$base-$suffix.$extension"
        var attempt = 2
        while (root.findFile(candidate) != null) {
            candidate = "$base-$suffix-$attempt.$extension"
            attempt++
        }
        return candidate
    }

    private fun DocumentFile.isSupportedAudio(): Boolean {
        val mime = type?.lowercase()
        if (mime?.startsWith("audio/") == true) return true
        val extension = name?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()
        return extension in SUPPORTED_EXTENSIONS
    }

    private fun extensionFor(mimeType: String): String =
        when (mimeType.lowercase()) {
            "audio/mpeg" -> "mp3"
            "audio/mp4",
            "audio/x-m4a" -> "m4a"
            "audio/flac",
            "audio/x-flac" -> "flac"
            "audio/ogg",
            "application/ogg" -> "ogg"
            "audio/opus" -> "opus"
            "audio/wav",
            "audio/x-wav" -> "wav"
            "audio/aac",
            "audio/aacp" -> "aac"
            else -> "m4a"
        }

    private companion object {
        const val DEFAULT_MIME = "audio/mp4"
        const val MAX_BASE_LENGTH = 80
        val UNSAFE_FILE_NAME = Regex("[\\\\/:*?\"<>|\\p{Cc}]")
        val SUPPORTED_EXTENSIONS =
            setOf("mp3", "m4a", "mp4", "wav", "flac", "ogg", "oga", "opus", "aac", "adts")
    }
}
