package com.tatskie.bootloaderunlocker.mtk

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

class MtkUsbTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint
) {

    var logger: ((String) -> Unit)? = null
    var muted: Boolean = false

    private fun hex(data: ByteArray): String =
        data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    fun write(data: ByteArray, timeout: Int = 1000): Int {
        val sent = connection.bulkTransfer(endpointOut, data, data.size, timeout)
        if (!muted) logger?.invoke("TX> ${hex(data)}  (${if (sent < 0) "ERR" else "$sent bytes"})")
        return sent
    }

    fun read(length: Int, timeout: Int = 1000): ByteArray {
        val buffer = ByteArray(length)
        val received = connection.bulkTransfer(endpointIn, buffer, length, timeout)
        val result = if (received > 0) buffer.copyOf(received) else ByteArray(0)
        if (!muted) logger?.invoke("RX< ${hex(result)}  (${if (received < 0) "ERR" else "$received bytes"})")
        return result
    }

    fun drain(maxRounds: Int = 8) {
        repeat(maxRounds) {
            val buffer = ByteArray(64)
            val received = connection.bulkTransfer(endpointIn, buffer, buffer.size, 20)
            if (received <= 0) return
        }
    }

    fun close() {
        try {
            connection.releaseInterface(usbInterface)
        } catch (_: Exception) {
        }
        connection.close()
    }

    companion object {
        const val MTK_VENDOR_ID = 0x0E8D

        fun open(manager: UsbManager, device: UsbDevice): MtkUsbTransport? {
            val connection = manager.openDevice(device) ?: return null
            for (i in 0 until device.interfaceCount) {
                val usbInterface = device.getInterface(i)
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
                    return MtkUsbTransport(connection, usbInterface, endpointIn, endpointOut)
                }
            }
            connection.close()
            return null
        }
    }
}
