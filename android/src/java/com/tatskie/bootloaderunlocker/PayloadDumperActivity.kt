package com.tatskie.bootloaderunlocker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.tatskie.bootloaderunlocker.databinding.ActivityPayloadDumperBinding
import com.tatskie.bootloaderunlocker.payload.ParsedPayload
import com.tatskie.bootloaderunlocker.payload.PayloadExtractor
import com.tatskie.bootloaderunlocker.payload.PayloadParser
import com.tatskie.bootloaderunlocker.payload.SeekableSource
import java.io.FileInputStream
import java.util.concurrent.Executors

class PayloadDumperActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPayloadDumperBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logLines = ArrayList<String>()

    private var payloadUri: Uri? = null
    private var outputTree: DocumentFile? = null
    private var parsed: ParsedPayload? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPayloadDumperBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.payloadButton.setOnClickListener { pickPayload() }
        binding.outputButton.setOnClickListener { pickOutputFolder() }
        binding.dumpButton.setOnClickListener { dumpAll() }
        binding.clearButton.setOnClickListener {
            logLines.clear()
            binding.logText.text = getString(R.string.payload_log_empty)
        }

        appendLog(getString(R.string.payload_log_empty))
        updateDumpButton()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun pickPayload() {
        if (busy) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_PAYLOAD)
    }

    private fun pickOutputFolder() {
        if (busy) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, REQUEST_OUTPUT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            REQUEST_PAYLOAD -> {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                }
                payloadUri = uri
                parsed = null
                binding.payloadFileText.text = uri.lastPathSegment ?: uri.toString()
                binding.partitionsText.text = getString(R.string.payload_none)
                updateDumpButton()
                parsePayload(uri)
            }
            REQUEST_OUTPUT -> {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                }
                val tree = DocumentFile.fromTreeUri(this, uri)
                if (tree == null) {
                    toast(getString(R.string.payload_output_none))
                    return
                }
                outputTree = tree
                binding.outputFolderText.text = uri.lastPathSegment ?: uri.toString()
                updateDumpButton()
            }
        }
    }

    private fun parsePayload(uri: Uri) {
        setBusy(true)
        setStatus(getString(R.string.payload_parsing))
        appendLog(getString(R.string.payload_parsing))
        executor.execute {
            try {
                val result = withSource(uri) { PayloadParser.parse(it) }
                mainHandler.post { onParsed(uri, result) }
            } catch (t: Throwable) {
                mainHandler.post { onParseFailed(t) }
            }
        }
    }

    private fun onParsed(uri: Uri, result: ParsedPayload) {
        parsed = result
        val rows = result.manifest.partitions.map { p ->
            val size = if (p.newPartitionSize > 0) {
                "  " + formatBytes(p.newPartitionSize)
            } else {
                ""
            }
            "• ${p.name}$size"
        }
        binding.partitionsText.text = rows.joinToString("\n")
        appendLog(
            "Parsed payload v${result.header.version}, ${result.manifest.partitions.size} partitions, " +
                "block size ${result.manifest.blockSize}"
        )
        setStatus(getString(R.string.payload_partitions_ready, result.manifest.partitions.size))
        updateDumpButton()
    }

    private fun onParseFailed(t: Throwable) {
        parsed = null
        binding.partitionsText.text = getString(R.string.payload_none)
        appendLog(getString(R.string.payload_parse_failed, t.message ?: t.javaClass.simpleName))
        setStatus(getString(R.string.payload_parse_failed, t.message ?: t.javaClass.simpleName))
        setBusy(false)
        updateDumpButton()
    }

    private fun dumpAll() {
        val uri = payloadUri ?: return toast(getString(R.string.payload_select_first))
        val tree = outputTree ?: return toast(getString(R.string.payload_select_first))
        val result = parsed ?: return toast(getString(R.string.payload_select_first))
        if (busy) return

        setBusy(true)
        appendLog("Starting dump of ${result.manifest.partitions.size} partitions")

        executor.execute {
            var done = 0
            try {
                withSource(uri) { source ->
                    val extractor = PayloadExtractor(source, result)
                    for (partition in result.manifest.partitions) {
                        mainHandler.post {
                            setStatus(getString(R.string.payload_dumping, partition.name))
                        }
                        val fileName = "${partition.name}.img"
                        val doc = tree.createFile(MIME_BINARY, fileName)
                            ?: throw java.io.IOException("Could not create $fileName in output folder")

                        var lastPosted = 0L
                        val stream = contentResolver.openOutputStream(doc.uri)
                            ?: throw java.io.IOException("Could not open $fileName for writing")
                        stream.use { out ->
                            extractor.extract(partition, out) { bytes ->
                                if (bytes - lastPosted >= PROGRESS_STEP) {
                                    lastPosted = bytes
                                    mainHandler.post {
                                        setStatus(
                                            getString(R.string.payload_dumping_progress, partition.name, formatBytes(bytes))
                                        )
                                    }
                                }
                            }
                        }
                        mainHandler.post {
                            appendLog("Extracted $fileName (${formatBytes(doc.length())})")
                        }
                        done++
                    }
                }
                mainHandler.post { onDumpDone(done) }
            } catch (t: Throwable) {
                mainHandler.post { onDumpFailed(t) }
            }
        }
    }

    private fun onDumpDone(count: Int) {
        appendLog(getString(R.string.payload_done, count))
        setStatus(getString(R.string.payload_done, count))
        setBusy(false)
        toast(getString(R.string.payload_done, count))
    }

    private fun onDumpFailed(t: Throwable) {
        val msg = t.message ?: t.javaClass.simpleName
        appendLog(getString(R.string.payload_failed, msg))
        setStatus(getString(R.string.payload_failed, msg))
        setBusy(false)
    }

    private fun <T> withSource(uri: Uri, block: (SeekableSource) -> T): T {
        val pfd = contentResolver.openFileDescriptor(uri, "r")
            ?: throw java.io.IOException("Could not open payload.bin")
        pfd.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                return block(SeekableSource(input.channel))
            }
        }
    }

    private fun updateDumpButton() {
        binding.dumpButton.isEnabled = !busy && parsed != null && outputTree != null
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.progressBar.visibility = if (value) android.view.View.VISIBLE else android.view.View.GONE
        binding.payloadButton.isEnabled = !value
        binding.outputButton.isEnabled = !value
        updateDumpButton()
    }

    private fun setStatus(text: String) {
        mainHandler.post { binding.statusText.text = text }
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

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    companion object {
        private const val REQUEST_PAYLOAD = 1001
        private const val REQUEST_OUTPUT = 1002
        private const val MIME_BINARY = "application/octet-stream"
        private const val PROGRESS_STEP = 8L * 1024 * 1024
    }
}
