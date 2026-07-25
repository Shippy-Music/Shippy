/*
 * Copyright (c) 2026 Shippy contributors
 * CrewViewModel.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewRequestResult
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewRuntime
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewRuntimeState

/** Thin UI seam over the application-owned active Crew runtime. */
@HiltViewModel
class CrewViewModel @Inject constructor(
    private val runtime: ActiveCrewRuntime,
) : ViewModel() {
    val state: StateFlow<ActiveCrewRuntimeState> = runtime.state

    fun startHost(): ActiveCrewRequestResult = runtime.startHost()

    fun join(link: String): ActiveCrewRequestResult = runtime.join(link)

    fun end(): ActiveCrewRequestResult = runtime.end()

    fun dismissFailure() = runtime.dismissFailure()
}
