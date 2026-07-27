/*
 * Copyright (c) 2026 Auxio Project
 * CrewNetworkChangeMonitor.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wakes an existing reconnect loop when Android's default network changes.
 *
 * This is only a hint. The authenticated transport remains the source of connection truth, and LAN
 * reconnect still works without an internet-capable default network.
 */
internal class CrewNetworkChangeMonitor(context: Context, private val onChange: () -> Unit) :
    Closeable {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val registered = AtomicBoolean(false)
    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange()

            override fun onLost(network: Network) = onChange()

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) = onChange()
        }

    init {
        val manager = connectivityManager
        if (
            manager != null &&
                runCatching {
                        manager.registerDefaultNetworkCallback(callback)
                        true
                    }
                    .getOrDefault(false)
        ) {
            registered.set(true)
        }
    }

    override fun close() {
        if (registered.compareAndSet(true, false)) {
            runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
        }
    }
}
