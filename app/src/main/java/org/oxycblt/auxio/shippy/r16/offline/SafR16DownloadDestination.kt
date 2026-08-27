/*
 * Copyright (c) 2026 Auxio Project
 * SafR16DownloadDestination.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.offline

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.shippy.download.DownloadArtifact
import org.oxycblt.auxio.shippy.download.DownloadJobId
import org.oxycblt.auxio.shippy.download.DownloadStorageFailure
import org.oxycblt.auxio.shippy.download.DownloadTransferStaging
import org.oxycblt.auxio.shippy.download.SafManagedDownloadDeletionResult
import org.oxycblt.auxio.shippy.download.StorageResult
import org.oxycblt.auxio.shippy.download.deleteSafManagedContentUri

/**
 * The exact destination/job/document tuple returned by the R16 SAF owner.
 *
 * The destination identity is deliberately retained beside the pending URI. Callers must supply the
 * same identity on every operation; a pending document from one tree cannot be copied, verified, or
 * cleaned up through another tree.
 */
data class R16PendingDownloadDocument(
    val destinationIdentity: String,
    val jobId: DownloadJobId,
    val contentUri: String,
    val displayName: String,
    val mimeType: String,
) {
    init {
        require(
            destinationIdentity.isNotBlank() && destinationIdentity == destinationIdentity.trim()
        ) {
            "Destination identity must be non-blank and trimmed"
        }
        require(contentUri.isNotBlank() && contentUri == contentUri.trim()) {
            "Pending document URI must be non-blank and trimmed"
        }
        require(displayName.isNotBlank() && displayName == displayName.trim()) {
            "Pending document name must be non-blank and trimmed"
        }
        require(mimeType.isNotBlank() && mimeType == mimeType.trim()) {
            "Pending document MIME type must be non-blank and trimmed"
        }
    }
}

/**
 * Settings-free SAF owner for the R16 permanent-download publication boundary.
 *
 * This adapter receives the persisted tree identity from the R16 download job. It never reads or
 * mutates [org.oxycblt.auxio.shippy.download.DownloadDestinationSettings],
 * [org.oxycblt.auxio.music.MusicSettings], or local-source configuration. The private
 * [DownloadTransferStaging] file is the only source copied into SAF, and it is required to be
 * complete before publication begins.
 */
