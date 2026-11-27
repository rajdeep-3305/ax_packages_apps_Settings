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

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Bundle
import android.os.UserManager
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.android.settings.R
import com.android.settings.dashboard.DashboardFragment
import com.android.settings.search.BaseSearchIndexProvider
import com.android.settings.widget.SettingsMainSwitchPreference
import com.android.settingslib.search.SearchIndexable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.netbond.android.Engine
import org.netbond.android.BondVpnService
import org.netbond.android.Stats

/** Native Settings controls backed by the advanced network bonding engine. */
@SearchIndexable
class NetBondFragment : DashboardFragment() {

    private lateinit var mainSwitch: SettingsMainSwitchPreference
    private lateinit var statusPreference: Preference
    private lateinit var modePreference: ListPreference
    private lateinit var modeInfoPreference: Preference
    private lateinit var excludedAppsPreference: Preference
    private lateinit var statsGrid: NetBondStatsPreference
    private lateinit var networks: PreferenceCategory
    private lateinit var chart: NetBondChartPreference
    private val networkRows = linkedMapOf<String, Preference>()
    private var lastModeIconRes = 0
    private var lastStatusIconRes = 0
    private var cachedAppList: List<Triple<String, String, ResolveInfo>>? = null
    private val appIconCache = HashMap<String, Drawable>()
    private var loadingExcludedDialog = false
    private var consentPending = false
    private var modeInfoDialog: AlertDialog? = null
    private var excludedAppsDialog: AlertDialog? = null
    private var detailsDialog: AlertDialog? = null
    private var detailsUpdater: (() -> Unit)? = null

