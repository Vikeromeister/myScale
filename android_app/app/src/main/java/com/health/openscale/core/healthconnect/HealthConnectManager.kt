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
package com.health.openscale.core.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.WeightRecord
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.core.utils.ConverterUtils
import com.health.openscale.core.utils.LogManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthConnectManager(
    private val context: Context,
    private val settingsFacade: SettingsFacade,
    private val databaseRepository: DatabaseRepository,
    private val customClient: HealthConnectClient? = null
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        settingsFacade: SettingsFacade,
        databaseRepository: DatabaseRepository
    ) : this(context, settingsFacade, databaseRepository, null)

    private val TAG = "HealthConnectManager"

    private var cachedSync: HealthConnectSync? = null

    val writePermissions: Set<String> = setOf(
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getWritePermission(BodyWaterMassRecord::class),
        HealthPermission.getWritePermission(BodyFatRecord::class),
        HealthPermission.getWritePermission(BoneMassRecord::class),
        HealthPermission.getWritePermission(BasalMetabolicRateRecord::class),
        HealthPermission.getWritePermission(LeanBodyMassRecord::class),
    )

    val readPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyWaterMassRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(BoneMassRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class),
    )

    fun getSdkStatus(): Int =
        if (customClient != null) HealthConnectClient.SDK_AVAILABLE else HealthConnectClient.getSdkStatus(context)

    fun isAvailable(): Boolean = getSdkStatus() == HealthConnectClient.SDK_AVAILABLE

    fun getClient(): HealthConnectClient? {
        if (customClient != null) return customClient
        if (!isAvailable()) return null
        return try {
            HealthConnectClient.getOrCreate(context)
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to get HealthConnectClient", e)
            null
        }
    }

    fun getSync(): HealthConnectSync? {
        val client = getClient() ?: return null
        if (cachedSync == null) {
            cachedSync = HealthConnectSync(client)
        }
        return cachedSync
    }

    suspend fun isExportEnabled(): Boolean {
        val dir = settingsFacade.healthConnectSyncDirection.first()
        return dir != "IMPORT"
    }

    suspend fun isImportEnabled(): Boolean {
        val dir = settingsFacade.healthConnectSyncDirection.first()
        return dir == "IMPORT" || dir == "BOTH"
    }

    suspend fun neededPermissions(): Set<String> =
        if (isImportEnabled()) writePermissions + readPermissions else writePermissions

    suspend fun hasAllPermissions(): Boolean {
        val client = getClient() ?: return false
        return try {
            val granted = client.permissionController.getGrantedPermissions()
            granted.containsAll(neededPermissions())
        } catch (e: Exception) {
            LogManager.e(TAG, "Error checking permissions", e)
            false
        }
    }

    suspend fun hasWritePermissions(): Boolean {
        val client = getClient() ?: return false
        return try {
            val granted = client.permissionController.getGrantedPermissions()
            granted.containsAll(writePermissions)
        } catch (e: Exception) {
            LogManager.e(TAG, "Error checking write permissions", e)
            false
        }
    }

    suspend fun hasReadPermissions(): Boolean {
        val client = getClient() ?: return false
        return try {
            val granted = client.permissionController.getGrantedPermissions()
            granted.containsAll(readPermissions)
        } catch (e: Exception) {
            LogManager.e(TAG, "Error checking read permissions", e)
            false
        }
    }

    suspend fun getGrantedPermissions(): Set<String> {
        val client = getClient() ?: return emptySet()
        return try {
            client.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            LogManager.e(TAG, "Error getting granted permissions", e)
            emptySet()
        }
    }

    suspend fun getEffectiveUserId(): Int {
        val configured = settingsFacade.healthConnectUserId.first()
        if (configured > 0) return configured
        val current = settingsFacade.currentUserId.first()
        if (current != null && current > 0) return current
        return try {
            val firstUser = databaseRepository.getAllUsers().first().firstOrNull()
            firstUser?.id ?: 1
        } catch (_: Throwable) {
            1
        }
    }

    suspend fun shouldSyncForUser(userId: Int): Boolean {
        val enabled = settingsFacade.healthConnectSyncEnabled.first()
        if (!enabled) return false
        val configured = settingsFacade.healthConnectUserId.first()
        if (configured <= 0) {
            val allUsers = runCatching { databaseRepository.getAllUsers().first() }.getOrDefault(emptyList())
            if (allUsers.size <= 1) return true
        }
        val effective = getEffectiveUserId()
        return userId == effective
    }

    fun toSyncMeasurement(mv: MeasurementWithValues): SyncMeasurement? {
        val m = mv.measurement
        val weightEntry = mv.values.firstOrNull { it.type.identity == MeasurementType.WEIGHT.identity }
        val rawWeight = weightEntry?.value?.floatValue ?: return null
        val weightKg = ConverterUtils.convertFloatValueUnit(rawWeight, weightEntry.type.unit, UnitType.KG)
        if (weightKg <= 0f) return null

        var fatPct: Float? = null
        var waterPct: Float? = null
        var boneKg: Float? = null
        var lbmKg: Float? = null
        var bmrKcal: Float? = null

        for (vwt in mv.values) {
            val rawVal = vwt.value.floatValue ?: vwt.value.intValue?.toFloat() ?: continue
            when (vwt.type.identity) {
                MeasurementType.BODY_FAT.identity -> {
                    fatPct = if (vwt.type.unit == UnitType.PERCENT) {
                        rawVal
                    } else {
                        val massKg = ConverterUtils.convertFloatValueUnit(rawVal, vwt.type.unit, UnitType.KG)
                        (massKg / weightKg) * 100f
                    }
                }
                MeasurementType.WATER.identity -> {
                    waterPct = if (vwt.type.unit == UnitType.PERCENT) {
                        rawVal
                    } else {
                        val massKg = ConverterUtils.convertFloatValueUnit(rawVal, vwt.type.unit, UnitType.KG)
                        (massKg / weightKg) * 100f
                    }
                }
                MeasurementType.BONE.identity -> {
                    boneKg = ConverterUtils.convertFloatValueUnit(rawVal, vwt.type.unit, UnitType.KG)
                }
                MeasurementType.LBM.identity -> {
                    lbmKg = ConverterUtils.convertFloatValueUnit(rawVal, vwt.type.unit, UnitType.KG)
                }
                MeasurementType.BMR.identity -> {
                    bmrKcal = rawVal
                }
            }
        }

        return SyncMeasurement(
            id = m.id,
            timestamp = m.timestamp,
            weightKg = weightKg,
            fatPct = fatPct,
            waterPct = waterPct,
            boneKg = boneKg,
            lbmKg = lbmKg,
            bmrKcal = bmrKcal
        )
    }

    suspend fun syncMeasurementInsert(measurementId: Int, userId: Int): HealthConnectResult<Unit>? {
        if (!shouldSyncForUser(userId) || !isExportEnabled()) return null
        val sync = getSync() ?: return null
        if (!hasWritePermissions()) return null

        val mv = databaseRepository.getMeasurementWithValuesById(measurementId).first() ?: return null
        val syncM = toSyncMeasurement(mv) ?: return null
        val result = sync.insert(syncM)
        if (result is HealthConnectResult.Success) {
            settingsFacade.setHealthConnectLastSyncTime(System.currentTimeMillis())
            LogManager.i(TAG, "Measurement $measurementId successfully synced to Health Connect")
        } else if (result is HealthConnectResult.Failure) {
            LogManager.w(TAG, "Auto-sync to Health Connect failed for $measurementId: ${result.message}")
        }
        return result
    }

    suspend fun syncMeasurementUpdate(measurementId: Int, userId: Int): HealthConnectResult<Unit>? {
        if (!shouldSyncForUser(userId) || !isExportEnabled()) return null
        val sync = getSync() ?: return null
        if (!hasWritePermissions()) return null

        val mv = databaseRepository.getMeasurementWithValuesById(measurementId).first() ?: return null
        val syncM = toSyncMeasurement(mv) ?: return null
        val result = sync.update(syncM)
        if (result is HealthConnectResult.Success) {
            settingsFacade.setHealthConnectLastSyncTime(System.currentTimeMillis())
            LogManager.i(TAG, "Measurement $measurementId update successfully synced to Health Connect")
        }
        return result
    }

    suspend fun syncMeasurementDelete(timestamp: Long, userId: Int): HealthConnectResult<Unit>? {
        if (!shouldSyncForUser(userId) || !isExportEnabled()) return null
        val sync = getSync() ?: return null
        if (!hasWritePermissions()) return null

        return sync.delete(timestamp)
    }

    suspend fun syncClear(userId: Int): HealthConnectResult<Unit>? {
        if (!shouldSyncForUser(userId) || !isExportEnabled()) return null
        val sync = getSync() ?: return null
        if (!hasWritePermissions()) return null

        return sync.clear()
    }

    suspend fun syncAll(userId: Int): HealthConnectResult<Int> {
        if (!isExportEnabled()) {
            return HealthConnectResult.Failure("Export is not enabled")
        }
        val sync = getSync() ?: return HealthConnectResult.Failure("Health Connect not available")
        if (!hasWritePermissions()) {
            return HealthConnectResult.Failure("Write permissions not granted")
        }

        val all = databaseRepository.getMeasurementsWithValuesForUser(userId).first()
        val syncMeasurements = all.mapNotNull { toSyncMeasurement(it) }
        val result = sync.fullSync(syncMeasurements)
        if (result is HealthConnectResult.Success) {
            settingsFacade.setHealthConnectLastSyncTime(System.currentTimeMillis())
        }
        return result
    }

    suspend fun syncInbound(userId: Int): HealthConnectResult<Int> {
        if (!isImportEnabled()) {
            return HealthConnectResult.Failure("Import is not enabled")
        }
        val sync = getSync() ?: return HealthConnectResult.Failure("Health Connect not available")
        if (!hasReadPermissions()) {
            return HealthConnectResult.Failure("Read permissions not granted")
        }

        val sinceMs = Instant.now().minus(730, ChronoUnit.DAYS).toEpochMilli()
        val readings = sync.readInboundReadings(context.packageName, sinceMs)
            .filter { it.weightKg.isFinite() && it.weightKg > 0f }

        if (readings.isEmpty()) {
            return HealthConnectResult.Success(0)
        }

        val weightType = databaseRepository.getMeasurementTypeByIdentity(MeasurementType.WEIGHT.identity)
        val fatType = databaseRepository.getMeasurementTypeByIdentity(MeasurementType.BODY_FAT.identity)
        val waterType = databaseRepository.getMeasurementTypeByIdentity(MeasurementType.WATER.identity)
        val boneType = databaseRepository.getMeasurementTypeByIdentity(MeasurementType.BONE.identity)
        val lbmType = databaseRepository.getMeasurementTypeByIdentity(MeasurementType.LBM.identity)

        val existing = databaseRepository.getMeasurementsWithValuesForUser(userId).first()
        val existingByTime = existing.associateBy { it.measurement.timestamp }

        var count = 0
        for (reading in readings) {
            val existingMv = existingByTime[reading.timeMs]
            if (existingMv != null) {
                val existingTypeIds = existingMv.values.map { it.value.typeId }.toSet()
                if (fatType != null && reading.fatPct != null && fatType.id !in existingTypeIds) {
                    databaseRepository.insertMeasurementValue(
                        MeasurementValue(measurementId = existingMv.measurement.id, typeId = fatType.id, floatValue = reading.fatPct)
                    )
                }
                if (waterType != null && reading.waterPct != null && waterType.id !in existingTypeIds) {
                    databaseRepository.insertMeasurementValue(
                        MeasurementValue(measurementId = existingMv.measurement.id, typeId = waterType.id, floatValue = reading.waterPct)
                    )
                }
                if (boneType != null && reading.boneKg != null && boneType.id !in existingTypeIds) {
                    val converted = ConverterUtils.convertFloatValueUnit(reading.boneKg, UnitType.KG, boneType.unit)
                    databaseRepository.insertMeasurementValue(
                        MeasurementValue(measurementId = existingMv.measurement.id, typeId = boneType.id, floatValue = converted)
                    )
                }
                if (lbmType != null && reading.leanKg != null && lbmType.id !in existingTypeIds) {
                    val converted = ConverterUtils.convertFloatValueUnit(reading.leanKg, UnitType.KG, lbmType.unit)
                    databaseRepository.insertMeasurementValue(
                        MeasurementValue(measurementId = existingMv.measurement.id, typeId = lbmType.id, floatValue = converted)
                    )
                }
                count++
            } else {
                val measurement = Measurement(id = 0, userId = userId, timestamp = reading.timeMs)
                val newId = databaseRepository.insertMeasurement(measurement).toInt()
                if (newId != -1) {
                    if (weightType != null) {
                        val converted = ConverterUtils.convertFloatValueUnit(reading.weightKg, UnitType.KG, weightType.unit)
                        databaseRepository.insertMeasurementValue(
                            MeasurementValue(measurementId = newId, typeId = weightType.id, floatValue = converted)
                        )
                    }
                    if (fatType != null && reading.fatPct != null) {
                        databaseRepository.insertMeasurementValue(
                            MeasurementValue(measurementId = newId, typeId = fatType.id, floatValue = reading.fatPct)
                        )
                    }
                    if (waterType != null && reading.waterPct != null) {
                        databaseRepository.insertMeasurementValue(
                            MeasurementValue(measurementId = newId, typeId = waterType.id, floatValue = reading.waterPct)
                        )
                    }
                    if (boneType != null && reading.boneKg != null) {
                        val converted = ConverterUtils.convertFloatValueUnit(reading.boneKg, UnitType.KG, boneType.unit)
                        databaseRepository.insertMeasurementValue(
                            MeasurementValue(measurementId = newId, typeId = boneType.id, floatValue = converted)
                        )
                    }
                    if (lbmType != null && reading.leanKg != null) {
                        val converted = ConverterUtils.convertFloatValueUnit(reading.leanKg, UnitType.KG, lbmType.unit)
                        databaseRepository.insertMeasurementValue(
                            MeasurementValue(measurementId = newId, typeId = lbmType.id, floatValue = converted)
                        )
                    }
                    databaseRepository.recalculateDerivedValuesForMeasurement(newId)
                    count++
                }
            }
        }

        settingsFacade.setHealthConnectLastSyncTime(System.currentTimeMillis())
        return HealthConnectResult.Success(count)
    }

    suspend fun syncNow(userId: Int): HealthConnectResult<String> {
        var exported = 0
        var imported = 0
        if (isExportEnabled()) {
            when (val res = syncAll(userId)) {
                is HealthConnectResult.Success -> exported = res.data
                is HealthConnectResult.Failure -> return res
            }
        }
        if (isImportEnabled()) {
            when (val res = syncInbound(userId)) {
                is HealthConnectResult.Success -> imported = res.data
                is HealthConnectResult.Failure -> return res
            }
        }
        settingsFacade.setHealthConnectLastSyncTime(System.currentTimeMillis())
        return HealthConnectResult.Success("Exported $exported, Imported $imported")
    }
}
