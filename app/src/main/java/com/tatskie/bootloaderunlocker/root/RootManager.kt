package com.tatskie.bootloaderunlocker.root

import java.io.File
import java.util.concurrent.TimeUnit

enum class RootType(val label: String) {
    KERNELSU("KernelSU"),
    APATCH("APatch"),
    MAGISK("Magisk"),
    OTHER_SU("Generic su"),
    NONE("Not rooted")
}

data class RootInfo(
    val type: RootType,
    val rooted: Boolean,
    val suPath: String?,
    val version: String?,
    val message: String
)

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val ok: Boolean get() = exitCode == 0
}

object RootManager {

    private val SU_CANDIDATES = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sbin/su",
        "/vendor/bin/su",
        "/su/bin/su",
        "/data/adb/ksu/bin/su",
        "/debug_ramdisk/su"
    )

    private const val SHELL_PATH =
        "/sbin:/system/sbin:/system/bin:/system/xbin:/vendor/bin:" +
            "/data/adb/ksu/bin:/data/adb/magisk:/product/bin"

    private const val SUDO_TIMEOUT_MS = 8_000L
    private const val GRANT_TIMEOUT_MS = 60_000L
    private const val RUN_TIMEOUT_MS = 30_000L

    val MANAGER_PACKAGES: Map<String, RootType> = mapOf(
        "me.weishu.kernelsu" to RootType.KERNELSU,
        "com.topjohnwu.magisk" to RootType.MAGISK,
        "io.github.huskydg.magisk" to RootType.MAGISK,
        "me.bmax.apatch" to RootType.APATCH
    )

    fun looksRooted(idOutput: String?): Boolean =
        idOutput?.contains("uid=0") == true

    fun classify(ksu: Boolean, apatch: Boolean, magisk: Boolean, su: Boolean): RootType = when {
        ksu -> RootType.KERNELSU
        apatch -> RootType.APATCH
        magisk -> RootType.MAGISK
        su -> RootType.OTHER_SU
        else -> RootType.NONE
    }

    fun hintFromPackages(installedPackages: Set<String>): RootType? =
        installedPackages.firstNotNullOfOrNull { MANAGER_PACKAGES[it] }

    fun cleanVersion(raw: String?): String? {
        val line = raw?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: return null
        return if (line.isEmpty()) null else line.take(64)
    }

    fun findSuPath(): String? {
        SU_CANDIDATES.firstOrNull { File(it).exists() }?.let { return it }
        val which = exec(arrayOf("sh", "-c", "command -v su 2>/dev/null"), SHELL_PATH, SUDO_TIMEOUT_MS)
        val found = which.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        return if (found.isNullOrEmpty()) null else found
    }

    /**
     * Runs `su -c id` and lets the root manager (KernelSU/Magisk/APatch) show
     * its grant prompt. Returns the raw result so the caller can report exactly
     * what happened (granted / denied / timed out).
     */
    fun requestRoot(): ShellResult {
        val su = findSuPath()
            ?: return ShellResult(-1, "", "No su binary found")
        return exec(arrayOf(su, "-c", "id"), SHELL_PATH, GRANT_TIMEOUT_MS)
    }

    fun detect(installedPackages: Set<String> = emptySet()): RootInfo {
        val hint = hintFromPackages(installedPackages)
        val su = findSuPath()

        if (su == null) {
            return if (hint != null) {
                RootInfo(
                    hint,
                    false,
                    null,
                    null,
                    "${hint.label} Manager is installed, but no 'su' is exposed to apps yet. " +
                        "Open ${hint.label} and make sure it is active, then tap Check again."
                )
            } else {
                RootInfo(
                    RootType.NONE,
                    false,
                    null,
                    null,
                    "No su binary found. This device does not appear to be rooted."
                )
            }
        }

        val id = exec(arrayOf(su, "-c", "id"), SHELL_PATH, GRANT_TIMEOUT_MS)
        if (!looksRooted(id.stdout)) {
            val reason = id.stderr.ifBlank { id.stdout }
                .ifBlank { "root request denied or timed out" }
            val manager = hint?.label ?: "your root manager"
            return RootInfo(
                hint ?: RootType.OTHER_SU,
                false,
                su,
                null,
                "su is present at $su but root was not granted. If a prompt appeared, grant " +
                    "root to this app in $manager, then tap Check again. ($reason)"
            )
        }

        val ksuRaw = exec(arrayOf(su, "-c", "ksud -V 2>/dev/null"), SHELL_PATH, SUDO_TIMEOUT_MS).stdout
        val hasKsu = ksuRaw.isNotBlank() ||
            exec(arrayOf(su, "-c", "[ -d /data/adb/ksu ] && echo yes"), SHELL_PATH, SUDO_TIMEOUT_MS)
                .stdout.contains("yes")

        val magiskRaw = exec(arrayOf(su, "-c", "magisk -V 2>/dev/null"), SHELL_PATH, SUDO_TIMEOUT_MS).stdout
        val hasMagisk = magiskRaw.isNotBlank() ||
            exec(arrayOf(su, "-c", "[ -d /data/adb/magisk ] && echo yes"), SHELL_PATH, SUDO_TIMEOUT_MS)
                .stdout.contains("yes")

        val hasApatch =
            exec(arrayOf(su, "-c", "[ -d /data/adb/ap ] && echo yes"), SHELL_PATH, SUDO_TIMEOUT_MS)
                .stdout.contains("yes")

        val probed = classify(hasKsu, hasApatch, hasMagisk, true)
        val type = if (probed == RootType.OTHER_SU && hint != null) hint else probed
        val version = cleanVersion(
            when (type) {
                RootType.KERNELSU -> ksuRaw.ifBlank { "KernelSU" }
                RootType.MAGISK -> magiskRaw
                RootType.APATCH -> "APatch"
                else -> null
            }
        )
        return RootInfo(type, true, su, version, "Root granted. Manager: ${type.label}.")
    }

    fun runAsRoot(command: String): ShellResult {
        val su = findSuPath()
            ?: return ShellResult(-1, "", "No su binary found")
        return exec(arrayOf(su, "-c", command), SHELL_PATH, RUN_TIMEOUT_MS)
    }

    fun runShell(command: String): ShellResult =
        exec(arrayOf("sh", "-c", command), SHELL_PATH, RUN_TIMEOUT_MS)

    fun run(command: String, asRoot: Boolean): ShellResult =
        if (asRoot) runAsRoot(command) else runShell(command)

    private fun exec(command: Array<String>, path: String, timeoutMs: Long): ShellResult {
        return try {
            val process = ProcessBuilder(*command).apply {
                environment()["PATH"] = path
            }.start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                return ShellResult(-1, stdout.trim(), "timed out after ${timeoutMs}ms")
            }
            ShellResult(process.exitValue(), stdout.trim(), stderr.trim())
        } catch (e: Exception) {
            ShellResult(-1, "", e.message ?: e.javaClass.simpleName)
        }
    }
}
