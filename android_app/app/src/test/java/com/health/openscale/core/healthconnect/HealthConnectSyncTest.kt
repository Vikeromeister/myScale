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

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.aggregate.AggregationResultGroupedByDuration
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.records.Record
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Date
import kotlin.reflect.KClass

class HealthConnectSyncTest {

    private class FakeHealthConnectClient : HealthConnectClient {
        val written = mutableListOf<List<Record>>()
        val deleted = mutableListOf<Pair<KClass<out Record>, TimeRangeFilter>>()
        val stored = mutableListOf<Record>()
        var readsRejected = true

        override suspend fun insertRecords(records: List<Record>): InsertRecordsResponse {
            written += records
            return InsertRecordsResponse(records.map { it.metadata.clientRecordId.orEmpty() })
        }

        override suspend fun deleteRecords(recordType: KClass<out Record>, timeRangeFilter: TimeRangeFilter) {
            deleted += recordType to timeRangeFilter
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Record> readRecords(request: ReadRecordsRequest<T>): ReadRecordsResponse<T> {
            if (readsRejected) throw SecurityException(
                "Caller does not have permission to read data for recordType: ${request.recordType}"
            )
            return ReadRecordsResponse(stored.filter { request.recordType.isInstance(it) } as List<T>, null)
        }

        override val permissionController: PermissionController get() = error("not used by these tests")
        override suspend fun updateRecords(records: List<Record>) = error("not used by these tests")
        override suspend fun deleteRecords(
            recordType: KClass<out Record>, recordIdsList: List<String>, clientRecordIdsList: List<String>
        ) = error("not used by these tests")
        override suspend fun <T : Record> readRecord(recordType: KClass<T>, recordId: String): ReadRecordResponse<T> =
            error("not used by these tests")
        override suspend fun aggregate(request: AggregateRequest): AggregationResult = error("not used by these tests")
        override suspend fun aggregateGroupByDuration(
            request: AggregateGroupByDurationRequest
        ): List<AggregationResultGroupedByDuration> = error("not used by these tests")
        override suspend fun aggregateGroupByPeriod(
            request: AggregateGroupByPeriodRequest
        ): List<AggregationResultGroupedByPeriod> = error("not used by these tests")
        override suspend fun getChangesToken(request: ChangesTokenRequest): String = error("not used by these tests")
        override suspend fun getChanges(changesToken: String): ChangesResponse = error("not used by these tests")
    }

    private val client = FakeHealthConnectClient()
    private val sync = HealthConnectSync(client)

    private fun measurement(
        id: Int = 7,
        timestamp: Long = 1_000_000L,
        weightKg: Float = 80f,
        fatPct: Float? = 20f,
        waterPct: Float? = 55f,
        boneKg: Float? = null,
        lbmKg: Float? = null,
        bmrKcal: Float? = null
    ) = SyncMeasurement(
        id = id,
        timestamp = timestamp,
        weightKg = weightKg,
        fatPct = fatPct,
        waterPct = waterPct,
        boneKg = boneKg,
        lbmKg = lbmKg,
        bmrKcal = bmrKcal
    )

    private fun lastWrittenIds() = client.written.last().map { it.metadata.clientRecordId }

    @Test
    fun update_writesWithoutReadingBack() = runTest {
        val result = sync.update(measurement())
        assertTrue("update must succeed", result is HealthConnectResult.Success)
        assertEquals(listOf("7_weight", "7_water", "7_fat"), lastWrittenIds())
    }

    @Test
    fun update_writesTheSameRecordsAsInsert() = runTest {
        sync.insert(measurement())
        val afterInsert = lastWrittenIds()

        sync.update(measurement())
        assertEquals(afterInsert, lastWrittenIds())
    }

    @Test
    fun update_carriesOptionalValues_whenProvided() = runTest {
        val m = measurement(
            boneKg = 3f,
            lbmKg = 60f,
            bmrKcal = 1600f
        )

        sync.update(m)
        assertEquals(listOf("7_weight", "7_water", "7_fat", "7_lbm", "7_bone", "7_bmr"), lastWrittenIds())
    }

    @Test
    fun write_omitsOptionalRecords_whenValuesAreAbsent() = runTest {
        sync.insert(measurement())
        assertEquals(listOf("7_weight", "7_water", "7_fat"), lastWrittenIds())
    }

    @Test
    fun delete_onlyCoversTheMeasurementItself_notTheWholeDay() = runTest {
        val timestamp = 1_000_000L
        val result = sync.delete(timestamp)

        assertTrue(result is HealthConnectResult.Success)
        assertEquals(6, client.deleted.size)
        val instant = Instant.ofEpochMilli(timestamp)
        client.deleted.forEach { (_, range) ->
            assertEquals(instant.minusSeconds(1), range.startTime)
            assertEquals(instant.plusSeconds(1), range.endTime)
        }
    }

    @Test
    fun delete_withDate_delegatesProperly() = runTest {
        val date = Date(1_000_000L)
        val result = sync.delete(date)

        assertTrue(result is HealthConnectResult.Success)
        assertEquals(6, client.deleted.size)
    }

