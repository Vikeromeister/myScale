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

import androidx.annotation.VisibleForTesting
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import com.health.openscale.core.utils.LogManager
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import kotlin.math.abs
import kotlin.reflect.KClass

/**
 * Clean data model representing one measurement payload for Health Connect sync.
 */
data class SyncMeasurement(
    val id: Int,
    val timestamp: Long,
    val weightKg: Float,
    val fatPct: Float? = null,
    val waterPct: Float? = null,
    val boneKg: Float? = null,
    val lbmKg: Float? = null,
    val bmrKcal: Float? = null
)

/**
 * Inbound reading read from Health Connect foreign records.
 */
data class InboundReading(
    val timeMs: Long,
    val weightKg: Float,
    val fatPct: Float? = null,
    val waterPct: Float? = null,
    val boneKg: Float? = null,
    val leanKg: Float? = null
)

sealed interface HealthConnectResult<out T> {
    data class Success<T>(val data: T) : HealthConnectResult<T>
    data class Failure(val message: String, val throwable: Throwable? = null) : HealthConnectResult<Nothing>
}

class HealthConnectSync(private val healthConnectClient: HealthConnectClient) {

    private val TAG = "HealthConnectSync"

    /**
     * Checks if [measurement] contains any value Health Connect would reject.
     */
    @VisibleForTesting
    internal fun rejectionReason(measurement: SyncMeasurement, now: Instant = Instant.now()): String? {
        val instant = Instant.ofEpochMilli(measurement.timestamp)
        if (instant.isAfter(now)) return "date $instant is in the future"

        if (measurement.weightKg <= 0f) return "weight is ${measurement.weightKg}"

        outOfRange("weight", measurement.weightKg, MAX_WEIGHT_KG)?.let { return it }
        measurement.fatPct?.let { outOfRange("body fat", it, MAX_PERCENT)?.let { r -> return r } }
        measurement.waterPct?.let { outOfRange("water", it, MAX_PERCENT)?.let { r -> return r } }
        measurement.lbmKg?.let { if (it > 0f) outOfRange("lean body mass", it, MAX_WEIGHT_KG)?.let { r -> return r } }
        measurement.boneKg?.let { if (it > 0f) outOfRange("bone mass", it, MAX_WEIGHT_KG)?.let { r -> return r } }
        measurement.bmrKcal?.let { if (it > 0f) outOfRange("BMR", it, MAX_BMR_KCAL_PER_DAY)?.let { r -> return r } }

        return null
    }

    private fun outOfRange(name: String, value: Float, max: Float): String? =
        if (!value.isFinite() || value < 0f || value > max) "$name is $value" else null

