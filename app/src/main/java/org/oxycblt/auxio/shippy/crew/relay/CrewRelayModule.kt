package org.oxycblt.auxio.shippy.crew.relay

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CrewRelayHttpClient

/** Dedicated shared relay client. It has no logging interceptor and never receives invite secrets. */
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
