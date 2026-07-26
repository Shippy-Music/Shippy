package org.oxycblt.auxio.shippy.lastfm

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class LastFmModule {
    @Binds abstract fun credentials(repository: AndroidKeystoreLastFmCredentialRepository): LastFmCredentialRepository
    @Binds abstract fun overviewCache(cache: AtomicLastFmOverviewCache): LastFmOverviewCache
}
