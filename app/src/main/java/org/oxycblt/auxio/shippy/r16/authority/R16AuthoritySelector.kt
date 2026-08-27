/*
 * Copyright (c) 2026 Auxio Project
 * R16AuthoritySelector.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.authority

import android.content.Context
import app.shippy.data.migration.R16StartupState
import app.shippy.data.migration.R16StartupStateReader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import org.oxycblt.auxio.shippy.r16.migration.R16MigrationProcessGate
import org.oxycblt.auxio.shippy.r16.migration.R16MigrationStorageAdapter

/**
 * Startup host modes selected from the durable R16 bootstrap.
 *
 * A validated M14 [R16StartupState.R16_ACTIVE] marker selects [ACTIVE]. Missing, in-progress, and
 * corrupt markers remain on their legacy or recovery hosts; this selector never creates or mutates
 * the marker.
 */
public enum class R16AuthorityMode {
    LEGACY,
    MIGRATION_RECOVERY,
    READY_TO_SWITCH,
    CORRUPT_RECOVERY,
    /** The canonical R16 host selected only after durable M14 activation. */
    ACTIVE,

    /**
     * Defensive fallback for an unavailable injected host; durable selection does not emit this.
     */
    ACTIVE_UNAVAILABLE;

    /** True only for the normal R15 host. */
    public val allowsR15Authority: Boolean
        get() = this == LEGACY

    /** True only for the explicitly enabled R16 host branch. */
    public val allowsR16Authority: Boolean
        get() = this == ACTIVE
}

/**
 * Read-only classification of durable R16 startup state.
 *
 * This seam only reads the bootstrap state and process gate. It does not open a database, player,
 * or runtime; it does not mutate bootstrap state; and it never reaches M14.
 */
public open class R16AuthoritySelector
internal constructor(
    private val startupStateReader: R16StartupStateReader,
    private val processGate: R16MigrationProcessGate,
) {
    @Inject
    public constructor(
        @ApplicationContext context: Context,
        processGate: R16MigrationProcessGate,
    ) : this(
        startupStateReader =
            R16StartupStateReader(R16MigrationStorageAdapter(context).bootstrapPath),
        processGate = processGate,
    )

    public open fun select(): R16AuthorityMode =
        when (startupStateReader.read()) {
            R16StartupState.LEGACY ->
                if (processGate.allowsLegacyAccess()) {
                    R16AuthorityMode.LEGACY
                } else {
                    // A process-local migration lock is also fail-closed until it is released.
                    R16AuthorityMode.MIGRATION_RECOVERY
                }
            R16StartupState.MIGRATION_RECOVERY -> R16AuthorityMode.MIGRATION_RECOVERY
            R16StartupState.READY_TO_SWITCH -> R16AuthorityMode.READY_TO_SWITCH
            R16StartupState.RECOVERY_REQUIRED -> R16AuthorityMode.CORRUPT_RECOVERY
            // The bootstrap reader accepts ACTIVE only after a valid atomic M14 write.
            R16StartupState.R16_ACTIVE -> R16AuthorityMode.ACTIVE
        }
}

/** Application entry point used before Activity field injection is available. */
@EntryPoint
@InstallIn(SingletonComponent::class)
public interface R16AuthorityEntryPoint {
    public fun authoritySelector(): R16AuthoritySelector
}
