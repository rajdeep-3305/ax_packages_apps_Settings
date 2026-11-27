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

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.android.settings.R
import com.android.settings.SettingsActivity
import com.android.settings.SubSettings
import com.android.settings.netbond.NetBondFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Quick Settings tile for Network Bonding. Tap to cycle through modes
 * (Off -> Turbo Boost -> Smart Adaptive -> Data Saver -> Split Evenly -> Off);
 * long-press opens the Network Bonding settings page.
 */
class BondTileService : TileService() {

    internal var tileProviderForTest: (() -> Tile?)? = null
    private var listenScope: CoroutineScope? = null
    private var batteryReceiverRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                val pm = getSystemService(PowerManager::class.java)
                Engine.setBatterySaverActive(pm?.isPowerSaveMode ?: false)
                refresh()
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        val pm = getSystemService(PowerManager::class.java)
        Engine.setBatterySaverActive(pm?.isPowerSaveMode ?: false)
        if (!batteryReceiverRegistered) {
            runCatching {
                val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(batteryReceiver, filter)
                }
                batteryReceiverRegistered = true
            }
        }
        listenScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        listenScope = scope
        scope.launch {
            combine(
                Engine.phase,
                Engine.mode,
                Engine.batterySaverActive,
            ) { _, _, _ -> }.collect { refresh() }
        }
    }

    override fun onStopListening() {
        listenScope?.cancel()
        listenScope = null
        if (batteryReceiverRegistered) {
            batteryReceiverRegistered = false
            runCatching { unregisterReceiver(batteryReceiver) }
        }
        super.onStopListening()
    }

    private fun isUnavailable(): Boolean {
        if (!Engine.isSupported(this)) return true
        val um = getSystemService(UserManager::class.java)
        return um?.hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN) == true
    }

    override fun onClick() {
        if (isUnavailable()) return
        if (Engine.phase.value == Engine.Phase.OFF) {
            Engine.loadSettings(this)
            if (VpnService.prepare(this) != null) {
                val intent = settingsIntent()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startActivityAndCollapse(
                        PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(intent)
                }
                return
            }
            Engine.setMode(this, Engine.Mode.TURBO_BOOST)
            BondVpnService.start(this)
            updateTileUi(active = true)
            return
        }

        when (Engine.mode.value) {
            Engine.Mode.TURBO_BOOST -> Engine.setMode(this, Engine.Mode.SMART_ADAPTIVE)
            Engine.Mode.SMART_ADAPTIVE -> Engine.setMode(this, Engine.Mode.DATA_SAVER)
            Engine.Mode.DATA_SAVER -> Engine.setMode(this, Engine.Mode.SPLIT_EVENLY)
            Engine.Mode.SPLIT_EVENLY -> BondVpnService.stop(this)
        }
        refresh()
    }

    private fun updateTileUi(active: Boolean) {
        val tile = (tileProviderForTest?.invoke() ?: qsTile) ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.netbond_qs_label)
        tile.icon = Icon.createWithResource(this, R.drawable.netbond_ic_bond)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (active) {
                Engine.effectiveMode.getQsTitle(this)
            } else {
                getString(R.string.netbond_status_off)
            }
        }
        tile.updateTile()
    }

    private fun refresh() {
        val tile = (tileProviderForTest?.invoke() ?: qsTile) ?: return
        if (isUnavailable()) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = getString(R.string.netbond_qs_label)
            tile.icon = Icon.createWithResource(this, R.drawable.netbond_ic_bond)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = getString(R.string.netbond_status_off)
            }
            tile.updateTile()
            return
        }
        if (Engine.phase.value == Engine.Phase.OFF) {
            Engine.loadSettings(this)
        }
        updateTileUi(active = Engine.phase.value != Engine.Phase.OFF)
    }

    private fun settingsIntent(): Intent =
        Intent(this, SubSettings::class.java)
            .putExtra(SettingsActivity.EXTRA_SHOW_FRAGMENT, NetBondFragment::class.java.name)
            .putExtra(
                SettingsActivity.EXTRA_SHOW_FRAGMENT_TITLE,
                getString(R.string.net_bond_title),
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}
