package com.tatskie.bootloaderunlocker.qualcomm

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Speaks the classic Firehose XML-over-bulk protocol with a programmer that was
 * booted through Sahara. Supports the frames a repair tech needs:
 *
 *   nop        – sanity ping (also satisfies some programmers that must first see text)
 *   configure  – sets memory name / page size / payload size
 *   program    – writes a raw image at a given start sector
 *   erase      – erases a sector range
 *   readback   – streams a sector range out of the device (backup discipline!)
 *   gptEntries – decodes the on-disk GPT into named partitions
 *   reset      – tells the device to reboot out of EDL
 *
 * All frames are wrapped with newlines exactly the way the original Qualcomm
 * firehose host tools do the handshake.
 */
class FirehoseSession(
    private val usb: QualcommUsbTransport,
    private val log: (String) -> Unit
) {

    var memoryName: String = "eMMC"
    var sectorSize: Int = DEFAULT_SECTOR_SIZE
    var currentPageSize: Int = DEFAULT_PAGE_SIZE

    /** One line of the on-disk GPT: partition label plus its sector footprint. */
    data class GptEntry(
        val name: String,
        val firstLba: Long,
        val numLba: Long
    )


    /** Sanity ping: proves the programmer prints firehose XML. */
    fun nop(): Boolean {
        val response = sendFrame("<data><nop nop=\"on\" /></data>", 6_000)
        val ok = response.contains("value=\"ACK\"", ignoreCase = true)
        if (!ok) log("NOP response ignored (programmer may be stuck).")
        return ok
    }

    /**
     * Configures the storage backend on the programmer (memory name, page size,
     * max payload size). Call once after booting before erasing/programming.
     */
    fun configure(pageSize: Int = DEFAULT_PAGE_SIZE, maxPayload: Int = DEFAULT_MAX_PAYLOAD): Boolean {
        currentPageSize = pageSize
        val frame = "<data><configure " +
            "MaxPayloadSizeToTargetInBytes=\"$maxPayload\" " +
            "MemoryName=\"${escape(memoryName)}\" " +
            "PageSize=\"$pageSize\" " +
            "SkipStorageInit=\"0\" " +
            "SkipWrite=\"0\" " +
            "ZLPAwareHost=\"1\" " +
            "ZLPPagesPerCG=\"1\" " +
            "ValidateDirectoryBuffer=\"0\" />" +
            "</data>"
        val response = sendFrame(frame, 10_000)
        val ok = response.contains("value=\"ACK\"", ignoreCase = true)
        if (!ok) log("configure was not ACKed: ${trim(response)}")
        return ok
    }

    /** Erases a sector range on the given physical partition. */
    fun erase(startSector: Long, numSectors: Long, physical: Int): Boolean {
        val frame = "<data><erase " +
            "start_sector=\"$startSector\" " +
            "num_partition_sectors=\"$numSectors\" " +
            "physical_partition_number=\"$physical\" />" +
            "</data>"
        val response = sendFrame(frame, 30_000)
        val ok = response.contains("value=\"ACK\"", ignoreCase = true)
        if (!ok) log("erase was not ACKed: ${trim(response)}")
        return ok
    }

    /**
     * Writes a raw image at the sector [startSector]. Streams the bytes right
     * after the program frame, then waits for the device ACK — which can take
     * minutes on large images, so the idle window is generous.
     */
    fun program(
        startSector: Long,
        numSectors: Long,
        physical: Int,
        input: InputStream,
        totalSize: Long,
        onProgress: ((Long) -> Unit)? = null
    ): Boolean {
        val frame = "<data><program " +
            "SECTOR_SIZE_IN_BYTES=\"$sectorSize\" " +
            "num_partition_sectors=\"$numSectors\" " +
            "physical_partition_number=\"$physical\" " +
            "start_sector=\"$startSector\" />" +
            "</data>"
        usb.muted = true // binary streaming must not flood the hex logger
        try {
            sendFrameQuiet(frame, 8_000)
            var sent = 0L
            val buffer = ByteArray(CHUNK_SIZE)
            while (sent < totalSize) {
                val read = input.read(buffer, 0, minOf(CHUNK_SIZE.toLong(), totalSize - sent).toInt())
                if (read <= 0) break
                usb.write(buffer.copyOf(read), 5000)
                sent += read
                onProgress?.invoke(sent)
            }
            log("Image data sent: ${human(sent)}. Waiting for ACK…")
            val response = awaitResponse(120_000)
            val ok = response.contains("value=\"ACK\"", ignoreCase = true)
            if (!ok) log("program ACK missing: ${trim(response)}")
            return ok
        } catch (e: Exception) {
            log("Program failed: ${e.message}")
            return false
        } finally {
            usb.muted = false
        }
    }

    /** Asks the device to leave EDL and reboot normal mode. */
    fun reset(): Boolean {
        sendFrame("<data><reset value=\"reset\" /></data>", 6_000)
        log("Reset requested — phone should reboot out of EDL now.")
        return true
    }

    /**
     * Dumps a sector range out of the device into [output]: the tech's backup
     * discipline over EDL. After readback data the classic host tool ACKs the
     * command, which we do too; text frames interleaved before the binary are
     * routed into the log.
     */
    fun readback(
        startSector: Long,
        numSectors: Long,
        physical: Int,
        output: OutputStream,
        onProgress: ((Long) -> Unit)? = null
    ): Boolean {
        val expected = numSectors * sectorSize
        val frame = "<data><readback " +
            "SECTOR_SIZE_IN_BYTES=\"$sectorSize\" " +
            "num_partition_sectors=\"$numSectors\" " +
            "physical_partition_number=\"$physical\" " +
            "start_sector=\"$startSector\" />" +
            "</data>"
        usb.muted = true
        var received = 0L
        var silentPolls = 0
        try {
            usb.write(("\n" + frame + "\n").toByteArray(Charsets.US_ASCII), 1000)
            val buffer = ByteArray(CHUNK_SIZE)
            while (received < expected && silentPolls < 20) {
                val wanted = minOf(CHUNK_SIZE.toLong(), expected - received).toInt()
                val chunk = usb.read(wanted, 500)
                if (chunk.isEmpty()) {
                    silentPolls++
                    continue
                }
                silentPolls = 0
                val first = chunk[0]
                val looksText = first == '<'.code.toByte() ||
                    first == '\n'.code.toByte() ||
                    first == '\r'.code.toByte() ||
                    chunk.contentStartsWithAscii()
                if (looksText && received == 0L) {
                    val text = sanitize(chunk)
                    text.lineSequence().filter { it.isNotBlank() }.forEach { log(it) }
                    // overflow text can carry the tail of this chunk: tolerate.
                    continue
                }
                if (looksText && received > 0L) {
                    // Stray trailing log frame after the payload — ack it onwards.
                    continue
                }
                output.write(chunk)
                received += chunk.size
                onProgress?.invoke(received)
            }
            output.flush()
            if (received < expected) {
                log("Readback incomplete: got ${human(received)} of ${human(expected)}.")
                return false
            }
            onProgress?.invoke(received)
            log("Readback complete: ${human(received)}. Sending command ACK…")
            usb.write(
                ("\n" + "<data><ack rawerror=\"service-unavailable\" /></data>" + "\n")
                    .toByteArray(Charsets.US_ASCII), 1000
            )
            var drainPolls = 0
            while (drainPolls++ < 8) {
                val chunk = usb.read(4096, 250)
                if (chunk.isEmpty()) break
                textLineDrain(chunk)
            }
            return true
        } catch (e: Exception) {
            log("Readback failed: ${e.message}")
            return false
        } finally {
            usb.muted = false
        }
    }

    private fun textLineDrain(data: ByteArray) {
        val text = sanitize(data)
        text.lineSequence().filter { it.isNotBlank() }.forEach { log(it) }
    }

    private fun ByteArray.contentStartsWithAscii(): Boolean {
        if (size < 4) return false
        val probe = String(this, 0, 4, Charsets.US_ASCII)
        return probe.startsWith("<data") || probe.startsWith("<ack") ||
            probe.startsWith("<rsp") || probe.startsWith("<log")
    }

    // ---------------------------------------------------------------------
    // frame plumbing
    // ---------------------------------------------------------------------

    /** Sends an XML frame and collects the response text (logs every fragment). */
    private fun sendFrame(xml: String, idleMs: Int): String {
        usb.muted = false
        usb.write(("\n" + xml + "\n").toByteArray(Charsets.US_ASCII), 1000)
        return awaitResponse(idleMs)
    }

    private fun sendFrameQuiet(xml: String, idleMs: Int): String {
        usb.write(("\n" + xml + "\n").toByteArray(Charsets.US_ASCII), 1000)
        return awaitResponse(idleMs)
    }

    private fun awaitResponse(idleMs: Int): String {
        val sb = StringBuilder()
        usb.muted = true
        try {
            var silentMs = 0
            var polls = 0
            while (polls++ < 20_000) {
                val chunk = usb.read(4096, 250)
                if (chunk.isEmpty()) {
                    silentMs += 250
                    if (silentMs >= idleMs) break
                    continue
                }
                silentMs = 0
                val text = sanitize(chunk)
                sb.append(text)
                text.lineSequence().filter { it.isNotBlank() }.forEach { log(it) }
                if (sb.contains("</data>")) break
            }
        } finally {
            usb.muted = false
        }
        return sb.toString()
    }

    private fun sanitize(data: ByteArray): String {
        val raw = String(data, Charsets.US_ASCII)
        return raw.filter { it >= ' ' || it == '\n' || it == '\r' || it == '\t' }
    }

    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    private fun trim(text: String, max: Int = 240): String {
        val t = text.trim()
        return if (t.length <= max) t else "…" + t.takeLast(max)
    }

    private fun human(bytes: Long): String = when {
        bytes >= 1048576L -> "%.2f MB".format(bytes / 1048576.0)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "${bytes} B"
    }

    /**
     * Reads the GPT partition table from LBA1 and hands every entry back as
     * [GptEntry]. Works on any storage whose disk sector size the configure
     * step reported. Returns null when the table is unreadable or laid out
     * differently than the standard (entry LBA must follow the header).
     */
    fun gptEntries(): List<GptEntry>? {
        val out = ByteArrayOutputStream()
        val ok = readback(1, GPT_TABLE_SECTORS, 0, out)
        if (!ok) {
            log("GPT readback failed — cannot enumerate partitions.")
            return null
        }
        val buf = out.toByteArray()
        if (buf.size < 4 || String(buf, Charsets.US_ASCII).startsWith("EFI PART").not()) {
            log("GPT header signature not found.")
            return null
        }
        if (buf.size < 96) {
            log("GPT header truncated.")
            return null
        }
        val entryLba = leLong(buf, 72)
        val entryCount = leInt(buf, 80)
        val entrySize = leInt(buf, 84)
        if (entryLba <= 1 || entrySize !in 16..512) return null
        val tableOffset = ((entryLba - 1) * sectorSize).toLong()
        if (tableOffset < 0 || tableOffset + entryCount.toLong() * entrySize > buf.size) {
            log("Partition entries outside the readback window (entryLba=$entryLba).")
            return null
        }
        val entries = ArrayList<GptEntry>()
        for (i in 0 until entryCount) {
            val at = (tableOffset + i.toLong() * entrySize).toInt()
            if (at + entrySize > buf.size) break
            var empty = true
            for (j in 0 until 16) {
                if (buf[at + j] != 0.toByte()) {
                    empty = false
                    break
                }
            }
            if (empty) break
            val first = leLong(buf, at + 32)
            val last = leLong(buf, at + 40)
            val name = String(buf, at + 56, 72, Charsets.UTF_16LE).trim('\u0000').trim()
            if (name.isNotBlank()) {
                entries.add(GptEntry(name, first, last - first + 1))
            }
        }
        log("GPT extracted: ${entries.size} named partitions.")
        return entries
    }

    private fun leLong(buf: ByteArray, at: Int): Long =
        (buf[at].toLong() and 0xFF) or
            ((buf[at + 1].toLong() and 0xFF) shl 8) or
            ((buf[at + 2].toLong() and 0xFF) shl 16) or
            ((buf[at + 3].toLong() and 0xFF) shl 24) or
            ((buf[at + 4].toLong() and 0xFF) shl 32) or
            ((buf[at + 5].toLong() and 0xFF) shl 40) or
            ((buf[at + 6].toLong() and 0xFF) shl 48) or
            ((buf[at + 7].toLong() and 0xFF) shl 56)

    private fun leInt(buf: ByteArray, at: Int): Int =
        (buf[at].toInt() and 0xFF) or
            ((buf[at + 1].toInt() and 0xFF) shl 8) or
            ((buf[at + 2].toInt() and 0xFF) shl 16) or
            ((buf[at + 3].toInt() and 0xFF) shl 24)

    companion object {
        const val DEFAULT_SECTOR_SIZE = 512
        const val DEFAULT_PAGE_SIZE = 4096
        const val DEFAULT_MAX_PAYLOAD = 16384
        private const val CHUNK_SIZE = 64 * 1024
        private const val GPT_TABLE_SECTORS = 34L
    }
}
