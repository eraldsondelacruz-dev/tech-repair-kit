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
import com.tatskie.bootloaderunlocker.databinding.ActivityEdlBinding
import com.tatskie.bootloaderunlocker.qualcomm.QualcommEdlDatabase
import com.tatskie.bootloaderunlocker.qualcomm.QualcommUsbTransport
import com.tatskie.bootloaderunlocker.qualcomm.Sahara
import java.util.concurrent.Executors

class EdlActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEdlBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logLines = ArrayList<String>()

    private var transport: QualcommUsbTransport? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (granted && device != null) {
                identify(device)
            } else {
                appendLog(getString(R.string.edl_permission_denied))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEdlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.edlConnectButton.setOnClickListener { connectFlow() }
        binding.edlClearButton.setOnClickListener {
            logLines.clear()
            binding.edlLogText.text = getString(R.string.edl_log_empty)
        }

        val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(permissionReceiver, permissionFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permissionReceiver, permissionFilter)
        }

        appendLog("Ready. ${getString(R.string.edl_requires_otg)}")
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
            QualcommUsbTransport.isEdlDevice(it)
        }
        if (device == null) {
            setStatus(getString(R.string.edl_status_waiting))
            binding.edlStatusDetail.text = getString(R.string.edl_no_device)
            appendLog(getString(R.string.edl_no_device))
            return
        }
        ensurePermission(device)
    }

    private fun ensurePermission(device: UsbDevice) {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (manager.hasPermission(device)) {
            identify(device)
            return
        }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getBroadcast(this, 0, intent, flags)
        setStatus(getString(R.string.edl_status_connecting))
        manager.requestPermission(device, pending)
    }

    private fun identify(device: UsbDevice) {
        setStatus(getString(R.string.edl_status_connecting))
        binding.edlStatusDetail.text =
            "VID %04X  PID %04X".format(device.vendorId, device.productId)
        appendLog("Opening EDL device VID=%04X PID=%04X".format(device.vendorId, device.productId))

        executor.execute {
            transport?.close()
            val manager = getSystemService(Context.USB_SERVICE) as UsbManager
            val opened = QualcommUsbTransport.open(manager, device)
            if (opened == null) {
                postStatus(getString(R.string.edl_status_failed), getString(R.string.edl_open_failed))
                appendLog(getString(R.string.edl_open_failed))
                return@execute
            }
            opened.logger = { line -> appendLog(line) }
            transport = opened

            val sahara = Sahara(opened) { line -> appendLog(line) }
            val hello = sahara.readHello()
            if (hello == null) {
                postStatus(getString(R.string.edl_status_failed), getString(R.string.edl_hello_failed))
                appendLog(getString(R.string.edl_hello_failed))
                return@execute
            }

            val edlMode = QualcommEdlDatabase.edlModeForProductId(device.productId)
            appendLog("Sahara version   : ${hello.version} (min supported ${hello.versionSupported})")
            appendLog("Command pkt size : ${hello.cmdPacketLength}")
            appendLog("Mode             : ${hello.mode} - ${hello.modeName}")
            appendLog("USB PID          : 0x%04X ($edlMode)".format(device.productId))

            val info = sahara.readInfo(hello)
            var platform: String? = null
            if (info != null) {
                info.serial?.let { appendLog("Serial number    : 0x%08X".format(it)) }
                info.hwId?.let { hwid ->
                    appendLog("MSM HW ID        : 0x%016X".format(hwid))
                    platform = QualcommEdlDatabase.platformForHwId(hwid)
                    if (platform != null) {
                        appendLog("Platform         : $platform")
                    } else {
                        appendLog(
                            "Platform         : Unknown (MSM ID 0x%06X)".format(
                                QualcommEdlDatabase.hwIdToMsmId(hwid)
                            )
                        )
                    }
                }
                info.pkhash?.let { appendLog("OEM PK hash      : $it") }
            } else {
                appendLog("Could not read chip info (command mode unavailable)")
            }

            val headline = platform ?: edlMode
            postStatus(
                getString(R.string.edl_status_identified, headline),
                "Sahara v${hello.version} · $edlMode · PID 0x%04X".format(device.productId)
            )
        }
    }

    private fun setStatus(text: String) {
        mainHandler.post { binding.edlStatus.text = text }
    }

    private fun postStatus(status: String, detail: String) {
        mainHandler.post {
            binding.edlStatus.text = status
            binding.edlStatusDetail.text = detail
        }
    }

    private fun appendLog(line: String) {
        mainHandler.post {
            logLines.add(line)
            if (logLines.size > 500) {
                logLines.subList(0, logLines.size - 500).clear()
            }
            binding.edlLogText.text = logLines.joinToString("\n")
            binding.edlLogScroll.post {
                binding.edlLogScroll.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION =
            "com.tatskie.bootloaderunlocker.EDL_PERMISSION"
    }
}
