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
package com.health.openscale.core.bluetooth

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.health.openscale.core.facade.SettingsFacadeImpl
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundScaleSyncTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var settings: SettingsFacadeImpl

    @Before
    fun setUp() {
        val prefsFile = File(app.cacheDir, "bg-scale-test-${System.nanoTime()}.preferences_pb")
        settings = SettingsFacadeImpl(PreferenceDataStoreFactory.create(produceFile = { prefsFile }))
    }

    @Test
    fun backgroundScaleSyncEnabled_defaultsToTrue() = runTest {
        assertTrue(settings.isBackgroundScaleSyncEnabled.first())
    }

    @Test
    fun backgroundScaleSyncEnabled_canBeToggled() = runTest {
        settings.setBackgroundScaleSyncEnabled(false)
        assertFalse(settings.isBackgroundScaleSyncEnabled.first())

        settings.setBackgroundScaleSyncEnabled(true)
        assertTrue(settings.isBackgroundScaleSyncEnabled.first())
    }

    @Test
    fun autoConnectOnStartup_defaultsToTrue() = runTest {
        assertTrue(settings.autoConnectOnStartup.first())
    }

    @Test
    fun backgroundSyncWindow_defaultsAndUpdates() = runTest {
        assertEquals(8, settings.backgroundSyncWindowStartHour.first())
        assertEquals(0, settings.backgroundSyncWindowStartMinute.first())
        assertEquals(45, settings.backgroundSyncWindowDurationMinutes.first())

        settings.setBackgroundSyncWindowStartHour(7)
        settings.setBackgroundSyncWindowStartMinute(30)
        settings.setBackgroundSyncWindowDurationMinutes(60)

        assertEquals(7, settings.backgroundSyncWindowStartHour.first())
        assertEquals(30, settings.backgroundSyncWindowStartMinute.first())
        assertEquals(60, settings.backgroundSyncWindowDurationMinutes.first())
    }

    @Test
    fun healthConnectSyncEnabled_defaultsToTrue() = runTest {
        assertTrue(settings.healthConnectSyncEnabled.first())
    }

    @Test
    fun backgroundScaleService_createsNotificationChannelOnStart() {
        val serviceController = Robolectric.buildService(BackgroundScaleService::class.java)
        val service = serviceController.get()
        service.settingsFacade = settings

        // Directly call createNotificationChannel via reflection or start service
        serviceController.create()

        val notificationManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = notificationManager.getNotificationChannel("background_scale_sync_channel")
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)

        serviceController.destroy()
    }
}
