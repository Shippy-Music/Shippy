/*
 * Copyright (c) 2026 Auxio Project
 * MigrationExpectedCountEvidence.kt is part of Auxio.
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

import java.security.MessageDigest
import org.json.JSONObject

/**
 * The counts M13 compares against are importer decisions, not a second read of the R16 target.
 *
 * A single bounded state object is kept in the migration audit. Each phase retains only its latest
 * page token and delta; replaying that page is therefore a no-op after its Room transaction
 * committed. Tokens are hashed so this evidence never adds a locator or other raw source value to
 * the audit JSON.
 */
internal data class MigrationExpectedCountDelta(
    val liked: Long = 0,
    val playlists: Long = 0,
    val playlistEntries: Long = 0,
    val savedSourceEntities: Long = 0,
    val verifiedDownloads: Long = 0,
    val checkpointEntries: Long = 0,
    val lastFmOutbox: Long = 0,
) {
    init {
        require(
            listOf(
                    liked,
                    playlists,
                    playlistEntries,
                    savedSourceEntities,
                    verifiedDownloads,
                    checkpointEntries,
                    lastFmOutbox,
                )
                .all { it >= 0 }
        ) {
            "Expected migration evidence deltas cannot be negative"
        }
    }

    operator fun plus(other: MigrationExpectedCountDelta): MigrationExpectedCountDelta =
        MigrationExpectedCountDelta(
            liked = Math.addExact(liked, other.liked),
            playlists = Math.addExact(playlists, other.playlists),
            playlistEntries = Math.addExact(playlistEntries, other.playlistEntries),
            savedSourceEntities = Math.addExact(savedSourceEntities, other.savedSourceEntities),
            verifiedDownloads = Math.addExact(verifiedDownloads, other.verifiedDownloads),
            checkpointEntries = Math.addExact(checkpointEntries, other.checkpointEntries),
            lastFmOutbox = Math.addExact(lastFmOutbox, other.lastFmOutbox),
        )

    fun toJson(): JSONObject =
        JSONObject()
            .put("liked", liked)
            .put("playlists", playlists)
            .put("playlistEntries", playlistEntries)
            .put("savedSourceEntities", savedSourceEntities)
            .put("verifiedDownloads", verifiedDownloads)
            .put("checkpointEntries", checkpointEntries)
            .put("lastFmOutbox", lastFmOutbox)

    companion object {
        fun fromJson(json: JSONObject): MigrationExpectedCountDelta =
            MigrationExpectedCountDelta(
                liked = json.getLong("liked"),
                playlists = json.getLong("playlists"),
                playlistEntries = json.getLong("playlistEntries"),
                savedSourceEntities = json.getLong("savedSourceEntities"),
                verifiedDownloads = json.getLong("verifiedDownloads"),
                checkpointEntries = json.getLong("checkpointEntries"),
                lastFmOutbox = json.getLong("lastFmOutbox"),
            )
    }
}

/** Durable, bounded expected-count evidence owned by the data import path. */
internal object MigrationExpectedCountEvidence {
    const val JSON_KEY = "m13ExpectedCounts"

    fun tracks(phase: LegacyImportPhase): Boolean = phase in TRACKED_PHASES

    private const val VERSION = 1
    private const val COUNTS_KEY = "counts"
    private const val PHASES_KEY = "phases"
    private const val TOKEN_HASH_KEY = "tokenHash"
    private const val DELTA_KEY = "delta"
    private const val COMPLETE_KEY = "complete"
    private const val SOURCE = "M0_SNAPSHOT_IMPORT_DECISIONS"

    /** Add one page's accepted/verified decisions to [target]. Safe to replay the same page. */
    fun recordPage(
        target: JSONObject,
        phase: LegacyImportPhase,
        pageToken: String,
        delta: MigrationExpectedCountDelta,
    ): JSONObject {
        require(pageToken.isNotBlank()) { "Expected-count evidence page token is blank" }
        require(phase in TRACKED_PHASES) {
            "Expected-count evidence does not apply to ${phase.code}"
        }
        val evidence = target.optJSONObject(JSON_KEY) ?: newEvidence()
        val phases = evidence.optJSONObject(PHASES_KEY) ?: JSONObject()
        val phaseKey = phase.code
        val tokenHash = sha256(pageToken)
        val prior = phases.optJSONObject(phaseKey)
        if (prior != null && prior.optString(TOKEN_HASH_KEY) == tokenHash) {
            // The importer transaction may be replayed after its durable page write committed.
            // Keep the first decision: re-verification can legitimately report zero because an
            // already-published asset/playlist is now being reused.
        } else {
            require(prior?.optBoolean(COMPLETE_KEY, false) != true) {
                "Expected-count evidence phase ${phase.code} is already complete"
            }
            val counts = MigrationExpectedCountDelta.fromJson(evidence.getJSONObject(COUNTS_KEY))
            evidence.put(COUNTS_KEY, (counts + delta).toJson())
            phases.put(
                phaseKey,
                JSONObject()
                    .put(TOKEN_HASH_KEY, tokenHash)
                    .put(DELTA_KEY, delta.toJson())
                    .put(COMPLETE_KEY, false),
            )
        }
        evidence.put(PHASES_KEY, phases)
        target.put(JSON_KEY, evidence)
        return target
    }

