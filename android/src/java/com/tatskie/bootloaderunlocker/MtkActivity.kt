package com.tatskie.bootloaderunlocker

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.tatskie.bootloaderunlocker.databinding.ActivityMtkBinding
import com.tatskie.bootloaderunlocker.mtk.MtkBrom
import com.tatskie.bootloaderunlocker.mtk.MtkUsbTransport
import java.util.concurrent.Executors

class MtkActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMtkBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logLines = ArrayList<String>()

    private var transport: MtkUsbTransport? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (granted && device != null) {
                connect(device)
            } else {
                appendLog(getString(R.string.mtk_permission_denied))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMtkBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.connectButton.setOnClickListener { connectFlow() }
        binding.clearButton.setOnClickListener {
            logLines.clear()
            binding.logText.text = getString(R.string.mtk_log_empty)
        }
        binding.extractButton.setOnClickListener {
            appendLog(getString(R.string.mtk_extract_todo))
        }

        val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(permissionReceiver, permissionFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permissionReceiver, permissionFilter)
        }

        appendLog("Ready. ${getString(R.string.mtk_requires_otg)}")
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

    private fun handleIntent(intent: Intent) {
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (device != null) {
                ensurePermission(device)
            }
        }
    }

    private fun connectFlow() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val device = manager.deviceList.values.firstOrNull {
            it.vendorId == MtkUsbTransport.MTK_VENDOR_ID
        }
        if (device == null) {
            setStatus(getString(R.string.mtk_status_waiting))
            binding.mtkStatusDetail.text = getString(R.string.mtk_no_device)
            appendLog(getString(R.string.mtk_no_device))
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
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getBroadcast(this, 0, intent, flags)
        setStatus(getString(R.string.mtk_status_connecting))
        manager.requestPermission(device, pending)
    }

    private fun connect(device: UsbDevice) {
        setStatus(getString(R.string.mtk_status_connecting))
        binding.mtkStatusDetail.text = "VID %04X  PID %04X".format(device.vendorId, device.deviceId)
        appendLog(
            "Opening USB device VID=%04X PID=%04X".format(device.vendorId, device.deviceId)
        )
        executor.execute {
            transport?.close()
            val manager = getSystemService(Context.USB_SERVICE) as UsbManager
            val opened = MtkUsbTransport.open(manager, device)
            if (opened == null) {
                postStatus(getString(R.string.mtk_status_failed), getString(R.string.mtk_open_failed))
                appendLog(getString(R.string.mtk_open_failed))
                return@execute
            }
            opened.logger = { line -> appendLog(line) }
            transport = opened

            val brom = MtkBrom(opened) { line -> appendLog(line) }
            if (!brom.handshake()) {
                postStatus(getString(R.string.mtk_status_failed), getString(R.string.mtk_handshake_failed))
                appendLog(getString(R.string.mtk_handshake_failed))
                opened.close()
                transport = null
                return@execute
            }

            val info = brom.identify()
            if (info == null) {
                postStatus(getString(R.string.mtk_status_failed), "Chip identification failed")
                return@execute
            }

            appendLog(
                "BL ver          : 0x%02X%s".format(
                    info.blVer,
                    if (info.isBrom) "  (BROM mode)" else "  (Preloader mode)"
                )
            )
            appendLog("BROM ver        : 0x%02X".format(info.bromVer))
            appendLog("HW code         : 0x%04X".format(info.hwCode))
            appendLog("HW version      : 0x%04X".format(info.hwVer))
            appendLog("SW version      : 0x%04X".format(info.swVer))
            appendLog("HW sub code     : 0x%04X".format(info.hwSubCode))
            appendLog("Chip            : ${info.chipName}")
            info.targetConfig?.let { cfg ->
                appendLog("Target config   : 0x%08X (status 0x%04X)".format(cfg.raw, cfg.status))
                appendLog(
                    "  sbc=${cfg.sbc} sla=${cfg.sla} daa=${cfg.daa} cert=${cfg.cert} " +
                        "memRead=${cfg.memRead} memWrite=${cfg.memWrite}"
                )
            }

            postStatus(
                getString(R.string.mtk_status_connected, info.chipName),
                "HW 0x%04X · HW ver 0x%04X · BROM 0x%02X".format(
                    info.hwCode, info.hwVer, info.bromVer
                )
            )
            mainHandler.post { binding.extractButton.isEnabled = true }
        }
    }

    private fun setStatus(text: String) {
        mainHandler.post { binding.mtkStatus.text = text }
    }

    private fun postStatus(status: String, detail: String) {
        mainHandler.post {
            binding.mtkStatus.text = status
            binding.mtkStatusDetail.text = detail
        }
    }

    private fun appendLog(line: String) {
        mainHandler.post {
            logLines.add(line)
            if (logLines.size > 500) {
                logLines.subList(0, logLines.size - 500).clear()
            }
            binding.logText.text = logLines.joinToString("\n")
            binding.logScroll.post {
                binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION =
            "com.tatskie.bootloaderunlocker.USB_PERMISSION"
    }
}
