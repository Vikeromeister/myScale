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

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.data.UnitType
import org.junit.Test

class XiaomiScaleAdvParserTest {

    @Test
    fun `parse181B parses fluctuating weight correctly`() {
        // Year 2026 (0x07EA -> low=0xEA, high=0x07), month 10, day 11, hour 10, min 30, sec 15
        // Weight: 72.50 kg = 7250 * 2 = 14500 (0x38A4 -> low=0xA4, high=0x38)
        // c0=0x02 (kg), c1=0x00 (not stabilized, no impedance)
        val payload = byteArrayOf(
            0x02, 0x00,
            0xEA.toByte(), 0x07, 10, 11, 10, 30, 15,
            0x00, 0x00, // imp = 0
            0xA4.toByte(), 0x38  // weight = 14500 -> 72.5 kg
        )

        val frame = XiaomiScaleAdvParser.parse181B(payload)
        assertThat(frame).isNotNull()
        assertThat(frame!!.isVersion2).isTrue()
        assertThat(frame.isStabilized).isFalse()
        assertThat(frame.hasImpedance).isFalse()
        assertThat(frame.impedanceOhm).isNull()
        assertThat(frame.weightRemoved).isFalse()
        assertThat(frame.weightKg).isWithin(0.01f).of(72.5f)
        assertThat(frame.unit).isEqualTo(UnitType.KG)
    }

    @Test
    fun `parse181B parses stabilized weight without impedance`() {
        // c0=0x02, c1=0x20 (stabilized, no impedance yet)
        // Weight: 80.00 kg = 16000 (0x3E80 -> low=0x80, high=0x3E)
        val payload = byteArrayOf(
            0x02, 0x20,
            0xEA.toByte(), 0x07, 10, 11, 10, 30, 15,
            0x00, 0x00,
            0x80.toByte(), 0x3E
        )

        val frame = XiaomiScaleAdvParser.parse181B(payload)
        assertThat(frame).isNotNull()
        assertThat(frame!!.isStabilized).isTrue()
        assertThat(frame.hasImpedance).isFalse()
        assertThat(frame.impedanceOhm).isNull()
        assertThat(frame.weightKg).isWithin(0.01f).of(80.0f)
    }

    @Test
    fun `parse181B parses stabilized weight with impedance`() {
        // c0=0x02, c1=0x22 (stabilized=0x20, hasImp=0x02)
        // Impedance: 520 Ohm (0x0208 -> low=0x08, high=0x02)
        // Weight: 65.40 kg = 13080 (0x3318 -> low=0x18, high=0x33)
        val payload = byteArrayOf(
            0x02, 0x22,
            0xEA.toByte(), 0x07, 10, 11, 10, 30, 15,
            0x08, 0x02, // 520 Ohm
            0x18, 0x33  // 65.4 kg
        )

        val frame = XiaomiScaleAdvParser.parse181B(payload)
        assertThat(frame).isNotNull()
        assertThat(frame!!.isStabilized).isTrue()
        assertThat(frame.hasImpedance).isTrue()
        assertThat(frame.impedanceOhm).isWithin(0.1f).of(520.0f)
        assertThat(frame.weightKg).isWithin(0.01f).of(65.4f)
    }

    @Test
    fun `parse181B parses weight in lbs correctly`() {
        // c0=0x01 (lbs), c1=0x20 (stabilized)
        // Weight in lbs: 150.0 lbs = 15000 (0x3A98 -> low=0x98, high=0x3A)
        // 150 lbs in kg = 150 * 0.45359237 = 68.0388 kg
        val payload = byteArrayOf(
            0x01, 0x20,
            0xEA.toByte(), 0x07, 10, 11, 10, 30, 15,
            0x00, 0x00,
            0x98.toByte(), 0x3A
        )

        val frame = XiaomiScaleAdvParser.parse181B(payload)
        assertThat(frame).isNotNull()
        assertThat(frame!!.unit).isEqualTo(UnitType.LB)
        assertThat(frame.weightKg).isWithin(0.05f).of(68.04f)
    }

    @Test
    fun `parse181D parses Mi Scale 1 weight correctly`() {
        // c0=0x20 (stabilized)
        // Weight: 70.0 kg = 14000 (0x36B0 -> low=0xB0, high=0x36)
        // year: 2026 (0x07EA), month 10, day 11, hour 10, min 30, sec 0
        val payload = byteArrayOf(
            0x20,
            0xB0.toByte(), 0x36,
            0xEA.toByte(), 0x07, 10, 11, 10, 30, 0
        )

        val frame = XiaomiScaleAdvParser.parse181D(payload)
        assertThat(frame).isNotNull()
        assertThat(frame!!.isVersion2).isFalse()
        assertThat(frame.isStabilized).isTrue()
        assertThat(frame.hasImpedance).isFalse()
        assertThat(frame.weightKg).isWithin(0.01f).of(70.0f)
    }

    @Test
    fun `parseRawBytes finds 181B payload inside raw BLE advertising packet`() {
        // AD structure 1: Flags (3 bytes: len 2, type 0x01, val 0x06)
        // AD structure 2: Service Data 16-bit UUID 0x181B (len 16, type 0x16, uuid 0x1B, 0x18, + 13 bytes)
        val raw = byteArrayOf(
            0x02, 0x01, 0x06,
            0x10, 0x16, 0x1B, 0x18,
            0x02, 0x22,
            0xEA.toByte(), 0x07, 10, 11, 10, 30, 15,
            0x08, 0x02, // 520 Ohm
            0x18, 0x33  // 65.4 kg
        )

        val frame = XiaomiScaleAdvParser.parseRawBytes(raw)
        assertThat(frame).isNotNull()
        assertThat(frame!!.weightKg).isWithin(0.01f).of(65.4f)
        assertThat(frame.hasImpedance).isTrue()
        assertThat(frame.impedanceOhm).isEqualTo(520.0f)
    }

    @Test
    fun `parse181B parses stabilized frame with both bit 7 and impedance set`() {
        // c0=0x02, c1=0xA6 (bit 7 = 0x80, bit 5 = 0x20, bit 2 = 0x04, bit 1 = 0x02)
        // This is commonly broadcast by Xiaomi Body Composition Scale 2 upon completion.
        val payload = byteArrayOf(
            0x02, 0xA6.toByte(),
            0xEA.toByte(), 0x07, 10, 11, 10, 30, 15,
            0x08, 0x02, // 520 Ohm
            0x18, 0x33  // 65.4 kg
        )

        val frame = XiaomiScaleAdvParser.parse181B(payload)
        assertThat(frame).isNotNull()
        assertThat(frame!!.isStabilized).isTrue()
        assertThat(frame.hasImpedance).isTrue()
        assertThat(frame.weightRemoved).isTrue()
        assertThat(frame.impedanceOhm).isWithin(0.1f).of(520.0f)
        assertThat(frame.weightKg).isWithin(0.01f).of(65.4f)
    }
}
