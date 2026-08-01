/*
 * Copyright (c) 2026 Auxio Project
 * CrewLanDiscovery.kt is part of Auxio.
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
package org.oxycblt.auxio.shippy.crew.lan

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.core.content.ContextCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.Closeable
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.oxycblt.auxio.shippy.crew.core.ProtocolVersion
import org.oxycblt.auxio.shippy.crew.invite.CrewInvite
import org.oxycblt.auxio.shippy.crew.invite.CrewInviteId
import org.oxycblt.auxio.shippy.crew.invite.CrewSessionLocator

private const val CREW_SERVICE_TYPE = "_shippy-crew._tcp."
private const val MAX_DISCOVERED_SERVICES = 32
private const val MAX_RESOLVED_MATCHES = 8
private const val MAX_TXT_VALUE_BYTES = 128
private const val TXT_PROTOCOL = "pv"
private const val TXT_SESSION_LOCATOR = "sid"
private const val TXT_INVITE_ID = "iid"

data class CrewLanIdentity(
    val protocolVersion: ProtocolVersion,
    val sessionLocator: CrewSessionLocator,
    val inviteId: CrewInviteId,
)

/** TXT data is intentionally locator-only. It never contains the invitation secret. */
object CrewLanTxtCodec {
    fun encode(identity: CrewLanIdentity): Map<String, String> =
        mapOf(
            TXT_PROTOCOL to identity.protocolVersion.value.toString(),
            TXT_SESSION_LOCATOR to identity.sessionLocator.value,
            TXT_INVITE_ID to identity.inviteId.value,
        )

    fun decode(attributes: Map<String, ByteArray>): CrewLanIdentity? =
        runCatching {
                val protocol = attributes.requiredUtf8(TXT_PROTOCOL).toInt()
                CrewLanIdentity(
                    ProtocolVersion(protocol),
                    CrewSessionLocator(attributes.requiredUtf8(TXT_SESSION_LOCATOR)),
                    CrewInviteId(attributes.requiredUtf8(TXT_INVITE_ID)),
                )
            }
            .getOrNull()
}

sealed interface CrewLanOperationState {
    data object Starting : CrewLanOperationState

    data object Active : CrewLanOperationState

    data class Failed(val operation: CrewLanFailureOperation, val platformCode: Int? = null) :
        CrewLanOperationState

    data object Closed : CrewLanOperationState
}

enum class CrewLanFailureOperation {
    PERMISSION,
    REGISTRATION,
    DISCOVERY,
}

data class CrewLanAdvertisedService(val serviceName: String)

data class CrewLanRendezvous(
    val identity: CrewLanIdentity,
    val serviceName: String,
    val addresses: List<InetAddress>,
    val port: Int,
    val network: Network?,
) {
    init {
        require(addresses.isNotEmpty()) { "Crew LAN rendezvous needs an address" }
        require(port in 1..65535) { "Crew LAN rendezvous port is invalid" }
    }

    override fun toString() =
        "CrewLanRendezvous(identity=$identity, serviceName=$serviceName, " +
            "addresses=redacted, port=$port, network=redacted)"
}

interface CrewLanAdvertisement : Closeable {
    val state: StateFlow<CrewLanOperationState>
    val service: StateFlow<CrewLanAdvertisedService?>
}

interface CrewLanDiscoverySession : Closeable {
    val state: StateFlow<CrewLanOperationState>
    val matches: StateFlow<List<CrewLanRendezvous>>
}

interface CrewLanDiscovery {
    fun advertise(invite: CrewInvite, signalingPort: Int): CrewLanAdvertisement

    fun discover(invite: CrewInvite): CrewLanDiscoverySession
}

/**
 * QR-scoped Android DNS-SD rendezvous.
 *
 * NSD only locates a short-lived signaling endpoint. It does not authenticate the member and never
 * replaces the invitation proof or WebRTC DTLS transport.
 */
@Singleton
class NsdCrewLanDiscovery @Inject constructor(@ApplicationContext context: Context) :
    CrewLanDiscovery {
    private val applicationContext = context.applicationContext
    private val nsdManager = requireNotNull(context.getSystemService(NsdManager::class.java))
    private val executor: Executor = ContextCompat.getMainExecutor(context)

    override fun advertise(invite: CrewInvite, signalingPort: Int): CrewLanAdvertisement {
        require(signalingPort in 1..65535) { "Crew signaling port is invalid" }
        return Advertisement(
            applicationContext,
            nsdManager,
            executor,
            invite.toLanIdentity(),
            signalingPort,
        )
    }

    override fun discover(invite: CrewInvite): CrewLanDiscoverySession =
        Discovery(applicationContext, nsdManager, executor, invite.toLanIdentity())
}

