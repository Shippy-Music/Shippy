/*
 * Copyright (c) 2026 Auxio Project
 * R16ActiveDataRuntimeOwner.kt is part of Auxio.
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
import app.shippy.data.R16DataRuntime
import app.shippy.data.library.R16LibraryReadRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import org.oxycblt.auxio.shippy.r16.library.R16LibraryReadModelsAuthority

/** Opens the process-owned R16 runtime; the seam keeps owner tests independent of Room. */
public fun interface R16DataRuntimeOpener {
    public fun open(): R16DataRuntime
}

/**
 * Process owner for the active R16 data runtime.
 *
 * The selector remains the only durable-state decision point. It opens the R16 database only after
 * a validated M14 bootstrap selects [R16AuthorityMode.ACTIVE]; all other startup modes return no
 * read models.
 */
@Singleton
public class R16ActiveDataRuntimeOwner
@Inject
constructor(
    private val authoritySelector: R16AuthoritySelector,
    private val runtimeOpener: R16DataRuntimeOpener,
) : R16LibraryReadModelsAuthority {
    private val lock = Any()
    private var runtime: R16DataRuntime? = null

    /** Returns the exact process-owned runtime when the durable R16 state is active. */
    public fun activeRuntimeOrNull(): R16DataRuntime? {
        synchronized(lock) {
            if (authoritySelector.select() != R16AuthorityMode.ACTIVE) return null
            return runtime ?: runtimeOpener.open().also { runtime = it }
        }
    }

    override fun activeReadModelsOrNull(): R16LibraryReadRepository? {
        return activeRuntimeOrNull()?.libraryReadModels
    }
}

@Module
@InstallIn(SingletonComponent::class)
public object R16ActiveDataRuntimeOwnerModule {
    @Provides
    @Singleton
    public fun runtimeOpener(@ApplicationContext context: Context): R16DataRuntimeOpener =
        R16DataRuntimeOpener {
            R16DataRuntime.open(context)
        }

    @Provides
    @Singleton
    public fun libraryReadModelsAuthority(
        owner: R16ActiveDataRuntimeOwner
    ): R16LibraryReadModelsAuthority = owner
}
