package org.oxycblt.auxio.shippy.provider.youtube

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import org.oxycblt.auxio.shippy.provider.MusicProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class YouTubeMusicModule {
    @Binds @IntoSet @Singleton
    abstract fun provider(provider: YouTubeMusicProvider): MusicProvider
}
