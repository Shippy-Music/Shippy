/*
 * Copyright (c) 2026 Auxio Project
 * DownloadTransferStaging.kt is part of Auxio.
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
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * App-private publication boundary for permanent downloads.
 *
 * MediaStore/SAF must never observe a file while network bytes are still arriving. A completed
 * stage is copied into the selected destination only after length verification.
 */
@Singleton
class DownloadTransferStaging internal constructor(private val directory: File) {
    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(File(context.noBackupFilesDir, DIRECTORY_NAME))

    suspend fun openOutput(jobId: DownloadJobId): OutputStream =
        withContext(Dispatchers.IO) {
            check(directory.exists() || directory.mkdirs()) {
                "Could not create private download staging"
            }
            val target = file(jobId)
            target.delete()
            FileOutputStream(target)
        }

    suspend fun verifiedFile(jobId: DownloadJobId, expectedBytes: Long?): File? =
        withContext(Dispatchers.IO) {
            file(jobId).takeIf { candidate ->
                candidate.isFile &&
                    candidate.length() > 0L &&
                    (expectedBytes == null || candidate.length() == expectedBytes)
            }
        }

    suspend fun copyTo(source: File, output: OutputStream) =
        withContext(Dispatchers.IO) {
            FileInputStream(source).use { input ->
                output.use { destination ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        destination.write(buffer, 0, read)
                    }
                    destination.flush()
                }
            }
        }

    suspend fun cleanup(jobId: DownloadJobId) = withContext(Dispatchers.IO) { file(jobId).delete() }

    private fun file(jobId: DownloadJobId) = File(directory, "${jobId.value}.stage")

    private companion object {
        const val DIRECTORY_NAME = "download-transfer-staging"
        const val BUFFER_SIZE = 64 * 1024
    }
}
