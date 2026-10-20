package com.tatskie.bootloaderunlocker.fastboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastbootProtocolTest {

    @Test
    fun matchesFastbootInterface() {
        assertTrue(FastbootProtocol.isFastbootInterface(0xFF, 0x42, 0x03))
        assertFalse(FastbootProtocol.isFastbootInterface(0xFF, 0x42, 0x01))
        assertFalse(FastbootProtocol.isFastbootInterface(0x08, 0x06, 0x50))
    }

    @Test
    fun hexSizeIsLowercaseZeroPadded() {
        assertEquals("00001000", FastbootProtocol.hexSize(4096))
        assertEquals("00000000", FastbootProtocol.hexSize(0))
        assertEquals("00100000", FastbootProtocol.hexSize(1048576))
    }

    @Test
    fun parsesOkayResponse() {
        val response = FastbootProtocol.parse("OKAY")
        assertTrue(response.okay)
        assertEquals("", response.payload)
    }

    @Test
    fun parsesOkayWithValue() {
        val response = FastbootProtocol.parse("OKAY7s Gen 2")
        assertTrue(response.okay)
        assertEquals("7s Gen 2", response.payload)
    }

    @Test
    fun parsesFailResponse() {
        val response = FastbootProtocol.parse("FAILunknown command")
        assertFalse(response.okay)
        assertEquals("FAIL", response.status)
        assertEquals("unknown command", response.payload)
    }

    @Test
    fun parsesDataResponseSize() {
        val response = FastbootProtocol.parse("DATA00001000")
        assertTrue(response.isData)
        assertEquals(4096, FastbootProtocol.parseDataSize(response.payload))
    }

    @Test
    fun parsesInfoResponse() {
        val response = FastbootProtocol.parse("INFOerasing userdata")
        assertEquals("INFO", response.status)
        assertEquals("erasing userdata", response.payload)
    }

    @Test
    fun shortResponseIsFailure() {
        val response = FastbootProtocol.parse("OK")
        assertFalse(response.okay)
        assertEquals("FAIL", response.status)
    }

    @Test
    fun trimsTrailingNulls() {
        val response = FastbootProtocol.parse("OKAYvalue\u0000\u0000")
        assertEquals("value", response.payload)
    }

    @Test
    fun invalidDataSizeReturnsMinusOne() {
        assertEquals(-1, FastbootProtocol.parseDataSize("zzzz"))
    }

    @Test
    fun unlockAbilityFromInfoLine() {
        val response = FastbootResponse(
            "OKAY", "", "(bootloader) unlock_ability = 1\n"
        )
        assertEquals(true, FastbootProtocol.parseUnlockAbility(response))
    }

    @Test
    fun unlockAbilityZeroFromInfoLine() {
        val response = FastbootResponse(
            "OKAY", "", "(bootloader) unlock_ability = 0\n"
        )
        assertEquals(false, FastbootProtocol.parseUnlockAbility(response))
    }

    @Test
    fun unlockAbilityFromPayload() {
        assertEquals(true, FastbootProtocol.parseUnlockAbility(FastbootResponse("OKAY", "1")))
        assertEquals(false, FastbootProtocol.parseUnlockAbility(FastbootResponse("OKAY", "0")))
    }

    @Test
    fun unlockAbilityUnknownWhenNoValue() {
        val response = FastbootResponse("OKAY", "")
        assertEquals(null, FastbootProtocol.parseUnlockAbility(response))
    }

    @Test
    fun unlockAbilityLabelBeatsLooseNumber() {
        val response = FastbootResponse(
            "OKAY", "", "(bootloader) partition-size:boot: 0x4000000\nunlock_ability: 0\n"
        )
        assertEquals(false, FastbootProtocol.parseUnlockAbility(response))
    }
}
