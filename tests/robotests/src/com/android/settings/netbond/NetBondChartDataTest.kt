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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NetBondChartDataTest {
    @Test
    fun emptySeries_hasNoBands() {
        assertThat(NetBondChartData.stack(emptyList(), 60)).isEmpty()
    }

    @Test
    fun shortHistory_alignsNewestSamplesToRight() {
        val bands = NetBondChartData.stack(listOf(listOf(2f, 3f)), 4)
        assertThat(bands.single().toList()).containsExactly(0f, 0f, 2f, 3f).inOrder()
    }

    @Test
    fun twoNetworks_stackToCombinedTraffic() {
        val bands = NetBondChartData.stack(listOf(listOf(2f, 3f), listOf(5f)), 4)
        assertThat(bands[0].toList()).containsExactly(0f, 0f, 2f, 3f).inOrder()
        assertThat(bands[1].toList()).containsExactly(0f, 0f, 2f, 8f).inOrder()
    }

    @Test
    fun longHistory_keepsOnlyLatestMinute() {
        val history = (1..80).map { it.toFloat() }
        val band = NetBondChartData.stack(listOf(history), 60).single()
        assertThat(band.size).isEqualTo(60)
        assertThat(band.first()).isEqualTo(21f)
        assertThat(band.last()).isEqualTo(80f)
    }

    @Test
    fun invalidRates_doNotBreakChartScale() {
        val band = NetBondChartData.stack(
            listOf(listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, 4f)), 4,
        ).single()
        assertThat(band.toList()).containsExactly(0f, 0f, 0f, 4f).inOrder()
    }

    @Test
    fun hugeRates_remainFinite() {
        val bands = NetBondChartData.stack(
            listOf(listOf(Float.MAX_VALUE), listOf(Float.MAX_VALUE)), 2,
        )
        assertThat(bands.last().last().isFinite()).isTrue()
    }

    @Test
    fun noTraffic_hasZeroBands() {
        val bands = NetBondChartData.stack(listOf(emptyList(), emptyList()), 60)
        assertThat(bands.size).isEqualTo(2)
        assertThat(bands.flatMap { it.toList() }.all { it == 0f }).isTrue()
    }
}
