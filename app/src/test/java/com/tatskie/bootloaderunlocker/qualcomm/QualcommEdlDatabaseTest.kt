package com.tatskie.bootloaderunlocker.qualcomm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QualcommEdlDatabaseTest {

    @Test
    fun extractsMsmIdFromHwId() {
        assertEquals(0x0A50E1, QualcommEdlDatabase.hwIdToMsmId(0x000A50E100000000L))
        assertEquals(0x0460E1, QualcommEdlDatabase.hwIdToMsmId(0x000460E100000000L))
    }

    @Test
    fun namesKnownPlatforms() {
        assertEquals("Snapdragon 855 (SM8150)", QualcommEdlDatabase.platformForHwId(0x000A50E100000000L))
        assertEquals("Snapdragon 625/636 (MSM8953)", QualcommEdlDatabase.platformForHwId(0x000460E100000000L))
        assertEquals("Snapdragon 845 (SDM845)", QualcommEdlDatabase.platformForMsmId(0x08B0E1))
    }

    @Test
    fun unknownPlatformReturnsNull() {
        assertNull(QualcommEdlDatabase.platformForMsmId(0x000000))
        assertNull(QualcommEdlDatabase.platformForHwId(0L))
    }

    @Test
    fun mapsEdlProductIdsToModes() {
        assertEquals("Firehose (EDL)", QualcommEdlDatabase.edlModeForProductId(0x9008))
        assertEquals("Crash dump", QualcommEdlDatabase.edlModeForProductId(0x900E))
        assertEquals("EDL (alternate)", QualcommEdlDatabase.edlModeForProductId(0x901D))
        assertEquals("Diag / dump mode", QualcommEdlDatabase.edlModeForProductId(0x90DB))
        assertEquals("Unknown", QualcommEdlDatabase.edlModeForProductId(0x1234))
    }
}
