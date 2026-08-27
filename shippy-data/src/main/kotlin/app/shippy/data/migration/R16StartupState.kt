/*
 * Copyright (c) 2026 Auxio Project
 * R16StartupState.kt is part of Auxio.
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

import java.io.File

/** Durable startup classification. This reads state only and never creates a migration marker. */
public enum class R16StartupState {
    LEGACY,
    MIGRATION_RECOVERY,
    READY_TO_SWITCH,
    R16_ACTIVE,
    RECOVERY_REQUIRED,
}

/**
 * Narrow read-only boundary for choosing the next startup host.
 *
 * Missing state is legacy. Corrupt or unsupported state fails closed into recovery. The bootstrap
 * file remains the only per-install authority; build flags may gate rollout but must not rewrite
 * it.
 */
public class R16StartupStateReader(bootstrapFile: File) {
    private val bootstrap = R16MigrationBootstrapStore(bootstrapFile)

    public fun read(): R16StartupState =
        when (val loaded = bootstrap.load()) {
            R16MigrationBootstrapLoadResult.Missing -> R16StartupState.LEGACY
            is R16MigrationBootstrapLoadResult.Corrupt -> R16StartupState.RECOVERY_REQUIRED
            is R16MigrationBootstrapLoadResult.Loaded -> loaded.state.toStartupState()
        }
}

private fun R16MigrationBootstrapState.toStartupState(): R16StartupState =
    when (status) {
        R16MigrationStatus.NOT_STARTED,
        R16MigrationStatus.PREPARING,
        R16MigrationStatus.IMPORTING,
        R16MigrationStatus.VERIFYING,
        R16MigrationStatus.FAILED_RECOVERABLE -> R16StartupState.MIGRATION_RECOVERY
        R16MigrationStatus.READY_TO_SWITCH -> R16StartupState.READY_TO_SWITCH
        R16MigrationStatus.ACTIVE -> R16StartupState.R16_ACTIVE
    }
