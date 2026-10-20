package com.tatskie.bootloaderunlocker.payload

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

data class PayloadHeader(
    val version: Long,
    val manifestSize: Long,
    val metadataSignatureSize: Long,
    val blobOffset: Long
)

data class ParsedPayload(
    val header: PayloadHeader,
    val manifest: PayloadManifest
)

data class PayloadManifest(
    val blockSize: Long,
    val partitions: List<PartitionUpdate>
)

data class PartitionUpdate(
    val name: String,
    val operations: List<InstallOperation>,
    val newPartitionSize: Long
)

data class InstallOperation(
    val type: OpType,
    val dataOffset: Long,
    val dataLength: Long,
    val srcExtents: List<Extent>,
    val dstExtents: List<Extent>
)

data class Extent(val startBlock: Long, val numBlocks: Long)

enum class OpType(val id: Int) {
    REPLACE(0),
    REPLACE_BZ(1),
    MOVE(2),
    BSDIFF(3),
    SOURCE_COPY(4),
    SOURCE_BSDIFF(5),
    ZERO(6),
    DISCARD(7),
    REPLACE_XZ(8),
    PUFFDIFF(9),
    BROTLI_BSDIFF(10),
    ZUCCHINI(11),
    LZ4DIFF_BSDIFF(12),
    LZ4DIFF_PUFFDIFF(13),
    UNKNOWN(-1);

    companion object {
        fun fromId(id: Long): OpType = entries.firstOrNull { it.id == id.toInt() } ?: UNKNOWN
    }
}

class SeekableSource(private val channel: FileChannel) {

    fun position(offset: Long) {
        channel.position(offset)
    }

    fun position(): Long = channel.position()

    fun readFully(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
        var read = 0
        while (read < length) {
            val n = channel.read(ByteBuffer.wrap(buffer, offset + read, length - read))
            if (n < 0) throw EOFException("Unexpected end of payload")
            read += n
        }
    }

    fun readLongBE(): Long {
        val b = ByteArray(8)
        readFully(b)
        return ByteBuffer.wrap(b).long
    }

    fun readIntBE(): Long {
        val b = ByteArray(4)
        readFully(b)
        return (ByteBuffer.wrap(b).int.toLong() and 0xFFFFFFFFL)
    }

    fun limitedStream(start: Long, length: Long): InputStream {
        position(start)
        return object : InputStream() {
            private var remaining = length
            private val single = ByteArray(1)

            override fun read(): Int {
                val n = read(single, 0, 1)
                return if (n < 0) -1 else single[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0L) return -1
                val toRead = minOf(len.toLong(), remaining).toInt()
                val n = channel.read(ByteBuffer.wrap(b, off, toRead))
                if (n < 0) return -1
                remaining -= n
                return n
            }
        }
    }
}

internal class ProtoReader(
    private val data: ByteArray,
    private var pos: Int = 0,
    private val end: Int = data.size
) {

    fun atEnd(): Boolean = pos >= end

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= end) throw EOFException("Truncated varint")
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b.toLong() and 0x7FL) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw IllegalStateException("Varint is too long")
        }
    }

    fun readTag(): Int = readVarint().toInt()

    fun readBytes(): ByteArray {
        val len = readVarint().toInt()
        if (len < 0 || pos + len > end) throw EOFException("Truncated length-delimited field")
        val out = data.copyOfRange(pos, pos + len)
        pos += len
        return out
    }

    fun readString(): String = String(readBytes(), Charsets.UTF_8)

    fun skip(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> pos += 8
            2 -> pos += readVarint().toInt()
            5 -> pos += 4
            else -> throw IllegalStateException("Unsupported wire type $wireType")
        }
        if (pos > end) throw EOFException("Truncated message")
    }
}

object PayloadParser {

    private val MAGIC = byteArrayOf(
        'C'.code.toByte(), 'r'.code.toByte(), 'A'.code.toByte(), 'U'.code.toByte()
    )

    private const val MAX_MANIFEST_SIZE = 64L * 1024 * 1024

    fun parse(source: SeekableSource): ParsedPayload {
        source.position(0)

        val magic = ByteArray(4)
        source.readFully(magic)
        if (!magic.contentEquals(MAGIC)) {
            throw IllegalArgumentException("This file is not an Android OTA payload.bin (bad magic)")
        }

        val version = source.readLongBE()
        val manifestSize = source.readLongBE()
        val metadataSignatureSize = if (version >= 2) source.readIntBE() else 0L

        if (manifestSize in 1..MAX_MANIFEST_SIZE) {
            // ok
        } else {
            throw IllegalArgumentException("Invalid payload manifest size: $manifestSize")
        }

        val manifestBytes = ByteArray(manifestSize.toInt())
        source.readFully(manifestBytes)

        val blobOffset = source.position() + metadataSignatureSize
        val manifest = parseManifest(manifestBytes)

        return ParsedPayload(
            PayloadHeader(version, manifestSize, metadataSignatureSize, blobOffset),
            manifest
        )
    }

    private fun parseManifest(data: ByteArray): PayloadManifest {
        val r = ProtoReader(data)
        var blockSize = 4096L
        val partitions = ArrayList<PartitionUpdate>()

        while (!r.atEnd()) {
            val tag = r.readTag()
            val field = tag ushr 3
            val wire = tag and 7
            when (field) {
                3 -> blockSize = r.readVarint()
                13 -> partitions.add(parsePartition(ProtoReader(r.readBytes())))
                else -> r.skip(wire)
            }
        }
        return PayloadManifest(blockSize, partitions)
    }

    private fun parsePartition(r: ProtoReader): PartitionUpdate {
        var name = ""
        var size = 0L
        val ops = ArrayList<InstallOperation>()

        while (!r.atEnd()) {
            val tag = r.readTag()
            val field = tag ushr 3
            val wire = tag and 7
            when (field) {
                1 -> name = r.readString()
                5 -> ops.add(parseOperation(ProtoReader(r.readBytes())))
                7 -> size = parsePartitionInfoSize(r.readBytes())
                else -> r.skip(wire)
            }
        }
        return PartitionUpdate(name, ops, size)
    }

    private fun parseOperation(r: ProtoReader): InstallOperation {
        var type = OpType.UNKNOWN
        var dataOffset = 0L
        var dataLength = 0L
        val src = ArrayList<Extent>()
        val dst = ArrayList<Extent>()

        while (!r.atEnd()) {
            val tag = r.readTag()
            val field = tag ushr 3
            val wire = tag and 7
            when (field) {
                1 -> type = OpType.fromId(r.readVarint())
                2 -> dataOffset = r.readVarint()
                3 -> dataLength = r.readVarint()
                4 -> src.add(parseExtent(ProtoReader(r.readBytes())))
                6 -> dst.add(parseExtent(ProtoReader(r.readBytes())))
                else -> r.skip(wire)
            }
        }
        return InstallOperation(type, dataOffset, dataLength, src, dst)
    }

    private fun parseExtent(r: ProtoReader): Extent {
        var start = 0L
        var count = 0L
        while (!r.atEnd()) {
            val tag = r.readTag()
            val field = tag ushr 3
            val wire = tag and 7
            when (field) {
                1 -> start = r.readVarint()
                2 -> count = r.readVarint()
                else -> r.skip(wire)
            }
        }
        return Extent(start, count)
    }

    private fun parsePartitionInfoSize(bytes: ByteArray): Long {
        val r = ProtoReader(bytes)
        while (!r.atEnd()) {
            val tag = r.readTag()
            val field = tag ushr 3
            val wire = tag and 7
            if (field == 1) return r.readVarint() else r.skip(wire)
        }
        return 0L
    }
}
