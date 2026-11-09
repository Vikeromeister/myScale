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

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.scales.XiaomiAdvFrame
import com.health.openscale.core.bluetooth.scales.XiaomiBodyMetricsCalculator
import com.health.openscale.core.bluetooth.scales.XiaomiScaleAdvParser
import com.health.openscale.core.data.Bpm
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.data.UnitValue
import com.health.openscale.core.facade.MeasurementFacade
import com.health.openscale.core.facade.SavedBleMeasurementResult
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.facade.UserFacade
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.utils.ConverterUtils
import com.health.openscale.core.utils.LogManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Represents the live state of a weigh-in session on a scale.
 */
sealed class LiveWeighingState {
    /** No active weigh-in in progress. */
    object Idle : LiveWeighingState()

    /**
     * Actively waiting for the user to step on the scale.
     * @param deviceName Scale name for display.
     * @param sessionId Unique identifier for this weigh-in session.
     */
    data class WaitingForScale(
        val deviceName: String? = null,
        val sessionId: Long = 0L
    ) : LiveWeighingState()

    /**
     * Scale is currently measuring.
     * @param weightKg Current weight reading in kg.
     * @param isStabilized True when the weight reading has stabilized on the scale display.
     * @param isMeasuringImpedance True when the scale is measuring electrical impedance.
     * @param unit Scale's native or preferred unit.
     * @param deviceName Scale name for display.
     * @param sessionId Unique identifier for this weigh-in session.
     */
    data class Measuring(
        val weightKg: Float,
        val isStabilized: Boolean,
        val isMeasuringImpedance: Boolean,
        val unit: UnitType = UnitType.KG,
        val deviceName: String? = null,
        val sessionId: Long = 0L
    ) : LiveWeighingState()

    /**
     * Weigh-in is complete and persisted to the database.
     * @param measurementId Generated database row ID for the saved measurement.
     * @param userId Assigned user ID.
     * @param weightKg Final weight in kg.
     * @param bodyFatPercent Calculated body fat % if impedance was measured.
     * @param musclePercent Calculated muscle % if impedance was measured.
     * @param waterPercent Calculated body water % if impedance was measured.
     * @param impedanceOhm Measured impedance in Ohms.
     * @param unit Unit type.
     * @param deviceName Scale name.
     * @param sessionId Unique identifier for this weigh-in session.
     */
    data class Completed(
        val measurementId: Int,
        val userId: Int,
        val weightKg: Float,
        val bodyFatPercent: Float? = null,
        val musclePercent: Float? = null,
        val waterPercent: Float? = null,
        val impedanceOhm: Float? = null,
        val unit: UnitType = UnitType.KG,
        val deviceName: String? = null,
        val sessionId: Long = 0L
    ) : LiveWeighingState()
}

/**
 * Monitors Xiaomi smart scale BLE advertisements in the foreground.
 *
 * When the user initiates a live weigh-in session, this monitor scans for the saved
 * scale's advertisements using [ScanSettings.SCAN_MODE_LOW_LATENCY] with completely
 * fresh session state.
 *
 * As soon as the user steps on the scale:
 * 1. Stale/cached packets broadcast by the scale on wake-up (from a previous weigh-in) are filtered out.
 * 2. Fluctuation phase: Advertised weight updates are forwarded in real time (<100ms lag)
 *    to [liveWeighingState] as [LiveWeighingState.Measuring], in exact sync with the scale display.
 * 3. Stabilization phase: Once weight stabilizes, [LiveWeighingState.Measuring.isStabilized] is set
 *    to true and impedance measurement progress is indicated.
 * 4. Impedance completion: When the scale broadcasts completed impedance, all body metrics
 *    are computed, saved to the database via [MeasurementFacade], and emitted as
 *    [LiveWeighingState.Completed] for 5 seconds before auto-dismissing.
 */
