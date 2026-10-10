/*
 * myScale
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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.health.openscale.core.utils.LogManager
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver triggered by AlarmManager when the scheduled weigh-in window begins.
 */
class BackgroundScaleReceiver : BroadcastReceiver() {
    companion object {
        const val TAG = "BackgroundScaleReceiver"
        const val ACTION_START_SCHEDULED_WINDOW = "com.health.openscale.ACTION_START_SCHEDULED_WINDOW"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_START_SCHEDULED_WINDOW) {
            LogManager.i(TAG, "Scheduled morning weigh-in window alarm received!")
            val appContext = context.applicationContext
            val entryPoint = EntryPointAccessors.fromApplication(
                appContext,
                BackgroundScaleSchedulerEntryPoint::class.java
            )
            val settingsFacade = entryPoint.settingsFacade()

            CoroutineScope(Dispatchers.Default).launch {
                val enabled = settingsFacade.isBackgroundScaleSyncEnabled.first()
                val duration = settingsFacade.backgroundSyncWindowDurationMinutes.first()
                if (enabled) {
                    LogManager.i(TAG, "Starting BackgroundScaleService for morning window ($duration min)")
                    BackgroundScaleService.startForWindow(appContext, duration)
                } else {
                    LogManager.i(TAG, "Background sync disabled, ignoring alarm trigger")
                }
            }
        }
    }
}
