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
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.android.settings.R
import com.android.settingslib.widget.GroupSectionDividerMixin
import org.netbond.android.Stats

/** A Settings card containing the engine's last minute of traffic. */
class NetBondChartPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs), GroupSectionDividerMixin {

    private var stats: List<Stats> = emptyList()
    private var history: Map<String, List<Float>> = emptyMap()
    private var chartView: NetBondChartView? = null
    private var legendView: TextView? = null

    init {
        layoutResource = R.layout.netbond_chart_preference
        isSelectable = false
        isPersistent = false
        isCopyingEnabled = false
    }

    fun update(stats: List<Stats>, history: Map<String, List<Float>>) {
        if (this.stats == stats && this.history == history) return
        this.stats = stats
        this.history = history
        bindViews()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.setDividerAllowedAbove(false)
        holder.setDividerAllowedBelow(false)
        chartView = holder.findViewById(R.id.netbond_chart_view) as? NetBondChartView
        legendView = holder.findViewById(android.R.id.summary) as? TextView
        bindViews()
    }

    private fun bindViews() {
        val chart = chartView ?: return
        chart.update(stats.map { it.name to history[it.name].orEmpty() })
        val legend = SpannableStringBuilder()
        val descriptions = stats.mapIndexed { index, stat ->
            val description = context.getString(
                R.string.netbond_chart_series, stat.name, Stats.rate(stat.rateIn + stat.rateOut),
            )
            if (index > 0) legend.append("      ")
            val start = legend.length
            legend.append("\u25cf ")
            legend.setSpan(
                ForegroundColorSpan(chart.colorFor(stat.name)), start, start + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            legend.append(description)
            description
        }
        legendView?.apply {
            text = legend.ifEmpty { context.getString(R.string.netbond_notif_waiting) }
            visibility = View.VISIBLE
        }
        chart.contentDescription = context.getString(
            R.string.netbond_chart_description, descriptions.joinToString(", "),
        )
    }
}
