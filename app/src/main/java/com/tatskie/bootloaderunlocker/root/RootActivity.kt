package com.tatskie.bootloaderunlocker.root

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.tatskie.bootloaderunlocker.R
import com.tatskie.bootloaderunlocker.databinding.ActivityRootBinding
import java.util.concurrent.Executors

class RootActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRootBinding
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logLines = ArrayList<String>()

    private var lastInfo: RootInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRootBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rootCheckButton.setOnClickListener { checkRoot() }
        binding.rootRequestButton.setOnClickListener { requestRoot() }
        binding.rootRunButton.setOnClickListener { runCommand() }
        binding.rootQuickBlockButton.setOnClickListener { setCommand("ls -l /dev/block/by-name") }
        binding.rootQuickPropsButton.setOnClickListener { setCommand("getprop | grep -E 'ro.product|ro.build.version.release'") }
        binding.rootClearButton.setOnClickListener {
            logLines.clear()
            binding.rootLogText.text = getString(R.string.root_log_empty)
        }

        appendLog(getString(R.string.root_intro))
        checkRoot()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun installedManagerPackages(): Set<String> {
        val pm = packageManager
        return RootManager.MANAGER_PACKAGES.keys.filter {
            try {
                pm.getPackageInfo(it, 0)
                true
            } catch (_: Exception) {
                false
            }
        }.toSet()
    }

    private fun checkRoot(verbose: Boolean = true) {
        binding.rootCheckButton.isEnabled = false
        binding.rootRequestButton.isEnabled = false
        binding.rootStatusValue.text = getString(R.string.root_status_checking)
        if (verbose) appendLog(getString(R.string.root_log_checking))
        val packages = installedManagerPackages()
        executor.execute {
            val info = RootManager.detect(packages)
            lastInfo = info
            mainHandler.post {
                binding.rootCheckButton.isEnabled = true
                binding.rootRequestButton.isEnabled = true
                showInfo(info)
            }
            appendLog(
                buildString {
                    append("rooted=").append(info.rooted)
                    append("  manager=").append(info.type.label)
                    info.suPath?.let { append("  su=").append(it) }
                    info.version?.let { append("  version=").append(it) }
                }
            )
            appendLog(info.message)
        }
    }

    private fun requestRoot() {
        binding.rootCheckButton.isEnabled = false
        binding.rootRequestButton.isEnabled = false
        appendLog(getString(R.string.root_log_requesting))
        executor.execute {
            val result = RootManager.requestRoot()
            appendLog(
                if (result.stdout.contains("uid=0")) {
                    getString(R.string.root_log_granted)
                } else {
                    getString(R.string.root_log_denied, result.stderr.ifBlank { result.stdout })
                }
            )
            val info = RootManager.detect(installedManagerPackages())
            lastInfo = info
            mainHandler.post {
                binding.rootCheckButton.isEnabled = true
                binding.rootRequestButton.isEnabled = true
                showInfo(info)
            }
        }
    }

    private fun showInfo(info: RootInfo) {
        binding.rootStatusValue.text = if (info.rooted) {
            getString(R.string.root_status_rooted)
        } else {
            getString(R.string.root_status_not_rooted)
        }
        binding.rootManagerValue.text = getString(R.string.root_manager_fmt, info.type.label)
        binding.rootSuValue.text = getString(
            R.string.root_su_fmt,
            info.suPath ?: getString(R.string.value_unknown)
        )
        binding.rootMessageValue.text = info.message +
            if (info.version != null) "\n" + getString(R.string.root_version_fmt, info.version) else ""
    }

    private fun setCommand(command: String) {
        binding.rootCommandInput.setText(command)
        binding.rootCommandInput.setSelection(command.length)
    }

    private fun runCommand() {
        val command = binding.rootCommandInput.text.toString().trim()
        if (command.isEmpty()) return
        val asRoot = lastInfo?.rooted == true
        binding.rootRunButton.isEnabled = false
        appendLog("$ ${if (asRoot) "su -c " else ""}$command")
        executor.execute {
            val result = RootManager.run(command, asRoot)
            appendLog(
                buildString {
                    result.stdout.takeIf { it.isNotEmpty() }?.let { append(it).append('\n') }
                    result.stderr.takeIf { it.isNotEmpty() }?.let { append("[stderr] ").append(it).append('\n') }
                    append("[exit ").append(result.exitCode).append(']')
                }
            )
            mainHandler.post { binding.rootRunButton.isEnabled = true }
        }
    }

    private fun appendLog(line: String) {
        mainHandler.post {
            logLines.add(line)
            if (logLines.size > 500) {
                logLines.subList(0, logLines.size - 500).clear()
            }
            binding.rootLogText.text = logLines.joinToString("\n")
            binding.rootLogScroll.post {
                binding.rootLogScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }
}
