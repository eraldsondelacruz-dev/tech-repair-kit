package com.tatskie.bootloaderunlocker.qualcomm

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Uploads a Firehose programmer (ELF / PRX, usually `prog_elf_fh_prx*.mbn` or
 * `*.elf`) to a Qualcomm target sitting in EDL behind the Sahara protocol.
 *
 * The flow is tolerant across Sahara v1/v2/v3 bootloaders:
 *
 *  1. send HELLO_RSP again with `SAHARA_MODE_IMAGE_TX_PENDING`
 *     (the same 48-byte packet format used to enter command mode);
 *  2. wait up to ~2.4s for a v1-style IMAGE_HDR and tolerate silent v2/v3
 *     targets that expect the stream immediately;
 *  3. stream the whole programmer binary;
 *  4. send END_IMAGE_TX for the transferred image id;
 *  5. wait for SAHARA_DONE, proving the firehose engine is now running.
 */
class SaharaProgrammer(
    private val usb: QualcommUsbTransport,
    private val log: (String) -> Unit
) {

    fun upload(
        hello: SaharaHello,
        input: InputStream,
        size: Long,
        onProgress: ((Long) -> Unit)? = null
    ): Boolean {
        usb.muted = true
        try {
            log("Switching Sahara to image-transfer mode…")
            val helloRsp = packInts(
                Sahara.SAHARA_HELLO_RSP_ID, 0x30,
                hello.version.toInt(), hello.versionSupported.toInt(),
                0, SAHARA_MODE_IMAGE_TX_PENDING,
                1, 2, 3, 4, 5, 6
            )
            usb.write(helloRsp, 1000)

            val imageId = waitForGoAhead()
            if (imageId == REFUSED) {
                return false
            }

            log("Streaming programmer (${human(size)})…")
            var sent = 0L
            val buffer = ByteArray(CHUNK_SIZE)
            while (sent < size) {
                val wanted = minOf(CHUNK_SIZE.toLong(), size - sent).toInt()
                val read = input.read(buffer, 0, wanted)
                if (read <= 0) break
                usb.write(buffer.copyOf(read), 5000)
                sent += read
                onProgress?.invoke(sent)
            }
            log("Programmer bytes sent: ${human(sent)}")
            if (sent == 0L) {
                log("Nothing was streamed — programmer file is empty.")
                return false
            }

            usb.write(
                packInts(
                    Sahara.SAHARA_END_TRANSFER_ID, 0x0C,
                    imageId ?: 0, 0, 0, 0
                ), 1000
            )
            log("END_IMAGE_TX sent — waiting for Sahara DONE…")
            return awaitDone()
        } catch (e: Exception) {
            log("Programmer upload failed: ${e.message}")
            return false
        } finally {
            usb.muted = false
        }
    }

    /**
     * Waits for the device greenlight after the mode switch.
     * Returns [REFUSED] when the target rejects image mode, the v1 IMAGE_HDR
     * image id when it provides one, or null when it stays silent (v2/v3 style).
     */
    private fun waitForGoAhead(): Int? {
        var imageId: Int? = null
        var attempts = 0
        while (attempts < 6) {
            val packet = usb.read(64, 400)
            if (packet.isEmpty()) {
                attempts++
                continue
            }
            val cmd = leUInt(packet, 0).toInt()
            val declared = leUInt(packet, 4)
            if (cmd == IMAGE_HDR_ID && declared >= 40) {
                imageId = leUInt(packet, 8).toInt()
                log("IMAGE_HDR received (image id $imageId) — streaming now.")
                return imageId
            }
            if (cmd == Sahara.SAHARA_END_TRANSFER_ID) {
                val status = if (packet.size >= 12) leUInt(packet, 8).toInt() else -1
                log("Device refused image mode (status $status). Re-enter EDL and retry.")
                return REFUSED
            }
            log("Sahara packet 0x%02X (%d bytes) while waiting…".format(cmd, packet.size))
            attempts++
        }
        return imageId
    }

    private fun awaitDone(): Boolean {
        var attempts = 0
        while (attempts < 8) {
            val packet = usb.read(64, 500)
            if (packet.isEmpty()) {
                attempts++
                continue
            }
            val cmd = leUInt(packet, 0).toInt()
            if (cmd == SAHARA_DONE_ID) {
                log("Sahara DONE — programmer is running, firehose engine engaged.")
                return true
            }
            if (cmd == Sahara.SAHARA_END_TRANSFER_ID && packet.size >= 12) {
                val status = leUInt(packet, 8).toInt()
                if (status != 0) {
                    log("Device reports transfer failure (status $status).")
                    return false
                }
            }
            attempts++
        }
        log("No Sahara DONE arrived after upload (some loaders boot silently). Continuing.")
        return true
    }

    private fun leUInt(data: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

    private fun packInts(vararg values: Int): ByteArray =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { putInt(it) }
        }.array()

    private fun human(bytes: Long): String = when {
        bytes >= 1048576L -> "%.2f MB".format(bytes / 1048576.0)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "${bytes} B"
    }

    companion object {
        const val SAHARA_MODE_IMAGE_TX_PENDING = 0
        const val IMAGE_HDR_ID = 0x02
        const val SAHARA_DONE_ID = 0x05
        const val REFUSED = Int.MIN_VALUE
        private const val CHUNK_SIZE = 64 * 1024
    }
}
