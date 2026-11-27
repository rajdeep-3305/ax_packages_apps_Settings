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

import org.json.JSONArray

/** Parsed form of the engine's per-network stats (netbond.Netbond.stats()). */
data class Stats(
    val name: String,
    val state: String,
    val state6: String,
    val active: Long,
    val total: Long,
    val failed: Long,
    val bytesIn: Long,
    val bytesOut: Long,
    val rateIn: Double,
    val rateOut: Double,
    val weight: Double,
    val present: Boolean,
    val rtt: Long = 0L,
    val ratio: Double = 0.0,
    val slotId: Long = 0L,
) {
    /** "up", "checking" or "no internet", folding IPv4 and IPv6 into one word. */
    val status: String
        get() = when {
            state == "up" || state6 == "up" -> "up"
            state == "checking" || state6 == "checking" -> "checking"
            else -> "no internet"
        }

    val rttString: String
        get() = if (rtt > 0) "${rtt}\u00A0ms" else "--"

    val ratioString: String
        get() = "%.1f%%".format(ratio)

    companion object {
        fun parse(json: String): List<Stats> {
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Stats(
                    name = o.optString("name"),
                    state = o.optString("state"),
                    state6 = o.optString("state6"),
                    active = o.optLong("active"),
                    total = o.optLong("total"),
                    failed = o.optLong("failed"),
                    bytesIn = o.optLong("bytesIn"),
                    bytesOut = o.optLong("bytesOut"),
                    rateIn = o.optDouble("rateIn", 0.0),
                    rateOut = o.optDouble("rateOut", 0.0),
                    weight = o.optDouble("weight", 1.0),
                    present = o.optBoolean("present"),
                    rtt = o.optLong("rtt", 0L),
                    ratio = o.optDouble("ratio", 0.0),
                    slotId = o.optLong("slotId", 0L),
                )
            }
        }

        fun rate(bytesPerSec: Double): String = size(bytesPerSec) + "/\u2060s"

        fun size(bytes: Double): String = when {
            bytes >= 1e9 -> "%.1f\u00A0GB".format(bytes / 1e9)
            bytes >= 1e6 -> "%.1f\u00A0MB".format(bytes / 1e6)
            bytes >= 1e3 -> "%.0f\u00A0KB".format(bytes / 1e3)
            else -> "%.0f\u00A0B".format(bytes)
        }
    }
}
