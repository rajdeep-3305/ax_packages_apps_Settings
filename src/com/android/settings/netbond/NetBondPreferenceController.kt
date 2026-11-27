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

package com.android.settings.netbond

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.android.settings.R
import com.android.settings.core.BasePreferenceController
import org.netbond.android.Engine

/** Provides live status summary for the Network Bonding entry in Network & internet. */
class NetBondPreferenceController(context: Context, key: String) :
    BasePreferenceController(context, key), DefaultLifecycleObserver {

    private var preference: Preference? = null
    private val main = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            preference?.let { refreshSummary(it) }
            main.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun getAvailabilityStatus(): Int {
        if (!Engine.isSupported(mContext)) return UNSUPPORTED_ON_DEVICE
        val um = mContext.getSystemService(android.os.UserManager::class.java)
        if (um?.hasUserRestriction(android.os.UserManager.DISALLOW_CONFIG_VPN) == true) return DISABLED_FOR_USER
        return AVAILABLE
    }

    override fun displayPreference(screen: PreferenceScreen) {
        super.displayPreference(screen)
        preference = screen.findPreference(preferenceKey)
    }

    override fun onStart(owner: LifecycleOwner) {
        main.removeCallbacks(refresher)
        main.post(refresher)
    }

    override fun onStop(owner: LifecycleOwner) {
        main.removeCallbacks(refresher)
    }

    override fun getSummary(): CharSequence = when (Engine.phase.value) {
        Engine.Phase.OFF -> mContext.getString(R.string.netbond_status_off)
        Engine.Phase.STARTING -> mContext.getString(R.string.netbond_status_starting)
        Engine.Phase.ON -> {
            val count = Engine.stats.value.size
            val modeTitle = Engine.effectiveMode.getTitle(mContext)
            if (count >= 2) {
                val bonded = mContext.resources.getQuantityString(
                    R.plurals.netbond_status_networks, count, count,
                )
                "$bonded · $modeTitle"
            } else {
                "${mContext.getString(R.string.netbond_status_on)} · $modeTitle"
            }
        }
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 1000L
    }
}