@Singleton
class SafR16DownloadDestination
@Inject
internal constructor(
    @ApplicationContext private val context: Context,
    private val transferStaging: DownloadTransferStaging,
) {
    private val resolver: ContentResolver
        get() = context.contentResolver

    /** Creates or reuses the exact job-owned child in the supplied persisted tree. */
    suspend fun createPending(
        destinationIdentity: String,
        jobId: DownloadJobId,
        title: String,
        mimeType: String?,
        existingPending: R16PendingDownloadDocument? = null,
    ): StorageResult<R16PendingDownloadDocument> =
        withContext(Dispatchers.IO) {
            val destination =
                when (
                    val checked = validateDestination(destinationIdentity, requireWritable = true)
                ) {
                    is DestinationCheck.Ready -> checked
                    is DestinationCheck.Failure -> return@withContext checked.result
                }
            if (!validJobId(jobId)) {
                return@withContext failure(DownloadStorageFailure.CREATE_FAILED)
            }

            val normalizedMime = normalizeMimeType(mimeType)
            val displayName = r16PendingDownloadName(jobId, title, normalizedMime)
            try {
                if (existingPending != null) {
                    if (existingPending.jobId != jobId) {
                        return@withContext failure(DownloadStorageFailure.CREATE_FAILED)
                    }
                    val existingUri =
                        pendingUri(destinationIdentity, existingPending, destination.treeUri)
                            ?: return@withContext failure(DownloadStorageFailure.CREATE_FAILED)
                    val existingDocument = DocumentFile.fromSingleUri(context, existingUri)
                    if (!isExactPendingDocument(existingDocument, existingPending)) {
                        return@withContext failure(DownloadStorageFailure.CREATE_FAILED)
                    }
                    return@withContext StorageResult.Success(existingPending)
                }

                // Never look up by display name: a same-name user file is not Shippy-owned.
                // createFile either gives us a fresh provider identity or fails closed.
                val document =
                    destination.root.createFile(normalizedMime, displayName)
                        ?: return@withContext failure(DownloadStorageFailure.CREATE_FAILED)
                if (!isExactPendingDocument(document, displayName, jobId)) {
                    runCatchingCancellable { document.delete() }
                    return@withContext failure(DownloadStorageFailure.CREATE_FAILED)
                }
                val pending =
                    R16PendingDownloadDocument(
                        destinationIdentity = destinationIdentity,
                        jobId = jobId,
                        contentUri = document.uri.toString(),
                        displayName = displayName,
                        mimeType = normalizedMime,
                    )
                if (pendingUri(destinationIdentity, pending, destination.treeUri) == null) {
                    runCatchingCancellable { document.delete() }
                    return@withContext failure(DownloadStorageFailure.CREATE_FAILED)
                }
                StorageResult.Success(pending)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failure(DownloadStorageFailure.CREATE_FAILED)
            }
        }

    /**
     * Rehydrates one persisted pending document after process death.
     *
     * Recovery is deliberately exact-URI only. The persisted tree grant, tree/document identity,
     * and provider metadata are rechecked; no destination listing or filename lookup is involved.
     */
    suspend fun recoverPending(
        destinationIdentity: String,
        jobId: DownloadJobId,
        contentUri: String,
    ): StorageResult<R16PendingDownloadDocument> =
        withContext(Dispatchers.IO) {
            if (!validJobId(jobId)) {
                return@withContext failure(DownloadStorageFailure.OPEN_FAILED)
            }
            val destination =
                when (
                    val checked = validateDestination(destinationIdentity, requireWritable = true)
                ) {
                    is DestinationCheck.Ready -> checked
                    is DestinationCheck.Failure -> return@withContext checked.result
                }
            val pendingUri =
                pendingUri(destinationIdentity, jobId, contentUri, destination.treeUri)
                    ?: return@withContext failure(DownloadStorageFailure.OPEN_FAILED)
            try {
                val document =
                    DocumentFile.fromSingleUri(context, pendingUri)
                        ?: return@withContext failure(DownloadStorageFailure.OPEN_FAILED)
                if (!document.exists() || !document.isFile || !document.canRead()) {
                    return@withContext failure(DownloadStorageFailure.OPEN_FAILED)
                }
                val recovered =
                    r16RecoveredPendingDocument(
                        destinationIdentity = destinationIdentity,
                        jobId = jobId,
                        contentUri = contentUri,
                        providerDisplayName = document.name,
                        providerMimeType = resolver.getType(pendingUri),
                    ) ?: return@withContext failure(DownloadStorageFailure.OPEN_FAILED)
                if (!isExactPendingDocument(document, recovered)) {
                    return@withContext failure(DownloadStorageFailure.OPEN_FAILED)
                }
                StorageResult.Success(recovered)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failure(DownloadStorageFailure.OPEN_FAILED)
            }
        }

    /**
     * Copies only the complete private stage for [pending] into its exact SAF document.
     *
     * A failed or cancelled copy removes the partial destination document. Cleanup is shielded from
     * cancellation so a partial file cannot be left looking like a permanent download.
     */
    suspend fun copyStage(
        destinationIdentity: String,
        pending: R16PendingDownloadDocument,
        expectedBytes: Long?,
    ): StorageResult<Unit> =
        withContext(Dispatchers.IO) {
            val destination =
                when (
                    val checked = validateDestination(destinationIdentity, requireWritable = true)
                ) {
                    is DestinationCheck.Ready -> checked
                    is DestinationCheck.Failure -> return@withContext checked.result
                }
            val pendingUri =
                pendingUri(destinationIdentity, pending, destination.treeUri)
                    ?: return@withContext failure(DownloadStorageFailure.OPEN_FAILED)
            try {
                val document = DocumentFile.fromSingleUri(context, pendingUri)
                if (!isExactPendingDocument(document, pending)) {
                    cleanupAfterFailure(destinationIdentity, pending)
                    return@withContext failure(DownloadStorageFailure.OPEN_FAILED)
                }
                val staged = transferStaging.verifiedFile(pending.jobId, expectedBytes)
                if (staged == null) {
                    cleanupAfterFailure(destinationIdentity, pending)
                    return@withContext failure(DownloadStorageFailure.VERIFY_FAILED)
                }
                val output =
                    resolver.openOutputStream(pendingUri, "w")
                        ?: run {
                            cleanupAfterFailure(destinationIdentity, pending)
                            return@withContext failure(DownloadStorageFailure.OPEN_FAILED)
                        }
                copyStage(staged, output)
                StorageResult.Success(Unit)
            } catch (error: CancellationException) {
                cleanupAfterFailure(destinationIdentity, pending)
                throw error
            } catch (_: Exception) {
                cleanupAfterFailure(destinationIdentity, pending)
                failure(DownloadStorageFailure.OPEN_FAILED)
            }
        }

    /** Verifies provider existence, exact declared length, readability, and actual openability. */
    suspend fun verify(
        destinationIdentity: String,
        pending: R16PendingDownloadDocument,
        expectedBytes: Long?,
        verifiedAtEpochMs: Long,
    ): StorageResult<DownloadArtifact> =
        withContext(Dispatchers.IO) {
            val destination =
                when (
                    val checked = validateDestination(destinationIdentity, requireWritable = false)
                ) {
                    is DestinationCheck.Ready -> checked
                    is DestinationCheck.Failure -> return@withContext checked.result
                }
            val pendingUri =
                pendingUri(destinationIdentity, pending, destination.treeUri)
                    ?: return@withContext failure(DownloadStorageFailure.VERIFY_FAILED)
            if (verifiedAtEpochMs < 0L || expectedBytes?.let { it < 0L } == true) {
                return@withContext failure(DownloadStorageFailure.VERIFY_FAILED)
            }
            try {
                val document =
                    DocumentFile.fromSingleUri(context, pendingUri)
                        ?: return@withContext failure(DownloadStorageFailure.VERIFY_FAILED)
                if (!isExactPendingDocument(document, pending) || document.canRead().not()) {
                    return@withContext failure(DownloadStorageFailure.VERIFY_FAILED)
                }
                val length = document.length()
                if (length <= 0L || (expectedBytes != null && length != expectedBytes)) {
                    return@withContext failure(DownloadStorageFailure.VERIFY_FAILED)
                }
                val input =
                    resolver.openInputStream(pendingUri)
                        ?: return@withContext failure(DownloadStorageFailure.VERIFY_FAILED)
                input.use { stream ->
                    if (stream.read() < 0) {
                        return@withContext failure(DownloadStorageFailure.VERIFY_FAILED)
                    }
                }
                StorageResult.Success(
                    DownloadArtifact(
                        contentUri = pending.contentUri,
                        contentLength = length,
                        mimeType = document.type ?: pending.mimeType,
                        verifiedAtEpochMs = verifiedAtEpochMs,
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failure(DownloadStorageFailure.VERIFY_FAILED)
            }
        }

    /** Removes only the exact pending document and never changes destination configuration. */
    suspend fun cleanup(
        destinationIdentity: String,
        pending: R16PendingDownloadDocument,
    ): SafManagedDownloadDeletionResult =
        withContext(Dispatchers.IO) {
            val destination =
                when (
                    val checked = validateDestination(destinationIdentity, requireWritable = true)
                ) {
                    is DestinationCheck.Ready -> checked
                    is DestinationCheck.Failure ->
                        return@withContext SafManagedDownloadDeletionResult.FAILED
                }
            val pendingUri =
                pendingUri(destinationIdentity, pending, destination.treeUri)
                    ?: return@withContext SafManagedDownloadDeletionResult.FAILED
            try {
                val document = DocumentFile.fromSingleUri(context, pendingUri)
                if (document?.exists() == true && !isExactPendingDocument(document, pending)) {
                    return@withContext SafManagedDownloadDeletionResult.FAILED
                }
                deleteSafManagedContentUri(resolver, pendingUri)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                SafManagedDownloadDeletionResult.FAILED
            }
        }

    /**
     * Removes one persisted pending URI after a crash without reconstructing provider metadata.
     *
     * A previously completed delete is reported as
     * [SafManagedDownloadDeletionResult.ALREADY_MISSING] so durable state can be cleared safely on
     * retry.
     */
    suspend fun cleanupPersistedPending(
        destinationIdentity: String,
        jobId: DownloadJobId,
        contentUri: String,
    ): SafManagedDownloadDeletionResult =
        withContext(Dispatchers.IO) {
            if (!validJobId(jobId)) {
                return@withContext SafManagedDownloadDeletionResult.FAILED
            }
            val destination =
                when (
                    val checked = validateDestination(destinationIdentity, requireWritable = true)
                ) {
                    is DestinationCheck.Ready -> checked
                    is DestinationCheck.Failure ->
                        return@withContext SafManagedDownloadDeletionResult.FAILED
                }
            val pendingUri =
                pendingUri(destinationIdentity, jobId, contentUri, destination.treeUri)
                    ?: return@withContext SafManagedDownloadDeletionResult.FAILED
            try {
                deleteSafManagedContentUri(resolver, pendingUri)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                SafManagedDownloadDeletionResult.FAILED
            }
        }

    private suspend fun cleanupAfterFailure(
        destinationIdentity: String,
        pending: R16PendingDownloadDocument,
    ) {
        withContext(NonCancellable) {
            try {
                cleanup(destinationIdentity, pending)
            } catch (_: CancellationException) {
                // Preserve the cancellation that caused the failed copy.
            } catch (_: Exception) {
                // Cleanup is best effort; the original copy failure remains authoritative.
            }
        }
    }

    private suspend fun copyStage(staged: java.io.File, output: OutputStream) {
        transferStaging.copyTo(staged, output)
    }

    private fun validateDestination(
        destinationIdentity: String,
        requireWritable: Boolean,
    ): DestinationCheck {
        if (destinationIdentity.isBlank() || destinationIdentity != destinationIdentity.trim()) {
            return DestinationCheck.Failure(failure(DownloadStorageFailure.PERMISSION_REVOKED))
        }
        val treeUri =
            runCatchingCancellable { Uri.parse(destinationIdentity) }.getOrNull()
                ?: return DestinationCheck.Failure(
                    failure(DownloadStorageFailure.PERMISSION_REVOKED)
                )
        if (treeUri.scheme != ContentResolver.SCHEME_CONTENT || treeUri.authority.isNullOrBlank()) {
            return DestinationCheck.Failure(failure(DownloadStorageFailure.PERMISSION_REVOKED))
        }
        if (!runCatchingCancellable { DocumentsContract.isTreeUri(treeUri) }.getOrDefault(false)) {
            return DestinationCheck.Failure(failure(DownloadStorageFailure.NOT_A_TREE))
        }
        val permission =
            runCatchingCancellable {
                    resolver.persistedUriPermissions.firstOrNull { it.uri == treeUri }
                }
                .getOrNull()
                ?: return DestinationCheck.Failure(
                    failure(DownloadStorageFailure.PERMISSION_REVOKED)
                )
        if (!permission.isReadPermission || !permission.isWritePermission) {
            return DestinationCheck.Failure(failure(DownloadStorageFailure.PERMISSION_REVOKED))
        }
        val root =
            runCatchingCancellable { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
                ?: return DestinationCheck.Failure(failure(DownloadStorageFailure.NOT_A_TREE))
        return try {
            when {
                !root.exists() || !root.isDirectory ->
                    DestinationCheck.Failure(failure(DownloadStorageFailure.NOT_A_TREE))
                !root.canRead() ->
                    DestinationCheck.Failure(failure(DownloadStorageFailure.NOT_READABLE))
                requireWritable && !root.canWrite() ->
                    DestinationCheck.Failure(failure(DownloadStorageFailure.NOT_WRITABLE))
                else -> DestinationCheck.Ready(treeUri, root)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: SecurityException) {
            DestinationCheck.Failure(failure(DownloadStorageFailure.PERMISSION_REVOKED))
        } catch (_: Exception) {
            DestinationCheck.Failure(failure(DownloadStorageFailure.NOT_A_TREE))
        }
    }

    private fun pendingUri(
        destinationIdentity: String,
        pending: R16PendingDownloadDocument,
        treeUri: Uri,
    ): Uri? {
        if (
            pending.destinationIdentity != destinationIdentity ||
                !pending.displayName.contains("[shippy-${pending.jobId.value}]")
        ) {
            return null
        }
        return pendingUri(destinationIdentity, pending.jobId, pending.contentUri, treeUri)
    }

    private fun pendingUri(
        destinationIdentity: String,
        jobId: DownloadJobId,
        contentUri: String,
        treeUri: Uri,
    ): Uri? {
        if (
            destinationIdentity.isBlank() ||
                destinationIdentity != destinationIdentity.trim() ||
                !validJobId(jobId) ||
                contentUri.isBlank() ||
                contentUri != contentUri.trim()
        ) {
            return null
        }
        val uri = runCatchingCancellable { Uri.parse(contentUri) }.getOrNull() ?: return null
        return uri.takeIf { r16PendingUriMatchesDestination(treeUri, it) }
    }

    private fun isExactPendingDocument(
        document: DocumentFile?,
        pending: R16PendingDownloadDocument,
    ): Boolean = isExactPendingDocument(document, pending.displayName, pending.jobId)

    private fun isExactPendingDocument(
        document: DocumentFile?,
        displayName: String,
        jobId: DownloadJobId,
    ): Boolean =
        document?.exists() == true &&
            document.isFile &&
            document.name == displayName &&
            displayName.contains("[shippy-${jobId.value}]")

    private sealed interface DestinationCheck {
        data class Ready(val treeUri: Uri, val root: DocumentFile) : DestinationCheck

        data class Failure(val result: StorageResult.Failure) : DestinationCheck
    }

    private companion object {
        const val DEFAULT_MIME = "audio/mp4"

        inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
            try {
                Result.success(block())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }

        fun failure(reason: DownloadStorageFailure): StorageResult.Failure =
            StorageResult.Failure(reason)

        fun validJobId(jobId: DownloadJobId): Boolean = r16PathSafeDownloadJobId(jobId)

        fun normalizeMimeType(mimeType: String?): String =
            mimeType?.trim()?.takeIf { it.isNotBlank() && '/' in it } ?: DEFAULT_MIME
    }
}

/** Reconstructs pending metadata supplied by one exact provider document. */
internal fun r16RecoveredPendingDocument(
    destinationIdentity: String,
    jobId: DownloadJobId,
    contentUri: String,
    providerDisplayName: String?,
    providerMimeType: String?,
): R16PendingDownloadDocument? {
    if (
        destinationIdentity.isBlank() ||
            destinationIdentity != destinationIdentity.trim() ||
            !r16PathSafeDownloadJobId(jobId) ||
            contentUri.isBlank() ||
            contentUri != contentUri.trim()
    ) {
        return null
    }
    val displayName =
        providerDisplayName?.takeIf {
            it.isNotBlank() && it == it.trim() && it.contains("[shippy-${jobId.value}]")
        } ?: return null
    val mimeType =
        providerMimeType?.takeIf { it.isNotBlank() && it == it.trim() && '/' in it } ?: return null
    return runCatching {
            R16PendingDownloadDocument(
                destinationIdentity = destinationIdentity,
                jobId = jobId,
                contentUri = contentUri,
                displayName = displayName,
                mimeType = mimeType,
            )
        }
        .getOrNull()
}

internal fun r16PathSafeDownloadJobId(jobId: DownloadJobId): Boolean =
    jobId.value.isNotBlank() &&
        jobId.value == jobId.value.trim() &&
        jobId.value.none { it == '/' || it == '\\' || it.isISOControl() }

/**
 * Returns whether [pendingUri] is a direct document child of the exact SAF tree [destinationUri].
 *
 * A same-authority URI is not sufficient: providers can expose multiple trees under one authority.
 * The tree document ID is therefore compared exactly, while a plain `/document/...` URI is rejected
 * because it no longer proves that the persisted destination tree owns the document.
 */
internal fun r16PendingUriMatchesDestination(destinationUri: Uri, pendingUri: Uri): Boolean {
    if (
        destinationUri.scheme != ContentResolver.SCHEME_CONTENT ||
            pendingUri.scheme != ContentResolver.SCHEME_CONTENT ||
            destinationUri.authority.isNullOrBlank() ||
            destinationUri.authority != pendingUri.authority
    ) {
        return false
    }
    return try {
        if (
            !DocumentsContract.isTreeUri(destinationUri) || !DocumentsContract.isTreeUri(pendingUri)
        ) {
            return false
        }
        val destinationTreeId = DocumentsContract.getTreeDocumentId(destinationUri)
        val pendingTreeId = DocumentsContract.getTreeDocumentId(pendingUri)
        val pendingDocumentId = DocumentsContract.getDocumentId(pendingUri)
        destinationTreeId.isNotBlank() &&
            pendingTreeId == destinationTreeId &&
            pendingDocumentId.isNotBlank() &&
            pendingDocumentId != pendingTreeId
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }
}

/** Stable, readable, and collision-resistant within one destination tree. */
internal fun r16PendingDownloadName(jobId: DownloadJobId, title: String, mimeType: String): String {
    val safeTitle =
        title.replace(Regex("[\\\\/:*?\"<>|\\p{Cc}]"), "_").trim(' ', '.', '_').take(80).ifBlank {
            "Track"
        }
    val extension =
        when (mimeType.lowercase()) {
            "audio/mpeg" -> "mp3"
            "audio/mp4",
            "audio/x-m4a" -> "m4a"
            "audio/flac",
            "audio/x-flac" -> "flac"
            "audio/webm",
            "video/webm" -> "webm"
            "audio/x-matroska",
            "video/x-matroska" -> "mka"
            "audio/ogg",
            "application/ogg" -> "ogg"
            "audio/opus" -> "opus"
            "audio/wav",
            "audio/x-wav" -> "wav"
            "audio/aac",
            "audio/aacp" -> "aac"
            else -> "m4a"
        }
    return "$safeTitle [shippy-${jobId.value}].$extension"
}
