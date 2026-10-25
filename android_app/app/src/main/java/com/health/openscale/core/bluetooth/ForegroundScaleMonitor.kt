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
     */
    data class WaitingForScale(
        val deviceName: String? = null
    ) : LiveWeighingState()

    /**
     * Scale is currently measuring.
     * @param weightKg Current weight reading in kg.
     * @param isStabilized True when the weight reading has stabilized on the scale display.
     * @param isMeasuringImpedance True when the scale is measuring electrical impedance.
     * @param unit Scale's native or preferred unit.
     * @param deviceName Scale name for display.
     */
    data class Measuring(
        val weightKg: Float,
        val isStabilized: Boolean,
        val isMeasuringImpedance: Boolean,
        val unit: UnitType = UnitType.KG,
        val deviceName: String? = null
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
        val deviceName: String? = null
    ) : LiveWeighingState()
}

/**
 * Monitors Xiaomi smart scale BLE advertisements in the foreground.
 *
 * When the app is open in the foreground, this monitor passively scans for the saved
 * scale's advertisements using [ScanSettings.SCAN_MODE_LOW_LATENCY].
 *
 * As soon as the user steps on the scale:
 * 1. Fluctuation phase: Advertised weight updates are forwarded in real time (<100ms lag)
 *    to [liveWeighingState] as [LiveWeighingState.Measuring], in exact sync with the scale display.
 * 2. Stabilization phase: Once weight stabilizes, [LiveWeighingState.Measuring.isStabilized] is set
 *    to true and impedance measurement progress is indicated.
 * 3. Impedance completion: When the scale broadcasts completed impedance, all body metrics
 *    are computed, saved to the database via [MeasurementFacade], and emitted as
 *    [LiveWeighingState.Completed].
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
        private const val INACTIVITY_TIMEOUT_MS = 12_000L
        private const val IMPEDANCE_TIMEOUT_MS = 10_000L
        private const val STEP_OFF_DEBOUNCE_MS = 3_000L
        private const val COOLDOWN_DURATION_MS = 7_000L
    }

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _liveWeighingState = MutableStateFlow<LiveWeighingState>(LiveWeighingState.Idle)
    val liveWeighingState: StateFlow<LiveWeighingState> = _liveWeighingState.asStateFlow()

    private var isMonitoring = false
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var currentSavedDevice: ScannedDeviceInfo? = null

    private var inactivityJob: Job? = null
    private var impedanceTimeoutJob: Job? = null
    private var stepOffJob: Job? = null
    private var lastStabilizedFrame: XiaomiAdvFrame? = null
    private var cooldownUntilMs: Long = 0L
    private var isFinalizing = false

    init {
        monitorScope.launch {
            settingsFacade.observeSavedDevice().collect { saved ->
                currentSavedDevice = saved
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
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
            handleAdvFrame(frame, saved.name)
        }

        override fun onScanFailed(errorCode: Int) {
            LogManager.w(TAG, "BLE scan failed with code: $errorCode")
            isMonitoring = false
        }
    }

    private fun handleAdvFrame(frame: XiaomiAdvFrame, deviceName: String) {
        // 0. If already completed and showing results, ignore incoming packets until cooldown/reset
        if (_liveWeighingState.value is LiveWeighingState.Completed) {
            return
        }

        // Branch 1: Completed impedance measurement arrived!
        // This is the final and most complete packet on Mi Scale 2.
        // Even if bit 7 (weightRemoved) is set or weight is low, this contains the full BIA metrics.
        if (frame.hasImpedance && frame.impedanceOhm != null && frame.impedanceOhm > 0) {
            stepOffJob?.cancel()
            stepOffJob = null
            finalizeMeasurement(frame, deviceName)
            return
        }

        // Branch 2: Weight removed flag is set (and no impedance was in this packet)
        if (frame.weightRemoved) {
            if (_liveWeighingState.value is LiveWeighingState.WaitingForScale) {
                // Ignore transient/cached load-removed frame while waiting for user to step on
                return
            }
            if (lastStabilizedFrame != null) {
                // User stepped off after weight stabilization -> finalize with the stabilized reading
                LogManager.i(TAG, "Weight removed after stabilization; finalizing measurement")
                finalizeMeasurement(lastStabilizedFrame!!, deviceName)
            } else {
                // User stepped off before stabilization, or transient load-removed signal during step-on.
                // Debounce for 3 seconds instead of instantly resetting to Idle!
                if (stepOffJob == null || !stepOffJob!!.isActive) {
                    stepOffJob = monitorScope.launch {
                        delay(STEP_OFF_DEBOUNCE_MS)
                        if (_liveWeighingState.value is LiveWeighingState.Measuring && lastStabilizedFrame == null) {
                            LogManager.d(TAG, "Step-off debounce expired without stabilization; resetting to Idle")
                            resetState()
                        }
                    }
                }
            }
            return
        }

        // Branch 3: Active weight on scale (user is standing on it, >= 5.0 kg)
        if (frame.weightKg >= 5.0f) {
            stepOffJob?.cancel()
            stepOffJob = null
            if (_liveWeighingState.value is LiveWeighingState.WaitingForScale) {
                inactivityJob?.cancel()
                inactivityJob = null
            }

            if (frame.isStabilized) {
                // Weight has stabilized!
                lastStabilizedFrame = frame
                inactivityJob?.cancel()
                inactivityJob = null

                if (frame.isVersion2) {
                    // Update UI to indicate weight is locked and scale is measuring BIA
                    _liveWeighingState.value = LiveWeighingState.Measuring(
                        weightKg = frame.weightKg,
                        isStabilized = true,
                        isMeasuringImpedance = true,
                        unit = frame.unit,
                        deviceName = deviceName
                    )

                    // Arm 10-second fallback timer in case user wears socks or scale fails to measure impedance
                    if (impedanceTimeoutJob == null || !impedanceTimeoutJob!!.isActive) {
                        impedanceTimeoutJob = monitorScope.launch {
                            delay(IMPEDANCE_TIMEOUT_MS)
                            if (_liveWeighingState.value !is LiveWeighingState.Completed) {
                                LogManager.i(TAG, "Impedance timeout reached; finalizing with stabilized weight only")
                                finalizeMeasurement(frame, deviceName)
                            }
                        }
                    }
                } else {
                    // Mi Scale 1 (v1) has no impedance hardware -> finalize immediately upon stabilization
                    finalizeMeasurement(frame, deviceName)
                }
            } else {
                // Weight is fluctuating (user stepping on, shifting weight)
                // Only update fluctuating state if we haven't already locked a stabilized reading
                if (lastStabilizedFrame == null) {
                    _liveWeighingState.value = LiveWeighingState.Measuring(
                        weightKg = frame.weightKg,
                        isStabilized = false,
                        isMeasuringImpedance = false,
                        unit = frame.unit,
                        deviceName = deviceName
                    )

                    // 12-second watchdog in case scale shuts off or user abandons weighing
                    inactivityJob?.cancel()
                    inactivityJob = monitorScope.launch {
                        delay(INACTIVITY_TIMEOUT_MS)
                        if (_liveWeighingState.value is LiveWeighingState.Measuring && lastStabilizedFrame == null) {
                            LogManager.d(TAG, "Inactivity timeout during fluctuating weight; resetting to Idle")
                            resetState()
                        }
                    }
                }
            }
            return
        }

        // Branch 4: Low/zero weight (< 5.0 kg) without weightRemoved flag (tare or step-off)
        if (_liveWeighingState.value is LiveWeighingState.Measuring) {
            if (lastStabilizedFrame != null) {
                // User stepped off after weight stabilized
                LogManager.i(TAG, "Weight dropped < 5kg after stabilization; finalizing measurement")
                finalizeMeasurement(lastStabilizedFrame!!, deviceName)
            } else {
                // Transient tare / stepping on scale (< 5kg)
                if (stepOffJob == null || !stepOffJob!!.isActive) {
                    stepOffJob = monitorScope.launch {
                        delay(STEP_OFF_DEBOUNCE_MS)
                        if (_liveWeighingState.value is LiveWeighingState.Measuring && lastStabilizedFrame == null) {
                            LogManager.d(TAG, "Tare/low weight debounce expired; resetting to Idle")
                            resetState()
                        }
                    }
                }
            }
        }
    }

    private fun finalizeMeasurement(frame: XiaomiAdvFrame, deviceName: String) {
        if (isFinalizing || _liveWeighingState.value is LiveWeighingState.Completed) return
        isFinalizing = true

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

                val m = ScaleMeasurement().apply {
                    dateTime = Date()
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

                _liveWeighingState.value = LiveWeighingState.Completed(
                    measurementId = measurementId,
                    userId = assignedUserId,
                    weightKg = frame.weightKg,
                    bodyFatPercent = m[MeasurementType.BODY_FAT]?.value,
                    musclePercent = m[MeasurementType.MUSCLE]?.value,
                    waterPercent = m[MeasurementType.WATER]?.value,
                    impedanceOhm = frame.impedanceOhm,
                    unit = frame.unit,
                    deviceName = deviceName
                )
                LogManager.i(TAG, "Live measurement saved successfully (id=$measurementId, user=$assignedUserId, weight=${frame.weightKg}kg)")
            } catch (t: Throwable) {
                LogManager.e(TAG, "Exception during live measurement finalization", t)
                cooldownUntilMs = 0L
                _liveWeighingState.value = LiveWeighingState.Completed(
                    measurementId = 0,
                    userId = 0,
                    weightKg = frame.weightKg,
                    bodyFatPercent = null,
                    musclePercent = null,
                    waterPercent = null,
                    impedanceOhm = frame.impedanceOhm,
                    unit = frame.unit,
                    deviceName = deviceName
                )
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
        if (!isMonitoring) return
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
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
        stopActiveScan()
        cancelTimers()
        isFinalizing = false
        if (_liveWeighingState.value !is LiveWeighingState.Completed) {
            _liveWeighingState.value = LiveWeighingState.Idle
        }
    }

    fun resetState() {
        stopActiveScan()
        cancelTimers()
        isFinalizing = false
        lastStabilizedFrame = null
        _liveWeighingState.value = LiveWeighingState.Idle
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

        monitorScope.launch {
            val saved = currentSavedDevice ?: settingsFacade.observeSavedDevice().first()
            currentSavedDevice = saved
            if (saved == null) {
                LogManager.d(TAG, "No scale device saved, cannot start live weighing session")
                return@launch
            }

            cancelTimers()
            isFinalizing = false
            lastStabilizedFrame = null
            cooldownUntilMs = 0L

            _liveWeighingState.value = LiveWeighingState.WaitingForScale(deviceName = saved.name)

            // Force fresh low-latency scan so duty cycle is at 100%
            restartLowLatencyScan(saved)

            // 45-second timeout waiting for user to step on scale
            inactivityJob = monitorScope.launch {
                delay(45_000L)
                if (_liveWeighingState.value is LiveWeighingState.WaitingForScale) {
                    LogManager.d(TAG, "Live weigh-in waiting session timed out (45s)")
                    resetState()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun restartLowLatencyScan(saved: ScannedDeviceInfo) {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: return
        if (!adapter.isEnabled) return
        val scanner = adapter.bluetoothLeScanner ?: return
        bluetoothLeScanner = scanner

        try {
            if (isMonitoring) {
                scanner.stopScan(scanCallback)
            }
        } catch (e: Exception) {
            LogManager.w(TAG, "Error stopping scan before restart: ${e.message}")
        }

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
            .setReportDelay(0)
            .build()

        try {
            scanner.startScan(filters, settings, scanCallback)
            isMonitoring = true
            LogManager.i(TAG, "Active low-latency BLE scan started for ${saved.name} (${saved.address})")
        } catch (e: Exception) {
            LogManager.e(TAG, "Error starting active low-latency BLE scan", e)
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
        if (_liveWeighingState.value is LiveWeighingState.WaitingForScale ||
            _liveWeighingState.value is LiveWeighingState.Measuring
        ) {
            stopActiveScan()
            cancelTimers()
            isFinalizing = false
            _liveWeighingState.value = LiveWeighingState.Completed(
                measurementId = measurementId,
                userId = userId,
                weightKg = weightKg,
                bodyFatPercent = bodyFatPercent,
                musclePercent = musclePercent,
                waterPercent = waterPercent,
                impedanceOhm = impedanceOhm,
                unit = UnitType.KG,
                deviceName = deviceName
            )
            LogManager.i(TAG, "Live weighing state transitioned to Completed via GATT saved measurement (id=$measurementId)")
        }
    }

    private fun cancelTimers() {
        inactivityJob?.cancel()
        inactivityJob = null
        impedanceTimeoutJob?.cancel()
        impedanceTimeoutJob = null
        stepOffJob?.cancel()
        stepOffJob = null
    }
}
