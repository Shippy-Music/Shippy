/*
 * Copyright (c) 2026 Auxio Project
 * R16MigrationAppAdapters.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.migration

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import app.shippy.data.backup.R16PortableSettingsProvider
import app.shippy.data.backup.R16SanitizedLastFmConfigProvider
import app.shippy.data.migration.precutover.R16MigrationAssetKind
import app.shippy.data.migration.precutover.R16MigrationAssetVerificationRequest
import app.shippy.data.migration.precutover.R16MigrationAssetVerifier
import app.shippy.data.migration.precutover.R16MigrationPreCutoverFolderCheck
import app.shippy.data.migration.precutover.R16MigrationPreCutoverFolderInspection
import app.shippy.data.migration.precutover.R16MigrationPreCutoverFolderInspectionProvider
import app.shippy.data.migration.precutover.R16MigrationPreCutoverStorage
import app.shippy.data.migration.precutover.R16MigrationVerifiedAsset
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.music.MusicSettings
import org.oxycblt.auxio.music.locations.LocationMode
import org.oxycblt.auxio.shippy.download.DownloadDestination
import org.oxycblt.auxio.shippy.download.DownloadDestinationSettings
import org.oxycblt.auxio.shippy.lastfm.LastFmCredentialRepository
import org.oxycblt.auxio.shippy.r16.backup.R16PortableSettingsAdapter
import org.oxycblt.auxio.shippy.r16.backup.R16SanitizedLastFmConfigAdapter

/** The two mandatory M13 recovery-archive coverage providers, prepared off the main thread. */
internal data class R16MigrationBackupCoverage(
    val portableSettingsProvider: R16PortableSettingsProvider,
    val sanitizedLastFmConfigProvider: R16SanitizedLastFmConfigProvider,
)

/** Reuses the app allowlist and encrypted Last.fm boundary without exporting credentials. */
internal class R16MigrationBackupCoverageAdapter(
    private val context: Context,
    private val credentials: LastFmCredentialRepository,
) {
    suspend fun create(): R16MigrationBackupCoverage =
        R16MigrationBackupCoverage(
            portableSettingsProvider = R16PortableSettingsAdapter(context),
            sanitizedLastFmConfigProvider =
                R16SanitizedLastFmConfigAdapter.fromRepository(credentials),
        )
}

/**
 * Stable app-private paths for the pre-cutover state and recovery artifacts.
 *
 * The directory is deliberately under [Context.noBackupFilesDir]: it survives process death and is
 * not treated as a cloud-backup payload. The legacy database itself remains the existing R15.3
 * database and is never copied or deleted by this adapter.
 */
public class R16MigrationStorageAdapter
internal constructor(private val rootDirectory: File, private val legacyDatabase: File) {
    public constructor(
        context: Context,
        legacyDatabaseName: String = LEGACY_DATABASE_NAME,
    ) : this(
        rootDirectory = File(context.applicationContext.noBackupFilesDir, ROOT_DIRECTORY_NAME),
        legacyDatabase = context.applicationContext.getDatabasePath(legacyDatabaseName),
    )

    internal val rootDirectoryPath: File
        get() = rootDirectory

    internal val legacyDatabasePath: File
        get() = legacyDatabase

    /** Path-only seam for the process gate; this does not create the bootstrap file. */
    public val bootstrapPath: File
        get() = File(rootDirectory, BOOTSTRAP_FILE_NAME)

    public val snapshotDirectoryPath: File
        get() = File(rootDirectory, SNAPSHOT_DIRECTORY_NAME)

    public val recoveryArchivePath: File
        get() = File(rootDirectory, RECOVERY_ARCHIVE_NAME)

    /** Whether any durable migration attempt marker exists, including a corrupt/empty directory. */
    public fun migrationAttemptExists(): Boolean = bootstrapPath.exists()

    /** Creates only the required directories; the bootstrap/archive files are created by data. */
    public fun storage(): R16MigrationPreCutoverStorage {
        requireDirectory(rootDirectory)
        val snapshotDirectory = File(rootDirectory, SNAPSHOT_DIRECTORY_NAME)
        requireDirectory(snapshotDirectory)
        return R16MigrationPreCutoverStorage(
            bootstrapFile = File(rootDirectory, BOOTSTRAP_FILE_NAME),
            legacyDatabase = legacyDatabase,
            snapshotDirectory = snapshotDirectory,
            recoveryArchive = File(rootDirectory, RECOVERY_ARCHIVE_NAME),
        )
    }

    private companion object {
        const val LEGACY_DATABASE_NAME = "shippy.db"
        const val ROOT_DIRECTORY_NAME = "shippy/r16-precutover"
        const val SNAPSHOT_DIRECTORY_NAME = "legacy-v10-snapshot"
        const val BOOTSTRAP_FILE_NAME = "bootstrap-v1.json"
        const val RECOVERY_ARCHIVE_NAME = "shippy-r16-recovery-v1.zip"

        fun requireDirectory(directory: File) {
            if (!directory.isDirectory && !directory.mkdirs()) {
                error("Unable to create migration directory")
            }
        }
    }
}

