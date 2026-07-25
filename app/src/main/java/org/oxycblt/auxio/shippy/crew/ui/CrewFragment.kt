/*
 * Copyright (c) 2026 Shippy contributors
 * CrewFragment.kt is part of Shippy.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.shippy.crew.ui

import android.view.LayoutInflater
import org.oxycblt.auxio.databinding.FragmentCrewBinding
import org.oxycblt.auxio.ui.ViewBindingFragment

/**
 * Crew's top-level surface.
 *
 * Session actions are intentionally not simulated here. Transport-backed start/join handlers are
 * connected when the Crew engine can create an authenticated session.
 */
class CrewFragment : ViewBindingFragment<FragmentCrewBinding>() {
    override fun onCreateBinding(inflater: LayoutInflater) = FragmentCrewBinding.inflate(inflater)
}
