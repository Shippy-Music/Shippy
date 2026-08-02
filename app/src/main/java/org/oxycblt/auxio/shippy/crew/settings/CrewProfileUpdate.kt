/*
 * Copyright (c) 2026 Auxio Project
 * CrewProfileUpdate.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.settings

import org.oxycblt.auxio.shippy.crew.core.CrewAction
import org.oxycblt.auxio.shippy.crew.core.CrewMember
import org.oxycblt.auxio.shippy.crew.core.CrewMemberId
import org.oxycblt.auxio.shippy.crew.core.CrewState

/** Builds the one canonical update for an active local member, without exposing device identity. */
internal fun profileUpdateAction(
    state: CrewState,
    localMemberId: CrewMemberId,
    profile: CrewMember,
): CrewAction.MemberUpdated? =
    state.members
        .firstOrNull { it.id == localMemberId }
        ?.takeUnless { it == profile }
        ?.let { profile.copy(id = it.id) }
        ?.let(CrewAction::MemberUpdated)
