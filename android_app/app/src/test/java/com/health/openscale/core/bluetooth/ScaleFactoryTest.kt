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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.health.openscale.core.bluetooth.scales.MiScaleHandler
import com.health.openscale.core.bluetooth.scales.ScaleDeviceHandler
import com.health.openscale.core.service.ScannedDeviceInfo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Registry-level tests for [ScaleFactory]'s handler list for Xiaomi Body Composition Scale 2.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScaleFactoryTest {

    private fun winner(device: ScannedDeviceInfo) = ScaleCatalog.winner(device)

    private fun device(name: String, vararg services: UUID) = ScaleCatalog.device(name, *services)

    private fun assertClaimedBy(device: ScannedDeviceInfo, expected: Class<out ScaleDeviceHandler>) {
        val winner = winner(device)
        assertThat(winner).isNotNull()
        assertThat(winner!!.javaClass.simpleName).isEqualTo(expected.simpleName)
    }

    @Test
    fun `only MiScaleHandler is registered in ScaleFactory`() {
        val handlers = ScaleFactory.createHandlers()
        assertThat(handlers).hasSize(1)
        assertThat(handlers[0]).isInstanceOf(MiScaleHandler::class.java)
    }

    @Test
    fun `catalog fixtures resolve to their own handler`() {
        for (fixture in ScaleCatalog.fixtures) {
            val winner = winner(fixture.device)

            assertWithMessage("device '${fixture.device.name.ifEmpty { "<nameless>" }}'")
                .that(winner?.javaClass?.simpleName)
                .isEqualTo(fixture.handler.simpleName)
        }
    }

    @Test
    fun `Xiaomi Body Composition Scale 2 advertisements are claimed`() {
        assertClaimedBy(device("MIBFS"), MiScaleHandler::class.java)
        assertClaimedBy(device("MIBCS"), MiScaleHandler::class.java)
        assertClaimedBy(device("MI SCALE2"), MiScaleHandler::class.java)
        assertClaimedBy(device("AnyName", MiScaleHandler.SERVICE_MI_CFG), MiScaleHandler::class.java)
    }

    @Test
    fun `other scales and v1 scales are not claimed`() {
        assertThat(winner(device("MI_SCALE"))).isNull()
        assertThat(winner(device("BEURER BF700"))).isNull()
        assertThat(winner(device("SANITAS SBF70"))).isNull()
        assertThat(winner(device("QN-Scale"))).isNull()
        assertThat(winner(device("YUNMAI-ISSE-1234"))).isNull()
    }
}
