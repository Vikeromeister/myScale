/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
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
package com.health.openscale.core.usecase

import android.content.Context
import com.health.openscale.core.data.InputFieldType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.healthconnect.HealthConnectManager
import com.health.openscale.core.utils.ConverterUtils
import com.health.openscale.core.utils.LogManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Bundles sync trigger use cases for native Google Health Connect sync.
 */
@Singleton
class SyncUseCases @Inject constructor(
    private val healthConnectManager: HealthConnectManager
) {
    private val _syncAppUnreachable = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /**
     * Triggers an insert sync event for [measurement] to Health Connect.
     */
    suspend fun triggerSyncInsert(
        measurement: Measurement,
        values: List<MeasurementValue> = emptyList(),
        pkgName: String = ""
    ): Result<Unit> = runCatching {
        healthConnectManager.syncMeasurementInsert(measurement.id, measurement.userId)
    }

    /**
     * Triggers an update sync event for [measurement] to Health Connect.
     */
    suspend fun triggerSyncUpdate(
        measurement: Measurement,
        values: List<MeasurementValue> = emptyList(),
        pkgName: String = ""
    ): Result<Unit> = runCatching {
        healthConnectManager.syncMeasurementUpdate(measurement.id, measurement.userId)
    }

    /**
     * Triggers a delete sync event for the given [id] / [userId] + [date] to Health Connect.
     */
    suspend fun triggerSyncDelete(
        id: Int,
        userId: Int,
        date: Date,
        pkgName: String = ""
    ): Result<Unit> = runCatching {
        healthConnectManager.syncMeasurementDelete(date.time, userId)
    }

    /**
     * Triggers a clear sync event to wipe synced measurements of [userId] on Health Connect.
     */
    suspend fun triggerSyncClear(
        userId: Int,
        pkgName: String = ""
    ): Result<Unit> = runCatching {
        healthConnectManager.syncClear(userId)
    }

    /**
     * Coalesced changed wake-up for bulk operations (CSV import / backup restore).
     */
    suspend fun triggerSyncChanged(
        pkgName: String = ""
    ): Result<Unit> = runCatching {
        triggerSyncChangedAll()
    }

    /** Convenience: fire a full sync of all measurements to Health Connect. */
    suspend fun triggerSyncChangedAll(): Result<Unit> = runCatching {
        if (!healthConnectManager.isExportEnabled()) return@runCatching
        val targetUser = healthConnectManager.getEffectiveUserId()
        healthConnectManager.syncAll(targetUser)
    }

    val syncAppUnreachable: SharedFlow<String> = _syncAppUnreachable.asSharedFlow()

    fun openSyncApp(pkgName: String) {}
}

/**
 * Builds/parses the self-describing **generic value set** shared between the sync Intent
 * ([SyncUseCases]) and the ContentProvider ([com.health.openscale.core.database.DatabaseProvider]).
 *
 * Each value carries its type metadata (`identity` — the namespaced, cross-installation
 * identifier, `name`, `unit` as a UCUM code, `inputType`, `isDerived`); the numeric value is
 * in the **canonical base unit** of its dimension. HL7 FHIR Quantity / UCUM-inspired — the
 * sync app forwards all types incl. device and user ones without knowing openScale's internals.
 */
object GenericValueJson {

    fun build(values: List<MeasurementValue>, typesById: Map<Int, MeasurementType>): String {
        val arr = JSONArray()
        for (v in values) {
            val type = typesById[v.typeId] ?: continue
            if (type.inputType == InputFieldType.IMAGE) continue
            val obj = JSONObject()
            obj.put("identity", type.identity)
            obj.put("name", type.name ?: MeasurementType.identityColumnKey(type.identity))
            obj.put("unit", ucumCode(type.unit))
            obj.put("inputType", type.inputType.name)
            obj.put("isDerived", type.isDerived)
            when (type.inputType) {
                InputFieldType.FLOAT, InputFieldType.INT -> {
                    val raw = v.floatValue ?: v.intValue?.toFloat()
                    if (raw != null) {
                        obj.put("value", ConverterUtils.convertFloatValueUnit(raw, type.unit, canonicalUnit(type.unit)).toDouble())
                    }
                }
                InputFieldType.TEXT -> v.textValue?.let { obj.put("text", it) }
                InputFieldType.DATE, InputFieldType.TIME -> v.dateValue?.let { obj.put("text", it.toString()) }
                else -> {}
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    /**
     * Reverse of [build] (inbound): parse a generic value JSON into (type, value) pairs, the value
     * already in the user's unit and in the field the type's [MeasurementType.inputType] requires.
     *
     * Matching is by `identity` — the canonical, cross-installation identifier of API v3.
     * Entries without one (pre-v3 peers) are skipped; the MIN_API_VERSION gate on the sync
     * side makes such payloads a misconfiguration, not a supported input.
     *
     * The returned [MeasurementValue]s carry `measurementId = 0` — the caller owns that.
     *
     * Filling the right field here rather than at the call sites is deliberate: consumers read
     * only the field their input type designates, so a value in the wrong one reads as *absent*.
     * Entries that carry nothing this type can hold are skipped.
     */
    fun parse(
        json: String,
        types: List<MeasurementType>
    ): List<Pair<MeasurementType, MeasurementValue>> {
        val typesByIdentity = types.associateBy { it.identity }

        val out = mutableListOf<Pair<MeasurementType, MeasurementValue>>()
        runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val type = typesByIdentity[o.optString("identity", "")]
                if (type == null) continue
                val empty = MeasurementValue(measurementId = 0, typeId = type.id)
                val parsed: MeasurementValue? = when (type.inputType) {
                    InputFieldType.FLOAT, InputFieldType.INT -> {
                        if (!o.has("value")) null else {
                            val canonical = o.getDouble("value").toFloat()
                            val userValue = ConverterUtils.convertFloatValueUnit(canonical, canonicalUnit(type.unit), type.unit)
                            if (type.inputType == InputFieldType.FLOAT) empty.copy(floatValue = userValue)
                            else empty.copy(intValue = userValue.roundToInt())
                        }
                    }
                    InputFieldType.TEXT ->
                        if (!o.has("text")) null else empty.copy(textValue = o.optString("text"))
                    // build() sends dateValue verbatim as epoch millis in a decimal string.
                    InputFieldType.DATE, InputFieldType.TIME ->
                        o.optString("text", "").toLongOrNull()?.let { empty.copy(dateValue = it) }
                    InputFieldType.USER, InputFieldType.IMAGE -> null
                }
                if (parsed != null) out.add(type to parsed)
            }
        }
        return out
    }

    /** Canonical base unit per dimension (everything is converted to this before sending). */
    fun canonicalUnit(u: UnitType): UnitType = when (u) {
        UnitType.LB, UnitType.ST -> UnitType.KG
        UnitType.INCH -> UnitType.CM
        else -> u
    }

    /** UCUM code for the canonical unit of the given unit's dimension. */
    fun ucumCode(u: UnitType): String = when (u) {
        UnitType.KG, UnitType.LB, UnitType.ST -> "kg"
        UnitType.PERCENT -> "%"
        UnitType.CM, UnitType.INCH -> "cm"
        UnitType.KCAL -> "kcal"
        UnitType.BPM -> "/min"
        UnitType.OHM -> "Ohm"
        UnitType.NONE -> ""
    }
}
