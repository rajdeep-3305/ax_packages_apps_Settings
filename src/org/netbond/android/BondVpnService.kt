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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.service.quicksettings.TileService
import android.util.Log
import com.android.settings.R
import com.android.settings.SettingsActivity
import com.android.settings.SubSettings
import com.android.settings.netbond.NetBondFragment
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Captures all traffic with a TUN and hands it to the Go engine, which re-dials every flow over
 * Wi-Fi or mobile data. The app itself is excluded from the VPN, so the engine's own sockets
 * (pinned to a specific network) leave the phone directly.
 */
class BondVpnService : VpnService() {

    companion object {
        const val ACTION_START = "org.netbond.android.START"
        const val ACTION_STOP = "org.netbond.android.STOP"
        const val ACTION_RESTART = "org.netbond.android.RESTART"
        const val ACTION_REFRESH_UI = "org.netbond.android.REFRESH_UI"

        private const val TAG = "NetBond"
        private const val CHANNEL = "netbond_status"
        private const val NOTIF_ID = 1001
        private const val MTU = 9000

        /** How long to wait for the first network before bringing the VPN up anyway. */
        private const val NETWORK_WAIT_MS = 3000L
        private const val REFRESH_INTERVAL_MS = 1000L

        private val LOCAL_IPV4_ROUTES = listOf(
            "10.0.0.0" to 8,
            "172.16.0.0" to 12,
            "192.168.0.0" to 16,
            "169.254.0.0" to 16,
            "224.0.0.0" to 4,
        )
        internal val LOCAL_IPV6_ROUTES = listOf(
            "fc00::" to 7,
            "fe80::" to 10,
            "ff00::" to 8,
        )

        // Single process-wide FIFO executor serializes all native engine calls across service
        // instances so an old teardown always finishes strictly before a replacement starts.
        private val sharedEngineExec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "netbond-engine").apply { isDaemon = true }
        }
        private val lifecycleLock = Any()
        private val globalSessionGen = AtomicLong(0L)
        private var activeOwner: BondVpnService? = null

        val running: Boolean get() = Engine.running

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, BondVpnService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            if (Engine.phase.value == Engine.Phase.OFF && activeOwner == null) return
            runCatching {
                ctx.startService(Intent(ctx, BondVpnService::class.java).setAction(ACTION_STOP))
            }.onFailure {
                if (activeOwner == null) Engine.setPhase(Engine.Phase.OFF)
            }
        }

        fun restart(ctx: Context) {
            if (Engine.running) {
                runCatching {
                    ctx.startService(Intent(ctx, BondVpnService::class.java).setAction(ACTION_RESTART))
                }
            }
        }

        fun refreshUi(ctx: Context) {
            if (Engine.phase.value != Engine.Phase.OFF) {
                runCatching {
                    ctx.startService(Intent(ctx, BondVpnService::class.java).setAction(ACTION_REFRESH_UI))
                }
            }
        }

        internal fun awaitEngineIdleForTest(timeoutMs: Long = 5000L) {
            sharedEngineExec.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
        }

        internal fun resetRuntimeForTest() {
            synchronized(lifecycleLock) {
                activeOwner = null
                globalSessionGen.incrementAndGet()
            }
            awaitEngineIdleForTest()
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var tracker: NetworkTracker? = null
    private var mySessionGen: Long = 0L
    private var engineStartedInSession: Boolean = false
    private var batteryReceiverRegistered: Boolean = false
    private var lastNetworksJson: String? = null
    private var lastUnderlyingHandles: List<Long>? = null
    private val main = Handler(Looper.getMainLooper())
    private var pendingEstablish: Runnable? = null

    private val refresh = object : Runnable {
        override fun run() {
            val gen = mySessionGen
            if (!isActiveSession(gen) || Engine.phase.value != Engine.Phase.ON) return
            sharedEngineExec.execute {
                if (globalSessionGen.get() != gen) return@execute
                Engine.sample()
                main.post {
                    if (!isActiveSession(gen) || Engine.phase.value != Engine.Phase.ON) return@post
                    updateNotification()
                    main.removeCallbacks(this)
                    main.postDelayed(this, REFRESH_INTERVAL_MS)
                }
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                val gen = mySessionGen
                if (!isActiveSession(gen)) return
                val pm = getSystemService(PowerManager::class.java)
                val isSave = pm?.isPowerSaveMode ?: false
                Engine.setBatterySaverActive(isSave)
                if (Engine.phase.value == Engine.Phase.ON) {
                    val modeId = Engine.effectiveMode.id.toLong()
                    sharedEngineExec.execute {
                        if (globalSessionGen.get() != gen) return@execute
                        runCatching { Engine.backend.setMode(modeId) }
                            .onFailure { Log.e(TAG, "setMode", it) }
                    }
                }
                updateNotification()
                requestTileUpdate()
            }
        }
    }

    private fun isActiveSession(gen: Long, expectedTracker: NetworkTracker? = null): Boolean =
        activeOwner === this &&
            gen != 0L &&
            mySessionGen == gen &&
            globalSessionGen.get() == gen &&
            (expectedTracker == null || (!expectedTracker.isStopped && tracker === expectedTracker))

    private fun cancelPendingEstablish() {
        pendingEstablish?.let { main.removeCallbacks(it) }
        pendingEstablish = null
    }

    private fun scheduleEstablish(gen: Long) {
        cancelPendingEstablish()
        val r = Runnable {
            if (isActiveSession(gen)) establish(gen)
        }
        pendingEstablish = r
        main.postDelayed(r, NETWORK_WAIT_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown(stopSelfAfter = true)
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                if (activeOwner === this && Engine.phase.value != Engine.Phase.OFF) {
                    restartEngine()
                }
                return START_STICKY
            }
            ACTION_REFRESH_UI -> {
                val gen = mySessionGen
                if (isActiveSession(gen) && Engine.phase.value == Engine.Phase.ON) {
                    val modeId = Engine.effectiveMode.id.toLong()
                    sharedEngineExec.execute {
                        if (globalSessionGen.get() != gen) return@execute
                        runCatching { Engine.backend.setMode(modeId) }
                            .onFailure { Log.e(TAG, "setMode", it) }
                    }
                    updateNotification()
                    requestTileUpdate()
                }
                return START_STICKY
            }
        }
        // ACTION_START, or the system starting us for always-on VPN (action android.net.VpnService).
        goForeground()
        if (activeOwner !== this) {
            begin()
        }
        return START_STICKY
    }

    private fun restartEngine() {
        if (activeOwner !== this) return
        val wasRunning = engineStartedInSession
        val gen = globalSessionGen.incrementAndGet()
        mySessionGen = gen
        engineStartedInSession = false
        main.removeCallbacks(refresh)
        cancelPendingEstablish()
        runCatching { tun?.close() }
        tun = null
        lastNetworksJson = null
        lastUnderlyingHandles = null
        Engine.setPhase(Engine.Phase.STARTING)
        requestTileUpdate()
        updateNotification()
        sharedEngineExec.execute {
            if (wasRunning) {
                runCatching { Engine.backend.stop() }
            }
            main.post {
                if (!isActiveSession(gen) || Engine.phase.value != Engine.Phase.STARTING) return@post
                val nets = tracker?.current().orEmpty()
                if (nets.isNotEmpty()) {
                    establish(gen)
                } else {
                    scheduleEstablish(gen)
                }
            }
        }
    }

    /**
     * Starts tracking networks first and brings the TUN up once at least one is known so the
     * VPN does not transiently report no connectivity before an interface arrives.
     */
    private fun begin() {
        val prevOwner = synchronized(lifecycleLock) { activeOwner }
        if (prevOwner != null && prevOwner !== this) {
            prevOwner.shutdown(stopSelfAfter = true)
        }
        val gen = synchronized(lifecycleLock) {
            val next = globalSessionGen.incrementAndGet()
            mySessionGen = next
            engineStartedInSession = false
            activeOwner = this
            next
        }
        Engine.setPhase(Engine.Phase.STARTING)
        Engine.loadSettings(this)

        val pm = getSystemService(PowerManager::class.java)
        Engine.setBatterySaverActive(pm?.isPowerSaveMode ?: false)
        if (!batteryReceiverRegistered) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(
                        batteryReceiver,
                        IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                        Context.RECEIVER_NOT_EXPORTED,
                    )
                } else {
                    registerReceiver(
                        batteryReceiver,
                        IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                    )
                }
                batteryReceiverRegistered = true
            } catch (e: Exception) {
                Log.w(TAG, "Cannot register battery saver receiver", e)
            }
        }

        requestTileUpdate()
        tracker?.stop()
        lateinit var t: NetworkTracker
        t = NetworkTracker(this) { nets ->
            if (t.isStopped) return@NetworkTracker
            main.post { onNetworks(t, nets) }
        }
        tracker = t
        t.start()
        scheduleEstablish(gen)
    }

    internal fun startSessionForTest(fd: Long, onEstablished: () -> Unit = {}) {
        val prevOwner = synchronized(lifecycleLock) { activeOwner }
        if (prevOwner != null && prevOwner !== this) {
            prevOwner.shutdown(stopSelfAfter = false)
        }
        val modeId = Engine.effectiveMode.id.toLong()
        synchronized(lifecycleLock) {
            val gen = globalSessionGen.incrementAndGet()
            mySessionGen = gen
            activeOwner = this
            engineStartedInSession = true
            Engine.setPhase(Engine.Phase.ON)
            sharedEngineExec.execute {
                if (globalSessionGen.get() != gen) return@execute
                runCatching { Engine.backend.setMode(modeId) }
                runCatching { Engine.backend.start(fd, MTU.toLong()) }
                onEstablished()
            }
        }
    }

    internal fun triggerShutdownForTest(stopSelfAfter: Boolean = false) {
        shutdown(stopSelfAfter = stopSelfAfter)
    }

    private fun establish(gen: Long) {
        cancelPendingEstablish()
        if (!isActiveSession(gen) || Engine.phase.value != Engine.Phase.STARTING || tun != null) return
        val nets = tracker?.current().orEmpty()

        val builder = Builder()
            .setSession(getString(R.string.netbond_app_name))
            .setMtu(MTU)
            .addAddress("198.18.0.1", 24)
            .addRoute("0.0.0.0", 0)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    for ((addr, prefix) in LOCAL_IPV4_ROUTES) {
                        runCatching { excludeRoute(IpPrefix(InetAddress.getByName(addr), prefix)) }
                    }
                }
            }
            // DNS goes into the TUN like everything else and is balanced over both networks.
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .addDnsServer("9.9.9.9")
            .addDisallowedApplication(packageName)
            .setConfigureIntent(
                PendingIntent.getActivity(this, 0, settingsIntent(), PendingIntent.FLAG_IMMUTABLE),
            )

        // Exclude user-selected apps (banking, DRM, streaming)
        val excluded = Engine.excludedApps.value
        for (pkg in excluded) {
            try {
                builder.addDisallowedApplication(pkg)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot exclude package $pkg", e)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        if (nets.isNotEmpty()) builder.setUnderlyingNetworks(nets.map { it.network }.toTypedArray())
        // IPv6: a ULA so apps still prefer IPv4 on dual-stack sites.
        runCatching {
            builder.addAddress("fd6e:626f:6e64::1", 64)
            builder.addRoute("::", 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                for ((addr, prefix) in LOCAL_IPV6_ROUTES) {
                    runCatching { builder.excludeRoute(IpPrefix(InetAddress.getByName(addr), prefix)) }
                }
            }
            builder.addDnsServer("2606:4700:4700::1111")
            builder.addDnsServer("2620:fe::fe")
        }.onFailure { Log.w(TAG, "IPv6 not captured", it) }

        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e)
            null
        }
        if (pfd == null) {
            // Not prepared (permission revoked) or another VPN took over.
            shutdown(stopSelfAfter = true)
            return
        }
        val fd = pfd.detachFd().toLong()
        val arr = buildNetworksJson(nets)
        val handles = nets.map { it.network.networkHandle }
        val modeId = Engine.effectiveMode.id.toLong()
        val enqueued = synchronized(lifecycleLock) {
            if (activeOwner !== this || mySessionGen != gen || globalSessionGen.get() != gen) {
                false
            } else {
                tun = pfd
                engineStartedInSession = true
                lastNetworksJson = arr
                lastUnderlyingHandles = handles
                sharedEngineExec.execute {
                    if (globalSessionGen.get() != gen) {
                        runCatching { ParcelFileDescriptor.adoptFd(fd.toInt()).close() }
                        return@execute
                    }
                    runCatching { Engine.backend.setMode(modeId) }
                        .onFailure { Log.e(TAG, "setMode", it) }
                    runCatching { Engine.backend.setNetworks(arr) }
                        .onFailure { Log.e(TAG, "setNetworks", it) }
                    try {
                        // The engine owns the descriptor from here and closes it in stop().
                        Engine.backend.start(fd, MTU.toLong())
                    } catch (e: Exception) {
                        Log.e(TAG, "engine start failed", e)
                        main.post {
                            if (isActiveSession(gen)) shutdown(stopSelfAfter = true)
                        }
                    }
                }
                true
            }
        }
        if (!enqueued) {
            runCatching { ParcelFileDescriptor.adoptFd(fd.toInt()).close() }
            return
        }
        setUnderlyingNetworks(if (nets.isEmpty()) null else nets.map { it.network }.toTypedArray())
        Engine.setPhase(Engine.Phase.ON)
        main.removeCallbacks(refresh)
        main.post(refresh)
        requestTileUpdate()
    }

    private fun onNetworks(source: NetworkTracker, nets: List<NetworkTracker.Tracked>) {
        val gen = mySessionGen
        if (!isActiveSession(gen, source)) return
        when (Engine.phase.value) {
            Engine.Phase.OFF -> return
            Engine.Phase.STARTING -> {
                pushNetworks(gen, nets)
                if (nets.isNotEmpty()) establish(gen)
            }
            Engine.Phase.ON -> {
                pushNetworks(gen, nets)
                updateNotification()
            }
        }
    }

    private fun buildNetworksJson(nets: List<NetworkTracker.Tracked>): String {
        val arr = JSONArray()
        for (n in nets) {
            arr.put(
                JSONObject()
                    .put("name", n.name)
                    .put("handle", n.network.networkHandle.toString())
                    .put("v4", n.v4)
                    .put("v6", n.v6)
                    .put("downKbps", n.downKbps),
            )
        }
        return arr.toString()
    }

    private fun pushNetworks(gen: Long, nets: List<NetworkTracker.Tracked>) {
        val js = buildNetworksJson(nets)
        if (js != lastNetworksJson) {
            lastNetworksJson = js
            sharedEngineExec.execute {
                if (globalSessionGen.get() != gen) return@execute
                runCatching { Engine.backend.setNetworks(js) }
                    .onFailure { Log.e(TAG, "setNetworks", it) }
            }
        }
        // Tells the system which networks we really use (metering, status bar icons). An empty
        // array would mean "no connectivity"; null means "follow the default network".
        val handles = nets.map { it.network.networkHandle }
        if (tun != null && handles != lastUnderlyingHandles) {
            lastUnderlyingHandles = handles
            setUnderlyingNetworks(if (nets.isEmpty()) null else nets.map { it.network }.toTypedArray())
        }
    }

    private fun shutdown(stopSelfAfter: Boolean) {
        var wasOwner = false
        var needsEngineStop = false
        var shutdownGen = 0L

        // Atomically release ownership and enqueue native stop() under lifecycleLock so no
        // replacement session can enqueue start() before our stop() enters the FIFO queue.
        synchronized(lifecycleLock) {
            wasOwner = activeOwner === this
            needsEngineStop = wasOwner && engineStartedInSession
            shutdownGen = if (wasOwner) {
                activeOwner = null
                globalSessionGen.incrementAndGet()
            } else {
                mySessionGen
            }
            mySessionGen = 0L
            engineStartedInSession = false
            if (needsEngineStop) {
                val expectedGen = shutdownGen
                sharedEngineExec.execute {
                    runCatching { Engine.backend.stop() }
                    main.post {
                        val isStillStopped = synchronized(lifecycleLock) {
                            globalSessionGen.get() == expectedGen && activeOwner == null
                        }
                        if (isStillStopped) {
                            Engine.setPhase(Engine.Phase.OFF)
                            requestTileUpdate()
                        }
                        if (stopSelfAfter && synchronized(lifecycleLock) { activeOwner !== this@BondVpnService }) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            }
        }

        main.removeCallbacks(refresh)
        cancelPendingEstablish()
        if (batteryReceiverRegistered) {
            batteryReceiverRegistered = false
            runCatching { unregisterReceiver(batteryReceiver) }
        }
        tracker?.stop()
        tracker = null
        runCatching { tun?.close() }
        tun = null
        lastNetworksJson = null
        lastUnderlyingHandles = null

        if (!needsEngineStop) {
            val isStillStopped = synchronized(lifecycleLock) {
                wasOwner && globalSessionGen.get() == shutdownGen && activeOwner == null
            }
            if (isStillStopped) {
                Engine.setPhase(Engine.Phase.OFF)
                requestTileUpdate()
            }
            if (stopSelfAfter && synchronized(lifecycleLock) { activeOwner !== this }) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onRevoke() {
        // Another VPN started or the user turned us off in Settings.
        // Android may invoke onRevoke() on a Binder thread; confine teardown to the main thread.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            shutdown(stopSelfAfter = true)
        } else {
            main.post { shutdown(stopSelfAfter = true) }
        }
    }

    override fun onDestroy() {
        shutdown(stopSelfAfter = false)
        super.onDestroy()
    }

    private fun requestTileUpdate() {
        runCatching {
            TileService.requestListeningState(this, ComponentName(this, BondTileService::class.java))
        }
    }

    private fun settingsIntent(): Intent =
        Intent(this, SubSettings::class.java)
            .putExtra(SettingsActivity.EXTRA_SHOW_FRAGMENT, NetBondFragment::class.java.name)
            .putExtra(
                SettingsActivity.EXTRA_SHOW_FRAGMENT_TITLE,
                getString(R.string.net_bond_title),
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.netbond_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val n = buildNotification(getString(R.string.netbond_notif_waiting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } catch (e: SecurityException) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            }
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(summary: String, bigText: String = summary): Notification {
        val open = PendingIntent.getActivity(
            this, 0, settingsIntent(), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BondVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.netbond_ic_bond)
            .setContentTitle(getString(R.string.netbond_notif_title))
            .setContentText(summary)
            .setStyle(Notification.BigTextStyle().bigText(bigText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addExtras(Bundle().apply {
                putBoolean("android.app.preferSmallIcon", true)
                val sysLabelId = resources.getIdentifier("android_system_label", "string", "android")
                putString(
                    "android.substName",
                    if (sysLabelId != 0) getString(sysLabelId) else "Android System",
                )
            })
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.netbond_stop), stop).build())
        val accentId = resources.getIdentifier("system_notification_accent_color", "color", "android")
        if (accentId != 0) {
            builder.setColor(getColor(accentId))
        }
        return builder.build()
    }

    private fun updateNotification() {
        if (Engine.phase.value != Engine.Phase.ON) return
        val stats = Engine.stats.value
        val (summary, bigText) = if (stats.isEmpty()) {
            val waiting = getString(R.string.netbond_notif_waiting)
            waiting to waiting
        } else {
            val totalRate = stats.sumOf { it.rateIn + it.rateOut }
            val modeLabel = Engine.effectiveMode.getTitle(this)
            val modeTitle = if (Engine.batterySaverActive.value) {
                "$modeLabel · ${getString(R.string.netbond_battery_saver_short)}"
            } else {
                modeLabel
            }
            val netCount = resources.getQuantityString(R.plurals.netbond_network_count, stats.size, stats.size)
            val collapsed = "$modeTitle · $netCount · ${Stats.rate(totalRate)}"
            val lines = mutableListOf("$modeTitle · ${Stats.rate(totalRate)}")
            for (s in stats) {
                val parts = mutableListOf(s.name, Stats.rate(s.rateIn + s.rateOut))
                if (s.rtt > 0) parts.add(s.rttString)
                if (s.ratio > 0) parts.add("${s.ratio.toInt()}%")
                when (s.status) {
                    "checking" -> parts.add(getString(R.string.netbond_network_checking))
                    "up" -> Unit
                    else -> parts.add(getString(R.string.netbond_network_no_internet))
                }
                lines.add(parts.joinToString(" · "))
            }
            collapsed to lines.joinToString("\n")
        }
        getSystemService(NotificationManager::class.java).notify(
            NOTIF_ID,
            buildNotification(summary, bigText),
        )
    }
}