    @Test
    fun clear_deletesAllSupportedTypes() = runTest {
        val result = sync.clear()
        assertTrue(result is HealthConnectResult.Success)
        assertEquals(6, client.deleted.size)
    }

    @Test
    fun fullSync_writesEveryMeasurementInOneBatch() = runTest {
        val result = sync.fullSync(listOf(measurement(id = 1), measurement(id = 2)))

        assertTrue(result is HealthConnectResult.Success)
        assertEquals(1, client.written.size)
        assertEquals(
            listOf("1_weight", "1_water", "1_fat", "2_weight", "2_water", "2_fat"),
            lastWrittenIds()
        )
    }

    @Test
    fun inbound_groupsRecordsWrittenSlightlyApart_andCarriesBoneAndLeanMass() {
        val readings = sync.groupInboundReadings(
            weights = listOf(10_000L to 80f),
            fatsPct = listOf(10_400L to 21f),
            watersKg = listOf(10_500L to 44f),
            bonesKg = listOf(10_900L to 3.2f),
            leansKg = listOf(11_000L to 60f)
        )

        val reading = readings.single()
        assertEquals(10_000L, reading.timeMs)
        assertEquals(80f, reading.weightKg, 0.001f)
        assertEquals(21f, reading.fatPct!!, 0.001f)
        assertEquals(55f, reading.waterPct!!, 0.001f)
        assertEquals(3.2f, reading.boneKg!!, 0.001f)
        assertEquals(60f, reading.leanKg!!, 0.001f)
    }

    @Test
    fun inbound_doesNotStealValuesFromANeighbouringWeighIn() {
        val readings = sync.groupInboundReadings(
            weights = listOf(10_000L to 80f, 60_000L to 81f),
            fatsPct = listOf(
                59_500L to 22f,
                40_000L to 99f
            )
        ).associateBy { it.timeMs }

        assertNull(readings.getValue(10_000L).fatPct)
        assertEquals(22f, readings.getValue(60_000L).fatPct!!, 0.001f)
    }

    @Test
    fun inbound_keepsTheClosestCandidate_whenSeveralCompete() {
        val readings = sync.groupInboundReadings(
            weights = listOf(10_000L to 80f),
            fatsPct = listOf(11_500L to 30f, 10_100L to 21f)
        )

        assertEquals(21f, readings.single().fatPct!!, 0.001f)
    }

    @Test
    fun inbound_withoutAWeightRecord_yieldsNothing() {
        assertEquals(
            emptyList<InboundReading>(),
            sync.groupInboundReadings(weights = emptyList(), fatsPct = listOf(10_000L to 21f))
        )
    }

    @Test
    fun rejectionReason_acceptsAnOrdinaryMeasurement() {
        assertNull(sync.rejectionReason(measurement()))
    }

    @Test
    fun rejectionReason_flagsABodyFatAbove100Percent() {
        assertNotNull(sync.rejectionReason(measurement(fatPct = 409.5f)))
    }

    @Test
    fun rejectionReason_flagsAMeasurementTimedInTheFuture() {
        val inFourHours = System.currentTimeMillis() + 4 * 60 * 60 * 1000L
        assertNotNull(sync.rejectionReason(measurement(timestamp = inFourHours)))
        assertNull(
            sync.rejectionReason(
                measurement(timestamp = inFourHours),
                now = Instant.ofEpochMilli(inFourHours).plusSeconds(1)
            )
        )
    }

    @Test
    fun rejectionReason_flagsAnImpossibleWeight() {
        assertNotNull(sync.rejectionReason(measurement(weightKg = 1_001f)))
        assertNotNull(sync.rejectionReason(measurement(weightKg = -1f)))
        assertNotNull(sync.rejectionReason(measurement(weightKg = Float.NaN)))
    }

    @Test
    fun rejectionReason_flagsAZeroWeight() {
        assertNotNull(sync.rejectionReason(measurement(weightKg = 0f)))
    }

    @Test
    fun rejectionReason_flagsAnOutOfRangeBmr() {
        assertNotNull(sync.rejectionReason(measurement(bmrKcal = 20_000f)))
    }

    @Test
    fun rejectionReason_ignoresOptionalValuesThatAreZeroOrNull() {
        assertNull(sync.rejectionReason(measurement(lbmKg = 0f, boneKg = 0f, bmrKcal = 0f)))
    }

    @Test
    fun insert_reportsAnInvalidMeasurementInsteadOfThrowing() = runTest {
        val result = sync.insert(measurement(fatPct = 409.5f))
        assertTrue(result is HealthConnectResult.Failure)
        assertTrue("nothing may be written for a rejected measurement", client.written.isEmpty())
    }

    @Test
    fun fullSync_stillWritesTheValidMeasurements_whenOneIsInvalid() = runTest {
        val valid = measurement(id = 1)
        val invalid = measurement(id = 2, weightKg = -5f)
        val result = sync.fullSync(listOf(valid, invalid))

        assertTrue(result is HealthConnectResult.Success)
        assertEquals(1, (result as HealthConnectResult.Success).data)
        assertFalse(client.written.isEmpty())
    }
}
