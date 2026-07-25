/*
 * Copyright (c) 2026 Shippy contributors
 * CrewIdentity.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.core

@JvmInline
value class ProtocolVersion(val value: Int) {
    init {
        require(value > 0) { "ProtocolVersion must be positive" }
    }
}

data class CrewSessionId(
    val value: String,
    val protocolVersion: ProtocolVersion,
) {
    init {
        require(value.isNotBlank()) { "CrewSessionId cannot be blank" }
    }
}

data class CrewMemberId(
    val value: String,
    val protocolVersion: ProtocolVersion,
) {
    init {
        require(value.isNotBlank()) { "CrewMemberId cannot be blank" }
    }
}

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
