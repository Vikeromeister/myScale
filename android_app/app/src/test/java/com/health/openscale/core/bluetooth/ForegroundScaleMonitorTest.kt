/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.UnitType
import org.junit.Test

class ForegroundScaleMonitorTest {

    @Test
    fun `LiveWeighingState Idle represents idle state`() {
        val state: LiveWeighingState = LiveWeighingState.Idle
        assertThat(state).isInstanceOf(LiveWeighingState.Idle::class.java)
    }

    @Test
    fun `LiveWeighingState WaitingForScale holds device name and represents waiting state`() {
        val waiting = LiveWeighingState.WaitingForScale(deviceName = "Xiaomi Scale")
        assertThat(waiting).isInstanceOf(LiveWeighingState.WaitingForScale::class.java)
        assertThat(waiting.deviceName).isEqualTo("Xiaomi Scale")
    }

    @Test
    fun `LiveWeighingState Measuring holds fluctuating weight and flags`() {
        val measuring = LiveWeighingState.Measuring(
            weightKg = 74.2f,
            isStabilized = false,
            isMeasuringImpedance = false,
            unit = UnitType.KG,
            deviceName = "Mi Scale 2"
        )

        assertThat(measuring.weightKg).isEqualTo(74.2f)
        assertThat(measuring.isStabilized).isFalse()
        assertThat(measuring.isMeasuringImpedance).isFalse()
        assertThat(measuring.unit).isEqualTo(UnitType.KG)
        assertThat(measuring.deviceName).isEqualTo("Mi Scale 2")
    }

    @Test
    fun `LiveWeighingState Measuring updates to impedance measurement when stabilized`() {
        val measuring = LiveWeighingState.Measuring(
            weightKg = 74.2f,
            isStabilized = true,
            isMeasuringImpedance = true,
            unit = UnitType.KG,
            deviceName = "Mi Scale 2"
        )

        assertThat(measuring.isStabilized).isTrue()
        assertThat(measuring.isMeasuringImpedance).isTrue()
    }

    @Test
    fun `LiveWeighingState Completed holds persisted measurement and calculated metrics`() {
        val completed = LiveWeighingState.Completed(
            measurementId = 42,
            userId = 1,
            weightKg = 74.2f,
            bodyFatPercent = 18.2f,
            musclePercent = 75.6f,
            waterPercent = 58.9f,
            impedanceOhm = 512.0f,
            unit = UnitType.KG,
            deviceName = "Mi Scale 2"
        )

        assertThat(completed.measurementId).isEqualTo(42)
        assertThat(completed.userId).isEqualTo(1)
        assertThat(completed.weightKg).isEqualTo(74.2f)
        assertThat(completed.bodyFatPercent).isEqualTo(18.2f)
        assertThat(completed.musclePercent).isEqualTo(75.6f)
        assertThat(completed.waterPercent).isEqualTo(58.9f)
        assertThat(completed.impedanceOhm).isEqualTo(512.0f)
        assertThat(completed.unit).isEqualTo(UnitType.KG)
        assertThat(completed.deviceName).isEqualTo("Mi Scale 2")
    }
}
