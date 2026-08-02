/*
 * Copyright (c) 2026 Auxio Project
 * CrewDiagnostics.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.diagnostics

import java.util.ArrayDeque
import java.util.UUID

/**
 * A short correlation ID for a single Crew attempt. It is deliberately unrelated to a session
 * bearer, invite secret, device identifier, route address, or media hash.
 */
@JvmInline
value class CrewDiagnosticSessionId(val value: String) {
    init {
        require(value.matches(Regex("[A-Za-z0-9_-]{8,64}"))) { "Invalid diagnostic session ID" }
    }

    override fun toString() = value

    companion object {
        fun create(): CrewDiagnosticSessionId =
            CrewDiagnosticSessionId(UUID.randomUUID().toString().replace("-", "").take(16))
    }
}

/** Safe fields that may be shown in support logs or exported by the user. */
data class CrewDiagnosticEvent(
    val elapsedRealtimeMs: Long,
    val kind: Kind,
    val detail: String,
    val requestIdSuffix: String? = null,
    val term: Long? = null,
    val sequence: Long? = null,
    val projectionVersion: Long? = null,
    val route: Route? = null,
    val readiness: Readiness? = null,
) {
    enum class Kind {
        START,
        JOIN,
        ROUTE_SELECTED,
        COMMAND_SUBMITTED,
        COMMAND_ACCEPTED,
        PROJECTION_APPLIED,
        RECONNECTING,
        RESTORED,
        RETRYABLE_FAILURE,
        EXPIRED,
        TEMP_MEDIA_CLEANUP,
        ENDED,
    }

    enum class Route {
        NEARBY,
        LAN,
        RELAY,
        UNKNOWN,
    }

    enum class Readiness {
        IDLE,
        PREPARING,
        READY,
        BUFFERING,
        UNAVAILABLE,
    }
}

/**
 * Bounded, privacy-safe support snapshot. IDs are deliberately suffix-only; it must never grow into
 * a transport dump. In particular it carries no secrets, links, IP addresses, local paths, content
 * URIs, provider credentials, artwork bytes, or media object hashes.
 */
data class CrewDiagnosticSnapshot(
    val diagnosticSessionId: CrewDiagnosticSessionId,
    val localMemberSuffix: String?,
    val activeMemberCount: Int,
    val term: Long?,
    val sequence: Long?,
    val route: CrewDiagnosticEvent.Route,
    val readiness: CrewDiagnosticEvent.Readiness,
    val events: List<CrewDiagnosticEvent>,
)

/**
 * Small in-memory recorder. Export is intentionally a value object rather than a file or a log
 * upload: the UI can show/share it only after the listener explicitly chooses to do so.
 */
class CrewDiagnosticRecorder(
    private val elapsedRealtimeMs: () -> Long,
    private val maximumEvents: Int = DEFAULT_MAXIMUM_EVENTS,
) {
    private val events = ArrayDeque<CrewDiagnosticEvent>()
    private var diagnosticSessionId = CrewDiagnosticSessionId.create()
    private var localMemberSuffix: String? = null
    private var activeMemberCount = 0
    private var term: Long? = null
    private var sequence: Long? = null
    private var route = CrewDiagnosticEvent.Route.UNKNOWN
    private var readiness = CrewDiagnosticEvent.Readiness.IDLE

    init {
        require(maximumEvents in 1..MAXIMUM_ALLOWED_EVENTS) { "Diagnostic event bound is invalid" }
    }

    @Synchronized
    fun begin(localMemberId: String?, activeMemberCount: Int) {
        diagnosticSessionId = CrewDiagnosticSessionId.create()
        localMemberSuffix = localMemberId?.suffix()
        this.activeMemberCount = activeMemberCount.coerceAtLeast(0)
        term = null
        sequence = null
        route = CrewDiagnosticEvent.Route.UNKNOWN
        readiness = CrewDiagnosticEvent.Readiness.IDLE
        events.clear()
        record(CrewDiagnosticEvent.Kind.START, "crew_started")
    }

    @Synchronized
    fun update(
        activeMemberCount: Int = this.activeMemberCount,
        term: Long? = this.term,
        sequence: Long? = this.sequence,
        route: CrewDiagnosticEvent.Route = this.route,
        readiness: CrewDiagnosticEvent.Readiness = this.readiness,
    ) {
        this.activeMemberCount = activeMemberCount.coerceAtLeast(0)
        this.term = term?.takeIf { it >= 0 }
        this.sequence = sequence?.takeIf { it >= 0 }
        this.route = route
        this.readiness = readiness
    }

    @Synchronized
    fun record(
        kind: CrewDiagnosticEvent.Kind,
        detail: String,
        requestId: String? = null,
        projectionVersion: Long? = null,
    ) {
        val event =
            CrewDiagnosticEvent(
                elapsedRealtimeMs = elapsedRealtimeMs(),
                kind = kind,
                detail = detail.safeDetail(),
                requestIdSuffix = requestId?.suffix(),
                term = term,
                sequence = sequence,
                projectionVersion = projectionVersion?.takeIf { it >= 0 },
                route = route,
                readiness = readiness,
            )
        if (events.size == maximumEvents) events.removeFirst()
        events.addLast(event)
    }

    @Synchronized
    fun snapshot(): CrewDiagnosticSnapshot =
        CrewDiagnosticSnapshot(
            diagnosticSessionId = diagnosticSessionId,
            localMemberSuffix = localMemberSuffix,
            activeMemberCount = activeMemberCount,
            term = term,
            sequence = sequence,
            route = route,
            readiness = readiness,
            events = events.toList(),
        )

    private fun String.suffix(): String = takeLast(ID_SUFFIX_LENGTH)

    private fun String.safeDetail(): String {
        val compact = trim().replace(Regex("\\s+"), " ")
        require(compact.length <= MAXIMUM_DETAIL_LENGTH) { "Diagnostic detail is too long" }
        require(!FORBIDDEN_DETAIL.containsMatchIn(compact)) {
            "Diagnostic detail contains sensitive data"
        }
        return compact
    }

    private companion object {
        const val DEFAULT_MAXIMUM_EVENTS = 64
        const val MAXIMUM_ALLOWED_EVENTS = 256
        const val ID_SUFFIX_LENGTH = 8
        const val MAXIMUM_DETAIL_LENGTH = 96
        val FORBIDDEN_DETAIL =
            Regex("(?i)(://|content:|file:|/storage/|token|secret|password|authorization|bearer)")
    }
}
