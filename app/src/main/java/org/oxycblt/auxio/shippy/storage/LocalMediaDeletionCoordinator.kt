/*
 * Copyright (c) 2026 Auxio Project
 * LocalMediaDeletionCoordinator.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.storage

import android.app.RecoverableSecurityException
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.Track

sealed interface LocalMediaDeletionResult {
    data object Deleted : LocalMediaDeletionResult

    data class ConsentRequired(val request: IntentSenderRequest) : LocalMediaDeletionResult

    data object MissingLocalObject : LocalMediaDeletionResult

    data object Failed : LocalMediaDeletionResult
}

/**
 * Keeps destructive local-file deletion separate from queue, playlist, cache, and download state.
 */
class LocalMediaDeletionCoordinator
@Inject
constructor(@ApplicationContext private val context: Context) {
    fun delete(track: Track): LocalMediaDeletionResult {
        val uri =
            track.candidates
                .firstOrNull { it.kind == CandidateKind.LOCAL }
                ?.locator
                ?.let(Uri::parse)
                ?.takeIf { it.scheme == "content" }
                ?: return LocalMediaDeletionResult.MissingLocalObject
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                try {
                    val pending =
                        MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                    LocalMediaDeletionResult.ConsentRequired(
                        IntentSenderRequest.Builder(pending.intentSender).build()
                    )
                } catch (_: SecurityException) {
                    LocalMediaDeletionResult.Failed
                } catch (_: RuntimeException) {
                    LocalMediaDeletionResult.Failed
                }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> deleteApi29(uri)
            else -> deleteDirectly(uri)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun deleteApi29(uri: Uri): LocalMediaDeletionResult =
        try {
            if (context.contentResolver.delete(uri, null, null) > 0) {
                LocalMediaDeletionResult.Deleted
            } else {
                LocalMediaDeletionResult.Failed
            }
        } catch (security: RecoverableSecurityException) {
            LocalMediaDeletionResult.ConsentRequired(
                IntentSenderRequest.Builder(security.userAction.actionIntent.intentSender).build()
            )
        } catch (_: SecurityException) {
            LocalMediaDeletionResult.Failed
        } catch (_: RuntimeException) {
            LocalMediaDeletionResult.Failed
        }

    private fun deleteDirectly(uri: Uri): LocalMediaDeletionResult =
        try {
            if (context.contentResolver.delete(uri, null, null) > 0) {
                LocalMediaDeletionResult.Deleted
            } else {
                LocalMediaDeletionResult.Failed
            }
        } catch (_: SecurityException) {
            LocalMediaDeletionResult.Failed
        } catch (_: RuntimeException) {
            LocalMediaDeletionResult.Failed
        }
}