    /** Mark a phase complete; empty source phases receive an explicit zero decision. */
    fun markPhaseComplete(
        target: JSONObject,
        phase: LegacyImportPhase,
        allowEmpty: Boolean = true,
    ): JSONObject {
        require(phase in TRACKED_PHASES) {
            "Expected-count evidence does not apply to ${phase.code}"
        }
        val evidence = target.optJSONObject(JSON_KEY) ?: newEvidence()
        val phases = evidence.optJSONObject(PHASES_KEY) ?: JSONObject()
        val prior = phases.optJSONObject(phase.code)
        if (prior == null) {
            require(allowEmpty) { "Missing importer evidence for ${phase.code}" }
            phases.put(
                phase.code,
                JSONObject()
                    .put(TOKEN_HASH_KEY, sha256("empty:${phase.code}"))
                    .put(DELTA_KEY, MigrationExpectedCountDelta().toJson())
                    .put(COMPLETE_KEY, true),
            )
        } else {
            prior.put(COMPLETE_KEY, true)
        }
        evidence.put(PHASES_KEY, phases)
        target.put(JSON_KEY, evidence)
        return target
    }

    /** Read only completed data-owned evidence for M13. */
    fun read(targetCountsJson: String?): MigrationExpectedCounts {
        require(!targetCountsJson.isNullOrBlank()) { "M13 expected-count evidence is missing" }
        val root = JSONObject(targetCountsJson)
        val evidence = root.optJSONObject(JSON_KEY)
        require(evidence != null) { "M13 expected-count evidence is missing" }
        require(evidence.getInt("version") == VERSION) {
            "Unsupported M13 expected-count evidence version"
        }
        require(evidence.getString("source") == SOURCE) {
            "M13 expected-count evidence has an invalid owner"
        }
        val phases = evidence.optJSONObject(PHASES_KEY)
        require(phases != null) { "M13 expected-count phase evidence is missing" }
        TRACKED_PHASES.forEach { phase ->
            val state = phases.optJSONObject(phase.code)
            require(state?.optBoolean(COMPLETE_KEY, false) == true) {
                "M13 expected-count evidence phase ${phase.code} is incomplete"
            }
            MigrationExpectedCountDelta.fromJson(checkNotNull(state).getJSONObject(DELTA_KEY))
        }
        val counts = MigrationExpectedCountDelta.fromJson(evidence.getJSONObject(COUNTS_KEY))
        return MigrationExpectedCounts(
            liked = counts.liked,
            playlists = counts.playlists,
            playlistEntries = counts.playlistEntries,
            savedSourceEntities = counts.savedSourceEntities,
            verifiedDownloads = counts.verifiedDownloads,
            checkpointEntries = counts.checkpointEntries,
            lastFmOutbox = counts.lastFmOutbox,
        )
    }

    private fun newEvidence(): JSONObject =
        JSONObject()
            .put("version", VERSION)
            .put("source", SOURCE)
            .put(COUNTS_KEY, MigrationExpectedCountDelta().toJson())
            .put(PHASES_KEY, JSONObject())

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(value.toByteArray(Charsets.UTF_8)).joinToString(separator = "") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private val TRACKED_PHASES =
        setOf(
            LegacyImportPhase.LIBRARY_RELATIONSHIPS,
            LegacyImportPhase.USER_PLAYLISTS,
            LegacyImportPhase.DEVICE_PLAYLISTS,
            LegacyImportPhase.DOWNLOADS,
            LegacyImportPhase.LASTFM_OUTBOX,
            LegacyImportPhase.PLAYBACK_CHECKPOINT,
            LegacyImportPhase.SAVED_PROVIDER_ENTITIES,
        )
}
