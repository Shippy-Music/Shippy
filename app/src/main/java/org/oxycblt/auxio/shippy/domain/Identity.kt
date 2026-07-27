/*
 * Copyright (c) 2026 Auxio Project
 * Identity.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.domain

@JvmInline
value class TrackId(val value: String) {
    init {
        require(value.isNotBlank()) { "TrackId cannot be blank" }
    }

    override fun toString() = value
}

@JvmInline
value class CandidateId(val value: String) {
    init {
        require(value.isNotBlank()) { "CandidateId cannot be blank" }
    }

    override fun toString() = value
}

@JvmInline
value class QueueItemId(val value: String) {
    init {
        require(value.isNotBlank()) { "QueueItemId cannot be blank" }
    }

    override fun toString() = value
}

@JvmInline
value class ProviderId(val value: String) {
    init {
        require(value.isNotBlank()) { "ProviderId cannot be blank" }
    }

    override fun toString() = value
}
