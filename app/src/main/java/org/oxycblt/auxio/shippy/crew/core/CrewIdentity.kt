/*
 * Copyright (c) 2026 Auxio Project
 * CrewIdentity.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.core

import java.security.MessageDigest

@JvmInline
value class ProtocolVersion(val value: Int) {
    init {
        require(value > 0) { "ProtocolVersion must be positive" }
    }
}

data class CrewSessionId(val value: String, val protocolVersion: ProtocolVersion) {
    init {
        require(value.isNotBlank()) { "CrewSessionId cannot be blank" }
    }
}

data class CrewMemberId(val value: String, val protocolVersion: ProtocolVersion) {
    init {
        require(value.isNotBlank()) { "CrewMemberId cannot be blank" }
    }
}

/**
 * Stable local-first identity for a listener profile. It is shared only with active Crew members.
 */
@JvmInline
value class CrewProfileId(val value: String) {
    init {
        require(value.matches(UUID_PATTERN)) { "CrewProfileId must be a UUID" }
    }

    companion object {
        internal fun fromMember(memberId: CrewMemberId): CrewProfileId {
            val digest = MessageDigest.getInstance("SHA-256").digest(memberId.value.toByteArray())
            val hex = digest.take(16).joinToString("") { "%02x".format(it) }
            return CrewProfileId(
                "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                    "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
            )
        }
    }
}

/** Stable installation identity; never serialized in public media/source metadata. */
@JvmInline
value class CrewDeviceId(val value: String) {
    init {
        require(value.matches(UUID_PATTERN)) { "CrewDeviceId must be a UUID" }
    }
}

/**
 * Session membership is the existing protocol-scoped [CrewMemberId]. It stays separate from a
 * profile (person-facing) and a device (installation-facing) identity.
 */
typealias CrewMembershipId = CrewMemberId

/** A tiny generated avatar descriptor. It contains no URI, image bytes, account ID, or path. */
@JvmInline
value class CrewAvatarDescriptor(val value: String) {
    init {
        require(value.matches(AVATAR_PATTERN)) { "Crew avatar descriptor is invalid" }
    }

    companion object {
        fun generated(profileId: CrewProfileId): CrewAvatarDescriptor {
            val digest = MessageDigest.getInstance("SHA-256").digest(profileId.value.toByteArray())
            return CrewAvatarDescriptor(digest.take(8).joinToString("") { "%02x".format(it) })
        }

        fun derived(memberId: CrewMemberId): CrewAvatarDescriptor =
            generated(CrewProfileId.fromMember(memberId))
    }
}

private val UUID_PATTERN =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
private val AVATAR_PATTERN = Regex("^[0-9a-f]{16}$")

@JvmInline
value class DurableEventId(val value: String) {
    init {
        require(value.isNotBlank()) { "DurableEventId cannot be blank" }
    }
}

@JvmInline
value class CoordinatorTerm(val value: Long) {
    init {
        require(value > 0) { "CoordinatorTerm must be positive" }
    }

    fun next() = CoordinatorTerm(Math.addExact(value, 1))
}

@JvmInline
value class EventSequence(val value: Long) {
    init {
        require(value >= 0) { "EventSequence cannot be negative" }
    }

    fun next() = EventSequence(Math.addExact(value, 1))
}