/** Result of checking one configured tree root without enumerating its children. */
internal data class R16MigrationFolderProbeResult(
    val exists: Boolean,
    val readable: Boolean,
    val writable: Boolean,
    val persistedReadGrant: Boolean,
    val persistedWriteGrant: Boolean,
)

internal fun interface R16MigrationFolderProbe {
    fun inspect(uriString: String): R16MigrationFolderProbeResult
}

/**
 * Adapts the existing MusicSettings and download-destination settings to M0's bounded inspection.
 * It checks only configured roots and grants; [SafDownloadStorage] remains the owner of any
 * recursive download reconciliation and is intentionally not invoked here.
 */
public class R16MigrationFolderInspectionAdapter
internal constructor(
    private val locationMode: () -> LocationMode,
    private val safSourceUris: () -> List<String>,
    private val downloadDestination: () -> DownloadDestination?,
    private val probe: R16MigrationFolderProbe,
) : R16MigrationPreCutoverFolderInspectionProvider {
    public constructor(
        context: Context,
        musicSettings: MusicSettings,
        downloadDestinationSettings: DownloadDestinationSettings,
    ) : this(
        locationMode = { musicSettings.locationMode },
        safSourceUris = { musicSettings.safQuery.source.map { it.uri.toString() } },
        downloadDestination = { downloadDestinationSettings.destination },
        probe = AndroidR16MigrationFolderProbe(context.applicationContext),
    )

    override suspend fun inspect(): R16MigrationPreCutoverFolderInspection =
        withContext(Dispatchers.IO) {
            val checks = ArrayList<R16MigrationPreCutoverFolderCheck>(MAX_FOLDER_CHECKS)
            val warnings = LinkedHashSet<String>()
            val mode = locationMode()
            val sourceUris = safSourceUris().distinct()

            if (mode == LocationMode.SAF) {
                if (sourceUris.isEmpty()) {
                    checks +=
                        R16MigrationPreCutoverFolderCheck(
                            label = MUSIC_SOURCE_LABEL,
                            exists = false,
                            readable = false,
                            writable = false,
                            required = true,
                        )
                    warnings += WARNING_MUSIC_SOURCE_MISSING
                } else {
                    sourceUris.take(MAX_SOURCE_CHECKS).forEachIndexed { index, uri ->
                        val result = probe.inspect(uri)
                        checks += result.toCheck("music-saf-source-$index", required = true)
                        result.warningFor(MUSIC_SOURCE_WARNING_PREFIX)?.let(warnings::add)
                    }
                    if (sourceUris.size > MAX_SOURCE_CHECKS) {
                        warnings += WARNING_MUSIC_SOURCE_TRUNCATED
                    }
                }
            } else {
                // MediaStore owns its own provider permissions; there is no SAF root to inspect.
                checks +=
                    R16MigrationPreCutoverFolderCheck(
                        label = MUSIC_SOURCE_LABEL,
                        exists = true,
                        readable = true,
                        writable = false,
                        required = false,
                    )
            }

            val destination = downloadDestination()
            if (destination == null) {
                checks +=
                    R16MigrationPreCutoverFolderCheck(
                        label = DOWNLOAD_DESTINATION_LABEL,
                        exists = false,
                        readable = false,
                        writable = false,
                        required = false,
                    )
                warnings += WARNING_DOWNLOAD_DESTINATION_NOT_SELECTED
            } else {
                val result = probe.inspect(destination.treeUri)
                checks +=
                    result.toCheck(
                        label = DOWNLOAD_DESTINATION_LABEL,
                        // A download folder is optional; an unavailable one must be reported but
                        // cannot make a migration with no verified downloads unsafe by itself.
                        required = false,
                    )
                result.warningFor(DOWNLOAD_WARNING_PREFIX)?.let(warnings::add)
            }

            R16MigrationPreCutoverFolderInspection(
                checks = checks,
                warningCodes = warnings.take(MAX_WARNING_CODES),
            )
        }

    private companion object {
        const val MAX_FOLDER_CHECKS = 64
        const val MAX_SOURCE_CHECKS = MAX_FOLDER_CHECKS - 2
        const val MAX_WARNING_CODES = 64
        const val MUSIC_SOURCE_LABEL = "music-saf-source"
        const val DOWNLOAD_DESTINATION_LABEL = "download-destination"
        const val MUSIC_SOURCE_WARNING_PREFIX = "music_saf_source"
        const val DOWNLOAD_WARNING_PREFIX = "download_destination"
        const val WARNING_MUSIC_SOURCE_MISSING = "music_saf_source_missing"
        const val WARNING_MUSIC_SOURCE_TRUNCATED = "music_saf_source_truncated"
        const val WARNING_DOWNLOAD_DESTINATION_NOT_SELECTED = "download_destination_not_selected"

        fun R16MigrationFolderProbeResult.toCheck(
            label: String,
            required: Boolean,
        ): R16MigrationPreCutoverFolderCheck =
            R16MigrationPreCutoverFolderCheck(
                label = label,
                exists = exists,
                readable = readable,
                writable = writable,
                required = required,
            )

        fun R16MigrationFolderProbeResult.warningFor(prefix: String): String? =
            when {
                !persistedReadGrant -> "${prefix}_permission_revoked"
                !exists -> "${prefix}_missing"
                !readable -> "${prefix}_not_readable"
                prefix == DOWNLOAD_WARNING_PREFIX && (!persistedWriteGrant || !writable) ->
                    "${prefix}_not_writable"
                else -> null
            }
    }
}

