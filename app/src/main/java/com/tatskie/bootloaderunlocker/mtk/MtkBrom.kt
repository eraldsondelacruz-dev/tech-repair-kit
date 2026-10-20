package com.tatskie.bootloaderunlocker.mtk

data class TargetConfig(val raw: Int, val status: Int) {
    val sbc: Boolean get() = (raw shr 0) and 1 == 1
    val sla: Boolean get() = (raw shr 1) and 1 == 1
    val daa: Boolean get() = (raw shr 2) and 1 == 1
    val cert: Boolean get() = (raw shr 3) and 1 == 1
    val memRead: Boolean get() = (raw shr 4) and 1 == 1
    val memWrite: Boolean get() = (raw shr 5) and 1 == 1
}

data class MtkBootInfo(
    val isBrom: Boolean,
    val blVer: Int,
    val bromVer: Int,
    val hwCode: Int,
    val hwVer: Int,
    val swVer: Int,
    val hwSubCode: Int,
    val chipName: String,
    val targetConfig: TargetConfig?
)

class MtkBrom(
    private val usb: MtkUsbTransport,
    private val log: (String) -> Unit
) {

    private object Cmd {
        const val GET_HW_CODE = 0xFD
        const val GET_TARGET_CONFIG = 0xD8
        const val GET_BL_VER = 0xFE
        const val GET_VERSION = 0xFF
        const val GET_HW_SW_VER = 0xFC
    }

    private val handshakeBytes = intArrayOf(0xA0, 0x0A, 0x50, 0x05)

    fun handshake(maxAttempts: Int = 100, perByteTimeout: Int = 200): Boolean {
        usb.muted = true
        try {
            repeat(maxAttempts) { attempt ->
                var matched = true
                for (b in handshakeBytes) {
                    usb.write(byteArrayOf(b.toByte()), perByteTimeout)
                    val echo = usb.read(1, perByteTimeout)
                    if (echo.isEmpty() || (echo[0].toInt() and 0xFF) != (b.inv() and 0xFF)) {
                        matched = false
                        break
                    }
                }
                if (matched) {
                    log("Handshake established after ${attempt + 1} attempt(s)")
                    return true
                }
                usb.read(64, 20)
            }
        } finally {
            usb.muted = false
        }
        return false
    }

    fun identify(): MtkBootInfo? {
        val blVer = readBlVer()
        val isBrom = blVer == Cmd.GET_BL_VER
        val bromVer = readBromVer()
        val hwCodeData = mtkCmd(Cmd.GET_HW_CODE, 4)
        if (hwCodeData == null || hwCodeData.size < 4) {
            log("Failed to read HW code")
            return null
        }
        val hwCode = ((hwCodeData[0].toInt() and 0xFF) shl 8) or (hwCodeData[1].toInt() and 0xFF)
        val hwVer = ((hwCodeData[2].toInt() and 0xFF) shl 8) or (hwCodeData[3].toInt() and 0xFF)

        val targetConfig = readTargetConfig()
        val hwSw = mtkCmd(Cmd.GET_HW_SW_VER, 8)
        val hwSubCode = if (hwSw != null && hwSw.size >= 2) {
            ((hwSw[0].toInt() and 0xFF) shl 8) or (hwSw[1].toInt() and 0xFF)
        } else 0
        val swVer = if (hwSw != null && hwSw.size >= 6) {
            ((hwSw[4].toInt() and 0xFF) shl 8) or (hwSw[5].toInt() and 0xFF)
        } else 0

        return MtkBootInfo(
            isBrom = isBrom,
            blVer = blVer,
            bromVer = bromVer,
            hwCode = hwCode,
            hwVer = hwVer,
            swVer = swVer,
            hwSubCode = hwSubCode,
            chipName = ChipDatabase.nameFor(hwCode),
            targetConfig = targetConfig
        )
    }

    private fun readBlVer(): Int {
        val res = usb.write(byteArrayOf(Cmd.GET_BL_VER.toByte()))
        if (res < 0) return -1
        val echo = usb.read(1)
        return if (echo.isNotEmpty()) echo[0].toInt() and 0xFF else -1
    }

    private fun readBromVer(): Int {
        val res = usb.write(byteArrayOf(Cmd.GET_VERSION.toByte()))
        if (res < 0) return -1
        val echo = usb.read(1)
        return if (echo.isNotEmpty()) echo[0].toInt() and 0xFF else -1
    }

    private fun readTargetConfig(): TargetConfig? {
        usb.write(byteArrayOf(Cmd.GET_TARGET_CONFIG.toByte()))
        val echo = usb.read(1)
        if (echo.size != 1 || (echo[0].toInt() and 0xFF) != Cmd.GET_TARGET_CONFIG) {
            log("Target config not acknowledged")
            return null
        }
        val data = usb.read(6)
        if (data.size != 6) {
            log("Target config short read (${data.size} bytes)")
            return null
        }
        val raw = ((data[0].toInt() and 0xFF) shl 24) or
            ((data[1].toInt() and 0xFF) shl 16) or
            ((data[2].toInt() and 0xFF) shl 8) or
            (data[3].toInt() and 0xFF)
        val status = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        return TargetConfig(raw, status)
    }

    private fun mtkCmd(cmd: Int, readLength: Int): ByteArray? {
        val written = usb.write(byteArrayOf(cmd.toByte()))
        if (written < 0) return null
        Thread.sleep(50)
        val echo = usb.read(1)
        if (echo.isEmpty() || (echo[0].toInt() and 0xFF) != cmd) {
            return null
        }
        if (readLength <= 0) return ByteArray(0)
        return usb.read(readLength)
    }
}
