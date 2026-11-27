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
import android.content.pm.PackageManager
import android.net.IpPrefix
import android.net.Network
import android.os.Looper
import android.os.UserManager
import android.service.quicksettings.Tile
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.TextView
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settings.R
import com.android.settings.core.BasePreferenceController
import com.android.settings.widget.SettingsMainSwitchPreference
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.netbond.android.BondTileService
import org.netbond.android.BondVpnService
import org.netbond.android.Engine
import org.netbond.android.NetworkTracker
import org.netbond.android.Stats
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NetBondSettingsTest {
    private lateinit var appContext: Context
    private lateinit var context: Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        context = ContextThemeWrapper(appContext, R.style.Theme_SubSettings)
        BondVpnService.resetRuntimeForTest()
        Engine.resetForTest()
        val pm = shadowOf(appContext.packageManager)
        pm.setSystemFeature(PackageManager.FEATURE_WIFI, true)
        pm.setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)
    }

    @After
    fun tearDown() {
        BondVpnService.resetRuntimeForTest()
        Engine.resetForTest()
    }

    @Test
    fun screen_usesNativeNonPersistentControls() {
        val manager = PreferenceManager(context)
        val screen = manager.inflateFromResource(context, R.xml.netbond_settings, null)
        val main = screen.findPreference<Preference>("netbond_enabled")
        val split = screen.findPreference<Preference>("netbond_even_split")
        val mode = screen.findPreference<Preference>("netbond_mode")
        val modeInfo = screen.findPreference<Preference>("netbond_mode_info")
        val excludedApps = screen.findPreference<Preference>("netbond_excluded_apps")
        assertThat(screen.title.toString()).isEqualTo(context.getString(R.string.net_bond_title))
        assertThat(main).isInstanceOf(SettingsMainSwitchPreference::class.java)
        assertThat(main!!.isPersistent).isFalse()
        assertThat(split).isNull()
        assertThat(mode).isInstanceOf(ListPreference::class.java)
        assertThat(mode!!.isPersistent).isFalse()
        assertThat(modeInfo).isNotNull()
        assertThat(modeInfo!!.isPersistent).isFalse()
        assertThat(excludedApps).isNotNull()
        assertThat(excludedApps!!.isPersistent).isFalse()
        assertThat(screen.findPreference<Preference>("netbond_chart"))
            .isInstanceOf(NetBondChartPreference::class.java)
    }

    @Test
    fun liveStatistics_areNotSearchResults() {
        val keys = NetBondFragment.SEARCH_INDEX_DATA_PROVIDER.getNonIndexableKeys(context)
        assertThat(keys).containsAtLeast(
            "netbond_status", "netbond_stats_grid", "netbond_networks", "netbond_chart",
            "netbond_mode_category", "netbond_mode", "netbond_mode_info", "netbond_excluded_apps",
        )
        assertThat(keys).doesNotContain("netbond_enabled")
    }

    @Test
    fun modeEnum_mapsAndResolvesLocalizedStrings() {
        assertThat(Engine.Mode.fromId(0)).isEqualTo(Engine.Mode.TURBO_BOOST)
        assertThat(Engine.Mode.fromId(1)).isEqualTo(Engine.Mode.SMART_ADAPTIVE)
        assertThat(Engine.Mode.fromId(2)).isEqualTo(Engine.Mode.DATA_SAVER)
        assertThat(Engine.Mode.fromId(3)).isEqualTo(Engine.Mode.SPLIT_EVENLY)
        assertThat(Engine.Mode.fromId(999)).isEqualTo(Engine.Mode.TURBO_BOOST)

        assertThat(Engine.Mode.TURBO_BOOST.getTitle(context))
            .isEqualTo(context.getString(R.string.netbond_mode_turbo_name))
        assertThat(Engine.Mode.SMART_ADAPTIVE.getTitle(context))
            .isEqualTo(context.getString(R.string.netbond_mode_adaptive_name))
        assertThat(Engine.Mode.SMART_ADAPTIVE.getQsTitle(context))
            .isEqualTo(context.getString(R.string.netbond_mode_adaptive_qs))
        assertThat(Engine.Mode.DATA_SAVER.getTitle(context))
            .isEqualTo(context.getString(R.string.netbond_mode_saver_name))
        assertThat(Engine.Mode.SPLIT_EVENLY.getTitle(context))
            .isEqualTo(context.getString(R.string.netbond_mode_split_name))
    }

    @Test
    fun emptyChart_keepsWaitingLegendVisible() {
        val preference = NetBondChartPreference(context)
        val view = LayoutInflater.from(context).inflate(R.layout.netbond_chart_preference, null)
        preference.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(view))
        val legend = view.findViewById<TextView>(android.R.id.summary)
        assertThat(legend.visibility).isEqualTo(android.view.View.VISIBLE)
        assertThat(legend.text.toString())
            .isEqualTo(context.getString(R.string.netbond_notif_waiting))
    }

    @Test
    fun preferenceController_availabilityAndSummary_reflectState() {
        val controller = NetBondPreferenceController(context, "net_bond")
        assertThat(controller.availabilityStatus).isEqualTo(BasePreferenceController.AVAILABLE)

        shadowOf(appContext.packageManager)
            .setSystemFeature(PackageManager.FEATURE_TELEPHONY, false)
        assertThat(controller.availabilityStatus)
            .isEqualTo(BasePreferenceController.UNSUPPORTED_ON_DEVICE)
        shadowOf(appContext.packageManager)
            .setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)

        val um = appContext.getSystemService(UserManager::class.java)
        shadowOf(um).setUserRestriction(
            android.os.Process.myUserHandle(),
            UserManager.DISALLOW_CONFIG_VPN,
            true,
        )
        assertThat(controller.availabilityStatus)
            .isEqualTo(BasePreferenceController.DISABLED_FOR_USER)
        shadowOf(um).setUserRestriction(
            android.os.Process.myUserHandle(),
            UserManager.DISALLOW_CONFIG_VPN,
            false,
        )

        Engine.setPhase(Engine.Phase.OFF)
        assertThat(controller.summary.toString())
            .isEqualTo(context.getString(R.string.netbond_status_off))

        Engine.setPhase(Engine.Phase.STARTING)
        assertThat(controller.summary.toString())
            .isEqualTo(context.getString(R.string.netbond_status_starting))

        Engine.setMode(context, Engine.Mode.SMART_ADAPTIVE)
        Engine.setPhase(Engine.Phase.ON)
        Engine.setStatsForTest(
            listOf(
                Stats("Wi-Fi", "up", "-", 1, 1, 0, 0, 0, 100.0, 50.0, 1.0, true, 12, 50.0),
                Stats("Mobile data", "up", "-", 1, 1, 0, 0, 0, 100.0, 50.0, 1.0, true, 30, 50.0),
            ),
        )
        assertThat(controller.summary.toString())
            .contains(context.getString(R.string.netbond_mode_adaptive_name))
        assertThat(controller.summary.toString()).contains("2")

        Engine.setBatterySaverActive(true)
        assertThat(controller.summary.toString())
            .contains(context.getString(R.string.netbond_mode_saver_name))
    }

    @Test
    fun mergeExcludedApps_preservesHiddenExclusions() {
        val existing = setOf("com.bank.hidden", "com.video.app", "com.game.app")
        val visible = setOf("com.video.app", "com.game.app", "com.music.app")
        val checkedVisible = setOf("com.video.app", "com.music.app")

        val merged = NetBondFragment.mergeExcludedApps(existing, visible, checkedVisible)
        assertThat(merged).containsExactly("com.bank.hidden", "com.video.app", "com.music.app")
    }

    @Test
    fun networkTracker_ignoresLateCallbacksAfterStop() {
        var callbackCount = 0
        val tracker = NetworkTracker(appContext) { callbackCount++ }
        tracker.start()
        val registered = tracker.registeredCallbacksForTest()
        assertThat(registered).isNotEmpty()

        tracker.stop()
        assertThat(tracker.isStopped).isTrue()

        val fakeNetwork = mock(Network::class.java)
        for (cb in registered) {
            cb.onAvailable(fakeNetwork)
            cb.onLost(fakeNetwork)
        }
        assertThat(callbackCount).isEqualTo(0)
    }

    @Test
    fun serviceReplacement_serializesOldStopBeforeNewStart() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val stopEntered = CountDownLatch(1)
        val allowStopFinish = CountDownLatch(1)
        Engine.backend = object : Engine.Backend {
            override fun setMode(modeId: Long) {}
            override fun setNetworks(json: String) {}
            override fun start(fd: Long, mtu: Long) {
                events.add("start:$fd")
            }
            override fun stop() {
                events.add("stop:begin")
                stopEntered.countDown()
                allowStopFinish.await(3, TimeUnit.SECONDS)
                events.add("stop:end")
            }
            override fun stats(): String = "[]"
            override fun sessionBytes(): Long = 0L
        }

        val oldService = Robolectric.buildService(BondVpnService::class.java).create().get()
        val newService = Robolectric.buildService(BondVpnService::class.java).create().get()

        oldService.startSessionForTest(fd = 10L)
        BondVpnService.awaitEngineIdleForTest()
        assertThat(Engine.phase.value).isEqualTo(Engine.Phase.ON)

        // Old service begins shutdown while stop() is still blocked on the shared executor.
        oldService.triggerShutdownForTest(stopSelfAfter = false)
        assertThat(stopEntered.await(3, TimeUnit.SECONDS)).isTrue()

        // Replacement service starts before old teardown completes.
        newService.startSessionForTest(fd = 20L)
        allowStopFinish.countDown()

        BondVpnService.awaitEngineIdleForTest()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(events).containsExactly("start:10", "stop:begin", "stop:end", "start:20").inOrder()
        assertThat(Engine.phase.value).isEqualTo(Engine.Phase.ON)
    }

    @Test
    fun tileService_updatesLiveWhileListeningAndStopsOnStopListening() {
        val tile = mock(Tile::class.java)
        val service = Robolectric.buildService(BondTileService::class.java).create().get()
        service.tileProviderForTest = { tile }

        service.onStartListening()
        shadowOf(Looper.getMainLooper()).idle()
        verify(tile).state = Tile.STATE_INACTIVE

        // Engine enters STARTING -> tile updates active.
        org.mockito.Mockito.clearInvocations(tile)
        Engine.setPhase(Engine.Phase.STARTING)
        shadowOf(Looper.getMainLooper()).idle()
        verify(tile).state = Tile.STATE_ACTIVE

        // Startup fails asynchronously -> tile reverts to INACTIVE while shade is still open.
        org.mockito.Mockito.clearInvocations(tile)
        Engine.setPhase(Engine.Phase.OFF)
        shadowOf(Looper.getMainLooper()).idle()
        verify(tile).state = Tile.STATE_INACTIVE

        // Mode & battery saver changes propagate live with localized subtitles.
        org.mockito.Mockito.clearInvocations(tile)
        Engine.setMode(context, Engine.Mode.SMART_ADAPTIVE)
        Engine.setPhase(Engine.Phase.ON)
        shadowOf(Looper.getMainLooper()).idle()
        verify(tile).subtitle = context.getString(R.string.netbond_mode_adaptive_qs)

        org.mockito.Mockito.clearInvocations(tile)
        Engine.setBatterySaverActive(true)
        shadowOf(Looper.getMainLooper()).idle()
        verify(tile).subtitle = context.getString(R.string.netbond_mode_saver_name)

        service.onStopListening()
        org.mockito.Mockito.clearInvocations(tile)
        Engine.setPhase(Engine.Phase.OFF)
        shadowOf(Looper.getMainLooper()).idle()
        org.mockito.Mockito.verifyNoInteractions(tile)
    }

    @Test
    fun onRevokeFromBinderThread_neverStopsReplacementSession() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        Engine.backend = object : Engine.Backend {
            override fun setMode(modeId: Long) {}
            override fun setNetworks(json: String) {}
            override fun start(fd: Long, mtu: Long) {
                events.add("start:$fd")
            }
            override fun stop() {
                events.add("stop")
            }
            override fun stats(): String = "[]"
            override fun sessionBytes(): Long = 0L
        }

        val oldService = Robolectric.buildService(BondVpnService::class.java).create().get()
        val newService = Robolectric.buildService(BondVpnService::class.java).create().get()

        oldService.startSessionForTest(fd = 10L)
        BondVpnService.awaitEngineIdleForTest()

        // Invoke onRevoke() from a non-main (Binder-like) thread.
        // Under the fixed implementation, onRevoke() only posts teardown to the main looper
        // and MUST NOT execute shutdown() or submit stop() synchronously on the Binder thread.
        val revokeCalled = CountDownLatch(1)
        val binderThread = Thread {
            oldService.onRevoke()
            revokeCalled.countDown()
        }
        binderThread.start()
        assertThat(revokeCalled.await(3, TimeUnit.SECONDS)).isTrue()
        BondVpnService.awaitEngineIdleForTest()

        // Would fail on the old synchronous onRevoke(): teardown must still be pending on main.
        assertThat(events).containsExactly("start:10")
        assertThat(Engine.phase.value).isEqualTo(Engine.Phase.ON)

        // Main thread starts replacement before the posted onRevoke() runnable executes.
        newService.startSessionForTest(fd = 20L)
        // Drain main looper so the queued onRevoke() runs against the already-replaced oldService.
        shadowOf(Looper.getMainLooper()).idle()
        BondVpnService.awaitEngineIdleForTest()
        shadowOf(Looper.getMainLooper()).idle()

        // Replacement shutdown-then-start ran in strict order; late onRevoke() did NOT stop it.
        assertThat(events).containsExactly("start:10", "stop", "start:20").inOrder()
        assertThat(Engine.phase.value).isEqualTo(Engine.Phase.ON)
    }

    @Test
    fun localIpv6Routes_excludeUlaDestinations() {
        val prefixes = BondVpnService.LOCAL_IPV6_ROUTES.map { (addr, prefixLen) ->
            IpPrefix(InetAddress.getByName(addr), prefixLen)
        }
        fun isExcluded(addr: String): Boolean =
            prefixes.any { it.contains(InetAddress.getByName(addr)) }

        assertThat(isExcluded("fc00::1")).isTrue()
        assertThat(isExcluded("fd00::1")).isTrue()
        assertThat(isExcluded("fd12:3456:789a::42")).isTrue()
        assertThat(isExcluded("fe80::1")).isTrue()
        assertThat(isExcluded("ff02::1")).isTrue()
        assertThat(isExcluded("2606:4700:4700::1111")).isFalse()
        assertThat(isExcluded("2001:4860:4860::8888")).isFalse()
    }

    @Test
    fun sessionTransferred_readsNativeTotalAcrossRemovalAndReturn() {
        var nativeStatsJson = "[]"
        var nativeSessionTotal = 0L
        Engine.backend = object : Engine.Backend {
            override fun setMode(modeId: Long) {}
            override fun setNetworks(json: String) {}
            override fun start(fd: Long, mtu: Long) {}
            override fun stop() {}
            override fun stats(): String = nativeStatsJson
            override fun sessionBytes(): Long = nativeSessionTotal
        }

        Engine.setPhase(Engine.Phase.ON)

        // Initial traffic: Wi-Fi 1,000 bytes.
        nativeStatsJson =
            "[{\"slotId\":1,\"name\":\"Wi-Fi\",\"state\":\"up\",\"state6\":\"-\",\"active\":1,\"total\":1,\"failed\":0,\"bytesIn\":1000,\"bytesOut\":0,\"rateIn\":10,\"rateOut\":0,\"weight\":1,\"present\":true,\"rtt\":10,\"ratio\":100}]"
        nativeSessionTotal = 1_000L
        Engine.sample()
        assertThat(Engine.sessionBytes.value).isEqualTo(1_000L)

        // Case 1 (no overcount): Wi-Fi temporarily absent, then same slot returns with no new traffic.
        nativeStatsJson = "[]"
        Engine.sample()
        assertThat(Engine.sessionBytes.value).isEqualTo(1_000L)

        nativeStatsJson =
            "[{\"slotId\":1,\"name\":\"Wi-Fi\",\"state\":\"up\",\"state6\":\"-\",\"active\":1,\"total\":1,\"failed\":0,\"bytesIn\":1000,\"bytesOut\":0,\"rateIn\":0,\"rateOut\":0,\"weight\":1,\"present\":true,\"rtt\":10,\"ratio\":0}]"
        Engine.sample()
        assertThat(Engine.sessionBytes.value).isEqualTo(1_000L)

        // Case 2 (no undercount): 1,000 more bytes transferred right before idle slot is pruned.
        nativeStatsJson = "[]"
        nativeSessionTotal = 2_000L
        Engine.sample()
        assertThat(Engine.sessionBytes.value).isEqualTo(2_000L)

        // Explicit session boundary resets total to 0.
        Engine.setPhase(Engine.Phase.OFF)
        assertThat(Engine.sessionBytes.value).isEqualTo(0L)
    }
}
