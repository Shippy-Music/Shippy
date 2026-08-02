/*
 * Copyright (c) 2026 Auxio Project
 * CrewViewModel.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.oxycblt.auxio.shippy.crew.reaction.ActiveCrewReaction
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewActivity
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewReactionSendResult
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewRequestResult
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewRuntime
import org.oxycblt.auxio.shippy.crew.runtime.ActiveCrewRuntimeState

/** Thin UI seam over the application-owned active Crew runtime. */
@HiltViewModel
class CrewViewModel @Inject constructor(private val runtime: ActiveCrewRuntime) : ViewModel() {
    val state: StateFlow<ActiveCrewRuntimeState> = runtime.state
    val reactions: SharedFlow<ActiveCrewReaction> = runtime.reactions
    val activity: StateFlow<List<ActiveCrewActivity>> = runtime.activity
    val peerMediaBlocked: StateFlow<Boolean> = runtime.peerMediaBlocked

    val allowedReactions: List<String>
        get() = runtime.allowedReactions

    fun startHost(): ActiveCrewRequestResult = runtime.startHost()

    fun join(link: String): ActiveCrewRequestResult = runtime.join(link)

    fun end(): ActiveCrewRequestResult = runtime.end()

    suspend fun sendReaction(emoji: String): ActiveCrewReactionSendResult =
        runtime.sendReaction(emoji)

    fun dismissFailure() = runtime.dismissFailure()
}
