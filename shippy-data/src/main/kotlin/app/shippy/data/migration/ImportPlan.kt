/*
 * Copyright (c) 2026 Auxio Project
 * ImportPlan.kt is part of Auxio.
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
package app.shippy.data.migration

internal enum class LegacyImportPhase(val code: String) {
    PREFLIGHT("M0"),
    CANONICAL_TRACKS("M1"),
    CANDIDATES("M2"),
    LIBRARY_RELATIONSHIPS("M3"),
    USER_PLAYLISTS("M4"),
    DEVICE_PLAYLISTS("M5"),
    DOWNLOADS("M6"),
    LYRICS("M7"),
    LASTFM_OUTBOX("M8"),
    PLAYBACK_CHECKPOINT("M9"),
    SAVED_PROVIDER_ENTITIES("M10"),
    CREW("M11"),
    LOCAL_REINDEX("M12"),
    VERIFY("M13"),
    CUTOVER("M14"),
}

internal data class LegacyImportCheckpoint(
    val migrationId: String,
    val phase: LegacyImportPhase,
    val lastStableKey: String?,
) {
    init {
        require(migrationId.isNotBlank()) { "Migration ID must not be blank" }
        require(lastStableKey == null || lastStableKey.isNotBlank()) {
            "Import checkpoint key must be null or non-blank"
        }
    }
}

internal object R15ToR16ImportPlan {
    val phases: List<LegacyImportPhase> = LegacyImportPhase.entries

    fun nextAfter(completed: LegacyImportPhase?): LegacyImportPhase? =
        if (completed == null) {
            phases.first()
        } else {
            phases.getOrNull(phases.indexOf(completed) + 1)
        }

    fun mayCancelSafelyBefore(phase: LegacyImportPhase): Boolean =
        phase != LegacyImportPhase.CUTOVER
}
