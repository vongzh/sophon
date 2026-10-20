package com.vongzh.sophon.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleModelsTest {

    @Test
    fun testDoorStateIsAnyAjar() {
        val closed = DoorState()
        assertFalse(closed.isAnyAjar)

        assertTrue(DoorState(isDriverAjar = true).isAnyAjar)
        assertTrue(DoorState(isPassengerAjar = true).isAnyAjar)
        assertTrue(DoorState(isRearLeftAjar = true).isAnyAjar)
        assertTrue(DoorState(isRearRightAjar = true).isAnyAjar)
        assertTrue(DoorState(isTrunkAjar = true).isAnyAjar)
        assertTrue(DoorState(isHoodAjar = true).isAnyAjar)
    }

    @Test
    fun testStorageUsageBreakdownCalculations() {
        val breakdown = StorageUsageBreakdown(
            totalBytes = 64L * 1024 * 1024 * 1024,
            freeBytes = 24L * 1024 * 1024 * 1024,
            drivingBytes = 20L * 1024 * 1024 * 1024,
            sentryBytes = 10L * 1024 * 1024 * 1024,
            photoBytes = 2L * 1024 * 1024 * 1024,
            otherBytes = 8L * 1024 * 1024 * 1024
        )

        assertEquals(40L * 1024 * 1024 * 1024, breakdown.usedBytes)

        val empty = StorageUsageBreakdown()
        assertEquals(0L, empty.usedBytes)
    }

    @Test
    fun testCameraPositionMapping() {
        assertEquals(0, CameraPosition.FRONT.channelId)
        assertEquals("F", CameraPosition.FRONT.tag)

        assertEquals(1, CameraPosition.BACK.channelId)
        assertEquals("B", CameraPosition.BACK.tag)

        assertEquals(2, CameraPosition.LEFT.channelId)
        assertEquals("L", CameraPosition.LEFT.tag)

        assertEquals(3, CameraPosition.RIGHT.channelId)
        assertEquals("R", CameraPosition.RIGHT.tag)
    }

    @Test
    fun testRecordCategoryDirectories() {
        assertEquals("Normal", RecordCategory.NORMAL.dirName)
        assertEquals("Event", RecordCategory.EVENT.dirName)
        assertEquals("Sentry", RecordCategory.SENTRY.dirName)
        assertEquals("Snapshot", RecordCategory.SNAPSHOT.dirName)
        assertEquals("Clips", RecordCategory.CLIPS.dirName)
    }

    @Test
    fun testGnssTelemetryFormatting() {
        val noFix = GnssTelemetry()
        assertEquals("--", noFix.toCoordinateString())

        val fixed = GnssTelemetry(
            latitude = 31.2304,
            longitude = 121.4737,
            hasFix = true
        )
        assertEquals("31.2304°N, 121.4737°E", fixed.toCoordinateString())

        val southWest = GnssTelemetry(
            latitude = -33.8688,
            longitude = -151.2093,
            hasFix = true
        )
        assertEquals("33.8688°S, 151.2093°W", southWest.toCoordinateString())
    }

    @Test
    fun testSafeGeofenceDistanceAndContainment() {
        // 上海人民广场中心坐标
        val homeFence = SafeGeofence(
            name = "家",
            latitude = 31.2304,
            longitude = 121.4737,
            radiusMeters = 200f
        )

        // 位于同一位置，距离应为 0
        assertEquals(0.0, homeFence.distanceToMeters(31.2304, 121.4737), 1.0)
        assertTrue(homeFence.contains(31.2304, 121.4737))

        // 偏移约 50 米处
        val nearLat = 31.2308
        val nearLon = 121.4737
        val distNear = homeFence.distanceToMeters(nearLat, nearLon)
        assertTrue("Distance should be around 44 meters", distNear in 30.0..60.0)
        assertTrue("Should be contained in 200m radius", homeFence.contains(nearLat, nearLon))

        // 偏移 1 公里处
        val farLat = 31.2400
        val farLon = 121.4737
        val distFar = homeFence.distanceToMeters(farLat, farLon)
        assertTrue("Distance should be around 1000 meters", distFar > 800.0)
        assertFalse("Should not be contained in 200m radius", homeFence.contains(farLat, farLon))
    }

    @Test
    fun testHornQuickSaveEnum() {
        val hornEvent = HardKeyEvent.HORN_QUICK_SAVE
        assertEquals("HORN_QUICK_SAVE", hornEvent.name)
    }
}


