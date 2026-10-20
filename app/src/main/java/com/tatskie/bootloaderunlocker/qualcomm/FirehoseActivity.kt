package com.tatskie.bootloaderunlocker.qualcomm

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
import com.tatskie.bootloaderunlocker.databinding.ActivityFirehoseBinding
import com.tatskie.bootloaderunlocker.partition.PartitionEntry
import com.tatskie.bootloaderunlocker.partition.PartitionMapParser
import com.tatskie.bootloaderunlocker.partition.PartitionMapSource
import com.tatskie.bootloaderunlocker.partition.PartitionMapUi
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * EDL Firehose — the Qualcomm-side workstation.
 *
 * Boots a Firehose programmer (.elf / .mbn) into the phone in EDL (9008) mode
 * via Sahara, then erases, programs or resets sectors through the XML firehose
 * protocol — the same job QPST / fh_loader does on a PC.
 */
class FirehoseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFirehoseBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logLines = ArrayList<String>()

    private var transport: QualcommUsbTransport? = null
    private var saharaHello: SaharaHello? = null
    private var session: FirehoseSession? = null
    private var programmerFile: File? = null
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
                appendLog(getString(R.string.edl_permission_denied))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFirehoseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.fhConnectButton.setOnClickListener { connectFlow() }
        binding.fhPickProgrammerButton.setOnClickListener { startPick(REQ_PICK_PROGRAMMER) }
        binding.fhPickImageButton.setOnClickListener { startPick(REQ_PICK_IMAGE) }
        binding.fhPickMapButton.setOnClickListener { startPick(REQ_PICK_MAP) }
        binding.fhBootButton.setOnClickListener { beginBoot() }
        binding.fhConfigureButton.setOnClickListener { beginConfigure() }
        binding.fhBrowseButton.setOnClickListener {
            PartitionMapUi.showPicker(this, mapEntries) { picked -> onPartitionPicked(picked) }
        }
        binding.fhProgramButton.setOnClickListener { beginProgram() }
        binding.fhEraseButton.setOnClickListener { beginErase() }
        binding.fhReadbackButton.setOnClickListener { beginReadback() }
        binding.fhResetButton.setOnClickListener { beginReset() }
        binding.fhClearButton.setOnClickListener {
            logLines.clear()
            binding.fhLogText.text = getString(R.string.fh_log_ready)
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permissionReceiver, filter)
        }

        appendLog(getString(R.string.fh_log_ready))
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
        session = null
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri: Uri? = data?.data
        if (uri == null || resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_PICK_PROGRAMMER -> importPickedFile(uri, "firehose_programmer.elf", Use.PROGRAMMER)
            REQ_PICK_IMAGE -> importPickedFile(uri, "firehose_image.img", Use.IMAGE)
            REQ_PICK_MAP -> importPickedFile(uri, "partition_map.txt", Use.MAP)
            REQ_SAVE_DUMP -> dumpTo(uri)
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

    private enum class Use { PROGRAMMER, IMAGE, MAP }

    private fun importPickedFile(uri: Uri, cacheName: String, use: Use) {
        executor.execute {
            try {
                val target = File(cacheDir, cacheName)
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { out -> input.copyTo(out) }
                } ?: run {
                    appendLog("Could not open the picked file.")
                    return@execute
                }
                when (use) {
                    Use.PROGRAMMER -> {
                        programmerFile = target
                        val sizeLabel = PartitionMapUi.humanBytes(target.length())
                        appendLog("Programmer picked: ${target.name} ($sizeLabel)")
                        mainHandler.post {
                            binding.fhProgrammerDetail.text =
                                getString(R.string.fh_picked_prog, "${target.name} · $sizeLabel")
                        }
                    }
                    Use.IMAGE -> {
                        imageFile = target
                        val sizeLabel = PartitionMapUi.humanBytes(target.length())
                        appendLog("Image picked: ${target.name} ($sizeLabel)")
                        mainHandler.post {
                            binding.fhImageDetail.text =
                                getString(R.string.fh_picked_image, "${target.name} · $sizeLabel")
                        }
                    }
                    Use.MAP -> {
                        val text = target.readText()
                        val entries = PartitionMapParser.parse(text)
                        mapEntries = entries
                        val source = PartitionMapParser.sniff(text)
                        appendLog("Partition map parsed: ${entries.size} partitions (${source.label}).")
                        mainHandler.post {
                            binding.fhProgrammerDetail.text =
                                "${source.label.replaceFirstChar { it.uppercase() }} · ${entries.size} partitions parsed"
                        }
                    }
                }
            } catch (e: Exception) {
                appendLog("Import failed: ${e.message}")
            }
        }
    }

    private fun onPartitionPicked(entry: PartitionEntry) {
        if (entry.source != PartitionMapSource.QC_RAWPROGRAM) {
            appendLog(
                "${entry.partitionName}: ${entry.source.label} maps cannot feed firehose " +
                    "sector numbers directly — enter them manually."
            )
        }
        entry.startSector?.let { binding.fhStartInput.setText(it.toString()) }
        entry.numSectors?.let { binding.fhNumInput.setText(it.toString()) }
        binding.fhPhysInput.setText(entry.physicalPartition.toString())
        appendLog(
            "Selected ${entry.partitionName} → start ${entry.startSector ?: "?"}, " +
                "${entry.numSectors ?: "?"} sectors, physical ${entry.physicalPartition}."
        )
    }

    // ---------------------------------------------------------------------
    // USB plumbing
    // ---------------------------------------------------------------------

    private fun handleIntent(intent: Intent) {
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (device != null && QualcommUsbTransport.isEdlDevice(device)) {
                ensurePermission(device)
            }
        }
    }

    private fun connectFlow() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val device = manager.deviceList.values.firstOrNull { QualcommUsbTransport.isEdlDevice(it) }
        if (device == null) {
            setStatus(getString(R.string.edl_status_waiting))
            binding.fhStatusDetail.text = getString(R.string.edl_no_device)
            appendLog(getString(R.string.edl_no_device))
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
        setStatus(getString(R.string.fh_status_connecting))
        manager.requestPermission(device, pending)
    }

    private fun connect(device: UsbDevice) {
        executor.execute {
            transport?.close()
            transport = null
            session = null
            saharaHello = null
            val manager = getSystemService(Context.USB_SERVICE) as UsbManager
            val opened = QualcommUsbTransport.open(manager, device)
            if (opened == null) {
                appendLog(getString(R.string.edl_open_failed))
                return@execute
            }
            opened.logger = { line -> appendLog(line) }
            transport = opened

            val sahara = Sahara(opened) { line -> appendLog(line) }
            val hello = sahara.readHello()
            if (hello == null) {
                postStatus(getString(R.string.edl_status_failed), getString(R.string.edl_hello_failed))
                return@execute
            }
            saharaHello = hello
            val headline = "EDL device"
            val info = sahara.readInfo(hello)
            if (info != null) {
                info.hwId?.let { hwid ->
                    val platform = QualcommEdlDatabase.platformForHwId(hwid)
                    platform?.let { appendLog("Platform $it") }
                }
            }
            postStatus(
                getString(R.string.edl_status_identified, headline),
                "Sahara v${hello.version} · ${hello.modeName} · PID 0x%04X".format(device.productId)
            )
        }
    }

    // ---------------------------------------------------------------------
    // operations
    // ---------------------------------------------------------------------

    private fun beginBoot() {
        val t = transport ?: run { appendLog(getString(R.string.fh_need_connect)); return }
        val file = programmerFile
        if (file == null || !file.exists()) {
            appendLog(getString(R.string.fh_need_programmer))
            return
        }
        val hello = saharaHello
        if (hello == null) {
            appendLog("No Sahara HELLO cached — connect the EDL device first.")
            return
        }
        if (session != null) {
            appendLog("Note: a firehose session is already up. Reboot the phone to restart from scratch.")
        }
        val memoryName = binding.fhMemoryInput.text?.toString()?.trim().orEmpty().ifEmpty { "eMMC" }
        val pageSize = binding.fhPageSizeInput.text?.toString()?.trim()?.toIntOrNull()
            ?: FirehoseSession.DEFAULT_PAGE_SIZE
        setStatus(getString(R.string.fh_boot_fmt, file.name))
        executor.execute {
            try {
                FileInputStream(file).use { input ->
                    val uploader = SaharaProgrammer(t) { line -> appendLog(line) }
                    val sentProgress = longArrayOf(0)
                    val ok = uploader.upload(hello, input, file.length()) { sent ->
                        if (sent - sentProgress[0] >= 1048576) {
                            sentProgress[0] = sent
                            mainHandler.post {
                                binding.fhProgress.text = getString(
                                    R.string.fh_progress_fmt,
                                    PartitionMapUi.humanBytes(sent),
                                    PartitionMapUi.humanBytes(file.length())
                                )
                            }
                        }
                    }
                    if (!ok) {
                        val failure = getString(R.string.fh_boot_failed, "upload rejected")
                        appendLog(failure)
                        postStatus(getString(R.string.edl_status_failed), failure)
                        return@use
                    }
                    appendLog(getString(R.string.fh_boot_done))
                    val fired = FirehoseSession(t) { line -> appendLog(line) }
                    fired.memoryName = memoryName
                    session = fired
                    val nopOk = fired.nop()
                    appendLog("NOP ping: ${if (nopOk) "ACK" else "NO RESPONSE"}")
                    val configured = fired.configure(pageSize = pageSize)
                    if (configured) {
                        appendLog("Configured: memory=${fired.memoryName}, page=$pageSize — firehose READY.")
                        postStatus(
                            getString(R.string.fh_status_ready),
                            "memory=${fired.memoryName} · page=$pageSize"
                        )
                    } else {
                        appendLog("Configure rejected — check memory name / page size in the log.")
                        postStatus(getString(R.string.edl_status_failed), "configure failed")
                    }
                }
            } catch (e: Exception) {
                appendLog("Boot failed: ${e.message}")
                postStatus(getString(R.string.edl_status_failed), "boot exception")
            }
        }
    }

    private fun beginConfigure() {
        val s = session ?: run { appendLog(getString(R.string.fh_need_connect)); return }
        val memoryName = binding.fhMemoryInput.text?.toString()?.trim().orEmpty().ifEmpty { "eMMC" }
        val pageSize = binding.fhPageSizeInput.text?.toString()?.trim()?.toIntOrNull()
            ?: FirehoseSession.DEFAULT_PAGE_SIZE
        executor.execute {
            s.memoryName = memoryName
            val ok = s.configure(pageSize = pageSize)
            appendLog(if (ok) "Reconfigured OK (memory=${s.memoryName}, page=$pageSize)." else "Configure failed.")
            postStatus(
                if (ok) getString(R.string.fh_status_ready) else getString(R.string.edl_status_failed),
                "memory=${s.memoryName} · page=$pageSize"
            )
        }
    }

    private fun beginProgram() {
        val s = session ?: run { appendLog(getString(R.string.fh_need_connect)); return }
        val file = imageFile
        if (file == null || !file.exists()) {
            appendLog(getString(R.string.fh_need_image))
            return
        }
        val start = binding.fhStartInput.text?.toString()?.trim()?.toLongOrNull()
        val num = binding.fhNumInput.text?.toString()?.trim()?.toLongOrNull()
        if (start == null || num == null || num <= 0) {
            appendLog(getString(R.string.fh_need_sectors))
            return
        }
        val phys = binding.fhPhysInput.text?.toString()?.trim()?.toIntOrNull() ?: 0
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fh_program_title))
            .setMessage(
                getString(
                    R.string.fh_program_warn,
                    "${file.name} (${PartitionMapUi.humanBytes(file.length())})",
                    start, num, phys
                )
            )
            .setPositiveButton(getString(R.string.fh_program_yes)) { _, _ ->
                executor.execute {
                    FileInputStream(file).use { input ->
                        val covered = (file.length() + s.sectorSize - 1) / s.sectorSize
                        val expected = minOf(num, maxOf(1L, covered))
                        appendLog("Programming $start + $expected sectors (file covers $covered).")
                        val ok = s.program(start, expected, phys, input, file.length()) { sent ->
                            mainHandler.post {
                                binding.fhProgress.text = PartitionMapUi.humanBytes(sent)
                            }
                        }
                        appendLog(if (ok) "PROGRAM OKAY ($start + $expected sectors)." else "PROGRAM FAILED.")
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun beginErase() {
        val s = session ?: run { appendLog(getString(R.string.fh_need_connect)); return }
        val start = binding.fhStartInput.text?.toString()?.trim()?.toLongOrNull()
        val num = binding.fhNumInput.text?.toString()?.trim()?.toLongOrNull()
        if (start == null || num == null || num <= 0) {
            appendLog(getString(R.string.fh_need_sectors))
            return
        }
        val phys = binding.fhPhysInput.text?.toString()?.trim()?.toIntOrNull() ?: 0
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fh_erase_title))
            .setMessage(getString(R.string.fh_erase_warn, start, num, phys))
            .setPositiveButton(getString(R.string.fh_erase_yes)) { _, _ ->
                executor.execute {
                    val ok = s.erase(start, num, phys)
                    appendLog(if (ok) "ERASE OKAY ($start + $num sectors)." else "ERASE FAILED.")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun beginReadback() {
        val s = session ?: run { appendLog(getString(R.string.fh_need_connect)); return }
        val start = binding.fhStartInput.text?.toString()?.trim()?.toLongOrNull()
        val num = binding.fhNumInput.text?.toString()?.trim()?.toLongOrNull()
        if (start == null || num == null || num <= 0) {
            appendLog(getString(R.string.fh_need_sectors))
            return
        }
        val phys = binding.fhPhysInput.text?.toString()?.trim()?.toIntOrNull() ?: 0
        pendingReadback = Triple(start, num, phys)
        val dumpIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "dump_${start}_${num}s.bin")
        }
        startActivityForResult(dumpIntent, REQ_SAVE_DUMP)
    }

    /** Streams the pending sector range through the session into the SAF file. */
    private fun dumpTo(uri: Uri) {
        val s = session ?: run { appendLog(getString(R.string.fh_need_connect)); return }
        val (start, num, phys) = pendingReadback ?: Triple(0L, 0L, 0)
        pendingReadback = null
        if (num <= 0L) { appendLog(getString(R.string.fh_need_connect)); return }
        setStatus(getString(R.string.fh_readback_fmt, num, phys))
        executor.execute {
            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    val received = longArrayOf(0)
                    val ok = s.readback(start, num, phys, output) { sent ->
                        if (sent - received[0] >= 1048576) {
                            received[0] = sent
                            mainHandler.post {
                                binding.fhProgress.text = PartitionMapUi.humanBytes(sent)
                            }
                        }
                    }
                    if (ok) {
                        appendLog(getString(R.string.fh_readback_saved, PartitionMapUi.humanBytes(num * s.sectorSize)))
                    } else {
                        appendLog(getString(R.string.fh_readback_failed, "incomplete transfer"))
                    }
                } ?: appendLog(getString(R.string.fh_readback_failed, "could not open output"))
            } catch (e: Exception) {
                appendLog(getString(R.string.fh_readback_failed, e.message ?: "exception"))
            }
        }
    }

    private fun beginReset() {
        val s = session ?: run { appendLog(getString(R.string.fh_need_connect)); return }
        executor.execute {
            s.reset()
            appendLog(getString(R.string.fh_reset_done))
        }
    }

    // ---------------------------------------------------------------------
    // console plumbing
    // ---------------------------------------------------------------------

    private fun setStatus(text: String) {
        mainHandler.post { binding.fhStatus.text = text }
    }

    private fun postStatus(status: String, detail: String) {
        mainHandler.post {
            binding.fhStatus.text = status
            binding.fhStatusDetail.text = detail
        }
    }

    private fun appendLog(line: String) {
        mainHandler.post {
            logLines.add(line)
            if (logLines.size > 500) {
                logLines.subList(0, logLines.size - 500).clear()
            }
            binding.fhLogText.text = logLines.joinToString("\n")
            binding.fhLogScroll.post {
                binding.fhLogScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION =
            "com.tatskie.bootloaderunlocker.FIREHOSE_PERMISSION"
        private const val REQ_PICK_PROGRAMMER = 41
        private const val REQ_PICK_IMAGE = 42
        private const val REQ_PICK_MAP = 43
        private const val REQ_SAVE_DUMP = 44
    }

    private var pendingReadback: Triple<Long, Long, Int>? = null
}
