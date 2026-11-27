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

/** Right-align short histories and reject invalid samples before stacking. */
internal object NetBondChartData {
    fun stack(series: List<List<Float>>, samples: Int): List<FloatArray> {
        require(samples >= 2)
        var lower = FloatArray(samples)
        return series.map { values ->
            val recent = values.takeLast(samples)
            val upper = FloatArray(samples) { i ->
                val value = recent.getOrElse(i - (samples - recent.size)) { 0f }
                val safe = if (value.isFinite()) value.coerceAtLeast(0f) else 0f
                (lower[i].toDouble() + safe).coerceAtMost(Float.MAX_VALUE.toDouble()).toFloat()
            }
            lower = upper
            upper
        }
    }
}
