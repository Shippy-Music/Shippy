/*
 * Copyright (c) 2026 Auxio Project
 * CrewRelayModule.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.relay

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class CrewRelayHttpClient

/**
 * Dedicated shared relay client. It has no logging interceptor and never receives invite secrets.
 */
@Module
@InstallIn(SingletonComponent::class)
object CrewRelayModule {
    @Provides
    @Singleton
    @CrewRelayHttpClient
    fun client(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
}
