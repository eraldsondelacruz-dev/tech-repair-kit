package com.tatskie.bootloaderunlocker.qualcomm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SaharaTest {

    private fun helloPacket(
        command: Int = 0x01,
        version: Int = 2,
        versionSupported: Int = 1,
        cmdPacketLength: Int = 0,
        mode: Int = 0
    ): ByteArray {
        val buffer = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(command)
        buffer.putInt(48)
        buffer.putInt(version)
        buffer.putInt(versionSupported)
        buffer.putInt(cmdPacketLength)
        buffer.putInt(mode)
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.putInt(0)
        return buffer.array()
    }

    @Test
    fun parsesEdlHello() {
        val hello = Sahara.parseHello(helloPacket(version = 2, versionSupported = 1, mode = 0))
        assertEquals(2L, hello!!.version)
        assertEquals(1L, hello.versionSupported)
        assertEquals(0, hello.mode)
        assertEquals("Image transfer pending (EDL)", hello.modeName)
    }

    @Test
    fun parsesCommandMode() {
        val hello = Sahara.parseHello(helloPacket(mode = 3))
        assertEquals(3, hello!!.mode)
        assertEquals("Command mode", hello.modeName)
    }

    @Test
    fun rejectsWrongCommand() {
        assertNull(Sahara.parseHello(helloPacket(command = 0x02)))
    }

    @Test
    fun rejectsShortPacket() {
        assertNull(Sahara.parseHello(ByteArray(16)))
    }
}
