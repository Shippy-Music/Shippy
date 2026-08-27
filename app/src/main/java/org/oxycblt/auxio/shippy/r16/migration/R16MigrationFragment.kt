/*
 * Copyright (c) 2026 Auxio Project
 * R16MigrationFragment.kt is part of Auxio.
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

import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import app.shippy.data.migration.precutover.R16MigrationPreCutoverPhase
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.databinding.FragmentR16MigrationBinding
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately

/** Isolated migration host that exposes M14 only after the durable READY_TO_SWITCH gate passes. */
@AndroidEntryPoint
internal class R16MigrationFragment : ViewBindingFragment<FragmentR16MigrationBinding>() {
    private val model: R16MigrationViewModel by viewModels()
    private lateinit var createDocumentLauncher: ActivityResultLauncher<String>
    private lateinit var createBackupDocumentLauncher: ActivityResultLauncher<String>
    private var activationHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createDocumentLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
                uri ->
                uri?.let(model::exportFailureReport)
            }
        createBackupDocumentLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
                uri ->
                uri?.let(model::exportReadyBackup)
            }
    }

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentR16MigrationBinding.inflate(inflater)

    override fun onBindingCreated(
        binding: FragmentR16MigrationBinding,
        savedInstanceState: Bundle?,
    ) {
        binding.migrationStart.setOnClickListener { model.startOrContinue() }
        binding.migrationContinue.setOnClickListener { model.startOrContinue() }
        binding.migrationRetry.setOnClickListener { model.retry() }
        binding.migrationCancel.setOnClickListener { model.requestCancellation() }
        binding.migrationActivate.setOnClickListener { model.activateCutover() }
        binding.migrationExport.setOnClickListener {
            createDocumentLauncher.launch("shippy-r16-migration-failure.json")
        }
        binding.migrationExportBackup.setOnClickListener {
            createBackupDocumentLauncher.launch("shippy-r16-verified-backup.zip")
        }
        collectImmediately(model.state, ::render)
    }

    override fun onStart() {
        super.onStart()
        updateKeepScreenOn(model.state.value.activelyRunning)
    }

    override fun onStop() {
        updateKeepScreenOn(false)
        super.onStop()
    }

    override fun onDestroyBinding(binding: FragmentR16MigrationBinding) {
        binding.migrationStart.setOnClickListener(null)
        binding.migrationContinue.setOnClickListener(null)
        binding.migrationRetry.setOnClickListener(null)
        binding.migrationCancel.setOnClickListener(null)
        binding.migrationActivate.setOnClickListener(null)
        binding.migrationExport.setOnClickListener(null)
        binding.migrationExportBackup.setOnClickListener(null)
        super.onDestroyBinding(binding)
    }

    private fun render(state: R16MigrationUiState) {
        val binding = requireBinding()
        val migration = state.migration
        val running = state.activelyRunning
        val fresh =
            migration.status ==
                app.shippy.data.migration.precutover.R16MigrationPreCutoverStatus.NOT_STARTED
        val retryable =
            state.status == R16MigrationUiStatus.FAILED ||
                state.status == R16MigrationUiStatus.CANCELLED

        binding.migrationStatus.text = state.status.name.replace('_', ' ')
        binding.migrationPhase.text =
            migration.currentPhase?.let { "${it.code}  ${it.name.replace('_', ' ')}" } ?: "-"
        binding.migrationCompleted.text =
            "${migration.completedPhases.size} / ${R16MigrationPreCutoverPhase.entries.size} phases completed"
        binding.migrationFailure.isVisible = state.failure != null || state.errorMessage != null
        binding.migrationFailure.text =
            state.failure?.let { "${it.code}: ${it.message}" } ?: state.errorMessage.orEmpty()
        binding.migrationBackupEvidence.isVisible = state.backupExport != null
        binding.migrationBackupEvidence.text =
            state.backupExport
                ?.let {
                    "Verified normalized backup: format ${it.formatVersion}, schema " +
                        "${it.databaseSchemaVersion}, ${it.entries.size} entries."
                }
                .orEmpty()

        binding.migrationProgress.isVisible = running
        binding.migrationStart.isVisible =
            !running && state.status == R16MigrationUiStatus.READY && fresh
        binding.migrationContinue.isVisible =
            !running && state.status == R16MigrationUiStatus.READY && !fresh
        binding.migrationRetry.isVisible =
            !running && (retryable || state.status == R16MigrationUiStatus.ERROR)
        binding.migrationCancel.isVisible = state.cancellationAllowed
        binding.migrationExport.isVisible = state.failure != null
        binding.migrationExportBackup.isVisible =
            !running && state.status == R16MigrationUiStatus.READY_TO_SWITCH
        binding.migrationActivate.isVisible =
            !running && state.status == R16MigrationUiStatus.READY_TO_SWITCH
        updateKeepScreenOn(running)

        if (state.status == R16MigrationUiStatus.ACTIVATED && !activationHandled) {
            activationHandled = true
            requireActivity().recreate()
        }
    }

    private fun updateKeepScreenOn(keepScreenOn: Boolean) {
        if (!isAdded) return
        if (keepScreenOn) {
            requireActivity()
                .window
                .addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            requireActivity()
                .window
                .clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
