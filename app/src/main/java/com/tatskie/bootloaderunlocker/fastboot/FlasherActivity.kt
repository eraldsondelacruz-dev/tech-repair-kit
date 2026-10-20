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
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.tatskie.bootloaderunlocker.R
import com.tatskie.bootloaderunlocker.databinding.ActivityFlasherBinding
import com.tatskie.bootloaderunlocker.partition.PartitionEntry
import com.tatskie.bootloaderunlocker.partition.PartitionMapParser
import com.tatskie.bootloaderunlocker.partition.PartitionMapUi
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Universal Flasher — the fastboot-side workstation.
 *
 * Load an MTK scatter.txt or a Qualcomm rawprogram*.xml, browse the partition
 * table it describes, then FLASH / ERASE / FETCH any partition on the phone
 * sitting in fastboot mode across the OTG cable. Manual mode (just type a
 * partition name and pick an image) still works when no map is available.
 */
class FlasherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFlasherBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logLines = ArrayList<String>()

    private var transport: FastbootUsbTransport? = null
    private var imageFile: File? = null
    private var mapEntries: List<PartitionEntry> = emptyList()

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (granted && device != null) {
                connect(device)
            } else {
                appendLog(getString(R.string.fb_permission_denied))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFlasherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.ufConnectButton.setOnClickListener { connectFlow() }
        binding.ufPickImageButton.setOnClickListener { startPick(REQ_PICK_IMAGE) }
        binding.ufPickMapButton.setOnClickListener { startPick(REQ_PICK_MAP) }
        binding.ufBrowseButton.setOnClickListener {
            PartitionMapUi.showPicker(this, mapEntries) { picked -> onPartitionPicked(picked) }
        }
        binding.ufFlashButton.setOnClickListener { beginFlash() }
        binding.ufEraseButton.setOnClickListener { beginErase() }
        binding.ufFetchButton.setOnClickListener { beginFetch() }
        binding.ufFrpButton.setOnClickListener { beginEraseFrp() }
        binding.ufRebootButton.setOnClickListener { beginReboot() }
        binding.ufClearButton.setOnClickListener {
            logLines.clear()
            binding.ufLogText.text = getString(R.string.uf_log_ready)
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permissionReceiver, filter)
        }

        appendLog(getString(R.string.uf_log_ready))
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri? = data?.data
        if (uri == null || resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_PICK_IMAGE -> importPickedFile(uri, "flasher_image.bin", asMap = false)
            REQ_PICK_MAP -> importPickedFile(uri, "partition_map.txt", asMap = true)
        }
    }

    // ---------------------------------------------------------------------
    // picking helpers
    // ---------------------------------------------------------------------

    private fun startPick(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, requestCode)
    }

    private fun importPickedFile(uri: Uri, cacheName: String, asMap: Boolean) {
        executor.execute {
            try {
                val target = File(cacheDir, cacheName)
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { out -> input.copyTo(out) }
                } ?: run {
                    appendLog("Could not open the picked file.")
                    return@execute
                }
                if (asMap) {
                    val text = target.readText()
                    val entries = PartitionMapParser.parse(text)
                    mapEntries = entries
                    val source = PartitionMapParser.sniff(text)
                    appendLog("Partition map parsed: ${entries.size} partitions (${source.label}).")
                    mainHandler.post {
                        binding.ufMapDetail.text =
                            getString(R.string.uf_map_loaded, entries.size, source.label)
                    }
                } else {
                    imageFile = target
                    val length = target.length()
                    appendLog(
                        "Image picked: ${target.name} (${PartitionMapUi.humanBytes(length)})"
                    )
                    mainHandler.post {
                        binding.ufImageDetail.text = getString(
                            R.string.uf_picked_image,
                            target.name,
                            PartitionMapUi.humanBytes(length)
                        )
                    }
                }
            } catch (e: Exception) {
                appendLog("Import failed: ${e.message}")
                mainHandler.post {
                    binding.ufMapDetail.text = getString(R.string.uf_bad_map, e.message ?: "?")
                }
            }
        }
    }

    private fun onPartitionPicked(entry: PartitionEntry) {
        binding.ufPartition.setText(entry.partitionName)
        val hinted = entry.fileName
        if (hinted != null) {
            appendLog(
                "Selected ${entry.partitionName}; the map references image \"$hinted\" — " +
                    "pick that file with \"Pick image\" before flashing."
            )
        } else {
            appendLog("Selected ${entry.partitionName}.")
        }
    }

    // ---------------------------------------------------------------------
    // USB plumbing
    // ---------------------------------------------------------------------

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
        val device = manager.deviceList.values.firstOrNull { FastbootUsbTransport.isFastbootDevice(it) }
        if (device == null) {
            setStatus(getString(R.string.uf_status_waiting))
            binding.ufStatusDetail.text = getString(R.string.fb_no_device)
            appendLog(getString(R.string.fb_no_device))
            return
        }
        ensurePermission(device)
    }

    private fun ensurePermission(device: UsbDevice) {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (manager.hasPermission(device)) {
            connect(device)
            return
        }
        val pending = PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        setStatus(getString(R.string.uf_status_connecting))
        manager.requestPermission(device, pending)
    }

    private fun connect(device: UsbDevice) {
        /** Serial executor job: open the transport and pull the headline vars. */
        val job = Runnable {
            transport?.close()
            transport = null
            val manager = getSystemService(Context.USB_SERVICE) as UsbManager
            val opened = FastbootUsbTransport.open(manager, device)
            if (opened == null) {
                appendLog(getString(R.string.fb_open_failed))
                return@Runnable
            }
            opened.logger = { line -> appendLog(line) }
            transport = opened

            val product = opened.getVar("product")
            val serial = opened.getVar("serialno")
            val unlocked = opened.unlocked()
            val size = opened.getVar("max-download-size")
            val slotInfo = opened.getVar("current-slot")

            appendLog("Product       : ${product ?: "?"}")
            appendLog("Serial        : ${serial ?: "?"}")
            appendLog("Max download  : ${size ?: "?"}")
            appendLog("Current slot  : ${slotInfo ?: "single-slot"}")
            appendLog("Unlocked      : ${if (unlocked) "YES" else "NO"}")
            postStatus(
                getString(
                    R.string.fb_status_connected,
                    product ?: getString(R.string.fb_status_connected_generic)
                ),
                "VID %04X  PID %04X".format(device.vendorId, device.productId)
            )
        }
        executor.execute(job)
    }

    // ---------------------------------------------------------------------
    // Operations
    // ---------------------------------------------------------------------

    private fun beginFlash() {
        val t = transport ?: run { appendLog(getString(R.string.uf_need_device)); return }
        val partition = binding.ufPartition.text?.toString()?.trim().orEmpty()
        if (partition.isEmpty()) {
            appendLog(getString(R.string.uf_need_partition))
            return
        }
        val file = imageFile
        if (file == null || !file.exists()) {
            appendLog(getString(R.string.uf_need_image))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fb_flash_title, partition))
            .setMessage(getString(R.string.fb_flash_warning, file.name, partition))
            .setPositiveButton(getString(R.string.fb_flash_confirm)) { _, _ ->
                doFlash(t, partition, file)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doFlash(t: FastbootUsbTransport, partition: String, file: File) {
        setStatus(
            getString(
                R.string.uf_flashing_fmt,
                file.name, partition, PartitionMapUi.humanBytes(file.length())
            )
        )
        executor.execute {
            try {
                FileInputStream(file).use { input ->
                    val total = file.length()
                    val sentRef = longArrayOf(0)
                    val response = t.flash(partition, total, input) { sent ->
                        if (sent - sentRef[0] >= 1048576) {
                            sentRef[0] = sent
                            mainHandler.post {
                                binding.ufProgress.text = getString(
                                    R.string.uf_progress_fmt,
                                    PartitionMapUi.humanBytes(sent),
                                    PartitionMapUi.humanBytes(total)
                                )
                            }
                            appendLog("…${PartitionMapUi.humanBytes(sent)} / ${PartitionMapUi.humanBytes(total)}")
                        }
                    }
                    if (response.okay) {
                        appendLog("FLASH OKAY: $partition")
                        postStatus(
                            getString(R.string.fb_status_connected, "flash done"),
                            "Wrote ${file.length()} bytes to $partition"
                        )
                    } else {
                        appendLog("FLASH FAILED: ${response.display}")
                        setStatus(getString(R.string.uf_op_failed, response.display))
                    }
                }
            } catch (e: Exception) {
                appendLog("Flash error: ${e.message}")
            }
        }
    }

    private fun beginErase() {
        val t = transport ?: run { appendLog(getString(R.string.uf_need_device)); return }
        val partition = binding.ufPartition.text?.toString()?.trim().orEmpty()
        if (partition.isEmpty()) {
            appendLog(getString(R.string.uf_need_partition))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.uf_erase_title))
            .setMessage(getString(R.string.uf_erase_warn, partition))
            .setPositiveButton(getString(R.string.uf_erase_yes)) { _, _ ->
                executor.execute {
                    val response = t.command("erase:$partition")
                    if (response.okay) {
                        appendLog("ERASE OKAY: $partition")
                    } else {
                        appendLog("ERASE FAILED: ${response.display}")
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun beginFetch() {
        val t = transport ?: run { appendLog(getString(R.string.uf_need_device)); return }
        val partition = binding.ufPartition.text?.toString()?.trim().orEmpty()
        if (partition.isEmpty()) {
            appendLog(getString(R.string.uf_need_partition))
            return
        }
        val dir = getExternalFilesDir(null) ?: cacheDir
        val out = File(dir, "fetch_${partition}_${System.currentTimeMillis()}.bin")
        setStatus(getString(R.string.uf_fetching_fmt, partition))
        executor.execute {
            try {
                FileOutputStream(out).use { output ->
                    val response = t.fetch(partition, output) { sent ->
                        mainHandler.post {
                            binding.ufProgress.text = PartitionMapUi.humanBytes(sent)
                        }
                    }
                    if (response.okay) {
                        appendLog(
                            "FETCH OKAY → ${out.absolutePath} (${PartitionMapUi.humanBytes(out.length())})"
                        )
                    } else {
                        appendLog("FETCH FAILED: ${response.display}")
                        out.delete()
                    }
                }
            } catch (e: Exception) {
                appendLog("Fetch error: ${e.message}")
                out.delete()
            }
        }
    }

    private fun beginReboot() {
        val t = transport ?: run { appendLog(getString(R.string.uf_need_device)); return }
        executor.execute {
            val response = t.rebootBootloader()
            appendLog("reboot-bootloader: ${response.display}")
        }
    }

    /**
     * FRP CLEAR over fastboot — erases the frp partition straight away. The
     * bootloader must be unlocked for the erase to be honored; the response
     * tells the truth either way.
     */
    private fun beginEraseFrp() {
        val t = transport ?: run { appendLog(getString(R.string.uf_need_device)); return }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fb_frp_title))
            .setMessage(getString(R.string.fb_frp_warning))
            .setPositiveButton(R.string.fb_frp_yes) { _, _ ->
                executor.execute {
                    val response = t.command("erase:frp")
                    appendLog(
                        if (response.okay) "FRP ERASE OKAY — reboot the phone, the Google gate is gone."
                        else "FRP ERASE FAILED: ${response.display} (bootloader locked?)"
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------------
    // console plumbing
    // ---------------------------------------------------------------------

    private fun setStatus(text: String) {
        mainHandler.post { binding.ufStatus.text = text }
    }

    private fun postStatus(status: String, detail: String) {
        mainHandler.post {
            binding.ufStatus.text = status
            binding.ufStatusDetail.text = detail
        }
    }

    private fun appendLog(line: String) {
        mainHandler.post {
            logLines.add(line)
            if (logLines.size > 500) {
                logLines.subList(0, logLines.size - 500).clear()
            }
            binding.ufLogText.text = logLines.joinToString("\n")
            binding.ufLogScroll.post {
                binding.ufLogScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION =
            "com.tatskie.bootloaderunlocker.FLASHER_PERMISSION"
        private const val REQ_PICK_IMAGE = 31
        private const val REQ_PICK_MAP = 32
    }
}
