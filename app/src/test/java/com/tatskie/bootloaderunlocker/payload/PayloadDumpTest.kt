package com.tatskie.bootloaderunlocker.payload

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

class PayloadDumpTest {

    private fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(b)
                break
            }
            out.write(b or 0x80)
        }
        return out.toByteArray()
    }

    private fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    private fun lengthDelimited(field: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag(field, 2))
        out.write(varint(payload.size.toLong()))
        out.write(payload)
        return out.toByteArray()
    }

    private fun varintField(field: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag(field, 0))
        out.write(varint(value))
        return out.toByteArray()
    }

    private fun extent(startBlock: Long, numBlocks: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(varintField(1, startBlock))
        out.write(varintField(2, numBlocks))
        return out.toByteArray()
    }

    private fun operation(
        type: Long,
        dataOffset: Long,
        dataLength: Long,
        dstBlocks: Long?
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(varintField(1, type))
        out.write(varintField(2, dataOffset))
        out.write(varintField(3, dataLength))
        if (dstBlocks != null) {
            out.write(lengthDelimited(6, extent(0, dstBlocks)))
        }
        return out.toByteArray()
    }

    private fun partition(name: String, ops: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(lengthDelimited(1, name.toByteArray(Charsets.UTF_8)))
        ops.forEach { out.write(lengthDelimited(5, it)) }
        return out.toByteArray()
    }

    private fun manifest(partitions: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(varintField(3, 4096))
        partitions.forEach { out.write(lengthDelimited(13, it)) }
        return out.toByteArray()
    }

    private fun xz(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        XZOutputStream(out, LZMA2Options()).use { it.write(data) }
        return out.toByteArray()
    }

    private fun beLong(value: Long): ByteArray {
        val b = ByteArray(8)
        for (i in 0 until 8) b[i] = (value ushr (8 * (7 - i))).toByte()
        return b
    }

    private fun beInt(value: Int): ByteArray {
        val b = ByteArray(4)
        for (i in 0 until 4) b[i] = (value ushr (8 * (3 - i))).toByte()
        return b
    }

    @Test
    fun parsesAndExtractsMultipleOperationTypes() {
        val rawData = ByteArray(70000) { (it % 251).toByte() }
        val xzData = ByteArray(150000) { (it * 31 % 253).toByte() }
        val xzBlob = xz(xzData)

        val blob = ByteArrayOutputStream()
        val xzOffset = 0L
        blob.write(xzBlob)
        val rawOffset = blob.size().toLong()
        blob.write(rawData)
        val blobBytes = blob.toByteArray()

        val bootOps = listOf(
            operation(8, xzOffset, xzBlob.size.toLong(), null),
            operation(0, rawOffset, rawData.size.toLong(), null),
            operation(6, 0, 0, 1)
        )
        val boot = partition("boot", bootOps)
        val manifestBytes = manifest(listOf(boot))

        val payload = ByteArrayOutputStream()
        payload.write("CrAU".toByteArray(Charsets.US_ASCII))
        payload.write(beLong(2))
        payload.write(beLong(manifestBytes.size.toLong()))
        payload.write(beInt(0))
        payload.write(manifestBytes)
        payload.write(blobBytes)

        val tmp = File.createTempFile("payload", ".bin")
        tmp.writeBytes(payload.toByteArray())

        RandomAccessFile(tmp, "r").use { raf ->
            val parsed = PayloadParser.parse(SeekableSource(raf.channel))
            assertEquals(4096L, parsed.manifest.blockSize)
            assertEquals(1, parsed.manifest.partitions.size)
            assertEquals("boot", parsed.manifest.partitions[0].name)

            val outBytes = ByteArrayOutputStream()
            PayloadExtractor(SeekableSource(raf.channel), parsed)
                .extract(parsed.manifest.partitions[0], outBytes)

            val expected = ByteArrayOutputStream()
            expected.write(xzData)
            expected.write(rawData)
            expected.write(ByteArray(4096))

            assertArrayEquals(expected.toByteArray(), outBytes.toByteArray())
        }

        tmp.delete()
    }

    @Test
    fun rejectsBadMagic() {
        val tmp = File.createTempFile("payload", ".bin")
        tmp.writeBytes(ByteArray(32))
        try {
            RandomAccessFile(tmp, "r").use { raf ->
                PayloadParser.parse(SeekableSource(raf.channel))
            }
            throw AssertionError("Expected failure")
        } catch (_: IllegalArgumentException) {
        } finally {
            tmp.delete()
        }
    }
}
