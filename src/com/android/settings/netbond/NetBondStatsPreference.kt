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
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.android.settings.R
import com.android.settingslib.widget.GroupSectionDividerMixin

/** Compact 2x1 + 1 Material 3 Expressive bento grid for live bonding statistics. */
class NetBondStatsPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs), GroupSectionDividerMixin {

    private var downloadText = "0\u00A0B/\u2060s"
    private var uploadText = "0\u00A0B/\u2060s"
    private var transferredText = "0\u00A0B"
    private var ratioText: String? = null

    private var downloadView: TextView? = null
    private var uploadView: TextView? = null
    private var transferredView: TextView? = null
    private var ratioView: TextView? = null

    init {
        layoutResource = R.layout.netbond_stats_preference
        isSelectable = false
        isPersistent = false
        isCopyingEnabled = false
    }

    fun update(download: String, upload: String, transferred: String, ratio: String?) {
        downloadText = download
        uploadText = upload
        transferredText = transferred
        ratioText = ratio
        applyTexts()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.setDividerAllowedAbove(false)
        holder.setDividerAllowedBelow(false)
        downloadView = holder.findViewById(R.id.netbond_stat_download) as? TextView
        uploadView = holder.findViewById(R.id.netbond_stat_upload) as? TextView
        transferredView = holder.findViewById(R.id.netbond_stat_transferred) as? TextView
        ratioView = holder.findViewById(R.id.netbond_stat_ratio) as? TextView
        applyTexts()
    }

    private fun applyTexts() {
        downloadView?.text = downloadText
        uploadView?.text = uploadText
        transferredView?.text = transferredText
        ratioView?.apply {
            val r = ratioText
            if (r.isNullOrEmpty()) {
                visibility = View.GONE
            } else {
                text = r
                visibility = View.VISIBLE
            }
        }
    }
}
