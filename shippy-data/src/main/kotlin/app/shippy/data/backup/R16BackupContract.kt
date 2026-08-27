/*
 * Copyright (c) 2026 Auxio Project
 * R16BackupContract.kt is part of Auxio.
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
package app.shippy.data.backup

/** Shared table, dependency, and redaction contract for ShippyBackupV1 export and restore. */
internal object R16BackupContract {
    const val MAX_NDJSON_ROW_BYTES = 8L * 1_024 * 1_024

    // Map order is the FK-safe restore order. Export sorts sections by entry name separately.
    val tablesBySection: LinkedHashMap<ShippyBackupSection, List<String>> =
        linkedMapOf(
            ShippyBackupSection.RECORDINGS to
                listOf(
                    "release",
                    "artist",
                    "recording",
                    "recording_artist_credit",
                    "release_track",
                ),
            ShippyBackupSection.SOURCES to
                listOf(
                    "metadata_observation",
                    "source_reference",
                    "external_identifier",
                    "artwork_reference",
                    "saved_source_entity",
                ),
            ShippyBackupSection.LIBRARY to listOf("library_recording", "library_layout_entry"),
            ShippyBackupSection.PLAYLISTS to listOf("playlist", "playlist_entry"),
            ShippyBackupSection.OVERRIDES to
                listOf("user_metadata_override", "canonical_field_provenance"),
            ShippyBackupSection.IDENTITY_DECISIONS to
                listOf("identity_decision", "identity_rejection", "merge_audit", "entity_redirect"),
            ShippyBackupSection.ASSET_MANIFESTS to listOf("media_asset", "audio_fingerprint"),
            ShippyBackupSection.DOWNLOADS to listOf("download_job"),
            // The data layer owns checkpoint recovery state, not Android preference settings.
            ShippyBackupSection.SETTINGS to
                listOf("playback_checkpoint", "playback_checkpoint_entry"),
            // This is the durable outbox only; sanitized Last.fm account settings are an app hook.
            ShippyBackupSection.LASTFM_CONFIG to listOf("lastfm_scrobble_outbox"),
            ShippyBackupSection.HISTORY to listOf("play_history"),
        )

    val sectionForTable: Map<String, ShippyBackupSection> =
        tablesBySection.flatMap { (section, tables) -> tables.map { it to section } }.toMap()

    /** Every Room entity/derived table that must be empty before a restore starts. */
    val allR16Tables: List<String> =
        (tablesBySection.values.flatten() +
                listOf(
                    "lyrics_cache",
                    "migration_audit",
                    "recording_fts",
                    "playlist_fts",
                    "library_membership_index",
                ))
            .distinct()

    /** Values that must be null in a portable backup; they are expiring or provider-private. */
    val redactedColumns: Map<String, Set<String>> =
        mapOf(
            "source_reference" to setOf("original_url"),
            "metadata_observation" to setOf("artwork_json", "extras_json"),
            "download_job" to setOf("pending_location"),
            "saved_source_entity" to setOf("artwork_url", "original_url"),
        )
}
