package com.tatskie.bootloaderunlocker

import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader

enum class LockState { LOCKED, UNLOCKED, UNKNOWN }

enum class SocVendor {
    QUALCOMM, MEDIATEK, EXYNOS, TENSOR, UNISOC, OTHER, UNKNOWN;

    val displayName: String
        get() = when (this) {
            QUALCOMM -> "Qualcomm Snapdragon"
            MEDIATEK -> "MediaTek"
            EXYNOS -> "Samsung Exynos"
            TENSOR -> "Google Tensor"
            UNISOC -> "Unisoc"
            OTHER -> "Other"
            UNKNOWN -> "Unknown"
        }
}

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val codename: String,
    val androidVersion: String,
    val sdkInt: Int,
    val lockState: LockState,
    val oemUnlockSupported: Boolean?,
    val verifiedBootState: String?,
    val socVendor: SocVendor,
    val socDetail: String
) {
    val socLabel: String
        get() = if (socDetail.isNotBlank()) "${socVendor.displayName} ($socDetail)"
        else socVendor.displayName
}

object DeviceInfoDetector {

    fun detect(): DeviceInfo {
        val flashLocked = readProp("ro.boot.flash.locked")
        val secureBootLock = readProp("ro.secureboot.lockstate")
        val verifiedBoot = readProp("ro.boot.verifiedbootstate")
        val soc = detectSoc()

        return DeviceInfo(
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            model = Build.MODEL,
            codename = Build.DEVICE,
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            sdkInt = Build.VERSION.SDK_INT,
            lockState = resolveLockState(flashLocked, secureBootLock, verifiedBoot),
            oemUnlockSupported = resolveOemUnlockSupported(),
            verifiedBootState = verifiedBoot,
            socVendor = soc.first,
            socDetail = soc.second
        )
    }

    private fun detectSoc(): Pair<SocVendor, String> {
        val platform = (readProp("ro.board.platform") ?: readProp("ro.hardware") ?: "").lowercase()
        val hardware = (readProp("ro.hardware") ?: "").lowercase()
        val board = (readProp("ro.product.board") ?: "").lowercase()
        val socModel = readProp("ro.soc.model")
        val socManufacturer = (readProp("ro.soc.manufacturer") ?: "").lowercase()

        val vendor = when {
            socManufacturer.contains("qualcomm") || hardware == "qcom" ||
                platform.startsWith("sm") || platform.startsWith("msm") ||
                platform.startsWith("sdm") || platform.startsWith("qcs") -> SocVendor.QUALCOMM
            socManufacturer.contains("mediatek") || hardware.contains("mtk") ||
                platform.startsWith("mt") -> SocVendor.MEDIATEK
            socManufacturer.contains("samsung") || hardware.contains("exynos") ||
                platform.startsWith("exynos") -> SocVendor.EXYNOS
            socManufacturer.contains("google") || hardware == "gs" ||
                platform.startsWith("gs") || platform.startsWith("zuma") ||
                platform.startsWith("tensor") -> SocVendor.TENSOR
            socManufacturer.contains("unisoc") || socManufacturer.contains("spreadtrum") ||
                platform.startsWith("ums") || platform.startsWith("sc") -> SocVendor.UNISOC
            platform.isNotBlank() || hardware.isNotBlank() -> SocVendor.OTHER
            else -> SocVendor.UNKNOWN
        }

        val detail = socModel?.takeIf { it.isNotBlank() }
            ?: platform.takeIf { it.isNotBlank() }
            ?: board.takeIf { it.isNotBlank() }
            ?: ""
        return vendor to detail
    }

    private fun resolveLockState(
        flashLocked: String?,
        secureBootLock: String?,
        verifiedBoot: String?
    ): LockState {
        flashLocked?.let { return if (it == "1") LockState.LOCKED else LockState.UNLOCKED }
        secureBootLock?.let {
            return if (it.equals("locked", ignoreCase = true)) LockState.LOCKED else LockState.UNLOCKED
        }
        verifiedBoot?.let {
            return when (it.lowercase()) {
                "green" -> LockState.LOCKED
                "orange", "yellow" -> LockState.UNLOCKED
                else -> LockState.UNKNOWN
            }
        }
        return LockState.UNKNOWN
    }

    private fun resolveOemUnlockSupported(): Boolean? {
        val value = readProp("ro.oem_unlock_supported") ?: return null
        return when (value) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }

    private fun readProp(key: String): String? {
        execGetProp(key)?.let { return it }
        return reflectGetProp(key)
    }

    private fun execGetProp(key: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", key))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            reader.close()
            process.destroy()
            line?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private fun reflectGetProp(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java)
            (get.invoke(null, key) as? String)?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
}