private class AndroidR16MigrationFolderProbe(context: Context) : R16MigrationFolderProbe {
    private val applicationContext = context.applicationContext
    private val resolver: ContentResolver
        get() = applicationContext.contentResolver

    override fun inspect(uriString: String): R16MigrationFolderProbeResult {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
        if (uri == null) {
            return R16MigrationFolderProbeResult(
                exists = false,
                readable = false,
                writable = false,
                persistedReadGrant = false,
                persistedWriteGrant = false,
            )
        }
        val persistedReadGrant =
            uri.scheme != ContentResolver.SCHEME_CONTENT ||
                resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        val persistedWriteGrant =
            uri.scheme != ContentResolver.SCHEME_CONTENT ||
                resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        val root = runCatching { DocumentFile.fromTreeUri(applicationContext, uri) }.getOrNull()
        return R16MigrationFolderProbeResult(
            exists = root?.exists() == true,
            readable = persistedReadGrant && root?.canRead() == true,
            writable = persistedReadGrant && persistedWriteGrant && root?.canWrite() == true,
            persistedReadGrant = persistedReadGrant,
            persistedWriteGrant = persistedWriteGrant,
        )
    }
}

internal fun interface R16MigrationAssetProbe {
    fun verify(locator: String, kind: R16MigrationAssetKind): R16MigrationVerifiedAsset?
}

/**
 * Verifies only the locator supplied by the data layer. DOWNLOAD_ARTIFACT is already restricted to
 * the importer's finalized artifactUri; pendingUri is never passed through this adapter.
 */
public class R16MigrationAssetVerifierAdapter
internal constructor(private val probe: R16MigrationAssetProbe) : R16MigrationAssetVerifier {
    public constructor(context: Context) : this(AndroidR16MigrationAssetProbe(context))

    override suspend fun verify(
        request: R16MigrationAssetVerificationRequest
    ): R16MigrationVerifiedAsset? =
        withContext(Dispatchers.IO) {
            val locator = request.locator?.takeIf { it.isNotBlank() } ?: return@withContext null
            val scheme = locator.substringBefore(':', missingDelimiterValue = "")
            if (scheme.isBlank()) return@withContext null
            // expectedLength is deliberately not compared here. The data importer owns the
            // declared-vs-observed length decision and receives the exact observed value.
            probe.verify(locator, request.kind)
        }
}

private class AndroidR16MigrationAssetProbe(context: Context) : R16MigrationAssetProbe {
    private val applicationContext = context.applicationContext
    private val resolver: ContentResolver
        get() = applicationContext.contentResolver

    override fun verify(locator: String, kind: R16MigrationAssetKind): R16MigrationVerifiedAsset? {
        val uri = runCatching { Uri.parse(locator) }.getOrNull() ?: return null
        return when (uri.scheme?.lowercase()) {
            ContentResolver.SCHEME_CONTENT -> verifyContent(uri)
            FILE_SCHEME -> verifyFile(uri)
            else -> null
        }
    }

