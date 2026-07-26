/*
 * Copyright (c) 2021 Auxio Project
 * RootPreferenceFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.transition.MaterialFadeThrough
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.settings.ui.WrappedDialogPreference
import org.oxycblt.auxio.shippy.download.DownloadDestinationReconciler
import org.oxycblt.auxio.shippy.download.DownloadDestinationState
import org.oxycblt.auxio.shippy.download.SafDownloadStorage
import org.oxycblt.auxio.shippy.download.StorageResult
import org.oxycblt.auxio.shippy.domain.ProviderId
import org.oxycblt.auxio.shippy.provider.ProviderHealth
import org.oxycblt.auxio.util.navigateSafe
import org.oxycblt.auxio.util.showToast
import timber.log.Timber as L

/**
 * The [PreferenceFragmentCompat] that displays the root settings list.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class RootPreferenceFragment : BasePreferenceFragment(R.xml.preferences_root) {
    private val musicModel: MusicViewModel by activityViewModels()
    private val lastFmModel: LastFmSettingsViewModel by viewModels()
    private val providerSettingsModel: ProviderSettingsViewModel by viewModels()
    @Inject lateinit var downloadStorage: SafDownloadStorage
    @Inject lateinit var downloadDestinationReconciler: DownloadDestinationReconciler
    private val downloadDestinationLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                lifecycleScope.launch {
                    when (downloadStorage.selectDestination(uri, null)) {
                        is StorageResult.Success -> refreshDownloadDestination()
                        is StorageResult.Failure ->
                            requireContext().showToast(R.string.msg_download_destination_failed)
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enterTransition = MaterialFadeThrough()
        returnTransition = MaterialFadeThrough()
        exitTransition = MaterialFadeThrough()
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onResume() {
        super.onResume()
        providerSettingsModel.refresh(force = false)
        lifecycleScope.launch { refreshDownloadDestination() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { lastFmModel.state.collect(::renderLastFm) }
                launch { providerSettingsModel.state.collect(::renderProviderSettings) }
                launch {
                    lastFmModel.events.collect { event ->
                        when (event) {
                            LastFmSettingsEvent.ShowCredentialsDialog -> showLastFmCredentialsDialog()
                            is LastFmSettingsEvent.OpenBrowser -> openLastFmAuthorization(event.authorizationUrl)
                        }
                    }
                }
            }
        }
    }

    override fun onOpenDialogPreference(preference: WrappedDialogPreference) {
        when (preference.key) {
            getString(R.string.set_key_music_dirs) -> {
                findNavController()
                    .navigateSafe(RootPreferenceFragmentDirections.musicLocationsSettings())
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        // Hook generic preferences to their specified preferences
        // TODO: These seem like good things to put into a side navigation view, if I choose to
        //  do one.
        when (preference.key) {
            getString(R.string.set_key_ui) -> {
                L.d("Navigating to UI preferences")
                findNavController().navigateSafe(RootPreferenceFragmentDirections.uiPreferences())
            }
            getString(R.string.set_key_personalize) -> {
                L.d("Navigating to personalization preferences")
                findNavController()
                    .navigateSafe(RootPreferenceFragmentDirections.personalizePreferences())
            }
            getString(R.string.set_key_music) -> {
                L.d("Navigating to music preferences")
                findNavController()
                    .navigateSafe(RootPreferenceFragmentDirections.musicPreferences())
            }
            getString(R.string.set_key_audio) -> {
                L.d("Navigating to audio preferences")
                findNavController().navigateSafe(RootPreferenceFragmentDirections.audioPeferences())
            }
            getString(R.string.set_key_download_destination_picker) -> {
                downloadDestinationLauncher.launch(null)
            }
            getString(R.string.set_key_lastfm) -> {
                when (lastFmModel.state.value) {
                    is LastFmSettingsState.Connected -> showLastFmConnectedActions()
                    else -> lastFmModel.onPreferenceClicked()
                }
            }
            getString(R.string.set_key_provider_refresh) -> providerSettingsModel.refresh()
            getString(R.string.set_key_reindex) -> musicModel.refresh()
            getString(R.string.set_key_rescan) -> musicModel.rescan()
            else -> return super.onPreferenceTreeClick(preference)
        }

        return true
    }

    private suspend fun updateDownloadDestinationSummary() {
        val preference =
            findPreference<Preference>(
                getString(R.string.set_key_download_destination_picker)
            ) ?: return
        preference.summary =
            when (val state = downloadStorage.inspectDestination()) {
                DownloadDestinationState.NotSelected ->
                    getString(R.string.set_download_destination_unselected)
                is DownloadDestinationState.Ready ->
                    getString(
                        R.string.set_download_destination_selected,
                        state.destination.displayName,
                    )
                is DownloadDestinationState.Unavailable ->
                    getString(R.string.msg_download_destination_failed)
            }
    }

    private suspend fun refreshDownloadDestination() {
        downloadDestinationReconciler.reconcile()
        updateDownloadDestinationSummary()
    }

    private fun renderLastFm(state: LastFmSettingsState) {
        val preference = findPreference<Preference>(getString(R.string.set_key_lastfm)) ?: return
        preference.isEnabled = state != LastFmSettingsState.Working
        preference.summary =
            when (state) {
                LastFmSettingsState.Working -> getString(R.string.set_lastfm_working)
                LastFmSettingsState.Disconnected -> getString(R.string.set_lastfm_disconnected)
                LastFmSettingsState.PendingAuthorization -> getString(R.string.set_lastfm_pending)
                is LastFmSettingsState.Connected ->
                    getString(R.string.set_lastfm_connected, state.username)
                is LastFmSettingsState.ReauthorizationRequired ->
                    getString(R.string.set_lastfm_reauthorization_required)
                is LastFmSettingsState.Error -> getString(state.error.summaryRes)
            }
    }

    private fun renderProviderSettings(state: ProviderSettingsState) {
        val category =
            findPreference<PreferenceCategory>(getString(R.string.set_key_provider_category))
                ?: return
        val preferred =
            providerPreference(
                key = getString(R.string.set_key_provider_preferred),
                title = getString(R.string.set_provider_preferred),
            ) {
                providerSettingsModel.setPreferred(it)
            }
        val fallback =
            providerPreference(
                key = getString(R.string.set_key_provider_fallback),
                title = getString(R.string.set_provider_fallback),
            ) {
                providerSettingsModel.setFallback(it)
            }
        val refresh =
            findPreference<Preference>(getString(R.string.set_key_provider_refresh))
                ?: Preference(requireContext()).also {
                    it.key = getString(R.string.set_key_provider_refresh)
                    it.title = getString(R.string.set_provider_refresh)
                    category.addPreference(it)
                }
        val entries = state.providers.map { it.displayName }.toTypedArray()
        val values = state.providers.map { it.id.value }.toTypedArray()
        preferred.entries = entries
        preferred.entryValues = values
        preferred.value = state.preferred?.value
        preferred.isEnabled = state.preferred != null
        preferred.summary = state.preferred?.let { providerSummary(state, it) }

        fallback.entries = entries
        fallback.entryValues = values
        fallback.value = state.fallback?.value
        fallback.isEnabled = state.fallback != null
        fallback.summary =
            state.fallback?.let { providerSummary(state, it) }
                ?: getString(R.string.set_provider_no_fallback)
        refresh.isEnabled = !state.refreshing && state.providers.isNotEmpty()
        refresh.summary = if (state.refreshing) getString(R.string.set_provider_refreshing) else null
    }

    private fun providerPreference(
        key: String,
        title: String,
        onChange: (ProviderId) -> Unit,
    ): ListPreference =
        findPreference<ListPreference>(key)
            ?: ListPreference(requireContext()).also { preference ->
                preference.key = key
                preference.title = title
                preference.setOnPreferenceChangeListener { _, value ->
                    (value as? String)?.let(::ProviderId)?.let(onChange)
                    false
                }
                findPreference<PreferenceCategory>(getString(R.string.set_key_provider_category))
                    ?.addPreference(preference)
            }

    private fun providerSummary(state: ProviderSettingsState, id: ProviderId): String {
        val option = state.providers.firstOrNull { it.id == id } ?: return id.value
        val format =
            if (option.checking) {
                R.string.set_provider_checking
            } else {
                when (option.health) {
                    ProviderHealth.AVAILABLE -> R.string.set_provider_available
                    ProviderHealth.DEGRADED -> R.string.set_provider_degraded
                    ProviderHealth.UNAVAILABLE,
                    ProviderHealth.DISABLED -> R.string.set_provider_unavailable
                }
            }
        return getString(format, option.displayName)
    }

    private fun showLastFmCredentialsDialog() {
        val content = layoutInflater.inflate(R.layout.dialog_lastfm_credentials, null)
        val keyContainer = content.findViewById<TextInputLayout>(R.id.lastfm_api_key_container)
        val secretContainer = content.findViewById<TextInputLayout>(R.id.lastfm_api_secret_container)
        val key = content.findViewById<TextInputEditText>(R.id.lastfm_api_key)
        val secret = content.findViewById<TextInputEditText>(R.id.lastfm_api_secret)
        val dialog =
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.set_lastfm_connect)
                .setMessage(R.string.set_lastfm_authorize)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.set_lastfm_connect, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val apiKey = key.text?.toString().orEmpty()
                val apiSecret = secret.text?.toString().orEmpty()
                keyContainer.error = null
                secretContainer.error = null
                if (!LastFmSettingsInput.isValid(apiKey) || !LastFmSettingsInput.isValid(apiSecret)) {
                    val message = getString(R.string.set_lastfm_invalid_input)
                    if (!LastFmSettingsInput.isValid(apiKey)) keyContainer.error = message
                    if (!LastFmSettingsInput.isValid(apiSecret)) secretContainer.error = message
                    return@setOnClickListener
                }
                dialog.dismiss()
                lastFmModel.beginAuthorization(apiKey, apiSecret)
            }
        }
        dialog.show()
    }

    private fun showLastFmConnectedActions() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.set_lastfm)
            .setItems(arrayOf(getString(R.string.set_lastfm_reconnect), getString(R.string.set_lastfm_disconnect))) { _, index ->
                if (index == 0) {
                    lastFmModel.reconnect()
                } else {
                    showLastFmDisconnectConfirmation()
                }
            }
            .show()
    }

    private fun showLastFmDisconnectConfirmation() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.set_lastfm_disconnect)
            .setMessage(R.string.set_lastfm_disconnect_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.set_lastfm_disconnect) { _, _ -> lastFmModel.disconnect() }
            .show()
    }

    private fun openLastFmAuthorization(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            requireContext().showToast(R.string.err_no_app)
        }
    }
}

private val LastFmSettingsError.summaryRes: Int
    get() =
        when (this) {
            LastFmSettingsError.INVALID_INPUT -> R.string.set_lastfm_invalid_input
            LastFmSettingsError.NETWORK -> R.string.set_lastfm_network
            LastFmSettingsError.SERVICE -> R.string.set_lastfm_service
            LastFmSettingsError.INVALID_CREDENTIALS -> R.string.set_lastfm_invalid_credentials
            LastFmSettingsError.AUTHORIZATION_EXPIRED -> R.string.set_lastfm_authorization_expired
            LastFmSettingsError.MALFORMED_RESPONSE -> R.string.set_lastfm_malformed_response
            LastFmSettingsError.UNKNOWN -> R.string.set_lastfm_unknown_error
            LastFmSettingsError.STORAGE -> R.string.set_lastfm_storage_error
        }
