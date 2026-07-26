package org.oxycblt.auxio.shippy.persistence.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.shippy.domain.CandidateAvailability
import org.oxycblt.auxio.shippy.domain.CandidateId
import org.oxycblt.auxio.shippy.domain.CandidateKind
import org.oxycblt.auxio.shippy.domain.QueueItem
import org.oxycblt.auxio.shippy.domain.QueueItemId
import org.oxycblt.auxio.shippy.domain.Track
import org.oxycblt.auxio.shippy.domain.TrackId
import org.oxycblt.auxio.shippy.domain.TrackRealm
import org.oxycblt.auxio.shippy.domain.TrackCandidate

class PlaybackCheckpointRepositoryTest {
    @Test fun `sanitizing keeps durable intent and removes transient candidates`() {
        val track = track(listOf(CandidateKind.PROVIDER, CandidateKind.DOWNLOAD, CandidateKind.CREW_TEMPORARY))
        assertEquals(listOf(CandidateKind.PROVIDER), sanitizeCheckpointTrack(track).candidates.map { it.kind })
    }

    @Test fun `checkpoint permits duplicate tracks but not duplicate queue identities`() {
        val track = track(listOf(CandidateKind.PROVIDER))
        val first = QueueItem(QueueItemId("one"), track)
        val second = QueueItem(QueueItemId("two"), track)
        assertEquals(true, CanonicalPlaybackCheckpoint(listOf(first, second), listOf(1, 0), 1, 0, RepeatMode.NONE).valid())
        assertFalse(CanonicalPlaybackCheckpoint(listOf(first, first), emptyList(), 0, 0, RepeatMode.NONE).valid())
    }

    @Test
    fun `checkpoint rejects negative position and oversized heap`() {
        val item = QueueItem(QueueItemId("item"), track(listOf(CandidateKind.PROVIDER)))
        assertFalse(
            CanonicalPlaybackCheckpoint(
                    listOf(item),
                    emptyList(),
                    0,
                    -1,
                    RepeatMode.NONE,
                )
                .valid()
        )
        assertFalse(
            CanonicalPlaybackCheckpoint(
                    List(MAX_PLAYBACK_CHECKPOINT_ITEMS + 1) { index ->
                        item.copy(id = QueueItemId("item-$index"))
                    },
                    emptyList(),
                    0,
                    0,
                    RepeatMode.NONE,
                )
                .valid()
        )
    }

    private fun track(kinds: List<CandidateKind>) =
        Track(
            TrackId("track"),
            TrackRealm.PROVIDER,
            "Track",
            listOf("Artist"),
            candidates =
                kinds.mapIndexed { index, kind ->
                    TrackCandidate(
                        CandidateId("candidate-$index"),
                        TrackId("track"),
                        kind,
                        "source",
                        "$index",
                        CandidateAvailability.RESOLVABLE,
                        providerId =
                            if (kind == CandidateKind.PROVIDER) {
                                org.oxycblt.auxio.shippy.domain.ProviderId("provider")
                            } else {
                                null
                            },
                    )
                },
        )
}
