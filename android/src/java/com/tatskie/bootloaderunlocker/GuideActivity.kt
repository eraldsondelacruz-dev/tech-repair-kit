package com.tatskie.bootloaderunlocker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tatskie.bootloaderunlocker.databinding.ActivityGuideBinding

class GuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGuideBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_UNLOCK
        if (mode == MODE_RELOCK) {
            title = getString(R.string.guide_relock_title)
            binding.warningCard.visibility = View.VISIBLE
        } else {
            title = getString(R.string.guide_unlock_title)
        }

        val steps = if (mode == MODE_RELOCK) relockSteps() else unlockSteps()
        renderSteps(steps)
    }

    private fun renderSteps(steps: List<Step>) {
        val inflater = LayoutInflater.from(this)
        steps.forEachIndexed { index, step ->
            val item = inflater.inflate(R.layout.item_guide_step, binding.stepsContainer, false)
            item.findViewById<TextView>(R.id.stepTitle).text = "${index + 1}. ${step.title}"
            item.findViewById<TextView>(R.id.stepBody).text = step.body

            val commandView = item.findViewById<TextView>(R.id.stepCommand)
            if (step.command == null) {
                commandView.visibility = View.GONE
            } else {
                commandView.text = step.command
                commandView.setOnClickListener { copy(step.command) }
            }
            binding.stepsContainer.addView(item)
        }
    }

    private fun copy(command: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("command", command))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun unlockSteps(): List<Step> {
        val manufacturer = DeviceInfoDetector.detect().manufacturer.lowercase()
        return if (manufacturer.contains("samsung")) {
            samsungUnlockSteps()
        } else {
            fastbootUnlockSteps(manufacturer)
        }
    }

    private fun fastbootUnlockSteps(manufacturer: String): List<Step> = listOf(
        Step(
            "Back up everything",
            "Unlocking erases ALL data including internal storage. Copy your photos, " +
                "contacts and files off the phone first."
        ),
        oemPermissionStep(manufacturer),
        Step(
            "Enable Developer options",
            "Settings > About phone > tap \"Build number\" 7 times."
        ),
        Step(
            "Enable USB debugging",
            "Settings > System > Developer options > turn on USB debugging."
        ),
        Step(
            "Enable OEM unlocking",
            "Same Developer options screen > turn on \"OEM unlocking\". If it is greyed out, " +
                "your device or carrier does not allow unlocking."
        ),
        Step(
            "No PC? Make this phone the host (USB-OTG)",
            "A phone cannot unlock itself. If you have a USB-OTG cable and a second phone, " +
                "put the target in bootloader mode, connect it to this phone, open " +
                "\"Fastboot over OTG\" in this app, tap \"Detect fastboot device\", then " +
                "\"Unlock bootloader\". This replaces the PC steps below."
        ),
        Step(
            "Install platform-tools on your PC",
            "Install Android SDK Platform-Tools (adb & fastboot) from developer.android.com."
        ),
        Step(
            "Connect and check adb",
            "Connect the phone by USB, allow the debugging prompt, then run:",
            "adb devices"
        ),
        Step(
            "Reboot into the bootloader",
            "This restarts the phone into fastboot mode.",
            "adb reboot bootloader"
        ),
        Step(
            "Verify fastboot sees the device",
            "",
            "fastboot devices"
        ),
        unlockCommandStep(manufacturer),
        Step(
            "Confirm on the phone",
            "Use the volume keys to select \"Unlock\" and press the power button. " +
                "The phone wipes and reboots."
        ),
        Step(
            "Check the result",
            "Reconnect to fastboot and verify the unlocked state.",
            "fastboot getvar unlocked"
        )
    )

    private fun samsungUnlockSteps(): List<Step> = listOf(
        Step(
            "Back up everything",
            "Unlocking wipes the phone and can trip Samsung's security (Knox). Back up first."
        ),
        Step(
            "Samsung uses Download mode, not fastboot",
            "Samsung phones do not accept the usual fastboot commands. Unlocking and flashing " +
                "are done with Odin after entering Download mode."
        ),
        Step(
            "Enable Developer options and USB debugging",
            "Settings > About phone > tap \"Build number\" 7 times, then in " +
                "Settings > Developer options turn on USB debugging."
        ),
        Step(
            "Enable OEM unlocking",
            "Settings > Developer options > turn on \"OEM unlocking\". If it is greyed out, " +
                "the device or carrier does not allow unlocking."
        ),
        Step(
            "Install Odin on your PC",
            "Download Samsung's Odin tool (or current equivalent) and the USB driver for your model."
        ),
        Step(
            "Enter Download mode",
            "Power the phone off. Hold Volume Down + Volume Up (on units with a Bixby key use " +
                "Volume Down + Bixby) and connect the USB cable. Press the key to continue at the warning."
        ),
        Step(
            "Trigger the unlock",
            "With OEM unlocking enabled, long-press Volume Up at the Download-mode warning to " +
                "confirm the bootloader unlock. The device wipes and reboots."
        ),
        Step(
            "Flash stock firmware to repair",
            "In Odin, load the official firmware for your exact model (AP/BL/CP/CSC) and press " +
                "Start. Use HOME_CSC to keep data or CSC to wipe."
        ),
        Step(
            "Note on Knox",
            "Unlocking permanently trips Knox, which disables Samsung Pay, Secure Folder and " +
                "some banking apps even after relocking."
        )
    )

    private fun oemPermissionStep(manufacturer: String): Step = when {
        manufacturer.contains("realme") || manufacturer.contains("oppo") -> Step(
            "Get official unlock approval (Realme/Oppo)",
            "Realme and Oppo do not allow unlocking directly. Install the official " +
                "\"In-Depth Test\" / \"DeepTesting\" application from Realme/Oppo, apply for " +
                "deep-testing, and wait for approval (often about 7 days). Only after approval " +
                "will fastboot accept the unlock command."
        )
        manufacturer.contains("xiaomi") ||
            manufacturer.contains("redmi") ||
            manufacturer.contains("poco") -> Step(
            "Bind your account with Mi Unlock (Xiaomi/Redmi/POCO)",
            "Install the Mi Unlock Tool on a PC and sign in with a Xiaomi account. On the phone " +
                "open Settings > Developer options > \"Mi Unlock status\" and bind the same " +
                "account, then wait about 168 hours (7 days) before unlocking is allowed."
        )
        manufacturer.contains("motorola") -> Step(
            "Request your Motorola unlock key",
            "Run the command below in fastboot, paste the output into Motorola's official " +
                "\"Unlock your device\" website, and keep the unlock code it gives you.",
            "fastboot oem get_unlock_data"
        )
        manufacturer.contains("sony") -> Step(
            "Request your Sony unlock code",
            "Enter your IMEI on Sony's official bootloader-unlock developer page to receive an " +
                "unlock code. You will use it with the command further down."
        )
        manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> Step(
            "Vivo/iQOO unlocking is restricted",
            "Vivo and iQOO only allow unlocking in limited regions through an official " +
                "application/account process. On most units fastboot refuses even with OEM " +
                "unlocking enabled."
        )
        manufacturer.contains("huawei") || manufacturer.contains("honor") -> Step(
            "Huawei/Honor: no official unlocking",
            "Huawei stopped issuing bootloader unlock codes in 2018, so there is no official way " +
                "to unlock modern Huawei/Honor phones."
        )
        manufacturer.contains("asus") -> Step(
            "Use the ASUS Unlock Tool (Asus)",
            "Asus devices are unlocked with the official \"ASUS Unlock Device Tool\" app, which " +
                "performs the unlock and wipe after you accept its warnings."
        )
        else -> Step(
            "Check your manufacturer's rules",
            "Most manufacturers allow unlocking after enabling OEM unlocking, but some require an " +
                "official tool, account or wait time. If the command below is refused, search " +
                "\"unlock bootloader <your exact model>\"."
        )
    }

    private fun unlockCommandStep(manufacturer: String): Step = when {
        manufacturer.contains("motorola") -> Step(
            "Unlock with your Motorola key",
            "Replace KEY with the unlock code from Motorola's website.",
            "fastboot oem unlock KEY"
        )
        manufacturer.contains("sony") -> Step(
            "Unlock with your Sony code",
            "Replace 0xKEY with the unlock code from Sony's website.",
            "fastboot oem unlock 0xKEY"
        )
        else -> Step(
            "Unlock the bootloader",
            "On Android 10+ use the command below. On Android 6-9 use \"fastboot oem unlock\" " +
                "instead if this is not recognized.",
            "fastboot flashing unlock"
        )
    }

    private fun relockSteps(): List<Step> {
        val manufacturer = DeviceInfoDetector.detect().manufacturer.lowercase()
        return if (manufacturer.contains("samsung")) samsungRelockSteps() else fastbootRelockSteps()
    }

    private fun fastbootRelockSteps(): List<Step> = listOf(
        Step(
            "Restore 100% stock firmware",
            "Flash the official factory image/firmware for your exact model and build. " +
                "Relocking on anything modified can brick the phone."
        ),
        Step(
            "Remove root and modifications",
            "Uninstall Magisk, custom recovery and custom kernels. The system must be " +
                "fully original and verified."
        ),
        Step(
            "Enable USB debugging",
            "Settings > System > Developer options > enable USB debugging again."
        ),
        Step(
            "Connect and check adb",
            "Connect by USB and allow the debugging prompt, then run:",
            "adb devices"
        ),
        Step(
            "Reboot into the bootloader",
            "",
            "adb reboot bootloader"
        ),
        Step(
            "Relock the bootloader",
            "On Android 10+ use the first command. On Android 6-9 use the second.",
            "fastboot flashing lock"
        ),
        Step(
            "Alternative for older devices",
            "Only if the command above is not recognized.",
            "fastboot oem lock"
        ),
        Step(
            "Confirm on the phone",
            "Use the volume keys to select \"Lock\" and press power. The device wipes and " +
                "returns to the verified, secure state."
        )
    )

    private fun samsungRelockSteps(): List<Step> = listOf(
        Step(
            "Restore 100% stock firmware first",
            "Download the official firmware for your exact model and flash it with Odin " +
                "(AP/BL/CP/CSC). Never relock on modified firmware or the phone can brick."
        ),
        Step(
            "Samsung relocks through stock firmware",
            "Unlike other brands, Samsung has no fastboot relock command. Flashing full stock " +
                "firmware and then disabling OEM unlocking returns the device to the locked state."
        ),
        Step(
            "Disable OEM unlocking",
            "After the stock flash, boot the system, then turn OFF \"OEM unlocking\" in " +
                "Settings > Developer options and reboot."
        ),
        Step(
            "Expect a data wipe",
            "Relocking wipes all data. Knox remains permanently tripped once it has been unlocked."
        )
    )

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    data class Step(val title: String, val body: String, val command: String? = null)

    companion object {
        const val MODE_UNLOCK = "unlock"
        const val MODE_RELOCK = "relock"
        private const val EXTRA_MODE = "extra_mode"

        fun newIntent(context: Context, mode: String): Intent =
            Intent(context, GuideActivity::class.java).putExtra(EXTRA_MODE, mode)
    }
}
