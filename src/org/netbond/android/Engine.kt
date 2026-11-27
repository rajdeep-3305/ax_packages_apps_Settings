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
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import com.android.settings.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide state shared by the VPN service, the UI, and the Quick Settings tile.
 */
object Engine {
    enum class Phase { OFF, STARTING, ON }

    enum class Mode(
        val id: Int,
        @StringRes val titleRes: Int,
        @StringRes val qsTitleRes: Int = titleRes,
    ) {
        TURBO_BOOST(0, R.string.netbond_mode_turbo_name),
        SMART_ADAPTIVE(1, R.string.netbond_mode_adaptive_name, R.string.netbond_mode_adaptive_qs),
        DATA_SAVER(2, R.string.netbond_mode_saver_name),
        SPLIT_EVENLY(3, R.string.netbond_mode_split_name);

        fun getTitle(context: Context): String = context.getString(titleRes)

        fun getQsTitle(context: Context): String = context.getString(qsTitleRes)

        companion object {
            fun fromId(id: Int): Mode = values().firstOrNull { it.id == id } ?: TURBO_BOOST
        }
    }

    internal interface Backend {
        fun setMode(modeId: Long)
        fun setNetworks(json: String)
        fun start(fd: Long, mtu: Long)
        fun stop()
        fun stats(): String
        fun sessionBytes(): Long
    }

    private object NativeBackend : Backend {
        override fun setMode(modeId: Long) = netbond.Netbond.setMode(modeId)
        override fun setNetworks(json: String) {
            netbond.Netbond.setNetworks(json)
        }
        override fun start(fd: Long, mtu: Long) = netbond.Netbond.start(fd, mtu)
        override fun stop() = netbond.Netbond.stop()
        override fun stats(): String = netbond.Netbond.stats()
        override fun sessionBytes(): Long = netbond.Netbond.sessionBytes()
    }

    @Volatile
    internal var backend: Backend = NativeBackend

    /** Seconds of throughput history kept for the live chart. */
    const val HISTORY = 60

    private val _phase = MutableStateFlow(Phase.OFF)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _stats = MutableStateFlow<List<Stats>>(emptyList())
    val stats: StateFlow<List<Stats>> = _stats.asStateFlow()

    private val statsLock = Any()
    private val _sessionBytes = MutableStateFlow(0L)
    val sessionBytes: StateFlow<Long> = _sessionBytes.asStateFlow()

    /** Per network: total (down + up) bytes/s for the last [HISTORY] seconds, oldest first. */
    private val _history = MutableStateFlow<Map<String, List<Float>>>(emptyMap())
    val history: StateFlow<Map<String, List<Float>>> = _history.asStateFlow()

    private val _mode = MutableStateFlow(Mode.TURBO_BOOST)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _excludedApps = MutableStateFlow<Set<String>>(emptySet())
    val excludedApps: StateFlow<Set<String>> = _excludedApps.asStateFlow()

    private val _batterySaverActive = MutableStateFlow(false)
    val batterySaverActive: StateFlow<Boolean> = _batterySaverActive.asStateFlow()

    val running: Boolean get() = _phase.value == Phase.ON

    val effectiveMode: Mode
        get() = if (_batterySaverActive.value) Mode.DATA_SAVER else _mode.value

    fun isSupported(ctx: Context): Boolean {
        val pm = ctx.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_WIFI) &&
            pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }

    internal fun setPhase(p: Phase) {
        _phase.value = p
        if (p == Phase.OFF) {
            synchronized(statsLock) {
                _sessionBytes.value = 0L
                _stats.value = emptyList()
                _history.value = emptyMap()
            }
        }
    }

    private fun applyStatsSnapshot(now: List<Stats>, totalSessionBytes: Long? = null) {
        synchronized(statsLock) {
            if (totalSessionBytes != null) {
                _sessionBytes.value = totalSessionBytes.coerceAtLeast(0L)
            }
            _stats.value = now
            val old = _history.value
            _history.value = now.associate { s ->
                val prev = old[s.name] ?: List(HISTORY) { 0f }
                s.name to (prev + (s.rateIn + s.rateOut).toFloat()).takeLast(HISTORY)
            }
        }
    }

    /** Reads the engine's counters on the serialized engine thread. */
    internal fun sample() {
        val raw = runCatching { backend.stats() }.getOrNull() ?: return
        val total = runCatching { backend.sessionBytes() }.getOrNull()
        applyStatsSnapshot(Stats.parse(raw).filter { it.present }, total)
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("netbond_settings", Context.MODE_PRIVATE)

    fun loadSettings(ctx: Context) {
        val p = prefs(ctx)
        val defaultMode = if (p.getBoolean("even_split", false)) Mode.SPLIT_EVENLY.id else Mode.TURBO_BOOST.id
        val modeId = p.getInt("bonding_mode", defaultMode)
        _mode.value = Mode.fromId(modeId)
        _excludedApps.value = p.getStringSet("excluded_apps", emptySet()) ?: emptySet()
    }

    fun setMode(ctx: Context, mode: Mode) {
        prefs(ctx).edit()
            .putInt("bonding_mode", mode.id)
            .putBoolean("even_split", mode == Mode.SPLIT_EVENLY)
            .apply()
        _mode.value = mode
        if (_phase.value != Phase.OFF) {
            BondVpnService.refreshUi(ctx)
        }
    }

    fun setExcludedApps(ctx: Context, apps: Set<String>) {
        prefs(ctx).edit().putStringSet("excluded_apps", apps).apply()
        _excludedApps.value = apps
        if (running) {
            BondVpnService.restart(ctx)
        }
    }

    fun setBatterySaverActive(active: Boolean) {
        if (_batterySaverActive.value != active) {
            _batterySaverActive.value = active
        }
    }

    internal fun setStatsForTest(stats: List<Stats>) {
        applyStatsSnapshot(stats)
    }

    internal fun resetForTest() {
        backend = NativeBackend
        _phase.value = Phase.OFF
        synchronized(statsLock) {
            _sessionBytes.value = 0L
            _stats.value = emptyList()
            _history.value = emptyMap()
        }
        _mode.value = Mode.TURBO_BOOST
        _excludedApps.value = emptySet()
        _batterySaverActive.value = false
    }
}
