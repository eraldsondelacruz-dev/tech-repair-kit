package com.tatskie.bootloaderunlocker.fastboot

/** A fastboot response: a 4-char status plus the remaining payload. */
data class FastbootResponse(
    val status: String,
    val payload: String,
    val info: String = ""
) {
    val okay: Boolean get() = status == "OKAY"
    val isData: Boolean get() = status == "DATA"
    val display: String
        get() = buildString {
            if (status == "OKAY") {
                append("OKAY")
                if (payload.isNotEmpty()) append(" ").append(payload)
            } else {
                append(status)
                if (payload.isNotEmpty()) append(' ').append(payload)
            }
            if (info.isNotBlank()) append('\n').append(info.trim())
        }
}

object FastbootProtocol {

    const val IFACE_CLASS = 0xFF
    const val IFACE_SUBCLASS = 0x42
    const val IFACE_PROTOCOL = 0x03

    const val STATUS_OKAY = "OKAY"
    const val STATUS_FAIL = "FAIL"
    const val STATUS_DATA = "DATA"
    const val STATUS_INFO = "INFO"

    fun isFastbootInterface(interfaceClass: Int, subClass: Int, protocol: Int): Boolean =
        interfaceClass == IFACE_CLASS &&
            subClass == IFACE_SUBCLASS &&
            protocol == IFACE_PROTOCOL

    /** fastboot uses a lowercase, zero-padded 8-digit hex byte count. */
    fun hexSize(size: Long): String = "%08x".format(size and 0xFFFFFFFFL)

    fun parseDataSize(payload: String): Int =
        payload.trim().take(8).toIntOrNull(16) ?: -1

    /**
     * Splits a raw response string into status + payload.
     * A response shorter than 4 chars is treated as a protocol error.
     */
    fun parse(raw: String): FastbootResponse {
        val text = raw.trim('\u0000')
        if (text.length < 4) {
            return FastbootResponse(STATUS_FAIL, "", "short response: '$text'")
        }
        val status = text.substring(0, 4)
        val payload = text.substring(4).trim('\u0000', '\n', '\r')
        return FastbootResponse(status, payload)
    }

    /** Fastboot commands sent with a trailing newline are rejected by some loaders. */
    fun commandBytes(command: String): ByteArray = command.toByteArray(Charsets.US_ASCII)

    private val ABILITY_LABEL = Regex(
        """(?:unlock_ability|unlockable|allowed|unlocked)\s*[=:]\s*(yes|no|true|false|1|0)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Interprets the reply to `flashing:get_unlock_ability`.
     *
     * Bootloaders differ: most answer `OKAY` and print the flag as an INFO line
     * (e.g. `(bootloader) unlock_ability = 1`), while some put it right in the
     * payload (`OKAY1`). Returns null when the value cannot be determined.
     */
    fun parseUnlockAbility(response: FastbootResponse): Boolean? {
        val joined = response.payload + "\n" + response.info
        ABILITY_LABEL.find(joined)?.let { match ->
            return when (match.groupValues[1].lowercase()) {
                "yes", "true", "1" -> true
                "no", "false", "0" -> false
                else -> null
            }
        }
        return when (response.payload.trim().lowercase()) {
            "1", "yes", "true" -> true
            "0", "no", "false" -> false
            else -> null
        }
    }
}