    private val vpnConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        consentPending = false
        if (result.resultCode == Activity.RESULT_OK && !isRestricted()) {
            BondVpnService.start(requireContext())
        } else {
            Toast.makeText(requireContext(), R.string.netbond_vpn_denied, Toast.LENGTH_LONG).show()
        }
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consentPending = savedInstanceState?.getBoolean(STATE_CONSENT_PENDING) ?: false
        Engine.loadSettings(requireContext())
    }

    override fun getPreferenceScreenResId() = R.xml.netbond_settings

    override fun getLogTag() = TAG

    override fun getMetricsCategory() =
        com.android.settingslib.core.instrumentation.Instrumentable.METRICS_CATEGORY_UNKNOWN

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        mainSwitch = findPreference<SettingsMainSwitchPreference>("netbond_enabled")!!
        statusPreference = findPreference<Preference>("netbond_status")!!
        modePreference = findPreference<ListPreference>("netbond_mode")!!
        modeInfoPreference = findPreference<Preference>("netbond_mode_info")!!
        excludedAppsPreference = findPreference<Preference>("netbond_excluded_apps")!!
        statsGrid = findPreference<NetBondStatsPreference>("netbond_stats_grid")!!
        networks = findPreference<PreferenceCategory>("netbond_networks")!!
        chart = findPreference<NetBondChartPreference>("netbond_chart")!!
        networkRows.clear()
        lastModeIconRes = 0
        lastStatusIconRes = 0

        statusPreference.setOnPreferenceClickListener {
            showConnectionDetailsDialog()
            true
        }

        mainSwitch.setOnBeforeCheckedChangeListener { enabled ->
            setBondingEnabled(enabled)
            true
        }

        modePreference.value = Engine.mode.value.id.toString()
        updateModeSummary(Engine.mode.value)
        modePreference.setOnPreferenceChangeListener { _, value ->
            if (isRestricted()) {
                false
            } else {
                val modeId = (value as String).toIntOrNull() ?: 0
                val newMode = Engine.Mode.fromId(modeId)
                Engine.setMode(requireContext(), newMode)
                updateModeSummary(newMode)
                true
            }
        }

        modeInfoPreference.setOnPreferenceClickListener {
            showModeInfoDialog()
            true
        }

        updateExcludedAppsSummary()
        excludedAppsPreference.setOnPreferenceClickListener {
            showExcludedAppsDialog()
            true
        }

        render()
    }

    override fun onResume() {
        super.onResume()
        requireActivity().setTitle(R.string.net_bond_title)
        cachedAppList = null
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = loadLaunchableApps(appCtx)
            cachedAppList = loaded
            val installedPkgs = loaded.mapTo(HashSet()) { it.first }
            appIconCache.keys.retainAll(installedPkgs)
        }
        updateExcludedAppsSummary()
        render()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    Engine.phase,
                    Engine.stats,
                    Engine.history,
                    Engine.mode,
                    Engine.batterySaverActive,
                ) { _, _, _, _, _ -> }
                    .collect { render() }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_CONSENT_PENDING, consentPending)
    }

    override fun onDestroyView() {
        modeInfoDialog?.dismiss()
        modeInfoDialog = null
        excludedAppsDialog?.dismiss()
        excludedAppsDialog = null
        detailsDialog?.dismiss()
        detailsDialog = null
        detailsUpdater = null
        super.onDestroyView()
    }

    private fun setBondingEnabled(enabled: Boolean) {
        if (isRestricted()) return
        if (enabled) {
            startBonding()
        } else {
            consentPending = false
            BondVpnService.stop(requireContext())
        }
    }

    private fun startBonding() {
        if (Engine.phase.value != Engine.Phase.OFF) return
        val consent = VpnService.prepare(requireContext())
        if (consent != null) {
            consentPending = true
            render()
            vpnConsent.launch(consent)
        } else {
            BondVpnService.start(requireContext())
        }
    }

    private fun isRestricted() = requireContext().getSystemService(UserManager::class.java)
        ?.hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN) == true

    private fun modeIconRes(mode: Engine.Mode): Int = when (mode) {
        Engine.Mode.TURBO_BOOST -> R.drawable.ic_netbond_mode_turbo
        Engine.Mode.SMART_ADAPTIVE -> R.drawable.ic_netbond_mode_adaptive
        Engine.Mode.DATA_SAVER -> R.drawable.ic_netbond_mode_saver
        Engine.Mode.SPLIT_EVENLY -> R.drawable.ic_netbond_mode_split
    }

    private fun updateModeSummary(mode: Engine.Mode) {
        val iconRes = modeIconRes(mode)
        if (iconRes != lastModeIconRes) {
            modePreference.setIcon(iconRes)
            lastModeIconRes = iconRes
        }
        modePreference.summary = when (mode) {
            Engine.Mode.TURBO_BOOST -> getString(R.string.netbond_mode_turbo_short)
            Engine.Mode.SMART_ADAPTIVE -> getString(R.string.netbond_mode_adaptive_short)
            Engine.Mode.DATA_SAVER -> getString(R.string.netbond_mode_saver_short)
            Engine.Mode.SPLIT_EVENLY -> getString(R.string.netbond_mode_split_short)
        }
    }

    private fun showModeInfoDialog() {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val (primaryColor, secondaryColor) = ctx.obtainStyledAttributes(
            intArrayOf(android.R.attr.textColorPrimary, android.R.attr.textColorSecondary),
        ).let { ta ->
            try {
                ta.getColor(0, 0xFFFFFFFF.toInt()) to ta.getColor(1, 0xFFAAAAAA.toInt())
            } finally {
                ta.recycle()
            }
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (24 * density).toInt(),
                (16 * density).toInt(),
                (24 * density).toInt(),
                (4 * density).toInt(),
            )
        }

        val items = listOf(
            Triple(Engine.Mode.TURBO_BOOST, R.string.netbond_mode_turbo, R.string.netbond_mode_turbo_desc),
            Triple(Engine.Mode.SMART_ADAPTIVE, R.string.netbond_mode_adaptive, R.string.netbond_mode_adaptive_desc),
            Triple(Engine.Mode.DATA_SAVER, R.string.netbond_mode_saver, R.string.netbond_mode_saver_desc),
            Triple(Engine.Mode.SPLIT_EVENLY, R.string.netbond_mode_split, R.string.netbond_mode_split_desc),
        )

        val iconSize = (24 * density).toInt()
        items.forEachIndexed { index, (mode, titleRes, descRes) ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                if (index < items.lastIndex) {
                    setPadding(0, 0, 0, (16 * density).toInt())
                }
            }
            val icon = ImageView(ctx).apply {
                setImageResource(modeIconRes(mode))
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    marginEnd = (16 * density).toInt()
                    topMargin = (2 * density).toInt()
                }
            }
            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(TextView(ctx).apply {
                text = getString(titleRes)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(primaryColor)
            })
            textCol.addView(TextView(ctx).apply {
                text = getString(descRes)
                textSize = 13.5f
                setTextColor(secondaryColor)
                setLineSpacing(2f * density, 1f)
                setPadding(0, (3 * density).toInt(), 0, 0)
            })
            row.addView(icon)
            row.addView(textCol)
            container.addView(row)
        }

        container.addView(View(ctx).apply {
            setBackgroundColor((secondaryColor and 0x00FFFFFF) or 0x26000000)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                maxOf(1, (1 * density).toInt()),
            ).apply {
                topMargin = (14 * density).toInt()
                bottomMargin = (12 * density).toInt()
            }
        })
        container.addView(TextView(ctx).apply {
            text = getString(R.string.netbond_mode_info_disclaimer)
            textSize = 12.5f
            setTextColor(secondaryColor)
            setLineSpacing(2f * density, 1f)
        })

        val scroll = ScrollView(ctx).apply { addView(container) }
        modeInfoDialog?.dismiss()
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.netbond_mode_info_dialog_title)
            .setView(scroll)
            .setPositiveButton(R.string.netbond_got_it, null)
            .create()
        modeInfoDialog = dialog
        dialog.setOnDismissListener {
            if (modeInfoDialog === dialog) modeInfoDialog = null
        }
        dialog.show()
    }

    private fun updateExcludedAppsSummary() {
        val count = Engine.excludedApps.value.size
        excludedAppsPreference.summary = if (count == 0) {
            getString(R.string.netbond_excluded_apps_none)
        } else {
            resources.getQuantityString(R.plurals.netbond_excluded_apps_count, count, count)
        }
    }

    private data class AppEntry(
        val pkg: String,
        val label: String,
        val resolveInfo: ResolveInfo,
        var checked: Boolean,
    )

    private suspend fun loadLaunchableApps(appCtx: Context): List<Triple<String, String, ResolveInfo>> =
        withContext(Dispatchers.IO) {
            val pm = appCtx.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.packageName != appCtx.packageName }
                .map { Triple(it.activityInfo.packageName, it.loadLabel(pm).toString(), it) }
                .distinctBy { it.first }
                .sortedBy { it.second.lowercase() }
        }

    private fun showExcludedAppsDialog() {
        if (isRestricted() || loadingExcludedDialog) return
        val cached = cachedAppList
        if (cached != null) {
            val excluded = Engine.excludedApps.value
            val apps = cached.map { (pkg, label, ri) ->
                AppEntry(pkg, label, ri, pkg in excluded)
            }.sortedWith(compareByDescending<AppEntry> { it.checked }.thenBy { it.label.lowercase() })
            presentExcludedAppsDialog(apps)
            return
        }
        loadingExcludedDialog = true
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val loaded = loadLaunchableApps(appCtx)
                cachedAppList = loaded
                if (!isAdded) return@launch
                val excluded = Engine.excludedApps.value
                val apps = loaded.map { (pkg, label, ri) ->
                    AppEntry(pkg, label, ri, pkg in excluded)
                }.sortedWith(compareByDescending<AppEntry> { it.checked }.thenBy { it.label.lowercase() })
                presentExcludedAppsDialog(apps)
            } finally {
                loadingExcludedDialog = false
            }
        }
    }

    private fun presentExcludedAppsDialog(apps: List<AppEntry>) {
        if (apps.isEmpty()) {
            Toast.makeText(requireContext(), R.string.netbond_excluded_apps_none, Toast.LENGTH_SHORT).show()
            return
        }

        val ctx = requireContext()
        val pm = ctx.applicationContext.packageManager
        val density = resources.displayMetrics.density
        val padH = (24 * density).toInt()
        val padV = (8 * density).toInt()
        val iconSize = (36 * density).toInt()
        val secondaryColor = ctx.obtainStyledAttributes(
            intArrayOf(android.R.attr.textColorSecondary),
        ).let { ta ->
            try {
                ta.getColor(0, 0xFFAAAAAA.toInt())
            } finally {
                ta.recycle()
            }
        }

        val adapter = object : ArrayAdapter<AppEntry>(ctx, 0, apps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView as? LinearLayout ?: LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(padH, padV, padH, padV)
                    addView(ImageView(context).apply {
                        id = 1
                        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                            marginEnd = (14 * density).toInt()
                        }
                    })
                    addView(TextView(context).apply {
                        id = 2
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
                    })
                    addView(CheckBox(context).apply {
                        id = 3
                        isFocusable = false
                        isClickable = false
                    })
                }
                val item = getItem(position)!!
                val iconView = row.findViewById<ImageView>(1)
                iconView.tag = item.pkg
                val cachedIcon = appIconCache[item.pkg]
                if (cachedIcon != null) {
                    iconView.setImageDrawable(cachedIcon)
                } else {
                    iconView.setImageDrawable(null)
                    viewLifecycleOwner.lifecycleScope.launch {
                        val loaded = withContext(Dispatchers.IO) {
                            item.resolveInfo.loadIcon(pm)
                        }
                        appIconCache[item.pkg] = loaded
                        if (iconView.tag == item.pkg) {
                            iconView.setImageDrawable(loaded)
                        }
                    }
                }
                row.findViewById<TextView>(2).text = item.label
                row.findViewById<CheckBox>(3).isChecked = item.checked
                return row
            }
        }

        val header = TextView(ctx).apply {
            text = getString(R.string.netbond_excluded_apps_dialog_desc)
            textSize = 13.5f
            setTextColor(secondaryColor)
            setLineSpacing(2f * density, 1f)
            setPadding(padH, (6 * density).toInt(), padH, (12 * density).toInt())
        }

        val listView = ListView(ctx).apply {
            divider = null
            dividerHeight = 0
            addHeaderView(header, null, false)
            this.adapter = adapter
            setOnItemClickListener { parent, _, position, _ ->
                val item = parent.getItemAtPosition(position) as? AppEntry
                    ?: return@setOnItemClickListener
                item.checked = !item.checked
                adapter.notifyDataSetChanged()
            }
        }

        excludedAppsDialog?.dismiss()
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.netbond_excluded_apps_title)
            .setView(listView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val visible = apps.mapTo(HashSet()) { it.pkg }
                val checkedVisible = apps.filter { it.checked }.mapTo(HashSet()) { it.pkg }
                val merged = mergeExcludedApps(Engine.excludedApps.value, visible, checkedVisible)
                Engine.setExcludedApps(requireContext(), merged)
                updateExcludedAppsSummary()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        excludedAppsDialog = dialog
        dialog.setOnDismissListener {
            if (excludedAppsDialog === dialog) excludedAppsDialog = null
        }
        dialog.show()
    }

    private fun isWifiNetwork(name: String): Boolean =
        name.contains("Wi-Fi", ignoreCase = true) || name.contains("wlan", ignoreCase = true)

    private fun showConnectionDetailsDialog() {
        detailsDialog?.dismiss()

        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val (primaryColor, secondaryColor, iconColor) = ctx.obtainStyledAttributes(
            intArrayOf(
                android.R.attr.textColorPrimary,
                android.R.attr.textColorSecondary,
                android.R.attr.colorControlNormal,
            ),
        ).let { ta ->
            try {
                val primary = ta.getColor(0, 0xFFFFFFFF.toInt())
                val secondary = ta.getColor(1, 0xFFAAAAAA.toInt())
                val icon = ta.getColor(2, primary)
                Triple(primary, secondary, icon)
            } finally {
                ta.recycle()
            }
        }

        fun appendStyled(sb: SpannableStringBuilder, text: String, bold: Boolean, color: Int) {
            val start = sb.length
            sb.append(text)
            val end = sb.length
            sb.setSpan(ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bold) {
                sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        val padH = (24 * density).toInt()
        val iconSize = (24 * density).toInt()
        val iconMarginEnd = (16 * density).toInt()
        val iconTopMargin = (2 * density).toInt()
        val iconTint = ColorStateList.valueOf(iconColor)

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, (14 * density).toInt(), padH, (4 * density).toInt())
        }

        val waitingView = TextView(ctx).apply {
            textSize = 14f
            setTextColor(secondaryColor)
            setLineSpacing(2f * density, 1f)
            visibility = View.GONE
        }
        container.addView(waitingView)

        val modeIcon = ImageView(ctx).apply {
            imageTintList = iconTint
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = iconMarginEnd
                topMargin = iconTopMargin
            }
        }
        val modeTitleView = TextView(ctx).apply {
            textSize = 15f
            setTextColor(primaryColor)
        }
        val summarySubView = TextView(ctx).apply {
            textSize = 13.5f
            setTextColor(secondaryColor)
            setLineSpacing(2f * density, 1f)
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        val summaryCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(modeTitleView)
            addView(summarySubView)
        }
        val summaryRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            addView(modeIcon)
            addView(summaryCol)
        }
        container.addView(summaryRow)

        val divider = View(ctx).apply {
            setBackgroundColor((secondaryColor and 0x00FFFFFF) or 0x26000000)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                maxOf(1, (1 * density).toInt()),
            ).apply {
                topMargin = (14 * density).toInt()
                bottomMargin = (14 * density).toInt()
            }
        }
        container.addView(divider)

        val linksContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(linksContainer)

        class LinkRowViews(
            val root: LinearLayout,
            val icon: ImageView,
            val title: TextView,
            val meta: TextView,
            val traffic: TextView,
            var iconRes: Int = 0,
        )

        val linkRows = linkedMapOf<String, LinkRowViews>()

        val scroll = ScrollView(ctx).apply { addView(container) }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.netbond_status_dialog_title)
            .setView(scroll)
            .setPositiveButton(R.string.netbond_status_close, null)
            .setNeutralButton(R.string.netbond_status_internet_settings) { _, _ ->
                runCatching {
                    startActivity(Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
                }.onFailure {
                    runCatching {
                        startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
                    }
                }
            }
            .create()
        detailsDialog = dialog

        var lastModeIcon = 0
        val update: () -> Unit = {
            if (isAdded) {
                val stats = Engine.stats.value
                val mode = Engine.mode.value
                val phase = Engine.phase.value
                dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.visibility =
                    if (stats.size < 2) View.VISIBLE else View.GONE

                if (stats.isEmpty()) {
                    waitingView.text = getString(
                        if (phase == Engine.Phase.OFF) R.string.netbond_status_off
                        else R.string.netbond_waiting_summary,
                    )
                    waitingView.visibility = View.VISIBLE
                    summaryRow.visibility = View.GONE
                    divider.visibility = View.GONE
                    linksContainer.visibility = View.GONE
                } else {
                    waitingView.visibility = View.GONE
                    summaryRow.visibility = View.VISIBLE
                    divider.visibility = View.VISIBLE
                    linksContainer.visibility = View.VISIBLE

                    val expectedModeIcon = modeIconRes(mode)
                    if (expectedModeIcon != lastModeIcon) {
                        modeIcon.setImageResource(expectedModeIcon)
                        lastModeIcon = expectedModeIcon
                    }

                    val modeSb = SpannableStringBuilder()
                    appendStyled(modeSb, mode.getTitle(ctx), bold = true, color = primaryColor)
                    if (Engine.batterySaverActive.value) {
                        val bsRes = if (mode == Engine.Mode.DATA_SAVER) {
                            R.string.netbond_battery_saver_short
                        } else {
                            R.string.netbond_status_battery_saver
                        }
                        appendStyled(
                            modeSb,
                            " · ${getString(bsRes)}",
                            bold = false,
                            color = secondaryColor,
                        )
                    }
                    modeTitleView.text = modeSb

                    val down = stats.sumOf { it.rateIn }
                    val up = stats.sumOf { it.rateOut }
                    val totalSpeed = down + up
                    val totalConns = stats.sumOf { it.active }
                    val totalBytes = Stats.size(Engine.sessionBytes.value.toDouble())
                    val connsText = resources.getQuantityString(
                        R.plurals.netbond_open_connections,
                        totalConns.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        totalConns,
                    )
                    val summarySb = SpannableStringBuilder()
                    appendStyled(summarySb, connsText, bold = true, color = primaryColor)
                    appendStyled(summarySb, " · ", bold = false, color = secondaryColor)
                    val transferredText = getString(R.string.netbond_details_transferred, totalBytes)
                    val bytesStart = summarySb.length
                    appendStyled(summarySb, transferredText, bold = false, color = secondaryColor)
                    val bytesOffset = transferredText.indexOf(totalBytes)
                    if (bytesOffset >= 0) {
                        val bStart = bytesStart + bytesOffset
                        val bEnd = bStart + totalBytes.length
                        summarySb.setSpan(ForegroundColorSpan(primaryColor), bStart, bEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        summarySb.setSpan(StyleSpan(Typeface.BOLD), bStart, bEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    summarySubView.text = summarySb

                    val activeNames = stats.map { it.name }.toSet()
                    linkRows.keys.filter { it !in activeNames }.forEach { removed ->
                        linkRows.remove(removed)?.let { linksContainer.removeView(it.root) }
                    }

                    stats.forEachIndexed { index, s ->
                        val row = linkRows.getOrPut(s.name) {
                            val iconView = ImageView(ctx).apply {
                                imageTintList = iconTint
                                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                                    marginEnd = iconMarginEnd
                                    topMargin = iconTopMargin
                                }
                            }
                            val titleView = TextView(ctx).apply {
                                textSize = 15f
                            }
                            val metaView = TextView(ctx).apply {
                                textSize = 13.5f
                                setTextColor(secondaryColor)
                                setPadding(0, (2 * density).toInt(), 0, 0)
                            }
                            val trafficView = TextView(ctx).apply {
                                textSize = 13.5f
                                setPadding(0, (2 * density).toInt(), 0, 0)
                            }
                            val col = LinearLayout(ctx).apply {
                                orientation = LinearLayout.VERTICAL
                                layoutParams = LinearLayout.LayoutParams(
                                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                                )
                                addView(titleView)
                                addView(metaView)
                                addView(trafficView)
                            }
                            val root = LinearLayout(ctx).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.TOP
                                addView(iconView)
                                addView(col)
                            }
                            linksContainer.addView(root)
                            LinkRowViews(root, iconView, titleView, metaView, trafficView)
                        }

                        row.root.setPadding(
                            0, 0, 0,
                            if (index < stats.lastIndex) (14 * density).toInt() else 0,
                        )
                        val expectedIcon = if (isWifiNetwork(s.name)) {
                            R.drawable.ic_wifi_signal_4
                        } else {
                            R.drawable.ic_settings_cellular
                        }
                        if (expectedIcon != row.iconRes) {
                            row.icon.setImageResource(expectedIcon)
                            row.iconRes = expectedIcon
                        }

                        val statusBase = getString(when (s.status) {
                            "up" -> R.string.netbond_network_up
                            "checking" -> R.string.netbond_network_checking
                            else -> R.string.netbond_network_no_internet
                        })
                        val titleSb = SpannableStringBuilder()
                        appendStyled(titleSb, s.name, bold = true, color = primaryColor)
                        appendStyled(titleSb, " · $statusBase", bold = false, color = secondaryColor)
                        if (s.rtt > 0) {
                            appendStyled(titleSb, " · ${s.rttString}", bold = false, color = secondaryColor)
                        }
                        row.title.text = titleSb

                        val v4Up = s.state == "up"
                        val v6Up = s.state6 == "up"
                        val proto = when {
                            v4Up && v6Up -> getString(R.string.netbond_status_ipv4_ipv6)
                            v4Up -> getString(R.string.netbond_status_ipv4_only)
                            v6Up -> getString(R.string.netbond_status_ipv6_only)
                            else -> "--"
                        }
                        row.meta.text = if (totalSpeed > 0) {
                            val share = if (s.ratio > 0) {
                                s.ratio.toInt()
                            } else {
                                ((s.rateIn + s.rateOut) / totalSpeed * 100).toInt()
                            }
                            getString(R.string.netbond_details_meta, proto, share, s.active)
                        } else {
                            getString(R.string.netbond_details_meta_idle, proto, s.active)
                        }

                        val linkBytes = Stats.size(s.bytesIn.toDouble() + s.bytesOut)
                        val trafficSb = SpannableStringBuilder()
                        appendStyled(
                            trafficSb,
                            "↓ ${Stats.rate(s.rateIn)}  ↑ ${Stats.rate(s.rateOut)}",
                            bold = true,
                            color = primaryColor,
                        )
                        appendStyled(trafficSb, " · $linkBytes", bold = false, color = secondaryColor)
                        row.traffic.text = trafficSb
                    }
                }
            }
        }

        detailsUpdater = update
        dialog.setOnDismissListener {
            if (detailsDialog === dialog) {
                detailsDialog = null
                detailsUpdater = null
            }
        }
        dialog.show()
        update()
    }

    private fun render() {
        if (!::mainSwitch.isInitialized) return
        val phase = Engine.phase.value
        val stats = Engine.stats.value
        val running = phase == Engine.Phase.ON
        mainSwitch.setCheckedInternal(phase != Engine.Phase.OFF)
        mainSwitch.isEnabled = !consentPending && !isRestricted()

        modePreference.value = Engine.mode.value.id.toString()
        updateModeSummary(Engine.mode.value)
        modePreference.isEnabled = !isRestricted()
        excludedAppsPreference.isEnabled = !isRestricted()


        val down = stats.sumOf { it.rateIn }
        val up = stats.sumOf { it.rateOut }
        val totalSpeed = down + up

        // Show connection status card while active, starting, or awaiting VPN permission
        findPreference<PreferenceCategory>("netbond_status_category")?.isVisible =
            phase != Engine.Phase.OFF || consentPending

        val statusIconRes = when {
            stats.size >= 2 -> R.drawable.ic_settings_netbond
            stats.size == 1 && isWifiNetwork(stats[0].name) -> R.drawable.ic_wifi_signal_4
            stats.size == 1 -> R.drawable.ic_settings_cellular
            else -> R.drawable.ic_settings_netbond
        }
        if (statusIconRes != lastStatusIconRes) {
            statusPreference.setIcon(statusIconRes)
            lastStatusIconRes = statusIconRes
        }

        val batteryNote = if (Engine.batterySaverActive.value) {
            " · ${getString(R.string.netbond_status_battery_saver)}"
        } else {
            ""
        }
        when {
            consentPending -> {
                statusPreference.title = getString(R.string.netbond_connection_status)
                statusPreference.summary = getString(R.string.netbond_status_permission)
            }
            phase == Engine.Phase.OFF -> {
                statusPreference.title = getString(R.string.netbond_connection_status)
                statusPreference.summary = getString(R.string.netbond_status_off)
            }
            phase == Engine.Phase.STARTING -> {
                statusPreference.title = getString(R.string.netbond_connection_status)
                statusPreference.summary = getString(R.string.netbond_status_starting)
            }
            stats.isEmpty() -> {
                statusPreference.title = getString(R.string.netbond_connection_status)
                statusPreference.summary = getString(R.string.netbond_waiting_summary)
            }
            stats.size == 1 -> {
                val s = stats[0]
                statusPreference.title = resources.getQuantityString(
                    R.plurals.netbond_status_networks, 1, 1,
                )
                val linkText = if (s.rtt > 0) "${s.name} (${s.rttString})" else s.name
                val hint = getString(
                    if (isWifiNetwork(s.name)) R.string.netbond_status_hint_mobile
                    else R.string.netbond_status_hint_wifi,
                )
                statusPreference.summary = "$linkText · $hint$batteryNote"
            }
            else -> {
                statusPreference.title = resources.getQuantityString(
                    R.plurals.netbond_status_networks, stats.size, stats.size,
                )
                val linksText = stats.joinToString(" + ") { s ->
                    if (s.rtt > 0) "${s.name} (${s.rttString})" else s.name
                }
                statusPreference.summary = if (totalSpeed > 0) {
                    "$linksText\n${Stats.rate(totalSpeed)}$batteryNote"
                } else {
                    "$linksText$batteryNote"
                }
            }
        }

        findPreference<PreferenceCategory>("netbond_statistics")!!.isVisible = running
        networks.isVisible = running
        chart.isVisible = running
        findPreference<PreferenceCategory>("netbond_chart_category")?.isVisible = running
        val ratioParts = stats.filter { it.ratio > 0 }.map { "${it.name} ${it.ratioString}" }
        val ratioLine = if (running && ratioParts.size > 1) ratioParts.joinToString(" · ") else null
        statsGrid.update(
            download = Stats.rate(down),
            upload = Stats.rate(up),
            transferred = Stats.size(Engine.sessionBytes.value.toDouble()),
            ratio = ratioLine,
        )

        val names = stats.map { it.name }.toSet()
        networkRows.keys.filter { it !in names }.forEach { name ->
            networks.removePreference(networkRows.remove(name)!!)
        }
        stats.forEachIndexed { index, stat ->
            val row = networkRows.getOrPut(stat.name) {
                Preference(preferenceScreen.context).apply {
                    key = "netbond_network_${stat.name}"
                    isSelectable = true
                    isPersistent = false
                    isIconSpaceReserved = true
                    setOnPreferenceClickListener {
                        showConnectionDetailsDialog()
                        true
                    }
                    if (isWifiNetwork(stat.name)) {
                        setIcon(R.drawable.ic_wifi_signal_4)
                    } else {
                        setIcon(R.drawable.ic_settings_cellular)
                    }
                    networks.addPreference(this)
                }
            }
            row.order = index
            row.title = stat.name
            val statusBase = getString(when (stat.status) {
                "up" -> R.string.netbond_network_up
                "checking" -> R.string.netbond_network_checking
                else -> R.string.netbond_network_no_internet
            })
            val status = if (stat.rtt > 0) "$statusBase · ${stat.rttString}" else statusBase
            val transferred = Stats.size(stat.bytesIn.toDouble() + stat.bytesOut)
            row.summary = if (totalSpeed > 0) {
                val share = if (stat.ratio > 0) {
                    stat.ratio.toInt()
                } else {
                    ((stat.rateIn + stat.rateOut) / totalSpeed * 100).toInt()
                }
                getString(
                    R.string.netbond_network_summary, status, share,
                    Stats.rate(stat.rateIn), Stats.rate(stat.rateOut), transferred,
                )
            } else {
                getString(
                    R.string.netbond_network_summary_idle, status,
                    Stats.rate(stat.rateIn), Stats.rate(stat.rateOut), transferred,
                )
            }
        }
        findPreference<Preference>("netbond_waiting")!!.isVisible = stats.isEmpty()
        chart.update(stats, Engine.history.value)
        if (detailsDialog?.isShowing == true) {
            detailsUpdater?.invoke()
        }
    }

    companion object {
        internal fun mergeExcludedApps(
            existing: Set<String>,
            visiblePackages: Set<String>,
            checkedVisible: Set<String>,
        ): Set<String> = (existing - visiblePackages) + checkedVisible

        private const val TAG = "NetBondFragment"
        private const val STATE_CONSENT_PENDING = "netbond_consent_pending"

        @JvmField
        val SEARCH_INDEX_DATA_PROVIDER: BaseSearchIndexProvider =
            object : BaseSearchIndexProvider(R.xml.netbond_settings) {
                override fun isPageSearchEnabled(context: Context): Boolean =
                    Engine.isSupported(context)

                override fun getNonIndexableKeys(context: Context): List<String> =
                    super.getNonIndexableKeys(context) + listOf(
                        "netbond_status", "netbond_statistics", "netbond_stats_grid",
                        "netbond_networks", "netbond_waiting", "netbond_chart",
                        "netbond_footer", "netbond_status_category", "netbond_chart_category",
                        "netbond_mode_category",
                        "netbond_mode", "netbond_mode_info", "netbond_excluded_apps",
                    )
            }
    }
}
