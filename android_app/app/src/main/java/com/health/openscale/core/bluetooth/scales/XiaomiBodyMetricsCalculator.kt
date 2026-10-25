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

import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.BodyMiScaleLib
import com.health.openscale.core.bluetooth.libs.MiScaleLib
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.Kcal
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Percent

/**
 * Shared body composition calculator for Xiaomi smart scales.
 * Used by both [MiScaleHandler] and the foreground live weighing pipeline.
 */
object XiaomiBodyMetricsCalculator {

    /**
     * openScale's reverse-engineered Mi Fit algorithm (the original Xiaomi parity path).
     */
    fun applyXiaomiComposition(m: ScaleMeasurement, impedance: Float, user: ScaleUser) {
        val sex = if (user.gender == GenderType.MALE) 1 else 0
        val lib = MiScaleLib(sex, user.age, user.bodyHeight)
        val weight = m[MeasurementType.WEIGHT]?.value ?: 0f

        m[MeasurementType.WATER] = Percent(lib.getWater(weight, impedance))
        m[MeasurementType.VISCERAL_FAT] = lib.getVisceralFat(weight)
        m[MeasurementType.BODY_FAT] = Percent(lib.getBodyFat(weight, impedance))
        m[MeasurementType.MUSCLE] = Percent(lib.getMuscle(weight, impedance))
        m[MeasurementType.LBM] = Kg(lib.getLBM(weight, impedance))
        m[MeasurementType.BONE] = Kg(lib.getBoneMass(weight, impedance))
    }

    /**
     * Peer-reviewed scientific estimator ported from the bodymiscale Home Assistant integration.
     */
    fun applyBodyMiScaleComposition(m: ScaleMeasurement, impedance: Float, user: ScaleUser) {
        val lib = BodyMiScaleLib(user.gender, user.age, user.bodyHeight)
        val weight = m[MeasurementType.WEIGHT]?.value ?: 0f

        val lbmKg = lib.getLbm(weight, impedance)
        val fatPct = lib.getFat(weight, lbmKg)
        val boneKg = lib.getBoneMass(lbmKg)
        val muscleKg = lib.getMuscleMass(weight, fatPct, boneKg)

        m[MeasurementType.BODY_FAT] = Percent(fatPct)
        m[MeasurementType.WATER] = Percent(lib.getWater(fatPct))
        m[MeasurementType.MUSCLE] = Percent(if (weight > 0f) muscleKg / weight * 100f else 0f)
        m[MeasurementType.LBM] = Kg(lbmKg)
        m[MeasurementType.BONE] = Kg(boneKg)
        m[MeasurementType.PROTEIN] = Percent(lib.getProtein(weight, lbmKg))
        m[MeasurementType.BMR] = Kcal(lib.getBmr(weight))
        m[MeasurementType.VISCERAL_FAT] = lib.getVisceralFat(weight)
    }
}
