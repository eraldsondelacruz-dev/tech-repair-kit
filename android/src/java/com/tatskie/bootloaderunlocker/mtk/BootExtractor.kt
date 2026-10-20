package com.tatskie.bootloaderunlocker.mtk

class BootExtractor(
    private val usb: MtkUsbTransport,
    private val log: (String) -> Unit
) {

    private object Cmd {
        const val SEND_DA = 0xD7
        const val JUMP_DA = 0xD5
    }

    sealed class Result {
        data class Success(val partitionName: String, val data: ByteArray, val gptOffset: Long) : Result()
        data class Failure(val reason: String) : Result()
    }

    fun extractBoot(daLoader: ByteArray?, daAddress: Long): Result {
        if (daLoader == null || daLoader.isEmpty()) {
            return Result.Failure(
                "DA loader file required. Select a matching Download Agent before extracting."
            )
        }
        log("Boot extraction is not implemented yet in this build.")
        log("Planned flow: SEND_DA (0x%02X) -> JUMP_DA (0x%02X) -> read GPT -> locate 'boot' -> write boot.img"
            .format(Cmd.SEND_DA, Cmd.JUMP_DA))
        return Result.Failure("Stage 2 (DA upload and partition read) is not enabled in this build.")
    }
}
