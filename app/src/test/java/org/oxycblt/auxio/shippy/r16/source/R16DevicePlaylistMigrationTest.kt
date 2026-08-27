/*
 * Copyright (c) 2026 Auxio Project
 * R16DevicePlaylistMigrationTest.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.source

import app.shippy.core.identity.RecordingId
import app.shippy.core.identitymatch.MatchingFeatures
import app.shippy.core.source.SourceKey
import app.shippy.data.ingest.R16ExistingSource
import app.shippy.data.ingest.R16IdentityCandidate
import app.shippy.data.ingest.R16IngestionCommand
import app.shippy.data.ingest.R16IngestionRepository
import app.shippy.data.ingest.R16IngestionSession
import app.shippy.data.ingest.R16ManagedAsset
import app.shippy.data.ingest.R16ObservedAsset
import app.shippy.data.migration.R16DevicePlaylistImport
import app.shippy.data.migration.R16DevicePlaylistImportRepository
import app.shippy.data.migration.R16DevicePlaylistPageResult
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.musikr.Library
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.tag.interpret.Naming

class R16DevicePlaylistMigrationTest {
    @Test
    fun `validation writes nothing until the stable snapshot is fully validated`() = runBlocking {
        var snapshot =
            listOf(
                playlist(1, "Alpha", listOf(song(1), song(2), song(1))),
                playlist(2, "Beta", listOf(song(2))),
                playlist(3, "Gamma", listOf(song(3))),
            )
        val importer = RecordingImporter()
        val migration =
            migration(
                snapshot = { snapshot },
                importer = importer,
                recordings =
                    mapOf(
                        "uas00000000-0000-0000-0000-000000000001" to recording(1),
                        "uas00000000-0000-0000-0000-000000000002" to recording(2),
                        "uas00000000-0000-0000-0000-000000000003" to recording(3),
                    ),
                pageSize = 2,
            )

        var page = migration.runPage("m5", importedAtEpochMs = 10)
        assertEquals(R16DevicePlaylistMigrationStage.VALIDATE, page.stage)
        assertEquals(2, page.processedPlaylistCount)
        assertTrue(importer.calls.isEmpty())

        page = migration.runPage("m5", page.nextCheckpoint, importedAtEpochMs = 10)
        assertEquals(R16DevicePlaylistMigrationStage.VALIDATE, page.stage)
        assertEquals(3, page.processedPlaylistCount)
        assertTrue(importer.calls.isEmpty())

        page = migration.runPage("m5", page.nextCheckpoint, importedAtEpochMs = 10)
        assertEquals(R16DevicePlaylistMigrationStage.IMPORT, page.nextCheckpoint?.stage)
        assertEquals(0, page.nextCheckpoint?.processedPlaylistCount)
        assertTrue(importer.calls.isEmpty())

        page = migration.runPage("m5", page.nextCheckpoint, importedAtEpochMs = 10)
        assertEquals(R16DevicePlaylistMigrationStage.IMPORT, page.stage)
        assertEquals(1, importer.calls.size)
        assertEquals(
            listOf(recording(1), recording(2), recording(1)),
            importer.calls.single().playlists.first().orderedRecordingIds,
        )
        assertFalse(page.complete)
    }

    @Test
    fun `fingerprint change restarts validation before writing the new snapshot`() = runBlocking {
        var snapshot =
            listOf(playlist(1, "Alpha", listOf(song(1))), playlist(2, "Beta", listOf(song(2))))
        val importer = RecordingImporter()
        val migration =
            migration(
                snapshot = { snapshot },
                importer = importer,
                recordings =
                    mapOf(
                        "uas00000000-0000-0000-0000-000000000001" to recording(1),
                        "uas00000000-0000-0000-0000-000000000002" to recording(2),
                        "uas00000000-0000-0000-0000-000000000003" to recording(3),
                    ),
                pageSize = 2,
            )

        var page = migration.runPage("m5", pageSize = 2, importedAtEpochMs = 10)
        page = migration.runPage("m5", page.nextCheckpoint, pageSize = 2, importedAtEpochMs = 10)
        page = migration.runPage("m5", page.nextCheckpoint, pageSize = 2, importedAtEpochMs = 10)
        val firstImport =
            migration.runPage("m5", page.nextCheckpoint, pageSize = 1, importedAtEpochMs = 10)
        assertEquals(1, importer.calls.size)

        snapshot = snapshot + playlist(3, "Changed", listOf(song(3)))
        val restarted =
            migration.runPage(
                "m5",
                firstImport.nextCheckpoint,
                pageSize = 1,
                importedAtEpochMs = 10,
            )

        assertEquals(R16DevicePlaylistMigrationStage.VALIDATE, restarted.stage)
        assertEquals(1, restarted.processedPlaylistCount)
        assertEquals(1, importer.calls.size)
    }

    @Test
    fun `unresolved validation returns a bounded sample and never calls the importer`() =
        runBlocking {
            val unresolvedUid = "uas00000000-0000-0000-0000-000000000009"
            val importer = RecordingImporter()
            val migration =
                migration(
                    snapshot = { listOf(playlist(1, "Unresolved", listOf(song(9)))) },
                    importer = importer,
                    recordings = emptyMap(),
                    pageSize = 1,
                )

            val page = migration.runPage("m5", importedAtEpochMs = 10)

            assertEquals(R16DevicePlaylistMigrationStage.VALIDATE, page.stage)
            assertEquals(1, page.unresolvedCount)
            assertEquals(setOf(unresolvedUid), page.unresolvedSongUids)
            assertEquals(0, page.nextCheckpoint?.processedPlaylistCount)
            assertTrue(importer.calls.isEmpty())
        }

    private fun migration(
        snapshot: () -> List<Playlist>,
        importer: RecordingImporter,
        recordings: Map<String, RecordingId>,
        pageSize: Int,
    ): R16DevicePlaylistMigration =
        R16DevicePlaylistMigration.createForTesting(
            musicRepository = musicRepository(snapshot),
            ingestion = FakeIngestionRepository(recordings),
            importer = importer,
            pageSize = pageSize,
        )

    private class RecordingImporter : R16DevicePlaylistImportRepository {
        data class Call(
            val snapshotOriginKeys: Set<String>,
            val startOrdinal: Int,
            val playlists: List<R16DevicePlaylistImport>,
        )

        val calls = mutableListOf<Call>()

        override suspend fun importPage(
            migrationId: String,
            snapshotOriginKeys: Set<String>,
            startOrdinal: Int,
            playlists: List<R16DevicePlaylistImport>,
            importedAtEpochMs: Long,
        ): R16DevicePlaylistPageResult {
            calls += Call(snapshotOriginKeys, startOrdinal, playlists)
            val processed = startOrdinal + playlists.size
            return R16DevicePlaylistPageResult(
                processedPlaylistCount = processed,
                importedPlaylistCount = playlists.size,
                reusedPlaylistCount = 0,
                importedEntryCount = playlists.sumOf { it.orderedRecordingIds.size },
                complete = processed == snapshotOriginKeys.size,
            )
        }
    }

    private class FakeIngestionRepository(private val recordings: Map<String, RecordingId>) :
        R16IngestionRepository {
        private val session =
            object : R16IngestionSession {
                override suspend fun exactSource(sourceKey: SourceKey): R16ExistingSource? =
                    recordings[sourceKey.sourceItemId]?.let { R16ExistingSource(sourceKey, it) }

                override suspend fun managedAssetCandidates(
                    asset: R16ObservedAsset
                ): List<R16ManagedAsset> = error("unused")

                override suspend fun identityCandidates(
                    observation: app.shippy.data.ingest.R16SourceObservation,
                    features: MatchingFeatures,
                ): List<R16IdentityCandidate> = error("unused")

                override suspend fun persist(command: R16IngestionCommand) = error("unused")
            }

        override suspend fun <T> transaction(block: suspend R16IngestionSession.() -> T): T =
            block(session)
    }

    private companion object {
        fun recording(index: Int) =
            RecordingId("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}")

        fun song(index: Int): Song =
            proxy(Song::class.java) { method ->
                when (method.name) {
                    "getUid" -> uid('s', index)
                    "getName" -> Naming.simple().name("Song $index", null)
                    else -> defaultValue(method.returnType)
                }
            }

        fun playlist(index: Int, name: String, songs: List<Song>): Playlist =
            proxy(Playlist::class.java) { method ->
                when (method.name) {
                    "getUid" -> uid('p', index)
                    "getName" -> Naming.simple().name(name, null)
                    "getSongs" -> songs
                    "getDurationMs" -> 0L
                    else -> defaultValue(method.returnType)
                }
            }

        fun uid(kind: Char, index: Int): Music.UID =
            checkNotNull(
                Music.UID.fromString(
                    "ua${kind}00000000-0000-0000-0000-${index.toString().padStart(12, '0')}"
                )
            )

        fun musicRepository(snapshot: () -> List<Playlist>): MusicRepository {
            val library =
                proxy(Library::class.java) { method ->
                    when (method.name) {
                        "getPlaylists" -> snapshot()
                        "empty" -> snapshot().isEmpty()
                        else -> defaultValue(method.returnType)
                    }
                }
            return proxy(MusicRepository::class.java) { method ->
                if (method.name == "getLibrary") library else defaultValue(method.returnType)
            }
        }

        fun <T : Any> proxy(type: Class<T>, value: (java.lang.reflect.Method) -> Any?): T {
            lateinit var proxy: T
            proxy =
                Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
                    when (method.name) {
                        "toString" -> "test-${type.simpleName}"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> false
                        else -> value(method)
                    }
                } as T
            return proxy
        }

        fun defaultValue(type: Class<*>): Any? =
            when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                java.lang.Character.TYPE -> '\u0000'
                else -> null
            }
    }
}
