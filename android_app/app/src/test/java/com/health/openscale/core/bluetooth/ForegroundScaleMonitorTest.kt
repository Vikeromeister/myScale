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

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.scales.XiaomiAdvFrame
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.data.User
import com.health.openscale.core.facade.MeasurementFacade
import com.health.openscale.core.facade.SavedBleMeasurementResult
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.facade.UserFacade
import com.health.openscale.core.service.ScannedDeviceInfo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundScaleMonitorTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var settingsFacade: SettingsFacade
    private lateinit var measurementFacade: MeasurementFacade
    private lateinit var userFacade: UserFacade
    private val savedDeviceFlow = MutableStateFlow<ScannedDeviceInfo?>(
        ScannedDeviceInfo(
            name = "MI_SCALE",
            address = "AA:BB:CC:DD:EE:FF",
            rssi = -60,
            serviceUuids = emptyList(),
            manufacturerData = null,
            serviceData = emptyMap(),
            isSupported = true
        )
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = mockk(relaxed = true)
        settingsFacade = mockk(relaxed = true)
        measurementFacade = mockk(relaxed = true)
        userFacade = mockk(relaxed = true)

        every { settingsFacade.observeSavedDevice() } returns savedDeviceFlow
        val testUser = User(
            id = 1,
            name = "Test User",
            birthDate = 631152000000L, // 1990-01-01
            gender = GenderType.MALE,
            heightCm = 180f,
            activityLevel = ActivityLevel.SEDENTARY,
            useAssistedWeighing = false
        )
        every { userFacade.observeSelectedUser() } returns flowOf(testUser)
        every { userFacade.observeAllUsers() } returns flowOf(listOf(testUser))
        every { measurementFacade.getAllMeasurementTypes() } returns flowOf(
            MeasurementType.seedRows().mapIndexed { index, type -> type.copy(id = index + 1) }
        )
        coEvery { measurementFacade.saveMeasurementFromBleDevice(any(), any()) } returns Result.success(
            SavedBleMeasurementResult(measurementId = 99, userId = 1)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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

    @Test
    fun `stale wakeup packets from previous day are ignored when starting a new live weighing session`() = runTest {
        val monitor = ForegroundScaleMonitor(context, settingsFacade, measurementFacade, userFacade)
        runCurrent()

        val sessionId = monitor.startSessionForTesting("MI_SCALE")
        val waitingState = monitor.liveWeighingState.value
        assertThat(waitingState).isInstanceOf(LiveWeighingState.WaitingForScale::class.java)

        // 1. Scale wakes up and broadcasts yesterday's completed measurement with impedance from NVRAM
        monitor.handleAdvFrame(
            frame = XiaomiAdvFrame(
                weightKg = 76.8f,
                unit = UnitType.KG,
                isStabilized = true,
                hasImpedance = true,
                isImpedanceMeasuring = false,
                impedanceOhm = 495f,
                weightRemoved = false,
                isVersion2 = true,
                timestampMillis = 1_700_000_000_000L,
                scaleTimestampSeconds = 1_700_000_000L
            ),
            deviceName = "MI_SCALE",
            sessionId = sessionId,
            nowMs = 1_000L
        )
        // Must remain WaitingForScale — yesterday's completed measurement must NOT be loaded
        assertThat(monitor.liveWeighingState.value).isInstanceOf(LiveWeighingState.WaitingForScale::class.java)

        // 2. Scale broadcasts a brief wake-up stabilized packet without impedance before taring
        monitor.handleAdvFrame(
            frame = XiaomiAdvFrame(
                weightKg = 76.8f,
                unit = UnitType.KG,
                isStabilized = true,
                hasImpedance = false,
                isImpedanceMeasuring = false,
                impedanceOhm = null,
                weightRemoved = false,
                isVersion2 = true,
                timestampMillis = 1_700_000_000_000L,
                scaleTimestampSeconds = 1_700_000_000L
            ),
            deviceName = "MI_SCALE",
            sessionId = sessionId,
            nowMs = 1_200L
        )
        assertThat(monitor.liveWeighingState.value).isInstanceOf(LiveWeighingState.WaitingForScale::class.java)

        // 3. Now live fluctuating weight arrives for today's weigh-in (74.2 kg)
        monitor.handleAdvFrame(
            frame = XiaomiAdvFrame(
                weightKg = 74.2f,
                unit = UnitType.KG,
                isStabilized = false,
                hasImpedance = false,
                isImpedanceMeasuring = false,
                impedanceOhm = null,
                weightRemoved = false,
                isVersion2 = true,
                timestampMillis = 1_700_086_400_000L,
                scaleTimestampSeconds = 1_700_086_400L
            ),
            deviceName = "MI_SCALE",
            sessionId = sessionId,
            nowMs = 1_500L
        )

        val measuring = monitor.liveWeighingState.value as LiveWeighingState.Measuring
        assertThat(measuring.weightKg).isWithin(0.01f).of(74.2f)
        assertThat(measuring.isStabilized).isFalse()
    }

    @Test
    fun `tare or low weight before stabilization keeps weigh-in window open and returns to WaitingForScale`() = runTest {
        val monitor = ForegroundScaleMonitor(context, settingsFacade, measurementFacade, userFacade)
        runCurrent()

        val sessionId = monitor.startSessionForTesting("MI_SCALE")

        // User taps scale to wake/tare it -> brief fluctuating reading > 5kg
        monitor.handleAdvFrame(
            frame = XiaomiAdvFrame(
                weightKg = 12.0f,
                unit = UnitType.KG,
                isStabilized = false,
                hasImpedance = false,
                impedanceOhm = null,
                weightRemoved = false,
                isVersion2 = true,
                timestampMillis = 1_700_086_400_000L
            ),
            deviceName = "MI_SCALE",
            sessionId = sessionId,
            nowMs = 1_000L
        )
        assertThat(monitor.liveWeighingState.value).isInstanceOf(LiveWeighingState.Measuring::class.java)

        // Scale drops to 0.0 kg during tare
        monitor.handleAdvFrame(
            frame = XiaomiAdvFrame(
                weightKg = 0.0f,
                unit = UnitType.KG,
                isStabilized = false,
                hasImpedance = false,
                impedanceOhm = null,
                weightRemoved = false,
                isVersion2 = true,
                timestampMillis = 1_700_086_400_000L
            ),
            deviceName = "MI_SCALE",
            sessionId = sessionId,
            nowMs = 1_500L
        )

        // After 3 seconds, window must NOT have disappeared to Idle!
        advanceTimeBy(3_500L)
        runCurrent()
        assertThat(monitor.liveWeighingState.value).isNotEqualTo(LiveWeighingState.Idle)

        // After 6 seconds without weight, it returns to WaitingForScale (keeping the dialog open)
        advanceTimeBy(3_000L)
        runCurrent()
        assertThat(monitor.liveWeighingState.value).isInstanceOf(LiveWeighingState.WaitingForScale::class.java)
    }

    @Test
    fun `full live weigh-in completes with body composition and auto-dismisses after 5 seconds`() = runTest {
        val monitor = ForegroundScaleMonitor(context, settingsFacade, measurementFacade, userFacade)
        runCurrent()

        val sessionId = monitor.startSessionForTesting("MI_SCALE")

        // 1. Fluctuating weight
        monitor.handleAdvFrame(
            frame = XiaomiAdvFrame(
                weightKg = 74.0f,
                unit = UnitType.KG,
                isStabilized = false,
                hasImpedance = false,
                impedanceOhm = null,
                weightRemoved = false,
                isVersion2 = true,
                timestampMillis = 1_700_086_400_000L,
                scaleTimestampSeconds = 1_700_086_400L
            ),
            deviceName = "MI_SCALE",
            sessionId = sessionId,
            nowMs = 1_000L
        )
        assertThat(monitor.liveWeighingState.value).isEqualTo(
            LiveWeighingState.Measuring(
                weightKg = 74.0f,
                isStabilized = false,
                isMeasuringImpedance = false,
                unit = UnitType.KG,
                deviceName = "MI_SCALE",
                sessionId = sessionId
            )
        )

        // 2. Stabilized weight while BIA impedance is measuring
        monitor.handleAdvFrame(
            frame = XiaomiAdvFrame(
                weightKg = 74.2f,
                unit = UnitType.KG,
                isStabilized = true,
                hasImpedance = false,
                isImpedanceMeasuring = true,
                impedanceOhm = null,
                weightRemoved = false,
                isVersion2 = true,
                timestampMillis = 1_700_086_402_000L,
                scaleTimestampSeconds = 1_700_086_402L
            ),
            deviceName = "MI_SCALE",
            sessionId = sessionId,
            nowMs = 3_000L
        )
        assertThat(monitor.liveWeighingState.value).isEqualTo(
            LiveWeighingState.Measuring(
                weightKg = 74.2f,
                isStabilized = true,
                isMeasuringImpedance = true,
                unit = UnitType.KG,
                deviceName = "MI_SCALE",
                sessionId = sessionId
            )
        )

        // 3. BIA impedance completes (510 Ohm)
        monitor.handleAdvFrame(
            frame = XiaomiAdvFrame(
                weightKg = 74.2f,
                unit = UnitType.KG,
                isStabilized = true,
                hasImpedance = true,
                isImpedanceMeasuring = false,
                impedanceOhm = 510f,
                weightRemoved = false,
                isVersion2 = true,
                timestampMillis = 1_700_086_405_000L,
                scaleTimestampSeconds = 1_700_086_405L
            ),
            deviceName = "MI_SCALE",
            sessionId = sessionId,
            nowMs = 6_000L
        )
        runCurrent()

        val completed = monitor.liveWeighingState.value
        assertThat(completed).isInstanceOf(LiveWeighingState.Completed::class.java)
        val completedState = completed as LiveWeighingState.Completed
        assertThat(completedState.weightKg).isWithin(0.01f).of(74.2f)
        assertThat(completedState.impedanceOhm).isWithin(0.1f).of(510f)
        assertThat(completedState.bodyFatPercent).isNotNull()
        assertThat(completedState.musclePercent).isNotNull()
        assertThat(completedState.waterPercent).isNotNull()

        // 4. Remains open at 4.9 seconds, then auto-dismisses at 5.0 seconds
        advanceTimeBy(4_900L)
        runCurrent()
        assertThat(monitor.liveWeighingState.value).isInstanceOf(LiveWeighingState.Completed::class.java)

        advanceTimeBy(200L)
        runCurrent()
        assertThat(monitor.liveWeighingState.value).isEqualTo(LiveWeighingState.Idle)
    }
}
