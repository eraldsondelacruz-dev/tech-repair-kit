package com.tatskie.bootloaderunlocker.qualcomm

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SaharaHello(
    val version: Long,
    val versionSupported: Long,
    val cmdPacketLength: Long,
    val mode: Int,
    val modeName: String
)

data class SaharaInfo(
    val serial: Long?,
    val hwId: Long?,
    val pkhash: String?
)

class Sahara(
    private val usb: QualcommUsbTransport,
    private val log: (String) -> Unit
) {

    fun readHello(timeout: Int = 3000): SaharaHello? {
        usb.muted = true
        try {
            var data = usb.read(HELLO_SIZE, timeout)
            if (data.size >= HEADER_SIZE) {
                val declaredLength = leUInt(data, 4).toInt()
                val expected = maxOf(declaredLength, HELLO_SIZE)
                if (data.size < expected) {
                    data += usb.read(expected - data.size, timeout)
                }
            }
            if (data.size < HEADER_SIZE) {
                log("No Sahara response (received ${data.size} bytes)")
                return null
            }
            val hello = parseHello(data)
            if (hello == null) {
                log("Could not parse Sahara HELLO (received ${data.size} bytes)")
            }
            return hello
        } finally {
            usb.muted = false
        }
    }

    /**
     * Puts the Sahara target into command mode and reads its persistent identifiers
     * (serial number, MSM HW ID and OEM public-key hash) using EXECUTE commands.
     *
     * These are read-only identification requests; no loader is uploaded and nothing
     * is written to the device's storage.
     */
    fun readInfo(hello: SaharaHello, timeout: Int = 800): SaharaInfo? {
        usb.muted = true
        try {
            val helloRsp = packInts(
                SAHARA_HELLO_RSP_ID, 0x30,
                hello.version.toInt(), hello.versionSupported.toInt(),
                0, SAHARA_MODE_COMMAND,
                1, 2, 3, 4, 5, 6
            )
            usb.write(helloRsp, timeout)

            val header = readExact(HEADER_SIZE, timeout)
            if (header.size < HEADER_SIZE) {
                log("No response to command-mode request")
                return null
            }
            val cmd = leUInt(header, 0).toInt()
            val len = leUInt(header, 4).toInt()
            if (len > HEADER_SIZE) {
                readExact(len - HEADER_SIZE, timeout)
            }
            if (cmd == SAHARA_END_TRANSFER_ID) {
                log("Device refused command mode")
                return null
            }
            if (cmd != SAHARA_CMD_READY_ID) {
                log("Unexpected response 0x%02X to command mode".format(cmd))
                return null
            }

            val serial = execute(EXEC_CMD_SERIAL_NUM_READ, timeout)?.let { leLong(it) }
            val hwId = execute(EXEC_CMD_MSM_HW_ID_READ, timeout)?.let { leLong(it) }
            val pkhash = execute(EXEC_CMD_OEM_PK_HASH_READ, timeout)?.let { hex(it) }
            return SaharaInfo(serial, hwId, pkhash)
        } catch (e: Exception) {
            log("Identifier read failed: ${e.message}")
            return null
        } finally {
            usb.muted = false
        }
    }

    private fun execute(mcmd: Int, timeout: Int): ByteArray? {
        usb.write(packInts(SAHARA_EXECUTE_REQ_ID, 0xC, mcmd), timeout)
        val rsp = readExact(EXECUTE_RSP_SIZE, timeout)
        if (rsp.size < EXECUTE_RSP_SIZE) {
            log("EXECUTE 0x%02X: no response".format(mcmd))
            return null
        }
        val cmd = leUInt(rsp, 0).toInt()
        if (cmd != SAHARA_EXECUTE_RSP_ID) {
            log("EXECUTE 0x%02X: unexpected 0x%02X".format(mcmd, cmd))
            return null
        }
        val dataLen = leUInt(rsp, 12).toInt()
        usb.write(packInts(SAHARA_EXECUTE_DATA_ID, 0xC, mcmd), timeout)
        if (dataLen <= 0) return ByteArray(0)
        return readExact(dataLen, timeout)
    }

    private fun readExact(length: Int, timeout: Int): ByteArray {
        val out = ByteArray(length)
        var offset = 0
        var emptyReads = 0
        while (offset < length && emptyReads < 3) {
            val chunk = usb.read(length - offset, timeout)
            if (chunk.isEmpty()) {
                emptyReads++
                continue
            }
            emptyReads = 0
            System.arraycopy(chunk, 0, out, offset, chunk.size)
            offset += chunk.size
        }
        return out.copyOf(offset)
    }

    companion object {
        const val SAHARA_HELLO_ID = 0x01
        const val SAHARA_HELLO_RSP_ID = 0x02
        const val SAHARA_END_TRANSFER_ID = 0x04
        const val SAHARA_CMD_READY_ID = 0x0B
        const val SAHARA_EXECUTE_REQ_ID = 0x0D
        const val SAHARA_EXECUTE_RSP_ID = 0x0E
        const val SAHARA_EXECUTE_DATA_ID = 0x0F
        const val SAHARA_MODE_COMMAND = 3

        const val EXEC_CMD_SERIAL_NUM_READ = 0x01
        const val EXEC_CMD_MSM_HW_ID_READ = 0x02
        const val EXEC_CMD_OEM_PK_HASH_READ = 0x03

        private const val HEADER_SIZE = 8
        private const val HELLO_SIZE = 48
        private const val EXECUTE_RSP_SIZE = 16

        fun parseHello(data: ByteArray): SaharaHello? {
            if (data.size < HELLO_SIZE) return null
            if (leUInt(data, 0) != SAHARA_HELLO_ID.toLong()) return null

            val version = leUInt(data, 8)
            val versionSupported = leUInt(data, 12)
            val cmdPacketLength = leUInt(data, 16)
            val mode = leUInt(data, 20).toInt()
            return SaharaHello(version, versionSupported, cmdPacketLength, mode, modeName(mode))
        }

        private fun leUInt(data: ByteArray, offset: Int): Long =
            ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

        private fun leLong(data: ByteArray): Long {
            var value = 0L
            for (i in 0 until minOf(data.size, 8)) {
                value = value or ((data[i].toLong() and 0xFF) shl (8 * i))
            }
            return value
        }

        private fun hex(data: ByteArray): String =
            data.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        private fun packInts(vararg values: Int): ByteArray {
            val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { buffer.putInt(it) }
            return buffer.array()
        }

        fun modeName(mode: Int): String = when (mode) {
            0 -> "Image transfer pending (EDL)"
            1 -> "Image transfer complete"
            2 -> "Memory debug"
            3 -> "Command mode"
            else -> "Unknown ($mode)"
        }
    }
}
