package org.oxycblt.auxio.shippy.lastfm

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local delivery health only. Credentials remain exclusively in [LastFmCredentialRepository].
 *
 * This deliberately is not persisted: after process restart the tracker must observe delivery again.
 */
@Singleton
class LastFmReauthState @Inject constructor() {
    private val mutableRequired = MutableStateFlow(false)
    val required: StateFlow<Boolean> = mutableRequired.asStateFlow()

    fun markRequired() {
        mutableRequired.value = true
    }

    fun clear() {
        mutableRequired.value = false
    }
}
