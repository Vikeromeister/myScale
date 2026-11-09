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
package com.health.openscale.core.bluetooth.scales

import android.bluetooth.le.ScanRecord
import android.os.ParcelUuid
import com.health.openscale.core.data.UnitType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class XiaomiAdvFrame(
    val weightKg: Float,
    val isStabilized: Boolean,
    val hasImpedance: Boolean,
    val impedanceOhm: Float?,
    val weightRemoved: Boolean,
    val date: Date?,
    val unit: UnitType,
    val isVersion2: Boolean,
    val isImpedanceMeasuring: Boolean = false,
    val scaleTimestampSeconds: Long? = null
)

object XiaomiScaleAdvParser {
    val UUID_BODY_COMPOSITION_181B: UUID = UUID.fromString("0000181b-0000-1000-8000-00805f9b34fb")
    val UUID_WEIGHT_SCALE_181D: UUID = UUID.fromString("0000181d-0000-1000-8000-00805f9b34fb")

    private val PARCEL_UUID_181B = ParcelUuid(UUID_BODY_COMPOSITION_181B)
    private val PARCEL_UUID_181D = ParcelUuid(UUID_WEIGHT_SCALE_181D)

    /**
     * Valid human bio-electrical impedance range in Ohms on Xiaomi Body Composition Scale 2.
     * While the scale is actively measuring impedance (or if measurement fails), bytes 9..10
     * carry 0xFFFE (65534) or 0xFFFF (65535) or 0, which must not be treated as a finished reading.
     */
    private const val MIN_VALID_IMPEDANCE_OHM = 1
    private const val MAX_VALID_IMPEDANCE_OHM = 3000

    fun parse(scanRecord: ScanRecord?): XiaomiAdvFrame? {
        if (scanRecord == null) return null

        // 1. Try parsed Service Data from ScanRecord
        scanRecord.getServiceData(PARCEL_UUID_181B)?.let { bytes ->
            parse181B(bytes)?.let { return it }
        }
        scanRecord.getServiceData(PARCEL_UUID_181D)?.let { bytes ->
            parse181D(bytes)?.let { return it }
        }

        // 2. Fallback to raw advertising bytes (handles devices/ROMs where Service Data is not exposed via Map)
        val rawBytes = scanRecord.bytes ?: return null
        return parseRawBytes(rawBytes)
    }

    fun parse181B(d: ByteArray): XiaomiAdvFrame? {
        if (d.size < 13) return null

        val c0 = d[0].toInt() and 0xFF
        val c1 = d[1].toInt() and 0xFF
        val isLbs = (c0 and 0x01) != 0 || (c1 and 0x01) != 0
        val isCatty = (c1 and 0x40) != 0
        val isStabilized = (c1 and 0x20) != 0
        val weightRemoved = (c1 and 0x80) != 0
        val hasImpBit = (c1 and 0x02) != 0

        val year = ((d[3].toInt() and 0xFF) shl 8) or (d[2].toInt() and 0xFF)
        val month = d[4].toInt() and 0xFF
        val day = d[5].toInt() and 0xFF
        val hour = d[6].toInt() and 0xFF
        val minute = d[7].toInt() and 0xFF
        val second = if (d.size > 8) d[8].toInt() and 0xFF else 0

        val date = parseDate(year, month, day, hour, minute, second)
        val scaleTimestampSeconds = parseRawScaleTimestampSeconds(year, month, day, hour, minute, second)

        val impRaw = ((d[10].toInt() and 0xFF) shl 8) or (d[9].toInt() and 0xFF)
        val hasImpedance = hasImpBit && impRaw in MIN_VALID_IMPEDANCE_OHM until MAX_VALID_IMPEDANCE_OHM
        val isImpedanceMeasuring = isStabilized && !weightRemoved && !hasImpedance &&
            (impRaw == 0xFFFE || !hasImpBit)

        val weightRaw = ((d[12].toInt() and 0xFF) shl 8) or (d[11].toInt() and 0xFF)
        val weightKg = when {
            isCatty -> (weightRaw / 100.0f) * 0.5f
            isLbs -> (weightRaw / 100.0f) * 0.45359237f
            else -> weightRaw / 200.0f
        }

        val unit = when {
            isLbs -> UnitType.LB
            else -> UnitType.KG
        }

        return XiaomiAdvFrame(
            weightKg = weightKg,
            isStabilized = isStabilized,
            hasImpedance = hasImpedance,
            impedanceOhm = if (hasImpedance) impRaw.toFloat() else null,
            weightRemoved = weightRemoved,
            date = date,
            unit = unit,
            isVersion2 = true,
            isImpedanceMeasuring = isImpedanceMeasuring,
            scaleTimestampSeconds = scaleTimestampSeconds
        )
    }

