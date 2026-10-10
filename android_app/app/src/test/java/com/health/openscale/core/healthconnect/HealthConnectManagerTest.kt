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

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.aggregate.AggregationResultGroupedByDuration
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ChangesResponse
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.response.ReadRecordResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.test.core.app.ApplicationProvider
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.data.User
import com.health.openscale.core.database.AppDatabase
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.facade.SettingsFacadeImpl
import com.health.openscale.core.model.MeasurementValueWithType
import com.health.openscale.core.model.MeasurementWithValues
import com.health.openscale.testutil.RoomTestSupport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.reflect.KClass

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthConnectManagerTest {

    private class TestHealthConnectClient : HealthConnectClient {
        val written = mutableListOf<List<Record>>()
        val deleted = mutableListOf<Pair<KClass<out Record>, TimeRangeFilter>>()
        var grantedPermissions: Set<String> = emptySet()

        override suspend fun insertRecords(records: List<Record>): InsertRecordsResponse {
            written += records
            return InsertRecordsResponse(records.map { it.metadata.clientRecordId.orEmpty() })
        }

        override suspend fun deleteRecords(recordType: KClass<out Record>, timeRangeFilter: TimeRangeFilter) {
            deleted += recordType to timeRangeFilter
        }

        override val permissionController: PermissionController = object : PermissionController {
            override suspend fun getGrantedPermissions(): Set<String> = grantedPermissions
            override suspend fun revokeAllPermissions() { grantedPermissions = emptySet() }
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Record> readRecords(request: ReadRecordsRequest<T>): ReadRecordsResponse<T> =
            ReadRecordsResponse(emptyList(), null)

        override suspend fun updateRecords(records: List<Record>) = error("not used")
        override suspend fun deleteRecords(
            recordType: KClass<out Record>, recordIdsList: List<String>, clientRecordIdsList: List<String>
        ) = error("not used")
        override suspend fun <T : Record> readRecord(recordType: KClass<T>, recordId: String): ReadRecordResponse<T> =
            error("not used")
        override suspend fun aggregate(request: AggregateRequest): AggregationResult = error("not used")
        override suspend fun aggregateGroupByDuration(
            request: AggregateGroupByDurationRequest
        ): List<AggregationResultGroupedByDuration> = error("not used")
        override suspend fun aggregateGroupByPeriod(
            request: AggregateGroupByPeriodRequest
        ): List<AggregationResultGroupedByPeriod> = error("not used")
        override suspend fun getChangesToken(request: ChangesTokenRequest): String = error("not used")
        override suspend fun getChanges(changesToken: String): ChangesResponse = error("not used")
    }

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: DatabaseRepository
    private lateinit var settings: SettingsFacadeImpl
    private lateinit var testClient: TestHealthConnectClient
    private lateinit var manager: HealthConnectManager

    @Before
    fun setUp() = runTest {
        db = RoomTestSupport.inMemory(app)
        repo = RoomTestSupport.repositoryFor(db)
        repo.insertAllMeasurementTypes(MeasurementType.seedRows())

        val prefsFile = File(app.cacheDir, "hcm-test-${System.nanoTime()}.preferences_pb")
        settings = SettingsFacadeImpl(PreferenceDataStoreFactory.create(produceFile = { prefsFile }))

        testClient = TestHealthConnectClient()
        manager = HealthConnectManager(app, settings, repo, customClient = testClient)

        repo.insertUser(
            User(
                name = "Test User", birthDate = 0L, gender = GenderType.MALE, heightCm = 180f,
                activityLevel = ActivityLevel.MODERATE, useAssistedWeighing = false
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun toSyncMeasurement_convertsWeightAndComposition() {
        val types = MeasurementType.seedRows()
        val weightType = types.first { it.identity == MeasurementType.WEIGHT.identity }
        val fatType = types.first { it.identity == MeasurementType.BODY_FAT.identity }
        val waterType = types.first { it.identity == MeasurementType.WATER.identity }

        val mv = MeasurementWithValues(
            measurement = Measurement(id = 42, userId = 1, timestamp = 5_000L),
            values = listOf(
                MeasurementValueWithType(MeasurementValue(measurementId = 42, typeId = weightType.id, floatValue = 80f), weightType),
                MeasurementValueWithType(MeasurementValue(measurementId = 42, typeId = fatType.id, floatValue = 18.5f), fatType),
                MeasurementValueWithType(MeasurementValue(measurementId = 42, typeId = waterType.id, floatValue = 58f), waterType)
            )
        )

        val syncM = manager.toSyncMeasurement(mv)
        assertNotNull(syncM)
        assertEquals(42, syncM!!.id)
        assertEquals(5_000L, syncM.timestamp)
        assertEquals(80f, syncM.weightKg, 0.001f)
        assertEquals(18.5f, syncM.fatPct!!, 0.001f)
        assertEquals(58f, syncM.waterPct!!, 0.001f)
        assertNull(syncM.boneKg)
    }

    @Test
    fun toSyncMeasurement_returnsNull_whenWeightIsMissingOrZero() {
        val mv = MeasurementWithValues(
            measurement = Measurement(id = 1, userId = 1, timestamp = 1_000L),
            values = emptyList()
        )
        assertNull(manager.toSyncMeasurement(mv))
    }

    @Test
    fun shouldSyncForUser_respectsEnabledAndUser() = runTest {
        settings.setHealthConnectSyncEnabled(false)
        assertFalse(manager.shouldSyncForUser(1))

        settings.setHealthConnectSyncEnabled(true)
        settings.setHealthConnectUserId(1)
        assertTrue(manager.shouldSyncForUser(1))
        assertFalse(manager.shouldSyncForUser(2))
    }

    @Test
    fun syncMeasurementInsert_writesToClient_whenPermissionsGranted() = runTest {
        settings.setHealthConnectSyncEnabled(true)
        settings.setHealthConnectUserId(1)
        testClient.grantedPermissions = manager.writePermissions

        val types = repo.getAllMeasurementTypes().first()
        val weightType = types.first { it.identity == MeasurementType.WEIGHT.identity }

        val newId = repo.insertMeasurement(Measurement(id = 0, userId = 1, timestamp = 10_000L)).toInt()
        repo.insertMeasurementValue(MeasurementValue(measurementId = newId, typeId = weightType.id, floatValue = 75.5f))

        val result = manager.syncMeasurementInsert(newId, 1)
        assertNotNull(result)
        assertTrue(result is HealthConnectResult.Success)
        assertEquals(1, testClient.written.size)
        assertEquals("${newId}_weight", testClient.written.first().first().metadata.clientRecordId)
        assertTrue(settings.healthConnectLastSyncTime.first() > 0L)
    }
}
