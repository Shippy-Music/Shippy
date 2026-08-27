/*
 * Copyright (c) 2026 Auxio Project
 * R16MigrationProcessGate.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.migration

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-local fail-closed gate for legacy R15 access while an R16 attempt exists.
 *
 * The bootstrap file is deliberately treated as an opaque marker here. An empty or corrupt file
 * still means that a migration attempt may need recovery, so normal R15 work must remain paused.
 */
@Singleton
public class R16MigrationProcessGate internal constructor(private val bootstrapFile: File) {
    private val stateLock = Any()
    private var migrationLocked = false
    private var activeLegacyLeases = 0

    @Inject
    public constructor(
        @ApplicationContext context: Context
    ) : this(R16MigrationStorageAdapter(context).bootstrapPath)

    /** True when normal R15 work must not resolve legacy-backed dependencies. */
    public fun isBlocked(): Boolean = synchronized(stateLock) { migrationLocked || markerPresent() }

    /**
     * True when startup must render the isolated migration host instead of the legacy nav graph.
     */
    public fun requiresMigrationEntry(): Boolean = isBlocked()

    /** True only while no durable attempt marker or process-local migration lock is present. */
    public fun allowsLegacyAccess(): Boolean = !isBlocked()

    /** Claims a read lease so the marker check and legacy dependency resolution cannot be split. */
    public fun tryAcquireLegacyAccess(): Closeable? {
        synchronized(stateLock) {
            if (migrationLocked || markerPresent()) return null
            activeLegacyLeases++
        }
        return Lease {
            synchronized(stateLock) {
                check(activeLegacyLeases > 0) { "Legacy access lease underflow" }
                activeLegacyLeases--
            }
        }
    }

    /** Runs a synchronous legacy operation while holding the process read lease. */
    public fun <T> runWithLegacyAccess(block: () -> T): T? {
        val lease = tryAcquireLegacyAccess() ?: return null
        return try {
            block()
        } finally {
            lease.close()
        }
    }

    /** Runs a suspending legacy operation while holding the process read lease. */
    public suspend fun <T> withLegacyAccess(block: suspend () -> T): T? {
        val lease = tryAcquireLegacyAccess() ?: return null
        return try {
            block()
        } finally {
            lease.close()
        }
    }

    /**
     * Claims the process-local migration lock. The returned lease releases it exactly once. A
     * durable marker is intentionally not created here; M0 owns that state transition.
     */
    public fun tryAcquireMigrationLock(): Closeable? {
        synchronized(stateLock) {
            if (migrationLocked || activeLegacyLeases != 0) return null
            migrationLocked = true
        }
        return Lease {
            synchronized(stateLock) {
                check(migrationLocked) { "Migration lock already released" }
                migrationLocked = false
            }
        }
    }

    private class Lease(private val release: () -> Unit) : Closeable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    private fun markerPresent(): Boolean =
        try {
            bootstrapFile.exists() || File(bootstrapFile.path + ".bak").exists()
        } catch (_: Exception) {
            // An unreadable marker path is not evidence that it is safe to use R15.
            true
        }
}