    private fun verifyFile(uri: Uri): R16MigrationVerifiedAsset? {
        val file = uri.path?.let(::File) ?: return null
        if (!file.isFile || !file.canRead()) return null
        val readable = runCatching { FileInputStream(file).use {} }.isSuccess
        if (!readable) return null
        return R16MigrationVerifiedAsset(
            locationType = FILE_URI_LOCATION_TYPE,
            location = uri.toString(),
            displayName = file.name.takeIf(String::isNotBlank),
            contentLength = file.length(),
        )
    }

    private fun verifyContent(uri: Uri): R16MigrationVerifiedAsset? {
        val mediaStore = uri.authority == MediaStore.AUTHORITY
        val documentUri = isDocumentUri(uri)
        if (!mediaStore && !hasPersistedReadGrant(uri)) return null
        if (
            documentUri &&
                !mediaStore &&
                runCatching { DocumentFile.fromSingleUri(applicationContext, uri)?.isFile == true }
                    .getOrDefault(false) == false
        ) {
            return null
        }

        val descriptor =
            runCatching { resolver.openAssetFileDescriptor(uri, "r") }.getOrNull() ?: return null
        val descriptorLength = descriptor.use { it.length.takeIf { length -> length >= 0L } }
        val metadata = queryMetadata(uri, mediaStore)
        val length = descriptorLength ?: metadata.contentLength ?: return null
        if (length < 0L) return null

        val documentId =
            if (documentUri) {
                runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            } else {
                null
            }
        return R16MigrationVerifiedAsset(
            locationType =
                when {
                    mediaStore -> MEDIASTORE_LOCATION_TYPE
                    documentUri -> SAF_DOCUMENT_LOCATION_TYPE
                    else -> CONTENT_URI_LOCATION_TYPE
                },
            location = uri.toString(),
            documentId = documentId,
            mediaStoreId =
                metadata.mediaStoreId ?: uri.lastPathSegment?.toLongOrNull()?.takeIf { mediaStore },
            displayName = metadata.displayName,
            contentLength = length,
        )
    }

    private fun hasPersistedReadGrant(uri: Uri): Boolean {
        val permissions = resolver.persistedUriPermissions
        if (permissions.any { it.uri == uri && it.isReadPermission }) return true
        val authority = uri.authority ?: return false
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        if (documentId == null) return false
        return permissions.any {
            it.isReadPermission &&
                it.uri.authority == authority &&
                runCatching { DocumentsContract.isTreeUri(it.uri) }.getOrDefault(false) &&
                runCatching { DocumentsContract.getTreeDocumentId(it.uri) }
                    .getOrNull()
                    ?.let { treeId -> documentId == treeId || documentId.startsWith("$treeId/") } ==
                    true
        }
    }

    private fun isDocumentUri(uri: Uri): Boolean =
        runCatching { DocumentsContract.isDocumentUri(applicationContext, uri) }
            .getOrDefault(
                uri.pathSegments.any { segment -> segment == "document" || segment == "tree" }
            )

    private fun queryMetadata(uri: Uri, mediaStore: Boolean): R16MigrationAssetMetadata {
        val projection =
            if (mediaStore) {
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE,
                )
            } else {
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            }
        return runCatching {
                resolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use R16MigrationAssetMetadata()
                    R16MigrationAssetMetadata(
                        displayName = cursor.string(OpenableColumns.DISPLAY_NAME),
                        contentLength = cursor.long(OpenableColumns.SIZE),
                        mediaStoreId = cursor.long(MediaStore.MediaColumns._ID)?.takeIf { it >= 0L },
                    )
                } ?: R16MigrationAssetMetadata()
            }
            .getOrDefault(R16MigrationAssetMetadata())
    }

    private companion object {
        const val FILE_URI_LOCATION_TYPE = "FILE_URI"
        const val FILE_SCHEME = "file"
        const val CONTENT_URI_LOCATION_TYPE = "CONTENT_URI"
        const val SAF_DOCUMENT_LOCATION_TYPE = "SAF_DOCUMENT"
        const val MEDIASTORE_LOCATION_TYPE = "MEDIASTORE"

        fun android.database.Cursor.string(column: String): String? {
            val index = getColumnIndex(column)
            return index.takeIf { it >= 0 && !isNull(it) }?.let(::getString)
        }

        fun android.database.Cursor.long(column: String): Long? {
            val index = getColumnIndex(column)
            return index.takeIf { it >= 0 && !isNull(it) }?.let(::getLong)
        }
    }
}

private data class R16MigrationAssetMetadata(
    val displayName: String? = null,
    val contentLength: Long? = null,
    val mediaStoreId: Long? = null,
)