@Singleton
class ForegroundScaleMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsFacade: SettingsFacade,
    private val measurementFacade: MeasurementFacade,
    private val userFacade: UserFacade,
) {
    companion object {
        private const val TAG = "ForegroundScaleMonitor"
        private const val MIN_ACTIVE_WEIGHT_KG = 5.0f
        internal const val WAITING_TIMEOUT_MS = 60_000L
        internal const val MEASURING_INACTIVITY_TIMEOUT_MS = 30_000L
        internal const val IMPEDANCE_TIMEOUT_MS = 15_000L
        internal const val TARE_RETURN_TO_WAITING_DEBOUNCE_MS = 6_000L
        internal const val POST_STABILIZATION_STEP_OFF_DEBOUNCE_MS = 3_000L
        internal const val STABILIZED_WITHOUT_LIVE_WARMUP_MS = 2_000L
        internal const val COMPLETED_DISPLAY_DURATION_MS = 5_000L
        private const val COOLDOWN_DURATION_MS = 5_000L
    }

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _liveWeighingState = MutableStateFlow<LiveWeighingState>(LiveWeighingState.Idle)
    val liveWeighingState: StateFlow<LiveWeighingState> = _liveWeighingState.asStateFlow()

    private var isMonitoring = false
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var activeScanCallback: ScanCallback? = null
    private var currentSavedDevice: ScannedDeviceInfo? = null

    private var sessionCounter: Long = 0L
    private var activeSessionId: Long = 0L

    private var sessionInitJob: Job? = null
    private var inactivityJob: Job? = null
    private var impedanceTimeoutJob: Job? = null
    private var stepOffJob: Job? = null
    private var autoDismissJob: Job? = null

    private var lastStabilizedFrame: XiaomiAdvFrame? = null
    private var hasSeenLiveWeight = false
    private var stabilizedCandidateStartMs: Long = 0L
    private var lastFinalizedScaleTimestampSeconds: Long? = null
    private var cooldownUntilMs: Long = 0L
    private var isFinalizing = false

    init {
        monitorScope.launch {
            settingsFacade.observeSavedDevice().collect { saved ->
                currentSavedDevice = saved
            }
        }
    }

    private fun createScanCallback(sessionId: Long): ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (sessionId != activeSessionId) return
            val state = _liveWeighingState.value
            if (isFinalizing || (state !is LiveWeighingState.WaitingForScale && state !is LiveWeighingState.Measuring)) {
                return
            }

            val device = result.device ?: return
            val saved = currentSavedDevice ?: return

            if (!device.address.equals(saved.address, ignoreCase = true)) return

            val now = System.currentTimeMillis()
            if (now < cooldownUntilMs) return

            val frame = XiaomiScaleAdvParser.parse(result.scanRecord) ?: return
            handleAdvFrame(frame, saved.name, sessionId, now)
        }

        override fun onScanFailed(errorCode: Int) {
            if (sessionId != activeSessionId) return
            LogManager.w(TAG, "BLE scan failed with code: $errorCode")
            isMonitoring = false
        }
    }

    internal fun handleAdvFrame(
        frame: XiaomiAdvFrame,
        deviceName: String,
        sessionId: Long = activeSessionId,
        nowMs: Long = System.currentTimeMillis()
    ) {
        // 0. Ignore packets from superseded sessions or when already finalized/idle
        if (sessionId != activeSessionId || isFinalizing) {
            return
        }
        val currentState = _liveWeighingState.value
        if (currentState !is LiveWeighingState.WaitingForScale && currentState !is LiveWeighingState.Measuring) {
            return
        }

        // Ignore packets carrying the exact same hardware scale timestamp as an already finalized weigh-in
        if (lastFinalizedScaleTimestampSeconds != null &&
            frame.scaleTimestampSeconds != null &&
            frame.scaleTimestampSeconds == lastFinalizedScaleTimestampSeconds &&
            (frame.isStabilized || frame.hasImpedance || frame.weightRemoved)
        ) {
            LogManager.d(TAG, "Ignoring stale scale packet with already-finalized timestamp ${frame.scaleTimestampSeconds}")
            return
        }

        // Phase 1: Waiting for a fresh live weigh-in to begin in this session.
        // When the Xiaomi scale first wakes up as the user steps on it, its BLE radio initially
        // broadcasts the previous measurement stored in its NVRAM (which has weightRemoved=true,
        // hasImpedance=true, and/or isStabilized=true from yesterday or earlier).
        if (!hasSeenLiveWeight) {
            if (frame.weightRemoved || frame.weightKg < MIN_ACTIVE_WEIGHT_KG) {
                stabilizedCandidateStartMs = 0L
                return
            }
            if (frame.hasImpedance) {
                // A live weigh-in never starts with impedance already completed before measuring weight.
                LogManager.d(TAG, "Ignoring stale completed-impedance packet (${frame.weightKg}kg, ${frame.impedanceOhm}Ω) while waiting for new weigh-in")
                stabilizedCandidateStartMs = 0L
                return
            }
            if (!frame.isStabilized) {
                // Live fluctuating weight detected! The scale is actively measuring the user now.
                hasSeenLiveWeight = true
                stabilizedCandidateStartMs = 0L
            } else {
                // Stabilized packet without impedance while we haven't seen fluctuating weight yet.
                // Require 2 seconds of continuous stabilized packets (without tare or weightRemoved)
                // so a brief wake-up NVRAM packet from a previous weight-only session is never latched.
                if (stabilizedCandidateStartMs == 0L) {
                    stabilizedCandidateStartMs = nowMs
                    return
                }
                if (nowMs - stabilizedCandidateStartMs < STABILIZED_WITHOUT_LIVE_WARMUP_MS) {
                    return
                }
                hasSeenLiveWeight = true
                stabilizedCandidateStartMs = 0L
            }
        }

        // Phase 2: Active live weigh-in is confirmed for this session.

        // Branch 1: Live fluctuating weight on scale (user standing on scale, weight not yet locked)
        if (!frame.isStabilized && !frame.weightRemoved && frame.weightKg >= MIN_ACTIVE_WEIGHT_KG) {
            stepOffJob?.cancel()
            stepOffJob = null
            impedanceTimeoutJob?.cancel()
            impedanceTimeoutJob = null
            lastStabilizedFrame = null

            _liveWeighingState.value = LiveWeighingState.Measuring(
                weightKg = frame.weightKg,
                isStabilized = false,
                isMeasuringImpedance = false,
                unit = frame.unit,
                deviceName = deviceName,
                sessionId = sessionId
            )

            armMeasuringInactivityTimeout(sessionId)
            return
        }

        // Branch 2: Completed impedance measurement arrived during active session!
        if (frame.hasImpedance && frame.impedanceOhm != null && frame.impedanceOhm > 0f) {
            val stabilized = lastStabilizedFrame
            if (stabilized != null &&
                frame.weightKg >= MIN_ACTIVE_WEIGHT_KG &&
                kotlin.math.abs(frame.weightKg - stabilized.weightKg) > 3.0f
            ) {
                LogManager.w(TAG, "Ignoring impedance packet with mismatched weight (${frame.weightKg}kg vs stabilized ${stabilized.weightKg}kg)")
                return
            }

            val finalFrame = if (frame.weightKg < MIN_ACTIVE_WEIGHT_KG && stabilized != null) {
                frame.copy(weightKg = stabilized.weightKg)
            } else {
                frame
            }

            stepOffJob?.cancel()
            stepOffJob = null
            impedanceTimeoutJob?.cancel()
            impedanceTimeoutJob = null
            inactivityJob?.cancel()
            inactivityJob = null
            finalizeMeasurement(finalFrame, deviceName, sessionId)
            return
        }

        // Branch 3: Weight has stabilized on the scale (and impedance is now being measured on v2)
        if (frame.isStabilized && !frame.weightRemoved && frame.weightKg >= MIN_ACTIVE_WEIGHT_KG) {
            stepOffJob?.cancel()
            stepOffJob = null
            inactivityJob?.cancel()
            inactivityJob = null
            lastStabilizedFrame = frame

            if (frame.isVersion2) {
                // Update UI to indicate weight is locked and scale is measuring BIA
                _liveWeighingState.value = LiveWeighingState.Measuring(
                    weightKg = frame.weightKg,
                    isStabilized = true,
                    isMeasuringImpedance = true,
                    unit = frame.unit,
                    deviceName = deviceName,
                    sessionId = sessionId
                )

                // Arm 15-second fallback timer in case user wears socks or scale fails to measure impedance
                if (impedanceTimeoutJob == null || !impedanceTimeoutJob!!.isActive) {
                    impedanceTimeoutJob = monitorScope.launch {
                        delay(IMPEDANCE_TIMEOUT_MS)
                        if (sessionId == activeSessionId && _liveWeighingState.value !is LiveWeighingState.Completed) {
                            val fallback = lastStabilizedFrame ?: frame
                            LogManager.i(TAG, "Impedance timeout reached; finalizing with stabilized weight only")
                            finalizeMeasurement(fallback, deviceName, sessionId)
                        }
                    }
                }
            } else {
                // Mi Scale 1 (v1) has no impedance hardware -> finalize immediately upon stabilization
                finalizeMeasurement(frame, deviceName, sessionId)
            }
            return
        }

        // Branch 4: Weight removed flag is set OR low/zero weight (< 5.0 kg) during Measuring
        if (_liveWeighingState.value is LiveWeighingState.Measuring) {
            if (lastStabilizedFrame != null) {
                // Weight already locked and we are waiting for body composition (impedance).
                // Debounce for 3 seconds before finalizing weight-only so transient frames or
                // stepping off right as BIA finishes still captures the final impedance packet.
                if (stepOffJob == null || !stepOffJob!!.isActive) {
                    stepOffJob = monitorScope.launch {
                        delay(POST_STABILIZATION_STEP_OFF_DEBOUNCE_MS)
                        if (sessionId == activeSessionId &&
                            _liveWeighingState.value is LiveWeighingState.Measuring &&
                            lastStabilizedFrame != null
                        ) {
                            LogManager.i(TAG, "Weight removed/dropped after stabilization; finalizing with stabilized reading")
                            finalizeMeasurement(lastStabilizedFrame!!, deviceName, sessionId)
                        }
                    }
                }
            } else {
                // Weight has NOT stabilized yet (e.g. scale taring to 0.0 kg after initial step-on).
                // Do NOT close the weigh-in window! After 6 seconds without weight, return to
                // WaitingForScale so the window stays open and ready.
                if (stepOffJob == null || !stepOffJob!!.isActive) {
                    stepOffJob = monitorScope.launch {
                        delay(TARE_RETURN_TO_WAITING_DEBOUNCE_MS)
                        if (sessionId == activeSessionId &&
                            _liveWeighingState.value is LiveWeighingState.Measuring &&
                            lastStabilizedFrame == null
                        ) {
                            LogManager.d(TAG, "Tare/low weight persisted before stabilization; returning to WaitingForScale")
                            hasSeenLiveWeight = false
                            stabilizedCandidateStartMs = 0L
                            _liveWeighingState.value = LiveWeighingState.WaitingForScale(
                                deviceName = deviceName,
                                sessionId = sessionId
                            )
                            armWaitingTimeout(sessionId, deviceName)
                        }
                    }
                }
            }
        }
    }

    private fun armWaitingTimeout(sessionId: Long, deviceName: String?) {
        inactivityJob?.cancel()
        inactivityJob = monitorScope.launch {
            delay(WAITING_TIMEOUT_MS)
            if (sessionId == activeSessionId && _liveWeighingState.value is LiveWeighingState.WaitingForScale) {
                LogManager.d(TAG, "Live weigh-in waiting session timed out (${WAITING_TIMEOUT_MS}ms) for $deviceName")
                resetState()
            }
        }
    }

    private fun armMeasuringInactivityTimeout(sessionId: Long) {
        inactivityJob?.cancel()
        inactivityJob = monitorScope.launch {
            delay(MEASURING_INACTIVITY_TIMEOUT_MS)
            if (sessionId == activeSessionId &&
                _liveWeighingState.value is LiveWeighingState.Measuring &&
                lastStabilizedFrame == null
            ) {
                LogManager.d(TAG, "Inactivity timeout during fluctuating weight; resetting to Idle")
                resetState()
            }
        }
    }

    private fun finalizeMeasurement(frame: XiaomiAdvFrame, deviceName: String, sessionId: Long) {
        if (sessionId != activeSessionId || isFinalizing || _liveWeighingState.value is LiveWeighingState.Completed) return
        isFinalizing = true
        if (frame.scaleTimestampSeconds != null) {
            lastFinalizedScaleTimestampSeconds = frame.scaleTimestampSeconds
        }

        cooldownUntilMs = System.currentTimeMillis() + COOLDOWN_DURATION_MS
        stopActiveScan()
        cancelTimers()

        monitorScope.launch {
            try {
                val currentSelected = userFacade.observeSelectedUser().first()
                    ?: userFacade.observeAllUsers().first().firstOrNull()

                if (currentSelected == null) {
                    LogManager.w(TAG, "No user available to save live measurement")
                    cooldownUntilMs = 0L
                    resetState()
                    return@launch
                }

                val scaleUser = ScaleUser().apply {
                    id = currentSelected.id
                    userName = currentSelected.name
                    birthday = Date(currentSelected.birthDate)
                    bodyHeight = if (currentSelected.heightCm > 0) currentSelected.heightCm else 175f
                    gender = currentSelected.gender
                }

                val nowDate = Date()
                val m = ScaleMeasurement().apply {
                    dateTime = nowDate
                    this[MeasurementType.WEIGHT] = Kg(frame.weightKg)
                    userId = currentSelected.id
                }

                if (frame.hasImpedance && frame.impedanceOhm != null && frame.impedanceOhm > 0) {
                    m[MeasurementType.IMPEDANCE] = Ohm(frame.impedanceOhm)
                    val algorithm = settingsFacade.observeSetting("ble/MiScaleHandler/body_comp_algorithm", "XIAOMI").first()
                    if (algorithm == "BODYMISCALE_SCIENCE") {
                        XiaomiBodyMetricsCalculator.applyBodyMiScaleComposition(m, frame.impedanceOhm, scaleUser)
                    } else {
                        XiaomiBodyMetricsCalculator.applyXiaomiComposition(m, frame.impedanceOhm, scaleUser)
                    }
                }

                val saveResult = persistScaleMeasurement(m, currentSelected.id)
                val (measurementId, assignedUserId) = if (saveResult.isSuccess) {
                    val resultData = saveResult.getOrNull()
                    Pair(resultData?.measurementId ?: 0, resultData?.userId ?: currentSelected.id)
                } else {
                    LogManager.w(TAG, "saveMeasurementFromBleDevice failed; saving directly to user ${currentSelected.id}: ${saveResult.exceptionOrNull()?.message}")
                    val values = buildMeasurementValues(m)
                    val dbM = Measurement(
                        userId = currentSelected.id,
                        timestamp = m.dateTime?.time ?: System.currentTimeMillis()
                    )
                    val directId = measurementFacade.saveMeasurement(dbM, values).getOrNull()?.toInt() ?: 0
                    Pair(directId, currentSelected.id)
                }

                // Update MiScaleHandler's last imported timestamp so GATT history never re-imports this weigh-in
                val nowSec = (nowDate.time / 1000L).toInt()
                val frameSec = frame.scaleTimestampSeconds?.toInt() ?: 0
                val latestTsSec = maxOf(nowSec, frameSec)
                runCatching {
                    settingsFacade.saveSetting("ble/MiScaleHandler/last_imported_ts_$assignedUserId", latestTsSec)
                    if (assignedUserId != currentSelected.id) {
                        settingsFacade.saveSetting("ble/MiScaleHandler/last_imported_ts_${currentSelected.id}", latestTsSec)
                    }
                }

                if (sessionId != activeSessionId) return@launch

                _liveWeighingState.value = LiveWeighingState.Completed(
                    measurementId = measurementId,
                    userId = assignedUserId,
                    weightKg = frame.weightKg,
                    bodyFatPercent = m[MeasurementType.BODY_FAT]?.value,
                    musclePercent = m[MeasurementType.MUSCLE]?.value,
                    waterPercent = m[MeasurementType.WATER]?.value,
                    impedanceOhm = frame.impedanceOhm,
                    unit = frame.unit,
                    deviceName = deviceName,
                    sessionId = sessionId
                )
                scheduleCompletedAutoDismiss(sessionId)
                LogManager.i(TAG, "Live measurement saved successfully (id=$measurementId, user=$assignedUserId, weight=${frame.weightKg}kg, imp=${frame.impedanceOhm}Ω)")
            } catch (t: Throwable) {
                LogManager.e(TAG, "Exception during live measurement finalization", t)
                cooldownUntilMs = 0L
                if (sessionId != activeSessionId) return@launch
                _liveWeighingState.value = LiveWeighingState.Completed(
                    measurementId = 0,
                    userId = 0,
                    weightKg = frame.weightKg,
                    bodyFatPercent = null,
                    musclePercent = null,
                    waterPercent = null,
                    impedanceOhm = frame.impedanceOhm,
                    unit = frame.unit,
                    deviceName = deviceName,
                    sessionId = sessionId
                )
                scheduleCompletedAutoDismiss(sessionId)
            }
        }
    }

    private fun scheduleCompletedAutoDismiss(sessionId: Long) {
        autoDismissJob?.cancel()
        autoDismissJob = monitorScope.launch {
            delay(COMPLETED_DISPLAY_DURATION_MS)
            if (sessionId == activeSessionId && _liveWeighingState.value is LiveWeighingState.Completed) {
                LogManager.i(TAG, "Auto-dismissing completed weigh-in window after ${COMPLETED_DISPLAY_DURATION_MS}ms")
                resetState()
            }
        }
    }

    private suspend fun buildMeasurementValues(measurementData: ScaleMeasurement): List<MeasurementValue> {
        val types = measurementFacade.getAllMeasurementTypes().firstOrNull().orEmpty()
        val typesByIdentity = types.associateBy { it.identity }

        val values = mutableListOf<MeasurementValue>()
        val reported = measurementData.values.entries
            .sortedByDescending { it.key == MeasurementType.BMR }

        for ((key, raw) in reported) {
            val type = typesByIdentity[key.identity]
                ?: measurementFacade.resolveOrCreateMeasurementType(key)
                ?: continue

            values += when (key.inputType) {
                InputFieldType.FLOAT -> MeasurementValue(
                    measurementId = 0,
                    typeId = type.id,
                    floatValue = ConverterUtils.convertFloatValueUnit(
                        if (raw is UnitValue) raw.value else raw as Float,
                        key.wireUnit,
                        type.unit
                    )
                )
                InputFieldType.INT -> MeasurementValue(
                    measurementId = 0,
                    typeId = type.id,
                    intValue = if (raw is Bpm) raw.value else raw as Int
                )
                InputFieldType.TEXT -> MeasurementValue(
                    measurementId = 0,
                    typeId = type.id,
                    textValue = raw as String
                )
                InputFieldType.DATE, InputFieldType.TIME -> MeasurementValue(
                    measurementId = 0,
                    typeId = type.id,
                    dateValue = (raw as Date).time
                )
                InputFieldType.USER, InputFieldType.IMAGE -> continue
            }
        }
        return values
    }

    private suspend fun persistScaleMeasurement(
        measurementData: ScaleMeasurement,
        userId: Int
    ): Result<SavedBleMeasurementResult> {
        val newDbMeasurement = Measurement(
            userId = userId,
            timestamp = measurementData.dateTime?.time ?: System.currentTimeMillis()
        )
        val values = buildMeasurementValues(measurementData)
        if (values.isEmpty()) {
            return Result.failure(IllegalStateException("No valid values in measurement"))
        }

        return measurementFacade.saveMeasurementFromBleDevice(newDbMeasurement, values)
    }

    private fun hasBluetoothPermissions(): Boolean {
        val scan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        val connect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        return scan && connect
    }

    @SuppressLint("MissingPermission")
    private fun stopActiveScan() {
        val cb = activeScanCallback
        activeScanCallback = null
        if (!isMonitoring && cb == null) return
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val scanner = bluetoothManager?.adapter?.bluetoothLeScanner ?: bluetoothLeScanner
            if (cb != null) {
                scanner?.stopScan(cb)
            }
            LogManager.i(TAG, "Active BLE scan stopped")
        } catch (e: Exception) {
            LogManager.e(TAG, "Error stopping BLE scan", e)
        } finally {
            isMonitoring = false
        }
    }

    fun startMonitoring() {
        // Automatic passive listening is disabled; live weighing only starts when
        // explicitly triggered via startLiveWeighingSession().
    }

    fun stopMonitoring() {
        resetState()
    }

    fun resetState() {
        activeSessionId = ++sessionCounter
        sessionInitJob?.cancel()
        sessionInitJob = null
        stopActiveScan()
        cancelTimers()
        isFinalizing = false
        lastStabilizedFrame = null
        hasSeenLiveWeight = false
        stabilizedCandidateStartMs = 0L
        cooldownUntilMs = 0L
        _liveWeighingState.value = LiveWeighingState.Idle
    }

    /**
     * Starts a fresh weigh-in session for tests without requiring Android Bluetooth hardware.
     */
    internal fun startSessionForTesting(deviceName: String = "Mi Scale 2"): Long {
        resetState()
        val sessionId = ++sessionCounter
        activeSessionId = sessionId
        _liveWeighingState.value = LiveWeighingState.WaitingForScale(
            deviceName = deviceName,
            sessionId = sessionId
        )
        armWaitingTimeout(sessionId, deviceName)
        return sessionId
    }

    @SuppressLint("MissingPermission")
    fun startLiveWeighingSession() {
        if (!hasBluetoothPermissions()) {
            LogManager.d(TAG, "Bluetooth permissions not granted, cannot start live weighing session")
            return
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            LogManager.d(TAG, "Bluetooth adapter not enabled, cannot start live weighing session")
            return
        }

        // Completely reset all previous weigh-in state before starting the new session
        resetState()
        val sessionId = ++sessionCounter
        activeSessionId = sessionId

        currentSavedDevice?.let { cached ->
            _liveWeighingState.value = LiveWeighingState.WaitingForScale(
                deviceName = cached.name,
                sessionId = sessionId
            )
        }

        sessionInitJob = monitorScope.launch {
            val saved = settingsFacade.observeSavedDevice().first() ?: currentSavedDevice
            currentSavedDevice = saved
            if (saved == null) {
                LogManager.d(TAG, "No scale device saved, cannot start live weighing session")
                if (sessionId == activeSessionId) {
                    resetState()
                }
                return@launch
            }
            if (sessionId != activeSessionId) return@launch

            _liveWeighingState.value = LiveWeighingState.WaitingForScale(
                deviceName = saved.name,
                sessionId = sessionId
            )

            // Force fresh low-latency scan so duty cycle is at 100%
            restartLowLatencyScan(saved, sessionId)

            // 60-second timeout waiting for user to step on scale
            armWaitingTimeout(sessionId, saved.name)
        }
    }

    @SuppressLint("MissingPermission")
    private fun restartLowLatencyScan(saved: ScannedDeviceInfo, sessionId: Long) {
        stopActiveScan()

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: return
        if (!adapter.isEnabled) return
        val scanner = adapter.bluetoothLeScanner ?: return
        bluetoothLeScanner = scanner

        val filters = listOf(
            ScanFilter.Builder()
                .setDeviceAddress(saved.address)
                .build(),
            ScanFilter.Builder()
                .setServiceData(ParcelUuid(XiaomiScaleAdvParser.UUID_BODY_COMPOSITION_181B), null)
                .build(),
            ScanFilter.Builder()
                .setServiceData(ParcelUuid(XiaomiScaleAdvParser.UUID_WEIGHT_SCALE_181D), null)
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .build()

        val callback = createScanCallback(sessionId)
        activeScanCallback = callback

        try {
            scanner.startScan(filters, settings, callback)
            isMonitoring = true
            LogManager.i(TAG, "Active low-latency BLE scan started for ${saved.name} (${saved.address}), sessionId=$sessionId")
        } catch (e: Exception) {
            LogManager.e(TAG, "Error starting active low-latency BLE scan", e)
            activeScanCallback = null
            isMonitoring = false
        }
    }

    fun onMeasurementSavedFromBle(
        measurementId: Int,
        userId: Int,
        weightKg: Float,
        bodyFatPercent: Float? = null,
        musclePercent: Float? = null,
        waterPercent: Float? = null,
        impedanceOhm: Float? = null,
        deviceName: String? = null
    ) {
        // When an interactive BLE advertisement weigh-in session is active, ForegroundScaleMonitor
        // handles the entire measurement lifecycle (fluctuation -> stabilization -> BIA -> save)
        // directly from live advertisements. Do not let asynchronous GATT history imports hijack
        // or prematurely terminate the live weigh-in window.
        LogManager.d(TAG, "Ignoring external GATT measurement callback (id=$measurementId) in ForegroundScaleMonitor")
    }

    private fun cancelTimers() {
        inactivityJob?.cancel()
        inactivityJob = null
        impedanceTimeoutJob?.cancel()
        impedanceTimeoutJob = null
        stepOffJob?.cancel()
        stepOffJob = null
        autoDismissJob?.cancel()
        autoDismissJob = null
    }
}