private class Advertisement(
    context: Context,
    private val nsdManager: NsdManager,
    private val executor: Executor,
    identity: CrewLanIdentity,
    signalingPort: Int,
) : CrewLanAdvertisement {
    private val closed = AtomicBoolean(false)
    private val mutableState =
        MutableStateFlow<CrewLanOperationState>(CrewLanOperationState.Starting)
    private val mutableService = MutableStateFlow<CrewLanAdvertisedService?>(null)
    private val multicastLock = acquireNsdMulticastLock(context)
    private val registrationRequested = AtomicBoolean(false)

    override val state = mutableState.asStateFlow()
    override val service = mutableService.asStateFlow()

    private val listener =
        object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                registrationRequested.set(false)
                if (closed.get()) return
                mutableState.value =
                    CrewLanOperationState.Failed(CrewLanFailureOperation.REGISTRATION, errorCode)
                releaseMulticastLock(multicastLock)
            }

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                if (closed.get()) {
                    unregisterIfRequested()
                    return
                }
                mutableService.value = CrewLanAdvertisedService(serviceInfo.serviceName)
                mutableState.value = CrewLanOperationState.Active
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                registrationRequested.set(false)
                if (!closed.get()) {
                    mutableService.value = null
                    mutableState.value = CrewLanOperationState.Closed
                }
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                registrationRequested.set(false)
                if (!closed.get()) {
                    mutableState.value =
                        CrewLanOperationState.Failed(
                            CrewLanFailureOperation.REGISTRATION,
                            errorCode,
                        )
                }
            }
        }

    init {
        if (multicastLock is MulticastLockResult.Failed) {
            mutableState.value = CrewLanOperationState.Failed(CrewLanFailureOperation.PERMISSION)
        } else {
            val serviceInfo =
                NsdServiceInfo().apply {
                    serviceName = crewServiceName(identity)
                    serviceType = CREW_SERVICE_TYPE
                    port = signalingPort
                    CrewLanTxtCodec.encode(identity).forEach(::setAttribute)
                }
            try {
                registrationRequested.set(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    nsdManager.registerService(
                        serviceInfo,
                        NsdManager.PROTOCOL_DNS_SD,
                        executor,
                        listener,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
                }
            } catch (_: SecurityException) {
                registrationRequested.set(false)
                mutableState.value =
                    CrewLanOperationState.Failed(CrewLanFailureOperation.PERMISSION)
                releaseMulticastLock(multicastLock)
            } catch (_: RuntimeException) {
                registrationRequested.set(false)
                mutableState.value =
                    CrewLanOperationState.Failed(CrewLanFailureOperation.REGISTRATION)
                releaseMulticastLock(multicastLock)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        unregisterIfRequested()
        mutableService.value = null
        mutableState.value = CrewLanOperationState.Closed
        releaseMulticastLock(multicastLock)
    }

    private fun unregisterIfRequested() {
        if (!registrationRequested.get()) return
        try {
            nsdManager.unregisterService(listener)
            registrationRequested.set(false)
        } catch (_: IllegalArgumentException) {
            Unit
        } catch (_: RuntimeException) {
            Unit
        }
    }
}

private class Discovery(
    context: Context,
    private val nsdManager: NsdManager,
    private val executor: Executor,
    private val expectedIdentity: CrewLanIdentity,
) : CrewLanDiscoverySession {
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val mutableState =
        MutableStateFlow<CrewLanOperationState>(CrewLanOperationState.Starting)
    private val mutableMatches = MutableStateFlow<List<CrewLanRendezvous>>(emptyList())
    private val multicastLock = acquireNsdMulticastLock(context)
    private val pendingResolutions = ArrayDeque<NsdServiceInfo>()
    private val knownServiceKeys = mutableSetOf<String>()
    private val queuedServiceKeys = mutableSetOf<String>()
    private val resolvedByServiceKey = linkedMapOf<String, CrewLanRendezvous>()
    private val discoveryRequested = AtomicBoolean(false)
    private var resolutionActive = false

    override val state = mutableState.asStateFlow()
    override val matches = mutableMatches.asStateFlow()

    private val discoveryListener =
        object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                if (closed.get()) {
                    stopDiscoveryIfRequested()
                    return
                }
                mutableState.value = CrewLanOperationState.Active
            }

            override fun onDiscoveryStopped(serviceType: String) {
                discoveryRequested.set(false)
                if (!closed.get()) mutableState.value = CrewLanOperationState.Closed
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (closed.get() || !isCrewServiceType(serviceInfo.serviceType)) return
                val startResolution =
                    synchronized(lock) {
                        val key = serviceInfo.serviceKey()
                        if (
                            key in queuedServiceKeys ||
                                pendingResolutions.size >= MAX_DISCOVERED_SERVICES
                        ) {
                            false
                        } else {
                            knownServiceKeys += key
                            queuedServiceKeys += key
                            pendingResolutions += serviceInfo
                            !resolutionActive
                        }
                    }
                if (startResolution) resolveNext()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val key = serviceInfo.serviceKey()
                synchronized(lock) {
                    knownServiceKeys -= key
                    queuedServiceKeys -= key
                    pendingResolutions.removeAll { it.serviceKey() == key }
                    resolvedByServiceKey.remove(key)
                    publishMatchesLocked()
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryRequested.set(false)
                if (closed.get()) return
                mutableState.value =
                    CrewLanOperationState.Failed(CrewLanFailureOperation.DISCOVERY, errorCode)
                releaseMulticastLock(multicastLock)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryRequested.set(false)
                if (!closed.get()) {
                    mutableState.value =
                        CrewLanOperationState.Failed(CrewLanFailureOperation.DISCOVERY, errorCode)
                }
            }
        }

    init {
        if (multicastLock is MulticastLockResult.Failed) {
            mutableState.value = CrewLanOperationState.Failed(CrewLanFailureOperation.PERMISSION)
        } else {
            try {
                discoveryRequested.set(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    nsdManager.discoverServices(
                        CREW_SERVICE_TYPE,
                        NsdManager.PROTOCOL_DNS_SD,
                        null as Network?,
                        executor,
                        discoveryListener,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    nsdManager.discoverServices(
                        CREW_SERVICE_TYPE,
                        NsdManager.PROTOCOL_DNS_SD,
                        discoveryListener,
                    )
                }
            } catch (_: SecurityException) {
                discoveryRequested.set(false)
                mutableState.value =
                    CrewLanOperationState.Failed(CrewLanFailureOperation.PERMISSION)
                releaseMulticastLock(multicastLock)
            } catch (_: RuntimeException) {
                discoveryRequested.set(false)
                mutableState.value = CrewLanOperationState.Failed(CrewLanFailureOperation.DISCOVERY)
                releaseMulticastLock(multicastLock)
            }
        }
    }

    private fun resolveNext() {
        val serviceInfo =
            synchronized(lock) {
                if (closed.get() || resolutionActive) return
                (if (pendingResolutions.isEmpty()) null else pendingResolutions.removeFirst())
                    ?.also { resolutionActive = true }
            } ?: return
        val listener =
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    finishResolution(serviceInfo, null)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    finishResolution(serviceInfo, serviceInfo.toRendezvousOrNull())
                }
            }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                nsdManager.resolveService(serviceInfo, executor, listener)
            } else {
                @Suppress("DEPRECATION") nsdManager.resolveService(serviceInfo, listener)
            }
        } catch (_: RuntimeException) {
            finishResolution(serviceInfo, null)
        }
    }

    private fun finishResolution(serviceInfo: NsdServiceInfo, rendezvous: CrewLanRendezvous?) {
        val continueResolving =
            synchronized(lock) {
                resolutionActive = false
                val key = serviceInfo.serviceKey()
                queuedServiceKeys -= key
                if (
                    !closed.get() &&
                        rendezvous != null &&
                        key in knownServiceKeys &&
                        rendezvous.identity == expectedIdentity
                ) {
                    resolvedByServiceKey[key] = rendezvous
                    while (resolvedByServiceKey.size > MAX_RESOLVED_MATCHES) {
                        resolvedByServiceKey.remove(resolvedByServiceKey.keys.first())
                    }
                    publishMatchesLocked()
                }
                pendingResolutions.isNotEmpty() && !closed.get()
            }
        if (continueResolving) resolveNext()
    }

    private fun NsdServiceInfo.toRendezvousOrNull(): CrewLanRendezvous? {
        val identity =
            resolveCrewLanIdentity(serviceName, attributes, expectedIdentity) ?: return null
        val addresses =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    hostAddresses
                } else {
                    @Suppress("DEPRECATION") listOfNotNull(host)
                }
                .filterNot { it.isAnyLocalAddress }
                .distinct()
        if (addresses.isEmpty() || port !in 1..65535) return null
        val resolvedNetwork =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) network else null
        return CrewLanRendezvous(identity, serviceName, addresses, port, resolvedNetwork)
    }

    private fun publishMatchesLocked() {
        mutableMatches.value = resolvedByServiceKey.toSortedMap().values.toList()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopDiscoveryIfRequested()
        synchronized(lock) {
            pendingResolutions.clear()
            knownServiceKeys.clear()
            queuedServiceKeys.clear()
            resolvedByServiceKey.clear()
            publishMatchesLocked()
        }
        mutableState.value = CrewLanOperationState.Closed
        releaseMulticastLock(multicastLock)
    }

    private fun stopDiscoveryIfRequested() {
        if (!discoveryRequested.get()) return
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
            discoveryRequested.set(false)
        } catch (_: IllegalArgumentException) {
            Unit
        } catch (_: RuntimeException) {
            Unit
        }
    }
}

