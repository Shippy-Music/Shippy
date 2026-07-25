/*
 * Copyright (c) 2026 Shippy contributors
 * LastFmSettingsViewModel.kt is part of Shippy.
 */

package org.oxycblt.auxio.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.oxycblt.auxio.shippy.lastfm.LastFmAuthClient
import org.oxycblt.auxio.shippy.lastfm.LastFmAuthFailureCode
import org.oxycblt.auxio.shippy.lastfm.LastFmAuthResult
import org.oxycblt.auxio.shippy.lastfm.LastFmCredentialRepository
import org.oxycblt.auxio.shippy.lastfm.LastFmCredentials

/**
 * Drives the intentionally small Last.fm browser-authorization flow.
 *
 * The API key, secret, and request token are only held in this ViewModel. They are never written
 * to preferences or SavedStateHandle. A process death during browser authorization therefore
 * requires the user to begin again; that is preferable to persisting an un-authorized secret.
 */
@HiltViewModel
class LastFmSettingsViewModel @Inject constructor(
    private val credentials: LastFmCredentialRepository,
    private val auth: LastFmAuthClient,
) : ViewModel() {
    private val mutableState = MutableStateFlow<LastFmSettingsState>(LastFmSettingsState.Working)
    val state: StateFlow<LastFmSettingsState> = mutableState.asStateFlow()

    private val eventChannel = Channel<LastFmSettingsEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    /** Memory-only state for a browser authorization that has not become a Last.fm session yet. */
    private var pendingAuthorization: PendingAuthorization? = null

    init {
        refresh()
    }

    fun onPreferenceClicked() {
        when (val current = mutableState.value) {
            LastFmSettingsState.Disconnected -> eventChannel.trySend(LastFmSettingsEvent.ShowCredentialsDialog)
            is LastFmSettingsState.PendingAuthorization -> completeAuthorization()
            is LastFmSettingsState.Error ->
                when (current.retry) {
                    LastFmRetry.START -> eventChannel.trySend(LastFmSettingsEvent.ShowCredentialsDialog)
                    LastFmRetry.COMPLETE -> completeAuthorization()
                }
            LastFmSettingsState.Working,
            is LastFmSettingsState.Connected -> Unit
        }
    }

    fun beginAuthorization(apiKey: String, apiSecret: String) {
        if (mutableState.value == LastFmSettingsState.Working || !LastFmSettingsInput.isValid(apiKey) || !LastFmSettingsInput.isValid(apiSecret)) {
            mutableState.value = LastFmSettingsState.Error(LastFmRetry.START, LastFmSettingsError.INVALID_INPUT)
            return
        }
        viewModelScope.launch {
            mutableState.value = LastFmSettingsState.Working
            when (val result = auth.requestToken(apiKey.trim(), apiSecret.trim())) {
                is LastFmAuthResult.Success -> {
                    when (val url = auth.authorizationUrl(apiKey.trim(), result.value)) {
                        is LastFmAuthResult.Success -> {
                            pendingAuthorization = PendingAuthorization(apiKey.trim(), apiSecret.trim(), result.value)
                            mutableState.value = LastFmSettingsState.PendingAuthorization
                            eventChannel.send(LastFmSettingsEvent.OpenBrowser(url.value))
                        }
                        is LastFmAuthResult.Failure -> failStart(url)
                    }
                }
                is LastFmAuthResult.Failure -> failStart(result)
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            mutableState.value = LastFmSettingsState.Working
            pendingAuthorization = null
            try {
                credentials.clear()
                mutableState.value = LastFmSettingsState.Disconnected
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = LastFmSettingsState.Error(LastFmRetry.START, LastFmSettingsError.STORAGE)
            }
        }
    }

    fun reconnect() {
        viewModelScope.launch {
            mutableState.value = LastFmSettingsState.Working
            pendingAuthorization = null
            try {
                credentials.clear()
                mutableState.value = LastFmSettingsState.Disconnected
                eventChannel.send(LastFmSettingsEvent.ShowCredentialsDialog)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = LastFmSettingsState.Error(LastFmRetry.START, LastFmSettingsError.STORAGE)
            }
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            try {
                val saved = credentials.load()
                if (pendingAuthorization == null) {
                    mutableState.value = saved?.let { LastFmSettingsState.Connected(it.username) }
                        ?: LastFmSettingsState.Disconnected
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = LastFmSettingsState.Error(LastFmRetry.START, LastFmSettingsError.STORAGE)
            }
        }
    }

    private fun completeAuthorization() {
        val pending = pendingAuthorization
        if (pending == null) {
            mutableState.value = LastFmSettingsState.Error(LastFmRetry.START, LastFmSettingsError.AUTHORIZATION_EXPIRED)
            return
        }
        viewModelScope.launch {
            mutableState.value = LastFmSettingsState.Working
            when (val result = auth.exchangeAuthorizedToken(pending.apiKey, pending.apiSecret, pending.token)) {
                is LastFmAuthResult.Success -> save(result.value)
                is LastFmAuthResult.Failure -> failCompletion(result)
            }
        }
    }

    private suspend fun save(value: LastFmCredentials) {
        try {
            credentials.save(value)
            pendingAuthorization = null
            mutableState.value = LastFmSettingsState.Connected(value.username)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.value = LastFmSettingsState.Error(LastFmRetry.COMPLETE, LastFmSettingsError.STORAGE)
        }
    }

    private fun failStart(failure: LastFmAuthResult.Failure) {
        pendingAuthorization = null
        mutableState.value = LastFmSettingsState.Error(LastFmRetry.START, failure.toSettingsError())
    }

    private fun failCompletion(failure: LastFmAuthResult.Failure) {
        val retry =
            when ((failure as? LastFmAuthResult.Failure.Api)?.kind) {
                LastFmAuthFailureCode.AUTHENTICATION_FAILED,
                LastFmAuthFailureCode.INVALID_AUTH_TOKEN,
                LastFmAuthFailureCode.INVALID_API_KEY,
                LastFmAuthFailureCode.INVALID_SIGNATURE,
                LastFmAuthFailureCode.INVALID_SESSION -> LastFmRetry.START
                else -> LastFmRetry.COMPLETE
            }
        if (retry == LastFmRetry.START) pendingAuthorization = null
        mutableState.value = LastFmSettingsState.Error(retry, failure.toSettingsError())
    }
}

sealed interface LastFmSettingsState {
    data object Working : LastFmSettingsState
    data object Disconnected : LastFmSettingsState
    data object PendingAuthorization : LastFmSettingsState
    data class Connected(val username: String) : LastFmSettingsState
    data class Error(val retry: LastFmRetry, val error: LastFmSettingsError) : LastFmSettingsState
}

enum class LastFmRetry { START, COMPLETE }

enum class LastFmSettingsError {
    INVALID_INPUT,
    NETWORK,
    SERVICE,
    INVALID_CREDENTIALS,
    AUTHORIZATION_EXPIRED,
    MALFORMED_RESPONSE,
    UNKNOWN,
    STORAGE,
}

sealed interface LastFmSettingsEvent {
    data object ShowCredentialsDialog : LastFmSettingsEvent
    data class OpenBrowser(val authorizationUrl: String) : LastFmSettingsEvent
}

internal object LastFmSettingsInput {
    private const val MAX_BYTES = 1024

    fun isValid(value: String): Boolean =
        value.isNotBlank() &&
            value.toByteArray(Charsets.UTF_8).size <= MAX_BYTES &&
            value.none { it.isISOControl() }
}

private data class PendingAuthorization(
    val apiKey: String,
    val apiSecret: String,
    val token: String,
)

private fun LastFmAuthResult.Failure.toSettingsError(): LastFmSettingsError =
    when (this) {
        LastFmAuthResult.Failure.InvalidInput -> LastFmSettingsError.INVALID_INPUT
        LastFmAuthResult.Failure.Network -> LastFmSettingsError.NETWORK
        is LastFmAuthResult.Failure.Http -> LastFmSettingsError.SERVICE
        LastFmAuthResult.Failure.MalformedResponse -> LastFmSettingsError.MALFORMED_RESPONSE
        is LastFmAuthResult.Failure.Api ->
            when (kind) {
                LastFmAuthFailureCode.INVALID_API_KEY,
                LastFmAuthFailureCode.INVALID_SIGNATURE -> LastFmSettingsError.INVALID_CREDENTIALS
                LastFmAuthFailureCode.AUTHENTICATION_FAILED,
                LastFmAuthFailureCode.INVALID_AUTH_TOKEN,
                LastFmAuthFailureCode.INVALID_SESSION -> LastFmSettingsError.AUTHORIZATION_EXPIRED
                LastFmAuthFailureCode.SERVICE_OFFLINE,
                LastFmAuthFailureCode.TEMPORARY_ERROR -> LastFmSettingsError.SERVICE
                else -> LastFmSettingsError.UNKNOWN
            }
    }
