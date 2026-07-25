/*
 * Copyright (c) 2026 Shippy contributors
 * JioSaavnModule.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.provider.jiosaavn

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import org.oxycblt.auxio.shippy.provider.MusicProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class JioSaavnModule {
    @Binds
    @IntoSet
    @Singleton
    abstract fun provider(provider: JioSaavnProvider): MusicProvider
}
