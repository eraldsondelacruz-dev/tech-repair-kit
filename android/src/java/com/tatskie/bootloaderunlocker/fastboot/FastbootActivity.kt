package com.tatskie.bootloaderunlocker.fastboot

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tatskie.bootloaderunlocker.R
import com.tatskie.bootloaderunlocker.databinding.ActivityFastbootBinding
import java.io.InputStream
import java.util.concurrent.Executors

class FastbootActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFastbootBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logLines = ArrayList<String>()

    private var transport: FastbootUsbTransport? = null
    private var connected = false
    private var pickedUri: Uri? = null
    private var pickedName: String = ""
    private var pickedSize: Long = -1

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (granted && device != null) {
                openDevice(device)
            } else {
                appendLog(getString(R.string.fb_permission_denied))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFastbootBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.fbConnectButton.setOnClickListener { connectFlow() }
        binding.fbUnlockButton.setOnClickListener { confirmUnlock() }
        binding.fbUnlockAbilityButton.setOnClickListener { checkUnlockAbility() }
        binding.fbGetAllButton.setOnClickListener { getAllVars() }
        binding.fbRunCommandButton.setOnClickListener { runCustomCommand() }
        binding.fbRebootBootloaderButton.setOnClickListener { runAction("Reboot to bootloader") { it.rebootBootloader() } }
        binding.fbRebootSystemButton.setOnClickListener { runAction("Reboot system") { it.reboot() } }
        binding.fbPickButton.setOnClickListener { pickImage() }
        binding.fbFlashButton.setOnClickListener { confirmFlash() }
        binding.fbClearButton.setOnClickListener {
            logLines.clear()
            binding.fbLogText.text = getString(R.string.fb_log_empty)
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permissionReceiver, filter)
        }

        appendLog(getString(R.string.fb_intro))
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(permissionReceiver)
        } catch (_: Exception) {
        }
        transport?.close()
        transport = null
        executor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val meta = queryFile(uri)
            pickedUri = uri
            pickedName = meta?.first ?: "image"
            pickedSize = meta?.second ?: -1
            binding.fbFlashButton.isEnabled = pickedSize > 0
            appendLog(getString(R.string.fb_picked, pickedName, pickedSize))
        }
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (device != null && FastbootUsbTransport.isFastbootDevice(device)) {
                ensurePermission(device)
            }
        }
    }

    private fun connectFlow() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val device = manager.deviceList.values.firstOrNull {
            FastbootUsbTransport.isFastbootDevice(it)
        }
        if (device == null) {
            setStatus(getString(R.string.fb_status_waiting), "")
            appendLog(getString(R.string.fb_no_device))
            return
        }
        ensurePermission(device)
    }

    private fun ensurePermission(device: UsbDevice) {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (manager.hasPermission(device)) {
            openDevice(device)
            return
        }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getBroadcast(this, 0, intent, flags)
        setStatus(getString(R.string.fb_status_connecting), "")
        manager.requestPermission(device, pending)
    }

    private fun openDevice(device: UsbDevice) {
        setStatus(getString(R.string.fb_status_connecting), "")
        appendLog("Opening fastboot device VID=%04X PID=%04X".format(device.vendorId, device.productId))
        executor.execute {
            transport?.close()
            val manager = getSystemService(Context.USB_SERVICE) as UsbManager
            val opened = FastbootUsbTransport.open(manager, device)
            if (opened == null) {
                setStatus(getString(R.string.fb_status_failed), "")
                appendLog(getString(R.string.fb_open_failed))
                return@execute
            }
            opened.logger = { line -> appendLog(line) }
            transport = opened
            connected = true
            readIdentity()
        }
    }

    private fun readIdentity() {
        val t = transport ?: return
        val product = t.getVar("product")
        val version = t.getVar("version")
        val bootloader = t.getVar("version-bootloader")
        val serial = t.getVar("serialno")
        val unlocked = t.getVar("unlocked")
        val secure = t.getVar("secure")

        appendLog("product        : ${product ?: "-"}")
        appendLog("fastboot version: ${version ?: "-"}")
        appendLog("bootloader     : ${bootloader ?: "-"}")
        appendLog("serial         : ${serial ?: "-"}")
        appendLog("unlocked       : ${unlocked ?: "-"}")
        appendLog("secure         : ${secure ?: "-"}")

        val label = product ?: getString(R.string.fb_status_connected_generic)
        mainHandler.post {
            setStatus(getString(R.string.fb_status_connected, label), "unlocked=$unlocked  secure=$secure")
        }
    }

    private fun confirmUnlock() {
        if (!ensureConnected()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.fb_unlock_title)
            .setMessage(R.string.fb_unlock_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.fb_unlock_confirm) { _, _ ->
                executor.execute {
                    logUnlockAbility()
                    val result = transport?.flashingUnlock()
                        ?: FastbootResponse("FAIL", "", "not connected")
                    appendLog("flashing:unlock -> ${result.display}")
                    if (!result.okay) {
                        val fallback = transport?.oemUnlock()
                        if (fallback != null) appendLog("oem:unlock -> ${fallback.display}")
                    } else {
                        appendLog(getString(R.string.fb_unlock_confirm_hint))
                    }
                    readIdentity()
                }
            }
            .show()
    }

    private fun checkUnlockAbility() {
        if (!ensureConnected()) return
        executor.execute { logUnlockAbility() }
    }

    /** Reads and interprets `flashing:get_unlock_ability`, logging a human summary. */
    private fun logUnlockAbility() {
        val result = transport?.getUnlockAbility() ?: return
        appendLog("flashing:get_unlock_ability -> ${result.display}")
        when (FastbootProtocol.parseUnlockAbility(result)) {
            true -> appendLog(getString(R.string.fb_ability_true))
            false -> appendLog(getString(R.string.fb_ability_false))
            null -> appendLog(getString(R.string.fb_ability_unknown))
        }
    }

    private fun getAllVars() {
        if (!ensureConnected()) return
        executor.execute {
            val result = transport?.getAllVars() ?: return@execute
            appendLog("getvar:all -> ${result.status}")
            if (result.info.isNotBlank()) {
                result.info.trim().lines().forEach { appendLog("  $it") }
            }
            if (result.payload.isNotBlank()) appendLog("  ${result.payload}")
        }
    }

    private fun runCustomCommand() {
        if (!ensureConnected()) return
        val cmd = binding.fbCommandInput.text.toString().trim()
        if (cmd.isEmpty()) {
            Toast.makeText(this, R.string.fb_need_command, Toast.LENGTH_SHORT).show()
            return
        }
        binding.fbCommandInput.setText("")
        executor.execute {
            val t = transport ?: return@execute
            appendLog(getString(R.string.fb_running_command, cmd))
            val result = t.runCommand(cmd)
            appendLog("$cmd -> ${result.display}")
        }
    }

    private fun ensureConnected(): Boolean {
        if (!connected || transport == null) {
            appendLog(getString(R.string.fb_not_connected))
            return false
        }
        return true
    }

    private fun runAction(label: String, block: (FastbootUsbTransport) -> FastbootResponse) {
        if (!ensureConnected()) return
        executor.execute {
            val t = transport ?: return@execute
            val result = block(t)
            appendLog("$label -> ${result.display}")
        }
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_PICK)
    }

    private fun confirmFlash() {
        if (!connected) {
            appendLog(getString(R.string.fb_not_connected))
            return
        }
        val partition = binding.fbPartitionInput.text.toString().trim()
        if (partition.isEmpty()) {
            Toast.makeText(this, R.string.fb_need_partition, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = pickedUri
        if (uri == null) {
            appendLog(getString(R.string.fb_need_image))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fb_flash_title, partition))
            .setMessage(getString(R.string.fb_flash_warning, pickedName, partition))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.fb_flash_confirm) { _, _ -> flash(partition, uri) }
            .show()
    }

    private fun flash(partition: String, uri: Uri) {
        binding.fbFlashButton.isEnabled = false
        appendLog(getString(R.string.fb_flashing, pickedName, partition, pickedSize))
        executor.execute {
            try {
                val input: InputStream = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("cannot open file")
                input.use { stream ->
                    val lastPct = intArrayOf(-1)
                    val result = transport?.flash(partition, pickedSize, stream) { done ->
                        if (pickedSize > 0) {
                            val pct = (done * 100 / pickedSize).toInt()
                            if (pct / 10 != lastPct[0] / 10) {
                                lastPct[0] = pct
                                appendLog("  ... $pct%")
                            }
                        }
                    } ?: FastbootResponse("FAIL", "", "not connected")
                    appendLog("flash:$partition -> ${result.display}")
                }
            } catch (e: Exception) {
                appendLog(getString(R.string.fb_flash_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                mainHandler.post { binding.fbFlashButton.isEnabled = pickedSize > 0 }
            }
        }
    }

    private fun queryFile(uri: Uri): Pair<String, Long>? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) else "image"
                val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else -1L
                return name to size
            }
        }
        return null
    }

    private fun setStatus(text: String, detail: String) {
        mainHandler.post {
            binding.fbStatus.text = text
            binding.fbStatusDetail.text = detail
        }
    }

    private fun appendLog(line: String) {
        mainHandler.post {
            logLines.add(line)
            if (logLines.size > 500) {
                logLines.subList(0, logLines.size - 500).clear()
            }
            binding.fbLogText.text = logLines.joinToString("\n")
            binding.fbLogScroll.post {
                binding.fbLogScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    companion object {
        private const val REQUEST_PICK = 1001
        private const val ACTION_USB_PERMISSION =
            "com.tatskie.bootloaderunlocker.FASTBOOT_PERMISSION"
    }
}
