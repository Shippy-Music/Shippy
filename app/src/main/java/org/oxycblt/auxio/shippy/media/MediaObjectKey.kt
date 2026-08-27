/*
 * Copyright (c) 2026 Auxio Project
 * MediaObjectKey.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.media

import java.security.MessageDigest
import org.oxycblt.auxio.shippy.domain.TrackCandidate

/**
 * Stable identity for one reusable byte representation of a track.
 *
 * Queue item IDs deliberately do not participate: the same provider rendition must use the same
 * cache entry when it appears more than once, or in a later queue/session. Provider URLs are also
 * deliberately excluded because many providers rotate short-lived URLs for unchanged media.
 */
@JvmInline
value class MediaObjectKey private constructor(val value: String) {
    companion object {
        const val CACHE_KEY_PREFIX = "shippy-media-v1:"
        private const val SCHEMA = "shippy-media-v1"

        fun from(
            candidate: TrackCandidate,
            mimeType: String? = candidate.media?.mimeType,
            container: String? = candidate.media?.container,
            bitrateBps: Int? = candidate.media?.bitrateBps,
        ): MediaObjectKey {
            val rendition =
                listOf(container.orEmpty(), mimeType.orEmpty(), bitrateBps?.toString().orEmpty())
                    .joinToString("\u0000")
            val canonical =
                listOf(
                        SCHEMA,
                        candidate.kind.name,
                        candidate.providerId?.value.orEmpty(),
                        candidate.sourceId,
                        candidate.sourceItemId,
                        rendition,
                    )
                    .joinToString("\u0000")
            val digest =
                MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toByteArray(Charsets.UTF_8))
                    .joinToString("") { byte -> "%02x".format(byte) }
            return MediaObjectKey("$CACHE_KEY_PREFIX$digest")
        }

        /** Compatibility identity for older persisted/test [CandidateId]-only resolved playback. */
        fun fromCandidateId(
            candidateId: org.oxycblt.auxio.shippy.domain.CandidateId
        ): MediaObjectKey {
            val digest =
                MessageDigest.getInstance("SHA-256")
                    .digest(
                        "$SCHEMA\u0000candidate\u0000${candidateId.value}"
                            .toByteArray(Charsets.UTF_8)
                    )
                    .joinToString("") { byte -> "%02x".format(byte) }
            return MediaObjectKey("$CACHE_KEY_PREFIX$digest")
        }

        /** R16 canonical source reference identity for bounded streaming cache. */
        fun fromSourceReference(
            sourceReferenceId: app.shippy.core.identity.SourceReferenceId,
            mediaVariant: String = "DEFAULT",
        ): MediaObjectKey {
            val canonical = "$SCHEMA\u0000source\u0000${sourceReferenceId.value}\u0000$mediaVariant"
            val digest =
                MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toByteArray(Charsets.UTF_8))
                    .joinToString("") { byte -> "%02x".format(byte) }
            return MediaObjectKey("$CACHE_KEY_PREFIX$digest")
        }

        fun fromCustomCacheKey(cacheKey: String): MediaObjectKey {
            require(cacheKey.startsWith(CACHE_KEY_PREFIX)) {
                "Custom cache key must start with $CACHE_KEY_PREFIX"
            }
            return MediaObjectKey(cacheKey)
        }
    }
}