    @VisibleForTesting
    internal fun buildRecords(measurement: SyncMeasurement): List<Record> {
        val records = mutableListOf<Record>()
        val instant = Instant.ofEpochMilli(measurement.timestamp)
        val zoneOffset = ZoneId.systemDefault().rules.getOffset(instant)

        // 1. Weight (mandatory anchor)
        records.add(
            WeightRecord(
                time = instant,
                zoneOffset = zoneOffset,
                weight = Mass.kilograms(measurement.weightKg.toDouble()),
                metadata = Metadata.manualEntry(
                    clientRecordId = "${measurement.id}_weight",
                    clientRecordVersion = Instant.now().toEpochMilli()
                )
            )
        )

        // 2. Body Water (stored in kg in Health Connect)
        val waterPct = measurement.waterPct
        if (waterPct != null && waterPct > 0f) {
            val waterKg = measurement.weightKg * (waterPct / 100f)
            records.add(
                BodyWaterMassRecord(
                    time = instant,
                    zoneOffset = zoneOffset,
                    mass = Mass.kilograms(waterKg.toDouble()),
                    metadata = Metadata.manualEntry(
                        clientRecordId = "${measurement.id}_water",
                        clientRecordVersion = Instant.now().toEpochMilli()
                    )
                )
            )
        }

        // 3. Body Fat (%)
        val fatPct = measurement.fatPct
        if (fatPct != null && fatPct > 0f) {
            records.add(
                BodyFatRecord(
                    time = instant,
                    zoneOffset = zoneOffset,
                    percentage = Percentage(fatPct.toDouble()),
                    metadata = Metadata.manualEntry(
                        clientRecordId = "${measurement.id}_fat",
                        clientRecordVersion = Instant.now().toEpochMilli()
                    )
                )
            )
        }

        // 4. Lean Body Mass (kg)
        val lbmKg = measurement.lbmKg
        if (lbmKg != null && lbmKg > 0f) {
            records.add(
                LeanBodyMassRecord(
                    time = instant,
                    zoneOffset = zoneOffset,
                    mass = Mass.kilograms(lbmKg.toDouble()),
                    metadata = Metadata.manualEntry(
                        clientRecordId = "${measurement.id}_lbm",
                        clientRecordVersion = Instant.now().toEpochMilli()
                    )
                )
            )
        }

        // 5. Bone Mass (kg)
        val boneKg = measurement.boneKg
        if (boneKg != null && boneKg > 0f) {
            records.add(
                BoneMassRecord(
                    time = instant,
                    zoneOffset = zoneOffset,
                    mass = Mass.kilograms(boneKg.toDouble()),
                    metadata = Metadata.manualEntry(
                        clientRecordId = "${measurement.id}_bone",
                        clientRecordVersion = Instant.now().toEpochMilli()
                    )
                )
            )
        }

        // 6. Basal Metabolic Rate (BMR in Watts: kcal/day * 4184 / 86400)
        val bmrKcal = measurement.bmrKcal
        if (bmrKcal != null && bmrKcal > 0f) {
            val bmrWatts = bmrKcal.toDouble() * (4184.0 / 86400.0)
            records.add(
                BasalMetabolicRateRecord(
                    time = instant,
                    zoneOffset = zoneOffset,
                    basalMetabolicRate = Power.watts(bmrWatts),
                    metadata = Metadata.manualEntry(
                        clientRecordId = "${measurement.id}_bmr",
                        clientRecordVersion = Instant.now().toEpochMilli()
                    )
                )
            )
        }

        return records
    }

    suspend fun insert(measurement: SyncMeasurement): HealthConnectResult<Unit> {
        val reason = rejectionReason(measurement)
        if (reason != null) {
            LogManager.w(TAG, "Skipping measurement id=${measurement.id} ($reason)")
            return HealthConnectResult.Failure(reason)
        }
        return try {
            healthConnectClient.insertRecords(buildRecords(measurement))
            HealthConnectResult.Success(Unit)
        } catch (e: Exception) {
            LogManager.e(TAG, "Error inserting measurement ${measurement.id} into Health Connect", e)
            HealthConnectResult.Failure(e.message ?: "Unknown error", e)
        }
    }

    suspend fun fullSync(measurements: List<SyncMeasurement>): HealthConnectResult<Int> {
        val valid = mutableListOf<SyncMeasurement>()
        for (m in measurements) {
            val reason = rejectionReason(m)
            if (reason == null) {
                valid.add(m)
            } else {
                LogManager.w(TAG, "Full sync skipped id=${m.id}: $reason")
            }
        }
        if (valid.isEmpty()) return HealthConnectResult.Success(0)

        val records = valid.flatMap { buildRecords(it) }
        return try {
            // Write in batches of up to 500 records to respect platform transaction limits
            records.chunked(500).forEach { batch ->
                healthConnectClient.insertRecords(batch)
            }
            HealthConnectResult.Success(valid.size)
        } catch (e: Exception) {
            LogManager.e(TAG, "Error executing full sync to Health Connect", e)
            HealthConnectResult.Failure(e.message ?: "Sync error", e)
        }
    }

    suspend fun update(measurement: SyncMeasurement): HealthConnectResult<Unit> = insert(measurement)

    suspend fun delete(timestamp: Long): HealthConnectResult<Unit> {
        val instant = Instant.ofEpochMilli(timestamp)
        val timeRange = TimeRangeFilter.between(instant.minusSeconds(1), instant.plusSeconds(1))
        return deleteRange(timeRange)
    }

    suspend fun delete(date: Date): HealthConnectResult<Unit> = delete(date.time)

