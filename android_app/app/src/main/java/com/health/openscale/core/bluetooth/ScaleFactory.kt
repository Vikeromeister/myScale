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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.health.openscale.core.bluetooth.scales.BroadcastScaleAdapter
import com.health.openscale.core.bluetooth.scales.DebugGattHandler
import com.health.openscale.core.bluetooth.scales.DeviceSupport
import com.health.openscale.core.bluetooth.scales.GattScaleAdapter
import com.health.openscale.core.bluetooth.scales.LinkMode
import com.health.openscale.core.bluetooth.scales.MiScaleHandler
import com.health.openscale.core.bluetooth.scales.ScaleDeviceHandler
import com.health.openscale.core.bluetooth.scales.SppScaleAdapter
import com.health.openscale.core.bluetooth.scales.TuningProfile
import com.health.openscale.core.facade.MeasurementFacade
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.facade.UserFacade
import com.health.openscale.core.utils.LogManager
import com.health.openscale.core.service.ScannedDeviceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

/**
 * Factory class responsible for creating appropriate [ScaleCommunicator] instances
 * for different Bluetooth scale devices.
 */
@Singleton
class ScaleFactory @Inject constructor(
    @param:ApplicationContext private val applicationContext: Context,
    private val settingsFacade: SettingsFacade,
    private val measurementFacade: MeasurementFacade,
    private val userFacade: UserFacade,
) {
    private val TAG = "ScaleHandlerFactory"

    private val modernKotlinHandlers: List<ScaleDeviceHandler> = createHandlers()

    companion object {
        /**
         * Builds the list of modern Kotlin-based device handlers.
         *
         * Order matters: [createCommunicator] returns the FIRST handler whose [ScaleDeviceHandler.supportFor]
         * is non-null. TaylorBIAHandler, FitTrackDaraHandler, RelaxmedicHandler, RobiS9Handler and
         * DrTrustSSW532Handler must stay ahead of MGBHandler — all live on service 0xFFB0, which
         * MGBHandler matches on its own, so a later position would let MGB wrongly claim them.
         *
         * Exposed so the registry (order, device claims, duplicates) can be asserted in unit tests
         * without building the Hilt graph — see `ScaleFactoryTest`.
         */
        @VisibleForTesting
        internal fun createHandlers(): List<ScaleDeviceHandler> = listOf(
            MiScaleHandler(),
        )
    }

    /**
     * Reads the current value of a settings [Flow] from a non-suspending context.
     *
     * Communicator creation happens on the caller's thread, so the few settings needed here are
     * read with a short timeout rather than restructuring every call site; `null` means the value
     * was unavailable in time and the caller falls back to its default.
     */
    private fun <T> readSettingBlocking(flow: Flow<T>): T? = runCatching {
        runBlocking(Dispatchers.IO) {
            withTimeout(250.milliseconds) { flow.firstOrNull() }
        }
    }.getOrNull()

    /**
     * Creates a [ScaleCommunicator] based on a modern [ScaleDeviceHandler].
     * This method is conceptual for now, as the current DummyScaleHandlers are not full communicators.
     * In a full implementation, this might return the handler itself if it's a ScaleCommunicator,
     * or wrap it in a modern adapter.
     *
     * @param handler The [ScaleDeviceHandler] that can handle the device.
     * @return A [ScaleCommunicator] instance if one can be provided by or for the handler, otherwise null.
     */
    private fun createModernCommunicator(
        handler: ScaleDeviceHandler,
        support: DeviceSupport
    ): ScaleCommunicator? {
        // Resolve effective tuning: prefer user-saved value, fall back to handler default
        val effectiveTuning: TuningProfile = run {
            val saved: String? = readSettingBlocking(settingsFacade.savedBluetoothTuneProfile)

            saved?.let { runCatching { TuningProfile.valueOf(it) }.getOrNull() }
                ?: support.tuningProfile
        }

        return when (support.linkMode) {
            LinkMode.CONNECT_GATT ->
                GattScaleAdapter(
                    applicationContext,
                    settingsFacade,
                    measurementFacade,
                    userFacade,
                    handler,
                    effectiveTuning
                )

            LinkMode.BROADCAST_ONLY ->
                BroadcastScaleAdapter(
                    applicationContext,
                    settingsFacade,
                    measurementFacade,
                    userFacade,
                    handler,
                    effectiveTuning
                )

            LinkMode.CLASSIC_SPP ->
                SppScaleAdapter(
                    applicationContext,
                    settingsFacade,
                    measurementFacade,
                    userFacade,
                    handler,
                    effectiveTuning
                )
        }
    }

    /**
     * Creates the most suitable [ScaleCommunicator] for the given scanned device.
     *
     * @param deviceInfo Information about the scanned Bluetooth device.
     * @return A [ScaleCommunicator] instance if a suitable handler or adapter is found, otherwise null.
     */
    fun createCommunicator(deviceInfo: ScannedDeviceInfo): ScaleCommunicator? {
        val primaryIdentifier = deviceInfo.name
        LogManager.d(TAG, "createCommunicator: Searching for communicator for '${primaryIdentifier}' (${deviceInfo.address}). Handler hint: '${deviceInfo.determinedHandlerDisplayName}'")

        // 0. Developer mode wins over every registered handler: route to the diagnostic handler,
        //    which dumps the GATT tree and logs notifications but stores nothing.
        if (readSettingBlocking(settingsFacade.developerModeEnabled) == true) {
            LogManager.i(TAG, "Developer mode active → routing '$primaryIdentifier' to DebugGattHandler. No measurement will be stored.")
            return createModernCommunicator(DebugGattHandler(), DebugGattHandler.SUPPORT)
        }

        // 1. Check if a modern Kotlin handler explicitly supports the device.
        for (handler in modernKotlinHandlers) {
            val support = handler.supportFor(deviceInfo)
            if (support != null) {
                LogManager.i(TAG, "Modern handler '${support.displayName}' supports '$primaryIdentifier'.")
                val modern = createModernCommunicator(handler, support)
                if (modern != null) {
                    LogManager.i(TAG, "Modern communicator '${modern.javaClass.simpleName}' created for '$primaryIdentifier' with linkMode=${support.linkMode}.")
                    return modern
                }
                LogManager.w(TAG, "Modern handler '${support.displayName}' supports '$primaryIdentifier', but no communicator is available.")
            }
        }

        LogManager.w(TAG, "No suitable communicator found for device (name: '${deviceInfo.name}', address: '${deviceInfo.address}', handler hint: '${deviceInfo.determinedHandlerDisplayName}').")
        return null
    }

    /**
     * Returns the [DeviceSupport] of the first handler that claims [device].
     *
     * Always pass the complete advertisement: handlers that identify a scale by its services,
     * manufacturer data or service data (Etekcity Fit 8S, Yunmai X, the standard weight profile,
     * ...) cannot recognise it from name and address alone and would report "no support".
     */
    fun getDeviceSupportFor(device: ScannedDeviceInfo): DeviceSupport? =
        modernKotlinHandlers.firstNotNullOfOrNull { it.supportFor(device) }

    /**
     * Checks if any known handler can theoretically support the given device.
     * This can be used by the UI to indicate if a device is potentially recognizable.
     *
     * @param deviceInfo Information about the scanned Bluetooth device.
     * @return A Pair where `first` is true if a handler is found, and `second` is the name of the handler/driver, or null.
     */
    fun getSupportingHandlerInfo(deviceInfo : ScannedDeviceInfo): Pair<Boolean, String?> {
        // Check modern handlers first
        for (handler in modernKotlinHandlers) {
            val support = handler.supportFor(deviceInfo)
            if (support != null) return true to support.displayName
        }

        return false to null
    }
}
