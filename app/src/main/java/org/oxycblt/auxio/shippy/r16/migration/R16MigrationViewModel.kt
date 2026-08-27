/*
 * Copyright (c) 2026 Auxio Project
 * R16MigrationViewModel.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.r16.migration

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shippy.data.backup.R16BackupArchiveMetadata
import app.shippy.data.migration.precutover.R16MigrationPreCutoverFailureReport
import app.shippy.data.migration.precutover.R16MigrationPreCutoverOutcome
import app.shippy.data.migration.precutover.R16MigrationPreCutoverPhase
import app.shippy.data.migration.precutover.R16MigrationPreCutoverState
import app.shippy.data.migration.precutover.R16MigrationPreCutoverStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class R16MigrationUiStatus {
    LOADING,
    READY,
    RUNNING,
    CANCELLING,
    CANCELLED,
    FAILED,
    READY_TO_SWITCH,
    ACTIVATING,
    ACTIVATED,
    ERROR,
}

internal data class R16MigrationUiState(
    val status: R16MigrationUiStatus,
    val migration: R16MigrationPreCutoverState,
    val failure: R16MigrationPreCutoverFailureReport? = null,
    val backupExport: R16BackupArchiveMetadata? = null,
    val errorMessage: String? = null,
) {
    val activelyRunning: Boolean
        get() =
            status == R16MigrationUiStatus.RUNNING ||
                status == R16MigrationUiStatus.CANCELLING ||
                status == R16MigrationUiStatus.ACTIVATING

    val cancellationAllowed: Boolean
        get() = status == R16MigrationUiStatus.RUNNING || status == R16MigrationUiStatus.CANCELLING
}

/** Lifecycle owner for the isolated, pre-cutover migration entry screen. */
@HiltViewModel
internal class R16MigrationViewModel
@Inject
constructor(
    private val entryRuntime: R16MigrationEntryRuntime,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(initialState())
    internal val state: StateFlow<R16MigrationUiState> = _state.asStateFlow()
    private var runJob: Job? = null

    init {
        refresh()
    }

    internal fun refresh() {
        if (runJob?.isActive == true) return
        runJob =
            viewModelScope.launch {
                _state.value = _state.value.copy(status = R16MigrationUiStatus.LOADING)
                try {
                    val migration = entryRuntime.loadState()
                    _state.value =
                        R16MigrationUiState(
                            status = statusFor(migration.status),
                            migration = migration,
                        )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    _state.value =
                        _state.value.copy(
                            status = R16MigrationUiStatus.ERROR,
                            errorMessage = "Migration could not start safely. Retry to continue.",
                        )
                }
            }
    }

    internal fun startOrContinue() {
        if (runJob?.isActive == true) return
        runJob =
            viewModelScope.launch {
                entryRuntime.beginOrResume()
                _state.value =
                    _state.value.copy(status = R16MigrationUiStatus.RUNNING, errorMessage = null)
                try {
                    while (true) {
                        val result = entryRuntime.runNext()
                        _state.value =
                            R16MigrationUiState(
                                status = statusFor(result.outcome),
                                migration = result.state,
                                failure = result.failure,
                            )
                        if (result.outcome != R16MigrationPreCutoverOutcome.IMPORTING) break
                        _state.value = _state.value.copy(status = R16MigrationUiStatus.RUNNING)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    _state.value =
                        _state.value.copy(
                            status = R16MigrationUiStatus.ERROR,
                            errorMessage =
                                "Migration stopped safely. Retry to resume from its checkpoint.",
                        )
                }
            }
    }

    internal fun retry() {
        if (_state.value.status == R16MigrationUiStatus.ERROR) refresh() else startOrContinue()
    }

    internal fun requestCancellation() {
        if (!_state.value.cancellationAllowed) return
        entryRuntime.requestCancellation()
        _state.value = _state.value.copy(status = R16MigrationUiStatus.CANCELLING)
    }

    /**
     * Activates only the already-verified migration; failures leave the durable state retryable.
     */
    internal fun activateCutover() {
        if (
            _state.value.status != R16MigrationUiStatus.READY_TO_SWITCH || runJob?.isActive == true
        ) {
            return
        }
        runJob =
            viewModelScope.launch {
                _state.value =
                    _state.value.copy(status = R16MigrationUiStatus.ACTIVATING, errorMessage = null)
                try {
                    entryRuntime.activateCutover()
                    _state.value = _state.value.copy(status = R16MigrationUiStatus.ACTIVATED)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    _state.value =
                        _state.value.copy(
                            status = R16MigrationUiStatus.ERROR,
                            errorMessage =
                                "Activation stopped safely. R16 remains pending; retry to continue.",
                        )
                }
            }
    }

    internal fun exportFailureReport(uri: Uri) {
        viewModelScope.launch {
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    entryRuntime.exportFailureReport(output)
                } ?: error("Unable to open report destination")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _state.value =
                    _state.value.copy(
                        errorMessage = "Could not create the failure report. Try again."
                    )
            }
        }
    }

    internal fun exportReadyBackup(uri: Uri) {
        if (_state.value.status != R16MigrationUiStatus.READY_TO_SWITCH) return
        viewModelScope.launch {
            try {
                val backup =
                    entryRuntime.exportReadyBackup(
                        openOutput = {
                            requireNotNull(context.contentResolver.openOutputStream(uri)) {
                                "Unable to open backup destination"
                            }
                        },
                        reopen = {
                            requireNotNull(context.contentResolver.openInputStream(uri)) {
                                "Unable to reopen backup destination for verification"
                            }
                        },
                    )
                _state.value = _state.value.copy(backupExport = backup, errorMessage = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _state.value =
                    _state.value.copy(
                        errorMessage =
                            "The normalized backup copy could not be verified. Nothing was activated."
                    )
            }
        }
    }

    private fun statusFor(status: R16MigrationPreCutoverStatus): R16MigrationUiStatus =
        when (status) {
            R16MigrationPreCutoverStatus.NOT_STARTED,
            R16MigrationPreCutoverStatus.PREPARING,
            R16MigrationPreCutoverStatus.IMPORTING,
            R16MigrationPreCutoverStatus.VERIFYING -> R16MigrationUiStatus.READY
            R16MigrationPreCutoverStatus.READY_TO_SWITCH -> R16MigrationUiStatus.READY_TO_SWITCH
            R16MigrationPreCutoverStatus.FAILED_RECOVERABLE -> R16MigrationUiStatus.FAILED
        }

    private fun statusFor(outcome: R16MigrationPreCutoverOutcome): R16MigrationUiStatus =
        when (outcome) {
            R16MigrationPreCutoverOutcome.IMPORTING -> R16MigrationUiStatus.RUNNING
            R16MigrationPreCutoverOutcome.READY_TO_SWITCH -> R16MigrationUiStatus.READY_TO_SWITCH
            R16MigrationPreCutoverOutcome.CANCELLED -> R16MigrationUiStatus.CANCELLED
            R16MigrationPreCutoverOutcome.FAILED_RECOVERABLE -> R16MigrationUiStatus.FAILED
        }

    private companion object {
        fun initialState() =
            R16MigrationUiState(
                status = R16MigrationUiStatus.LOADING,
                migration =
                    R16MigrationPreCutoverState(
                        status = R16MigrationPreCutoverStatus.NOT_STARTED,
                        currentPhase = null,
                        completedPhases = emptyList<R16MigrationPreCutoverPhase>(),
                        checkpointPresent = false,
                        backupSatisfied = false,
                        revision = 0L,
                    ),
            )
    }
}
