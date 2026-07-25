/*
 * Copyright (c) 2026 Shippy contributors
 * CollectionDetailPresentation.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.library

import org.oxycblt.auxio.shippy.domain.LibraryCollectionId
import org.oxycblt.auxio.shippy.domain.SystemCollectionKind

/** The Local collection is an entry point into Auxio's real indexed local-library UI. */
internal fun LibraryCollectionId.usesAuxioLocalSurface(): Boolean =
    value == "system:${SystemCollectionKind.LOCAL.id}"

/** A screen must not turn unresolved canonical IDs into fake song rows. */
internal fun ShippyCollectionDetailState.messageKind(): CollectionDetailMessage =
    when {
        this is ShippyCollectionDetailState.Missing -> CollectionDetailMessage.DELETED
        unresolvedTrackCount == 0 -> CollectionDetailMessage.EMPTY
        else -> CollectionDetailMessage.METADATA_PENDING
    }

internal enum class CollectionDetailMessage {
    EMPTY,
    METADATA_PENDING,
    DELETED,
}
