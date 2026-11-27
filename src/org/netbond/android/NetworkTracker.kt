/*
 * Copyright (C) 2026 Casanova.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.netbond.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * Keeps Wi-Fi and mobile data both connected and reports the usable ones.
 *
 * Android normally tears mobile data down once Wi-Fi is up. Holding a [NetworkRequest] for each
 * transport keeps both alive for as long as the tracker runs (needs CHANGE_NETWORK_STATE).
 */
class NetworkTracker(
    context: Context,
    private val onChange: (List<Tracked>) -> Unit,
) {
    data class Tracked(
        val name: String,
        val network: Network,
        val v4: Boolean,
        val v6: Boolean,
        /** The system's downstream bandwidth estimate in kbit/s, 0 if unknown. */
        val downKbps: Int,
    )

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private val links = LinkedHashMap<Network, Pair<Int, LinkProperties?>>()
    private val bandwidth = HashMap<Network, Int>()
    private val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()

    @Volatile
    internal var isStopped: Boolean = false
        private set

    internal fun registeredCallbacksForTest(): List<ConnectivityManager.NetworkCallback> =
        synchronized(lock) { callbacks.toList() }

    fun start() {
        synchronized(lock) {
            isStopped = false
        }
        for (transport in listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR)) {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .addTransportType(transport)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val snapshot = synchronized(lock) {
                        if (isStopped) return
                        links[network] = transport to cm.getLinkProperties(network)
                        snapshotLocked()
                    }
                    if (!isStopped) onChange(snapshot)
                }

                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                    val snapshot = synchronized(lock) {
                        if (isStopped) return
                        links[network] = transport to lp
                        snapshotLocked()
                    }
                    if (!isStopped) onChange(snapshot)
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val kbps = caps.linkDownstreamBandwidthKbps
                    synchronized(lock) {
                        if (isStopped) return
                        bandwidth[network] = kbps
                    }
                }

                override fun onLost(network: Network) {
                    val snapshot = synchronized(lock) {
                        if (isStopped) return
                        links.remove(network)
                        bandwidth.remove(network)
                        snapshotLocked()
                    }
                    if (!isStopped) onChange(snapshot)
                }
            }
            // requestNetwork (not registerNetworkCallback): this is what keeps the radio up.
            cm.requestNetwork(request, cb)
            synchronized(lock) {
                if (isStopped) {
                    runCatching { cm.unregisterNetworkCallback(cb) }
                } else {
                    callbacks += cb
                }
            }
        }
    }

    fun stop() {
        val toUnregister = synchronized(lock) {
            isStopped = true
            links.clear()
            bandwidth.clear()
            val copy = callbacks.toList()
            callbacks.clear()
            copy
        }
        for (cb in toUnregister) {
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
    }

    fun current(): List<Tracked> = synchronized(lock) {
        if (isStopped) emptyList() else snapshotLocked()
    }

    private fun snapshotLocked(): List<Tracked> {
        val seen = HashMap<String, Int>()
        return links.map { (network, info) ->
            val (transport, lp) = info
            val base = if (transport == NetworkCapabilities.TRANSPORT_WIFI) "Wi-Fi" else "Mobile data"
            // Dual SIM or two Wi-Fi links get "Mobile data 2" etc.; names must stay unique and stable.
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            val name = if (n == 1) base else "$base $n"
            Tracked(name, network, hasV4(lp), hasV6(lp), bandwidth[network] ?: 0)
        }.filter { it.v4 || it.v6 }
    }

    private fun hasV4(lp: LinkProperties?): Boolean {
        if (lp == null) return false
        if (lp.linkAddresses.any { it.address is Inet4Address }) return true
        // IPv6-only mobile data with 464XLAT still carries IPv4 through the CLAT.
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && lp.nat64Prefix != null
    }

    private fun hasV6(lp: LinkProperties?): Boolean {
        if (lp == null) return false
        return lp.linkAddresses.any {
            val a = it.address
            a is Inet6Address && !a.isLinkLocalAddress && !a.isLoopbackAddress &&
                (a.address[0].toInt() and 0xfe) != 0xfc // skip ULA (fc00::/7)
        }
    }
}
