/*
 * Copyright (c) 2026 Shippy contributors
 * DownloadPublicationGate.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.download

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes changes that publish download availability into Library.
 *
 * Download files and jobs live in separate stores from Library relationships. This gate keeps
 * finalization, removal, and destination reconciliation from publishing stale projections across
 * those stores.
 */
@Singleton
class DownloadPublicationGate @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}
