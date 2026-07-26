/* Copyright (c) 2026 Shippy contributors */
package org.oxycblt.auxio.shippy.crew.media

import org.oxycblt.auxio.shippy.crew.core.CrewMemberId

/**
 * Small, synchronous supplier-side budget for temporary active-Crew media.
 *
 * There is intentionally no pending queue: a busy peer receives RetryLater and its existing
 * retry/cooldown policy decides whether and when to ask again. A permit is keyed by the exact
 * transfer, making retransmitted Requests idempotent while preventing more than one active upload
 * to the same target.
 */
internal class CrewMediaFanoutPolicy(
    private val maxConcurrentUploads: Int = MAX_CONCURRENT_UPLOADS,
) {
    init {
        require(maxConcurrentUploads > 0)
    }

    private val activeTransfers = linkedSetOf<CrewMediaTransferRef>()

    @Synchronized
    fun acquire(transfer: CrewMediaTransferRef): Result =
        when {
            transfer in activeTransfers -> Result.Duplicate
            activeTransfers.size >= maxConcurrentUploads -> Result.RetryLater
            activeTransfers.any { it.supplierMemberId == transfer.supplierMemberId && it.targetMemberId == transfer.targetMemberId } ->
                Result.RetryLater
            else -> {
                activeTransfers += transfer
                Result.Acquired
            }
        }

    @Synchronized
    fun release(transfer: CrewMediaTransferRef) {
        activeTransfers -= transfer
    }

    @Synchronized
    fun hasPermit(transfer: CrewMediaTransferRef) = transfer in activeTransfers

    @Synchronized
    fun releaseForTarget(targetMemberId: CrewMemberId) {
        activeTransfers.removeAll { it.targetMemberId == targetMemberId }
    }

    @Synchronized
    fun releaseAll() {
        activeTransfers.clear()
    }

    @Synchronized
    internal fun activeCountForTest() = activeTransfers.size

    internal enum class Result { Acquired, Duplicate, RetryLater }

    private companion object {
        const val MAX_CONCURRENT_UPLOADS = 2
    }
}
