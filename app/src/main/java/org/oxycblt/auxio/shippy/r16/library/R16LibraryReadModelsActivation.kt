/*
 * Copyright (c) 2026 Auxio Project
 * R16LibraryReadModelsActivation.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.library

import app.shippy.data.library.R16LibraryReadRepository
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.Optional
import javax.inject.Inject

/**
 * Inactive boundary for the R16 Library Paging read path.
 *
 * The app deliberately has no default [R16LibraryReadModelsAuthority]. A lifecycle owner can bind
 * one without opening R16 eagerly; it returns [R16LibraryReadRepository] only after it has selected
 * the shared canonical runtime as the active authority. An R16 screen reads this activation object
 * from its ViewModel rather than opening Room itself.
 */
@Module
@InstallIn(SingletonComponent::class)
interface R16LibraryReadModelsModule {
    @BindsOptionalOf fun authority(): R16LibraryReadModelsAuthority
}

/** Owned by the lifecycle/authority boundary, never by a Library screen. */
fun interface R16LibraryReadModelsAuthority {
    fun activeReadModelsOrNull(): R16LibraryReadRepository?
}

class R16LibraryReadModelsActivation
@Inject
constructor(private val optionalAuthority: Optional<R16LibraryReadModelsAuthority>) {
    /** True only after the active lifecycle owner has supplied the shared canonical runtime. */
    internal val isActive: Boolean
        get() = readModelsOrNull() != null

    /**
     * The future Paging-backed Songs/Library Search ViewModel must use this rather than opening
     * Room itself. A null result means legacy Library remains the sole active authority.
     */
    internal fun readModelsOrNull(): R16LibraryReadRepository? =
        optionalAuthority.orElse(null)?.activeReadModelsOrNull()
}