private sealed interface MulticastLockResult {
    data object NotRequired : MulticastLockResult

    data class Acquired(val lock: WifiManager.MulticastLock) : MulticastLockResult

    data object Failed : MulticastLockResult
}

private fun acquireNsdMulticastLock(context: Context): MulticastLockResult {
    if (!requiresLegacyMulticastLock()) return MulticastLockResult.NotRequired
    return try {
        val wifiManager =
            context.applicationContext.getSystemService(WifiManager::class.java)
                ?: return MulticastLockResult.Failed
        val lock =
            wifiManager.createMulticastLock("shippy-crew-nsd").apply {
                setReferenceCounted(false)
                acquire()
            }
        MulticastLockResult.Acquired(lock)
    } catch (_: SecurityException) {
        MulticastLockResult.Failed
    } catch (_: RuntimeException) {
        MulticastLockResult.Failed
    }
}

private fun requiresLegacyMulticastLock(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) < 7

private fun releaseMulticastLock(result: MulticastLockResult) {
    val lock = (result as? MulticastLockResult.Acquired)?.lock ?: return
    if (lock.isHeld) {
        try {
            lock.release()
        } catch (_: RuntimeException) {
            Unit
        }
    }
}

private fun Map<String, ByteArray>.requiredUtf8(key: String): String {
    val bytes = requireNotNull(this[key]) { "Crew LAN TXT key is missing" }
    require(bytes.size in 1..MAX_TXT_VALUE_BYTES) { "Crew LAN TXT value has invalid size" }
    return bytes.toString(StandardCharsets.UTF_8).also {
        require(it.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) {
            "Crew LAN TXT value is not valid UTF-8"
        }
    }
}

