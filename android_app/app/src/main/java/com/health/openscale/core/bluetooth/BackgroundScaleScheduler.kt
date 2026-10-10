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

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.health.openscale.core.facade.SettingsFacade
import com.health.openscale.core.utils.LogManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackgroundScaleSchedulerEntryPoint {
    fun settingsFacade(): SettingsFacade
}

/**
 * Orchestrates the scheduled daily weigh-in window for the Xiaomi Body Composition Scale 2.
 *
 * Rather than keeping Bluetooth scanning active 24/7 (which consumes significant battery),
 * this scheduler arms an AlarmManager wake-up only during the user's daily morning window
 * (e.g. 8:00 AM for 45 minutes).
 *
 * Outside the window, Bluetooth scanning and the background service are completely stopped (0.0% drain).
 * Once a measurement is successfully captured and synced to Health Connect, the service stops immediately
 * and schedules the next morning's window.
 */
object BackgroundScaleScheduler {
    private const val TAG = "BackgroundScaleScheduler"
    private const val ALARM_REQUEST_CODE = 4041

    fun scheduleNext(context: Context) {
        val appContext = context.applicationContext
        val entryPoint = EntryPointAccessors.fromApplication(
            appContext,
            BackgroundScaleSchedulerEntryPoint::class.java
        )
        val settingsFacade = entryPoint.settingsFacade()

        CoroutineScope(Dispatchers.Default).launch {
            try {
                val enabled = settingsFacade.isBackgroundScaleSyncEnabled.first()
                val savedDevice = settingsFacade.observeSavedDevice().first()

                if (!enabled || savedDevice == null) {
                    LogManager.i(TAG, "Background scale sync disabled or no device saved; canceling scheduler and stopping service")
                    cancelAlarm(appContext)
                    BackgroundScaleService.stop(appContext)
                    return@launch
                }

                val startHour = settingsFacade.backgroundSyncWindowStartHour.first().coerceIn(0, 23)
                val startMinute = settingsFacade.backgroundSyncWindowStartMinute.first().coerceIn(0, 59)
                val durationMinutes = settingsFacade.backgroundSyncWindowDurationMinutes.first().coerceAtLeast(1)
                val lastMeasuredDate = settingsFacade.backgroundSyncLastMeasuredDate.first()

                val now = Calendar.getInstance()
                val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now.time)
                val alreadyMeasuredToday = (lastMeasuredDate == todayStr)

                val windowStartToday = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, startHour)
                    set(Calendar.MINUTE, startMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                val windowEndToday = (windowStartToday.clone() as Calendar).apply {
                    add(Calendar.MINUTE, durationMinutes)
                }

                when {
                    // Scenario 1: Currently inside today's window
                    now.timeInMillis >= windowStartToday.timeInMillis && now.timeInMillis < windowEndToday.timeInMillis -> {
                        if (alreadyMeasuredToday) {
                            LogManager.i(TAG, "Already measured today ($todayStr); scheduling window for tomorrow at $startHour:$startMinute")
                            BackgroundScaleService.stop(appContext)
                            val tomorrowStart = (windowStartToday.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
                            armAlarm(appContext, tomorrowStart.timeInMillis)
                        } else {
                            val remainingMinutes = ((windowEndToday.timeInMillis - now.timeInMillis) / 60_000L).toInt().coerceAtLeast(1)
                            LogManager.i(TAG, "Currently inside weigh-in window! Starting service for remaining $remainingMinutes minutes")
                            cancelAlarm(appContext)
                            BackgroundScaleService.startForWindow(appContext, remainingMinutes)
                        }
                    }

                    // Scenario 2: Before today's window
                    now.timeInMillis < windowStartToday.timeInMillis -> {
                        LogManager.i(TAG, "Scheduling weigh-in window for today at $startHour:$startMinute (${windowStartToday.time})")
                        BackgroundScaleService.stop(appContext)
                        armAlarm(appContext, windowStartToday.timeInMillis)
                    }

                    // Scenario 3: After today's window
                    else -> {
                        val tomorrowStart = (windowStartToday.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
                        LogManager.i(TAG, "Today's window passed; scheduling next weigh-in window for tomorrow at $startHour:$startMinute (${tomorrowStart.time})")
                        BackgroundScaleService.stop(appContext)
                        armAlarm(appContext, tomorrowStart.timeInMillis)
                    }
                }
            } catch (e: Exception) {
                LogManager.e(TAG, "Error scheduling next background scale window", e)
            }
        }
    }

    private fun armAlarm(context: Context, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, BackgroundScaleReceiver::class.java).apply {
            action = BackgroundScaleReceiver.ACTION_START_SCHEDULED_WINDOW
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            LogManager.i(TAG, "Scheduled morning window alarm armed for ${Date(triggerAtMillis)}")
        } catch (e: Exception) {
            LogManager.e(TAG, "Failed to arm alarm", e)
        }
    }

    fun cancelAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, BackgroundScaleReceiver::class.java).apply {
            action = BackgroundScaleReceiver.ACTION_START_SCHEDULED_WINDOW
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            LogManager.i(TAG, "Canceled scheduled window alarm")
        }
    }
}
