/*
 * Copyright (c) 2026 Auxio Project
 * DownloadDestinationLocalSourcePlan.kt is part of Auxio.
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

/**
 * Deterministically reconciles a download destination with Auxio's Local SAF sources.
 *
 * [autoAddedDestinationUri] is deliberately a single URI: Shippy only owns the Local source it
 * added for the current download destination and must not remove user sources.
 */
data class DownloadDestinationLocalSourcePlan(
    val sourceUris: List<String>,
    val autoAddedDestinationUri: String?,
    val sourceChanged: Boolean,
) {
    companion object {
        fun create(
            existingSourceUris: List<String>,
            previousDestinationUri: String?,
            autoAddedDestinationUri: String?,
            newDestinationUri: String,
        ): DownloadDestinationLocalSourcePlan {
            val previousWasAutoAdded =
                previousDestinationUri != newDestinationUri &&
                    autoAddedDestinationUri == previousDestinationUri
            val withoutPrevious =
                if (previousWasAutoAdded) {
                    existingSourceUris.filterNot { it == previousDestinationUri }
                } else {
                    existingSourceUris
                }
            val addNewSource = newDestinationUri !in withoutPrevious
            val sourceUris =
                if (addNewSource) withoutPrevious + newDestinationUri else withoutPrevious
            return DownloadDestinationLocalSourcePlan(
                sourceUris = sourceUris,
                autoAddedDestinationUri =
                    when {
                        addNewSource -> newDestinationUri
                        previousDestinationUri == newDestinationUri &&
                            autoAddedDestinationUri == newDestinationUri -> newDestinationUri
                        else -> null
                    },
                sourceChanged = sourceUris != existingSourceUris,
            )
        }
    }
}