private fun CrewInvite.toLanIdentity() = CrewLanIdentity(protocolVersion, sessionLocator, inviteId)

internal fun crewServiceName(identity: CrewLanIdentity) =
    "shippy-${identity.inviteId.value.take(24)}"

/**
 * Some Android NSD implementations resolve the address and port but omit TXT attributes. The
 * QR-scoped service instance name is sufficient for rendezvous in that case: the following TCP
 * handshake still proves possession of the full short-lived invitation secret. A present but
 * mismatched TXT identity is never accepted.
 */
internal fun resolveCrewLanIdentity(
    serviceName: String,
    attributes: Map<String, ByteArray>,
    expected: CrewLanIdentity,
): CrewLanIdentity? {
    val advertised = CrewLanTxtCodec.decode(attributes)
    if (advertised != null) return advertised.takeIf { it == expected }
    return expected.takeIf { serviceName.matchesCrewServiceName(crewServiceName(expected)) }
}

private fun String.matchesCrewServiceName(expected: String): Boolean {
    if (equals(expected, ignoreCase = true)) return true
    if (!startsWith("$expected (", ignoreCase = true) || !endsWith(')')) return false
    return substring(expected.length + 2, lastIndex).toIntOrNull()?.let { it > 1 } == true
}

private fun isCrewServiceType(value: String) =
    value.trimEnd('.').equals(CREW_SERVICE_TYPE.trimEnd('.'), ignoreCase = true)

private fun NsdServiceInfo.serviceKey() =
    serviceName + '\u0000' + serviceType.trimEnd('.').lowercase()

@Module
@InstallIn(SingletonComponent::class)
abstract class CrewLanModule {
    @Binds abstract fun discovery(implementation: NsdCrewLanDiscovery): CrewLanDiscovery
}