    fun parse181D(d: ByteArray): XiaomiAdvFrame? {
        if (d.size < 10) return null

        val c0 = d[0].toInt() and 0xFF
        val isLbs = (c0 and 0x01) != 0
        val isCatty = (c0 and 0x10) != 0
        val isStabilized = (c0 and 0x20) != 0
        val weightRemoved = (c0 and 0x80) != 0

        val weightRaw = ((d[2].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
        val weightKg = when {
            isCatty -> (weightRaw / 100.0f) * 0.5f
            isLbs -> (weightRaw / 100.0f) * 0.45359237f
            else -> weightRaw / 200.0f
        }

        val year = ((d[4].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF)
        val month = d[5].toInt() and 0xFF
        val day = d[6].toInt() and 0xFF
        val hour = d[7].toInt() and 0xFF
        val minute = d[8].toInt() and 0xFF
        val second = if (d.size > 9) d[9].toInt() and 0xFF else 0

        val date = parseDate(year, month, day, hour, minute, second)
        val scaleTimestampSeconds = parseRawScaleTimestampSeconds(year, month, day, hour, minute, second)

        val unit = when {
            isLbs -> UnitType.LB
            else -> UnitType.KG
        }

        return XiaomiAdvFrame(
            weightKg = weightKg,
            isStabilized = isStabilized,
            hasImpedance = false,
            impedanceOhm = null,
            weightRemoved = weightRemoved,
            date = date,
            unit = unit,
            isVersion2 = false,
            isImpedanceMeasuring = false,
            scaleTimestampSeconds = scaleTimestampSeconds
        )
    }

    fun parseRawBytes(raw: ByteArray): XiaomiAdvFrame? {
        var offset = 0
        while (offset < raw.size - 1) {
            val len = raw[offset].toInt() and 0xFF
            if (len == 0 || offset + 1 + len > raw.size) break
            val type = raw[offset + 1].toInt() and 0xFF
            if (type == 0x16 && len >= 3) { // Service Data - 16 bit UUID
                val uuid16 = ((raw[offset + 3].toInt() and 0xFF) shl 8) or (raw[offset + 2].toInt() and 0xFF)
                val payloadLen = len - 3
                val payloadStart = offset + 4
                if (uuid16 == 0x181B && payloadLen >= 13) {
                    val slice = raw.copyOfRange(payloadStart, payloadStart + 13)
                    parse181B(slice)?.let { return it }
                } else if (uuid16 == 0x181D && payloadLen >= 10) {
                    val slice = raw.copyOfRange(payloadStart, payloadStart + 10)
                    parse181D(slice)?.let { return it }
                }
            }
            offset += 1 + len
        }
        return null
    }

    private fun parseRawScaleTimestampSeconds(y: Int, m: Int, d: Int, h: Int, min: Int, s: Int): Long? =
        runCatching {
            if (y < 1970 || m !in 1..12 || d !in 1..31 || h !in 0..23 || min !in 0..59 || s !in 0..59) {
                return null
            }
            val sdf = SimpleDateFormat("yyyy/MM/dd/HH/mm/ss", Locale.US).apply {
                timeZone = TimeZone.getDefault()
                isLenient = false
            }
            sdf.parse("$y/$m/$d/$h/$min/$s")?.time?.let { it / 1000L }
        }.getOrNull()

    private fun parseDate(y: Int, m: Int, d: Int, h: Int, min: Int, s: Int): Date? =
        runCatching {
            val sdf = SimpleDateFormat("yyyy/MM/dd/HH/mm/ss", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
            val parsed = sdf.parse("$y/$m/$d/$h/$min/$s")
            val now = Calendar.getInstance()
            val max = (now.clone() as Calendar).apply { add(Calendar.YEAR, 2) }.time
            val minTime = (now.clone() as Calendar).apply { add(Calendar.YEAR, -5) }.time
            if (parsed != null && parsed.after(minTime) && parsed.before(max)) parsed else Date()
        }.getOrNull() ?: Date()
}
