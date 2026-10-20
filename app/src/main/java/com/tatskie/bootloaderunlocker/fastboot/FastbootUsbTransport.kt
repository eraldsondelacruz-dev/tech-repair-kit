package com.tatskie.bootloaderunlocker.fastboot

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.InputStream

/**
 * Minimal fastboot client over USB-OTG. Implements the fastboot USB protocol
 * (interface class 0xFF / subclass 0x42 / protocol 0x03) so a phone can act as
 * the host that a technician would normally need a PC for.
 */
class FastbootUsbTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint
) {

    var logger: ((String) -> Unit)? = null

    private val packetSize = maxOf(endpointIn.maxPacketSize, 64)

    fun write(command: String) {
        val bytes = FastbootProtocol.commandBytes(command)
        val sent = connection.bulkTransfer(endpointOut, bytes, bytes.size, WRITE_TIMEOUT)
        logger?.invoke("TX> $command  (${if (sent < 0) "ERR" else "$sent bytes"})")
    }

    private fun readResponse(timeout: Int): FastbootResponse {
        val sb = StringBuilder()
        val buffer = ByteArray(packetSize)
        var guard = 0
        while (guard++ < 4096) {
            val received = connection.bulkTransfer(endpointIn, buffer, buffer.size, timeout)
            if (received <= 0) break
            sb.append(String(buffer, 0, received, Charsets.US_ASCII))
            if (received < buffer.size) break
        }
        val response = FastbootProtocol.parse(sb.toString())
        logger?.invoke("RX< ${response.status}${if (response.payload.isNotEmpty()) " ${response.payload}" else ""}")
        return response
    }

    /** Sends a plain command, gathering any INFO lines before the final status. */
    fun command(command: String): FastbootResponse {
        write(command)
        val info = StringBuilder()
        var guard = 0
        while (guard++ < 128) {
            val response = readResponse(READ_TIMEOUT)
            if (response.status == FastbootProtocol.STATUS_INFO) {
                info.append(response.payload).append('\n')
                continue
            }
            return response.copy(info = info.toString())
        }
        return FastbootResponse(FastbootProtocol.STATUS_FAIL, "", info.toString())
    }

    fun getVar(name: String): String? {
        val response = command("getvar:$name")
        val value = response.payload.trim()
        return if (value.isEmpty()) null else value
    }

    fun unlocked(): Boolean {
        val value = getVar("unlocked") ?: return false
        return value == "yes" || value == "true" || value == "1"
    }

    fun download(
        size: Long,
        input: InputStream,
        onProgress: ((Long) -> Unit)? = null
    ): FastbootResponse {
        val start = command("download:${FastbootProtocol.hexSize(size)}")
        if (!start.isData) return start
        FastbootProtocol.parseDataSize(start.payload)

        var sent = 0L
        val buffer = ByteArray(CHUNK_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            var offset = 0
            while (offset < read) {
                val portion = minOf(USB_CHUNK, read - offset)
                val written = connection.bulkTransfer(
                    endpointOut, buffer, offset, portion, WRITE_TIMEOUT
                )
                if (written < 0) {
                    return FastbootResponse(
                        FastbootProtocol.STATUS_FAIL, "", "USB write error at $sent/$size bytes"
                    )
                }
                offset += written
                sent += written
                onProgress?.invoke(sent)
            }
        }
        return readResponse(DATA_TIMEOUT)
    }

    fun flash(
        partition: String,
        size: Long,
        input: InputStream,
        onProgress: ((Long) -> Unit)? = null
    ): FastbootResponse {
        val download = download(size, input, onProgress)
        if (!download.okay) return download
        return command("flash:$partition")
    }

    /** Asks the bootloader whether unlocking is currently permitted (OEM unlock on/off). */
    fun getUnlockAbility(): FastbootResponse = command("flashing:get_unlock_ability")

    /** Dumps the full variable table so a technician can inspect the target. */
    fun getAllVars(): FastbootResponse = command("getvar:all")

    /**
     * Streams a partition back from the bootloader ("fetch:…", fastboot V2 devices only).
     * The raw partition bytes are written into [output]; afterwards the trailing
     * OKAY/FAIL status is read normally.
     */
    fun fetch(
        partition: String,
        output: java.io.OutputStream,
        onProgress: ((Long) -> Unit)? = null
    ): FastbootResponse {
        val request = command("fetch:$partition")
        if (!request.isData) return request
        val remaining = FastbootProtocol.parseDataSize(request.payload).toLong()
        if (remaining <= 0) {
            return FastbootResponse(FastbootProtocol.STATUS_FAIL, "", "no payload size in DATA")
        }
        if (remaining > Int.MAX_VALUE) {
            return FastbootResponse(
                FastbootProtocol.STATUS_FAIL, "",
                "partition too large for one-shot fetch ($remaining bytes)"
            )
        }
        val buffer = ByteArray(CHUNK_SIZE)
        var received = 0L
        var guard = 0
        while (received < remaining && guard < 100_000) {
            val wanted = minOf(remaining - received, buffer.size.toLong()).toInt()
            val chunk = connection.bulkTransfer(endpointIn, buffer, wanted, DATA_TIMEOUT)
            if (chunk < 0) {
                return FastbootResponse(
                    FastbootProtocol.STATUS_FAIL, "",
                    "USB read error at $received/$remaining bytes"
                )
            }
            if (chunk == 0) {
                guard++
                continue
            }
            output.write(buffer, 0, chunk)
            received += chunk
            guard = 0
            onProgress?.invoke(received)
        }
        output.flush()
        if (received < remaining) {
            return FastbootResponse(
                FastbootProtocol.STATUS_FAIL, "",
                "incomplete fetch: $received/$remaining bytes"
            )
        }
        onProgress?.invoke(received)
        return readResponse(DATA_TIMEOUT)
    }

    /** Runs an arbitrary fastboot command typed by the user. */
    fun runCommand(raw: String): FastbootResponse = command(raw.trim())

    fun flashingUnlock(): FastbootResponse = command("flashing:unlock")
    fun flashingUnlockCritical(): FastbootResponse = command("flashing:unlock_critical")
    fun flashingLock(): FastbootResponse = command("flashing:lock")
    fun oemUnlock(): FastbootResponse = command("oem:unlock")
    fun reboot(): FastbootResponse = command("reboot")
    fun rebootBootloader(): FastbootResponse = command("reboot-bootloader")

    fun close() {
        try {
            connection.releaseInterface(usbInterface)
        } catch (_: Exception) {
        }
        connection.close()
    }

    companion object {
        private const val WRITE_TIMEOUT = 5_000
        private const val READ_TIMEOUT = 5_000
        private const val DATA_TIMEOUT = 30_000
        private const val CHUNK_SIZE = 64 * 1024
        private const val USB_CHUNK = 16 * 1024

        fun isFastbootDevice(device: UsbDevice): Boolean {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (FastbootProtocol.isFastbootInterface(
                        iface.interfaceClass, iface.interfaceSubclass, iface.interfaceProtocol
                    )
                ) {
                    return true
                }
            }
            return false
        }

        fun open(manager: UsbManager, device: UsbDevice): FastbootUsbTransport? {
            val connection = manager.openDevice(device) ?: return null
            for (i in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(i)
                if (!FastbootProtocol.isFastbootInterface(
                        usbInterface.interfaceClass,
                        usbInterface.interfaceSubclass,
                        usbInterface.interfaceProtocol
                    )
                ) {
                    continue
                }
                var endpointIn: UsbEndpoint? = null
                var endpointOut: UsbEndpoint? = null
                for (j in 0 until usbInterface.endpointCount) {
                    val endpoint = usbInterface.getEndpoint(j)
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                            endpointIn = endpoint
                        } else {
                            endpointOut = endpoint
                        }
                    }
                }
                if (endpointIn != null && endpointOut != null &&
                    connection.claimInterface(usbInterface, true)
                ) {
                    return FastbootUsbTransport(connection, usbInterface, endpointIn, endpointOut)
                }
            }
            connection.close()
            return null
        }
    }
}
