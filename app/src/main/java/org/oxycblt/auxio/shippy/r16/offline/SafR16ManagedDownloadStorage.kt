/*
 * Copyright (c) 2026 Auxio Project
 * SafR16ManagedDownloadStorage.kt is part of Auxio.
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
import android.net.Uri
import app.shippy.data.offline.R16ManagedDownloadStorage
import app.shippy.data.offline.R16ManagedDownloadTarget
import app.shippy.data.offline.R16PhysicalRemovalResult
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import org.oxycblt.auxio.shippy.download.SafDownloadStorage
import org.oxycblt.auxio.shippy.download.SafManagedDownloadDeletionResult

/** Android/SAF implementation of the data-layer managed-download deletion boundary. */
class SafR16ManagedDownloadStorage
internal constructor(
    private val deleteDocument: suspend (String) -> SafManagedDownloadDeletionResult
) : R16ManagedDownloadStorage {
    @Inject constructor(storage: SafDownloadStorage) : this(storage::deleteManagedDownload)

    override suspend fun delete(target: R16ManagedDownloadTarget): R16PhysicalRemovalResult {
        val contentUri = target.contentUriOrNull() ?: return R16PhysicalRemovalResult.FAILED
        return try {
            when (deleteDocument(contentUri)) {
                SafManagedDownloadDeletionResult.DELETED -> R16PhysicalRemovalResult.DELETED
                SafManagedDownloadDeletionResult.ALREADY_MISSING ->
                    R16PhysicalRemovalResult.ALREADY_MISSING
                SafManagedDownloadDeletionResult.FAILED -> R16PhysicalRemovalResult.FAILED
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            R16PhysicalRemovalResult.FAILED
        }
    }

    private fun R16ManagedDownloadTarget.contentUriOrNull(): String? {
        if (locationType != CONTENT_URI_LOCATION_TYPE) return null
        val value = location.opaqueHandle
        if (value.isBlank() || value != value.trim()) return null
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        if (uri.authority.isNullOrBlank()) return null
        return value
    }

    private companion object {
        const val CONTENT_URI_LOCATION_TYPE = "CONTENT_URI"
    }
}