    suspend fun clear(): HealthConnectResult<Unit> {
        val now = Instant.now()
        val start = now.minus(3650, ChronoUnit.DAYS)
        val end = now.plus(3650, ChronoUnit.DAYS)
        return deleteRange(TimeRangeFilter.between(start, end))
    }

    private suspend fun deleteRange(timeRange: TimeRangeFilter): HealthConnectResult<Unit> {
        return try {
            listOf(
                WeightRecord::class,
                BodyFatRecord::class,
                BodyWaterMassRecord::class,
                LeanBodyMassRecord::class,
                BasalMetabolicRateRecord::class,
                BoneMassRecord::class
            ).forEach { healthConnectClient.deleteRecords(it, timeRange) }
            HealthConnectResult.Success(Unit)
        } catch (e: Exception) {
            LogManager.e(TAG, "Error deleting records from Health Connect", e)
            HealthConnectResult.Failure(e.message ?: "Delete error", e)
        }
    }

    /**
     * Reads records written by foreign applications since [sinceMillis], excluding own writes.
     */
    suspend fun readInboundReadings(ownPackage: String, sinceMillis: Long): List<InboundReading> {
        val range = TimeRangeFilter.after(Instant.ofEpochMilli(sinceMillis))
        fun foreign(record: Record) = record.metadata.dataOrigin.packageName != ownPackage

        suspend fun <T : Record> readForeign(type: KClass<T>, sample: (T) -> Pair<Long, Float>): List<Pair<Long, Float>> =
            runCatching {
                healthConnectClient.readRecords(ReadRecordsRequest(type, range))
                    .records.filter(::foreign).map(sample)
            }.getOrDefault(emptyList())

        return groupInboundReadings(
            weights = readForeign(WeightRecord::class) {
                it.time.toEpochMilli() to it.weight.inKilograms.toFloat()
            },
            fatsPct = readForeign(BodyFatRecord::class) {
                it.time.toEpochMilli() to it.percentage.value.toFloat()
            },
            watersKg = readForeign(BodyWaterMassRecord::class) {
                it.time.toEpochMilli() to it.mass.inKilograms.toFloat()
            },
            bonesKg = readForeign(BoneMassRecord::class) {
                it.time.toEpochMilli() to it.mass.inKilograms.toFloat()
            },
            leansKg = readForeign(LeanBodyMassRecord::class) {
                it.time.toEpochMilli() to it.mass.inKilograms.toFloat()
            }
        )
    }

    @VisibleForTesting
    internal fun groupInboundReadings(
        weights: List<Pair<Long, Float>>,
        fatsPct: List<Pair<Long, Float>> = emptyList(),
        watersKg: List<Pair<Long, Float>> = emptyList(),
        bonesKg: List<Pair<Long, Float>> = emptyList(),
        leansKg: List<Pair<Long, Float>> = emptyList()
    ): List<InboundReading> {
        if (weights.isEmpty()) return emptyList()
        val weightTimes = weights.map { it.first }

        fun near(candidates: List<Pair<Long, Float>>): Map<Long, Float> {
            val best = HashMap<Long, Pair<Long, Float>>()
            for ((time, value) in candidates) {
                val anchor = weightTimes.minByOrNull { abs(it - time) } ?: continue
                val distance = abs(anchor - time)
                if (distance > INBOUND_GROUPING_TOLERANCE_MS) continue
                val held = best[anchor]
                if (held == null || distance < held.first) best[anchor] = distance to value
            }
            return best.mapValues { it.value.second }
        }

        val fatAt = near(fatsPct)
        val waterAt = near(watersKg)
        val boneAt = near(bonesKg)
        val leanAt = near(leansKg)

        return weights.map { (t, kg) ->
            InboundReading(
                timeMs = t,
                weightKg = kg,
                fatPct = fatAt[t],
                waterPct = waterAt[t]?.let { if (kg > 0f) it / kg * 100f else null },
                boneKg = boneAt[t],
                leanKg = leanAt[t]
            )
        }
    }

    companion object {
        private const val INBOUND_GROUPING_TOLERANCE_MS = 2_000L
        private const val MAX_WEIGHT_KG = 1_000f
        private const val MAX_PERCENT = 100f
        private const val MAX_BMR_KCAL_PER_DAY = 10_000f
    }
}
