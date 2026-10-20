package com.tatskie.bootloaderunlocker.payload

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.InputStream
import java.io.OutputStream

class UnsupportedOperationException(message: String) : java.io.IOException(message)

class PayloadExtractor(
    private val source: SeekableSource,
    private val payload: ParsedPayload
) {

    private val blobOffset = payload.header.blobOffset
    private val blockSize = payload.manifest.blockSize

    fun extract(
        partition: PartitionUpdate,
        out: OutputStream,
        onProgress: (Long) -> Unit = {}
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var written = 0L

        for (op in partition.operations) {
            written += when (op.type) {
                OpType.REPLACE -> copyRaw(op, out, buffer)
                OpType.REPLACE_XZ -> copyDecompressed(op, out, buffer) { XZInputStream(it) }
                OpType.REPLACE_BZ -> copyDecompressed(op, out, buffer) {
                    BZip2CompressorInputStream(it, true)
                }
                OpType.ZERO, OpType.DISCARD -> writeZeros(op, out)
                else -> throw UnsupportedOperationException(
                    "${op.type.name} is not supported for ${partition.name}: " +
                        "this looks like an incremental (delta) OTA. Use a full OTA payload."
                )
            }
            onProgress(written)
        }
    }

    private fun copyRaw(op: InstallOperation, out: OutputStream, buffer: ByteArray): Long {
        source.position(blobOffset + op.dataOffset)
        var remaining = op.dataLength
        var total = 0L
        while (remaining > 0) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            source.readFully(buffer, 0, toRead)
            out.write(buffer, 0, toRead)
            remaining -= toRead
            total += toRead
        }
        return total
    }

    private fun copyDecompressed(
        op: InstallOperation,
        out: OutputStream,
        buffer: ByteArray,
        wrap: (InputStream) -> InputStream
    ): Long {
        val raw = source.limitedStream(blobOffset + op.dataOffset, op.dataLength)
        var total = 0L
        wrap(raw).use { decompressed ->
            while (true) {
                val n = decompressed.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                total += n
            }
        }
        return total
    }

    private fun writeZeros(op: InstallOperation, out: OutputStream): Long {
        val total = op.dstExtents.sumOf { it.numBlocks } * blockSize
        if (total <= 0L) return 0L
        val zeros = ByteArray(ZERO_CHUNK)
        var remaining = total
        while (remaining > 0) {
            val n = minOf(zeros.size.toLong(), remaining).toInt()
            out.write(zeros, 0, n)
            remaining -= n
        }
        return total
    }

    companion object {
        private const val BUFFER_SIZE = 256 * 1024
        private const val ZERO_CHUNK = 64 * 1024
    }
}
