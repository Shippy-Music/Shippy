/*
 * Copyright (c) 2026 Auxio Project
 * R16BackupExporterTest.kt is part of Auxio.
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

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shippy.data.db.ShippyR16Database
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class R16BackupExporterTest {
    private lateinit var database: ShippyR16Database

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    ShippyR16Database::class.java,
                )
                .allowMainThreadQueries()
                .build()
        seedDatabase()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `export is deterministic and contains normalized user data`() {
        val first = ByteArrayOutputStream()
        val firstReport = R16BackupExporter(database).export(first, createdAtEpochMs = 42)
        val second = ByteArrayOutputStream()
        val secondReport = R16BackupExporter(database).export(second, createdAtEpochMs = 42)

        assertArrayEquals(first.toByteArray(), second.toByteArray())
        assertEquals(firstReport, secondReport)
        assertEquals(ShippyR16Database.SCHEMA_VERSION, firstReport.databaseSchemaVersion)
        assertFalse(firstReport.includesHistory)
        assertFalse(firstReport.portableAppSettingsIncluded)
        assertFalse(firstReport.sanitizedLastFmConfigIncluded)
        assertEquals(1, firstReport.rowCounts.getValue("recording"))
        assertEquals(1, firstReport.rowCounts.getValue("playlist_entry"))
        assertEquals(1, firstReport.rowCounts.getValue("audio_fingerprint"))

        val archive = ShippyBackupV1.read(ByteArrayInputStream(first.toByteArray()))
        assertEquals(ShippyR16Database.SCHEMA_VERSION, archive.manifest.databaseSchemaVersion)
        assertEquals(42, archive.manifest.createdAtEpochMs)
        assertFalse(archive.manifest.includesHistory)
        assertFalse(archive.manifest.portableAppSettingsIncluded)
        assertFalse(archive.manifest.sanitizedLastFmConfigIncluded)
        val recordings = archive.sections.getValue(ShippyBackupSection.RECORDINGS).decodeToString()
        val sources = archive.sections.getValue(ShippyBackupSection.SOURCES).decodeToString()
        val assets = archive.sections.getValue(ShippyBackupSection.ASSET_MANIFESTS).decodeToString()
        val downloads = archive.sections.getValue(ShippyBackupSection.DOWNLOADS).decodeToString()
        assertTrue(recordings.contains("\"table\":\"recording\""))
        assertFalse(sources.contains("content://music/item-1"))
        assertFalse(sources.contains("https://provider.example"))
        assertTrue(assets.contains("\"blobBase64\":\"AQID\""))
        val downloadRow =
            downloads
                .lineSequence()
                .filter { it.isNotBlank() }
                .map { JSONObject(it) }
                .single { it.getString("table") == "download_job" }
        val downloadColumns = downloadRow.getJSONArray("columns")
        val downloadValues = downloadRow.getJSONArray("values")
        val downloadValuesByColumn =
            (0 until downloadColumns.length()).associate { index ->
                downloadColumns.getString(index) to downloadValues.get(index)
            }
        assertEquals("AAC", downloadValuesByColumn["requested_media_variant"])
        assertEquals("downloads:primary", downloadValuesByColumn["destination_identity"])
        assertEquals(JSONObject.NULL, downloadValuesByColumn["pending_location"])
        assertFalse(archive.sections.containsKey(ShippyBackupSection.HISTORY))
    }

    @Test
    fun `app coverage is deterministic sanitized and round trips`() {
        val settings =
            R16PortableSettingsSnapshot(
                mapOf(
                    "ui.theme" to R16BackupValue.LongValue(2),
                    "ui.blackTheme" to R16BackupValue.BooleanValue(true),
                    "playback.preAmpWith" to R16BackupValue.DoubleValue(1.5),
                )
            )
        val lastFm = R16SanitizedLastFmConfig(username = "scrobbler")
        val first = ByteArrayOutputStream()
        val firstReport =
            R16BackupExporter(
                    database,
                    portableSettingsProvider = { settings },
                    sanitizedLastFmConfigProvider = { lastFm },
                )
                .export(first, createdAtEpochMs = 42, includeHistory = true)

        assertTrue(firstReport.portableAppSettingsIncluded)
        assertTrue(firstReport.sanitizedLastFmConfigIncluded)
        val archive = ShippyBackupV1.read(ByteArrayInputStream(first.toByteArray()))
        assertEquals(
            settings,
            R16BackupCoverageCodec.decodePortableSettings(
                archive.sections.getValue(ShippyBackupSection.PORTABLE_SETTINGS)
            ),
        )
        assertEquals(
            lastFm,
            R16BackupCoverageCodec.decodeLastFmConfig(
                archive.sections.getValue(ShippyBackupSection.SANITIZED_LASTFM_CONFIG)
            ),
        )
        assertFalse(
            archive.sections
                .getValue(ShippyBackupSection.SANITIZED_LASTFM_CONFIG)
                .decodeToString()
                .contains("apiSecret")
        )
        assertFalse(
            archive.sections
                .getValue(ShippyBackupSection.SANITIZED_LASTFM_CONFIG)
                .decodeToString()
                .contains("sessionKey")
        )

        val restoredDatabase = newDatabase()
        try {
            val restored =
                R16BackupImporter(restoredDatabase)
                    .restore(ByteArrayInputStream(first.toByteArray()))
            assertEquals(settings, restored.portableSettings)
            assertEquals(lastFm, restored.sanitizedLastFmConfig)

            val second = ByteArrayOutputStream()
            R16BackupExporter(
                    restoredDatabase,
                    portableSettingsProvider = { checkNotNull(restored.portableSettings) },
                    sanitizedLastFmConfigProvider = { checkNotNull(restored.sanitizedLastFmConfig) },
                )
                .export(second, createdAtEpochMs = 42, includeHistory = true)
            assertArrayEquals(first.toByteArray(), second.toByteArray())
        } finally {
            restoredDatabase.close()
        }
    }

    @Test
    fun `coverage rejects wrong types and secret-like imported keys`() {
        assertThrows(IllegalArgumentException::class.java) {
            R16BackupExporter(
                    database,
                    portableSettingsProvider = {
                        R16PortableSettingsSnapshot(
                            mapOf("ui.theme" to R16BackupValue.Text("dark"))
                        )
                    },
                    sanitizedLastFmConfigProvider = { R16SanitizedLastFmConfig(username = "safe") },
                )
                .export(ByteArrayOutputStream(), createdAtEpochMs = 42)
        }

        val exported = ByteArrayOutputStream()
        R16BackupExporter(
                database,
                portableSettingsProvider = {
                    R16PortableSettingsSnapshot(mapOf("ui.theme" to R16BackupValue.LongValue(2)))
                },
                sanitizedLastFmConfigProvider = { R16SanitizedLastFmConfig(username = "safe") },
            )
            .export(exported, createdAtEpochMs = 42)
        val archive = ShippyBackupV1.read(ByteArrayInputStream(exported.toByteArray()))
        val malicious =
            archive.sections +
                (ShippyBackupSection.SANITIZED_LASTFM_CONFIG to
                    """
                    {"format":"ShippySanitizedLastFmConfigV1","formatVersion":1,
                     "username":"safe","preferences":{"apiSecret":{"type":"string","value":"nope"}}}
                    """
                        .toByteArray())
        val tampered = ByteArrayOutputStream()
        ShippyBackupV1.write(
            output = tampered,
            databaseSchemaVersion = archive.manifest.databaseSchemaVersion,
            createdAtEpochMs = archive.manifest.createdAtEpochMs,
            includesHistory = archive.manifest.includesHistory,
            sections = malicious,
            portableAppSettingsIncluded = true,
            sanitizedLastFmConfigIncluded = true,
        )
        val target = newDatabase()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                R16BackupImporter(target).restore(ByteArrayInputStream(tampered.toByteArray()))
            }
        } finally {
            target.close()
        }
    }

    @Test
    fun `history is opt in and remains checksum verified`() {
        val output = ByteArrayOutputStream()
        val report = R16BackupExporter(database).export(output, 99, includeHistory = true)

        assertTrue(report.includesHistory)
        val archive = ShippyBackupV1.read(ByteArrayInputStream(output.toByteArray()))
        assertTrue(archive.manifest.includesHistory)
        assertTrue(
            archive.sections
                .getValue(ShippyBackupSection.HISTORY)
                .decodeToString()
                .contains("play_history")
        )
    }

    @Test
    fun `restore into an empty database round trips every exported user row`() {
        val exported = ByteArrayOutputStream()
        R16BackupExporter(database).export(exported, createdAtEpochMs = 42, includeHistory = true)
        val restoredDatabase = newDatabase()
        try {
            val report =
                R16BackupImporter(restoredDatabase)
                    .restore(ByteArrayInputStream(exported.toByteArray()))
            assertTrue(report.includesHistory)
            restoredDatabase.openHelper.writableDatabase
                .query(
                    "SELECT requested_media_variant, destination_identity, pending_location " +
                        "FROM download_job WHERE job_id = 'job-1'"
                )
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("AAC", cursor.getString(0))
                    assertEquals("downloads:primary", cursor.getString(1))
                    assertTrue(cursor.isNull(2))
                }

            val restored = ByteArrayOutputStream()
            R16BackupExporter(restoredDatabase)
                .export(restored, createdAtEpochMs = 42, includeHistory = true)
            assertArrayEquals(exported.toByteArray(), restored.toByteArray())
        } finally {
            restoredDatabase.close()
        }
    }

    @Test
    fun `restore rejects non-empty targets and rolls back failed imports`() {
        val nonEmptyTarget = newDatabase()
        try {
            nonEmptyTarget.openHelper.writableDatabase.execSQL(
                "INSERT INTO recording (recording_id, canonical_title, version_kind, explicitness, " +
                    "retention_kind, created_at_epoch_ms, updated_at_epoch_ms) VALUES " +
                    "('existing', 'Existing', 'ORIGINAL', 'UNKNOWN', 'DURABLE', 1, 1)"
            )
            val exported = ByteArrayOutputStream()
            R16BackupExporter(database).export(exported, createdAtEpochMs = 42)
            assertThrows(IllegalArgumentException::class.java) {
                R16BackupImporter(nonEmptyTarget)
                    .restore(ByteArrayInputStream(exported.toByteArray()))
            }
        } finally {
            nonEmptyTarget.close()
        }

        val nonContractTarget = newDatabase()
        try {
            nonContractTarget.openHelper.writableDatabase.execSQL(
                "INSERT INTO migration_audit " +
                    "(migration_id, source_version, target_version, started_at_epoch_ms, " +
                    "source_counts_json, warnings_json, status) VALUES " +
                    "('existing-audit', 10, 1, 1, '{}', '[]', 'PREPARING')"
            )
            val exported = ByteArrayOutputStream()
            R16BackupExporter(database).export(exported, createdAtEpochMs = 42)
            assertThrows(IllegalArgumentException::class.java) {
                R16BackupImporter(nonContractTarget)
                    .restore(ByteArrayInputStream(exported.toByteArray()))
            }
        } finally {
            nonContractTarget.close()
        }

        val failingTarget = newDatabase()
        try {
            val exported = ByteArrayOutputStream()
            R16BackupExporter(database).export(exported, createdAtEpochMs = 42)
            val archive = ShippyBackupV1.read(ByteArrayInputStream(exported.toByteArray()))
            val invalidPlaylists =
                archive.sections
                    .getValue(ShippyBackupSection.PLAYLISTS)
                    .decodeToString()
                    .replace("recording-1", "missing-recording")
                    .toByteArray()
            val tampered =
                ByteArrayOutputStream().also { output ->
                    ShippyBackupV1.write(
                        output = output,
                        databaseSchemaVersion = archive.manifest.databaseSchemaVersion,
                        createdAtEpochMs = archive.manifest.createdAtEpochMs,
                        includesHistory = archive.manifest.includesHistory,
                        sections =
                            archive.sections + (ShippyBackupSection.PLAYLISTS to invalidPlaylists),
                    )
                }
            assertThrows(RuntimeException::class.java) {
                R16BackupImporter(failingTarget)
                    .restore(ByteArrayInputStream(tampered.toByteArray()))
            }
            assertEquals(
                0L,
                failingTarget.openHelper.writableDatabase
                    .query("SELECT COUNT(*) FROM recording")
                    .use { cursor ->
                        check(cursor.moveToFirst())
                        cursor.getLong(0)
                    },
            )
            assertEquals(
                0L,
                failingTarget.openHelper.writableDatabase
                    .query("SELECT COUNT(*) FROM playlist")
                    .use { cursor ->
                        check(cursor.moveToFirst())
                        cursor.getLong(0)
                    },
            )
        } finally {
            failingTarget.close()
        }
    }

    private fun seedDatabase() {
        val sql = database.openHelper.writableDatabase
        sql.execSQL(
            """
            INSERT INTO recording
            (recording_id, canonical_title, duration_ms, version_kind, version_label,
             explicitness, preferred_release_id, preferred_artwork_id, retention_kind,
             retained_until_epoch_ms, created_at_epoch_ms, updated_at_epoch_ms)
            VALUES ('recording-1', 'Track', 120000, 'ORIGINAL', NULL, 'UNKNOWN', NULL, NULL,
                    'DURABLE', NULL, 1, 2)
            """
                .trimIndent()
        )
        sql.execSQL(
            """
            INSERT INTO artist
            (artist_id, canonical_name, sort_name, disambiguation, created_at_epoch_ms,
             updated_at_epoch_ms)
            VALUES ('artist-1', 'Artist', NULL, NULL, 1, 1)
            """
                .trimIndent()
        )
        sql.execSQL(
            """
            INSERT INTO recording_artist_credit
            (recording_id, position, artist_id, credited_name, join_phrase)
            VALUES ('recording-1', 0, 'artist-1', 'Artist', '')
            """
                .trimIndent()
        )
        sql.execSQL(
            """
            INSERT INTO metadata_observation
            (observation_id, source_type, source_reference_id, asset_id, title,
             artist_credit_json, release_title, release_artist, duration_ms, artwork_json,
             release_year, track_number, disc_number, genres_json, version_hints_json,
             external_ids_json, extras_json, captured_at_epoch_ms)
            VALUES ('observation-1', 'TEST', 'source-1', NULL, 'Track', '[\"Artist\"]',
                    NULL, NULL, 120000, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 1)
            """
                .trimIndent()
        )
        sql.execSQL(
            """
            INSERT INTO source_reference
            (source_reference_id, recording_id, provider_id, source_kind, item_type,
             source_item_id, original_url, availability_state, availability_checked_at_epoch_ms,
             availability_expires_at_epoch_ms, failure_kind, failure_retryable, identity_status,
             raw_metadata_observation_id, created_at_epoch_ms, updated_at_epoch_ms)
            VALUES ('source-1', 'recording-1', 'local-file', 'LOCAL_FILE', 'LOCAL_FILE',
                    'item-1', 'content://music/item-1', 'AVAILABLE', 1, NULL, NULL, NULL,
                    'LINKED', 'observation-1', 1, 1)
            """
                .trimIndent()
        )
        sql.execSQL(
            """
            INSERT INTO external_identifier
            (external_identifier_id, owner_type, owner_id, scheme, value, verified,
             source_observation_id, created_at_epoch_ms)
            VALUES ('identifier-1', 'RECORDING', 'recording-1', 'test', 'track-1', 1,
                    'observation-1', 1)
            """
                .trimIndent()
        )
        sql.execSQL(
            """
            INSERT INTO saved_source_entity
            (provider_id, entity_type, source_item_id, title, subtitle, artwork_url, original_url,
             pinned, saved_at_epoch_ms, updated_at_epoch_ms)
            VALUES ('test-provider', 'ALBUM', 'album-1', 'Saved Album', NULL,
                    'https://provider.example/artwork.jpg', 'https://provider.example/album/1',
                    1, 1, 2)
            """
                .trimIndent()
        )
        sql.execSQL(
            """
            INSERT INTO library_recording
            (recording_id, liked, explicitly_saved, user_edited, manually_identified,
             first_added_at_epoch_ms, updated_at_epoch_ms)
            VALUES ('recording-1', 1, 1, 0, 1, 1, 2)
            """
                .trimIndent()
        )
        sql.execSQL(
            """
            INSERT INTO library_layout_entry
            (target_type, target_id, pinned, order_key)
            VALUES ('PLAYLIST', 'playlist-1', 1, 0)
            """
                .trimIndent()
        )
        sql.execSQL(
            """
            INSERT INTO playlist
            (playlist_id, name, pinned, library_order_key, artwork_override, display_sort_mode,
             display_sort_direction, origin_kind, origin_key, created_at_epoch_ms,
             updated_at_epoch_ms)
            VALUES ('playlist-1', 'Favorites', 1, 0, NULL, 'ADDED', 'ASC', 'USER', NULL, 1, 2)
            """
                .trimIndent()
        )
        sql.execSQL(
            """
            INSERT INTO playlist_entry
            (playlist_entry_id, playlist_id, recording_id, order_key, added_at_epoch_ms)
            VALUES ('entry-1', 'playlist-1', 'recording-1', 0, 1)
            """
                .trimIndent()
        )
        sql.execSQL(
            """
            INSERT INTO user_metadata_override
            (recording_id, field_name, value_json, updated_at_epoch_ms)
            VALUES ('recording-1', 'title', '\"Edited Track\"', 3)
            """
                .trimIndent()
        )
        sql.execSQL(
            """
            INSERT INTO download_job
            (job_id, recording_id, requested_source_reference_id, requested_media_variant,
             destination_identity, published_asset_id, state,
             bytes_transferred, expected_bytes, failure_kind, retry_after_epoch_ms,
             pending_location, display_fallback_json, created_at_epoch_ms, updated_at_epoch_ms)
            VALUES ('job-1', 'recording-1', 'source-1', 'AAC', 'downloads:primary', NULL,
                    'COMPLETED', 3, 3, NULL, NULL, 'content://private/pending/job-1', '{}', 1, 2)
            """
                .trimIndent()
        )
        sql.execSQL(
            """
            INSERT INTO media_asset
            (asset_id, recording_id, source_reference_id, asset_kind, asset_state, location_type,
             location, document_id, media_store_id, display_name, mime_type, container, codec,
             bitrate_bps, sample_rate_hz, channel_count, content_length, content_checksum,
             fingerprint_id, created_at_epoch_ms, updated_at_epoch_ms, last_verified_at_epoch_ms,
             normalized_path_token, last_modified_epoch_ms, download_job_id)
            VALUES ('asset-1', 'recording-1', 'source-1', 'SHIPPY_DOWNLOAD', 'AVAILABLE',
                    'CONTENT_URI', 'content://asset-1', 'asset-1', NULL, 'Track.mp3', 'audio/mpeg',
                    'mp3', 'mp3', NULL, NULL, NULL, 3, 'sha256:asset', 'fingerprint-1', 1, 2, 2,
                    NULL, NULL, 'job-1')
            """
                .trimIndent()
        )
        sql.execSQL(
            """
            INSERT INTO audio_fingerprint
            (fingerprint_id, asset_id, algorithm, algorithm_version, duration_seconds,
             compressed_fingerprint, created_at_epoch_ms)
            VALUES ('fingerprint-1', 'asset-1', 'TEST', '1', 120, ?, 1)
            """
                .trimIndent(),
            arrayOf(byteArrayOf(1, 2, 3)),
        )
        sql.execSQL(
            """
            INSERT INTO playback_checkpoint
            (slot, checkpoint_version, session_id, current_queue_entry_id, position_ms,
             playing_intent, repeat_mode, shuffle_enabled, shuffle_seed, base_order_json,
             traversal_order_json, updated_at_epoch_ms, checksum)
            VALUES ('active', 1, 'session-1', 'entry-1', 12, 0, 'OFF', 0, NULL,
                    '[\"entry-1\"]', '[\"entry-1\"]', 2, 'sha256:checkpoint')
            """
                .trimIndent()
        )
        sql.execSQL(
            """
            INSERT INTO playback_checkpoint_entry
            (slot, queue_entry_id, position, recording_id, origin_json, contributor_id,
             presentation_fallback_json)
            VALUES ('active', 'entry-1', 0, 'recording-1', NULL, NULL, '{}')
            """
                .trimIndent()
        )
        sql.execSQL(
            """
            INSERT INTO lastfm_scrobble_outbox
            (outbox_id, listening_session_id, recording_id, artist, track, album,
             duration_seconds, started_at_epoch_seconds, chosen_by_user, queued_at_epoch_ms,
             attempt_count, last_attempt_at_epoch_ms)
            VALUES ('outbox-1', 'session-1', 'recording-1', 'Artist', 'Track', NULL, 120, 1,
                    1, 2, 0, NULL)
            """
                .trimIndent()
        )
        sql.execSQL(
            """
            INSERT INTO play_history
            (listening_session_id, recording_id, queue_entry_id, source_reference_id,
             started_at_epoch_ms, ended_at_epoch_ms, active_listened_ms, last_position_ms,
             completion_kind, chosen_by_user)
            VALUES ('history-1', 'recording-1', 'entry-1', 'source-1', 1, 2, 100, 120000,
                    'COMPLETED', 1)
            """
                .trimIndent()
        )
    }

    private fun newDatabase(): ShippyR16Database =
        Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                ShippyR16Database::class.java,
            )
            .allowMainThreadQueries()
            .build()
}
