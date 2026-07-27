/*
 * Copyright (c) 2026 Auxio Project
 * CrewInviteQr.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal object CrewInviteQr {
    private const val MAX_INVITE_BYTES = 2_048
    private const val MIN_SIZE_PX = 128
    private const val MAX_SIZE_PX = 1_024

    fun createBitmap(inviteLink: String, sizePx: Int): Bitmap? {
        if (
            inviteLink.isBlank() || inviteLink.toByteArray(Charsets.UTF_8).size > MAX_INVITE_BYTES
        ) {
            return null
        }
        val boundedSize = sizePx.coerceIn(MIN_SIZE_PX, MAX_SIZE_PX)
        val matrix =
            runCatching {
                    QRCodeWriter()
                        .encode(
                            inviteLink,
                            BarcodeFormat.QR_CODE,
                            boundedSize,
                            boundedSize,
                            mapOf(
                                EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name(),
                                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                                EncodeHintType.MARGIN to 2,
                            ),
                        )
                }
                .getOrNull() ?: return null
        return Bitmap.createBitmap(boundedSize, boundedSize, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until boundedSize) {
                for (x in 0 until boundedSize) {
                    setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
        }
    }
}
