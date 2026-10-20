package com.tatskie.bootloaderunlocker

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Build
import android.view.View
import com.tatskie.bootloaderunlocker.databinding.ActivityMainBinding
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        showDeviceInfo(DeviceInfoDetector.detect())

        binding.decideButton.setOnClickListener {
            startActivity(Intent(this, DecisionActivity::class.java))
        }
        binding.unlockGuideButton.setOnClickListener {
            startActivity(GuideActivity.newIntent(this, GuideActivity.MODE_UNLOCK))
        }
        binding.relockGuideButton.setOnClickListener {
            startActivity(GuideActivity.newIntent(this, GuideActivity.MODE_RELOCK))
        }
        binding.mtkButton.setOnClickListener {
            startActivity(Intent(this, MtkActivity::class.java))
        }
        binding.payloadButton.setOnClickListener {
            startActivity(Intent(this, PayloadDumperActivity::class.java))
        }
        binding.edlButton.setOnClickListener {
            startActivity(Intent(this, EdlActivity::class.java))
        }
        binding.rootButton.setOnClickListener {
            startActivity(Intent(this, com.tatskie.bootloaderunlocker.root.RootActivity::class.java))
        }
        binding.fastbootButton.setOnClickListener {
            startActivity(Intent(this, com.tatskie.bootloaderunlocker.fastboot.FastbootActivity::class.java))
        }
        binding.flasherButton.setOnClickListener {
            startActivity(Intent(this, com.tatskie.bootloaderunlocker.fastboot.FlasherActivity::class.java))
        }
        binding.firehoseButton.setOnClickListener {
            startActivity(Intent(this, com.tatskie.bootloaderunlocker.qualcomm.FirehoseActivity::class.java))
        }
    }

    private fun showDeviceInfo(info: DeviceInfo) {
        binding.manufacturerValue.text = info.manufacturer
        binding.modelValue.text = info.model
        binding.codenameValue.text = info.codename
        binding.androidValue.text = info.androidVersion
        binding.chipsetValue.text = info.socLabel

        val (statusText, statusDesc, statusColorRes) = when (info.lockState) {
            LockState.LOCKED -> Triple(
                getString(R.string.status_locked),
                getString(R.string.status_locked_desc),
                R.color.statusLocked
            )
            LockState.UNLOCKED -> Triple(
                getString(R.string.status_unlocked),
                getString(R.string.status_unlocked_desc),
                R.color.statusUnlocked
            )
            LockState.UNKNOWN -> Triple(
                getString(R.string.status_unknown),
                getString(R.string.status_unknown_desc),
                R.color.statusUnknown
            )
        }

        binding.statusValue.text = statusText
        binding.statusValue.setTextColor(ContextCompat.getColor(this, statusColorRes))
        binding.statusDesc.text = statusDesc

        binding.oemUnlockValue.text = when (info.oemUnlockSupported) {
            true -> getString(R.string.value_yes)
            false -> getString(R.string.value_no)
            null -> getString(R.string.value_unknown)
        }
        binding.verifiedBootValue.text = info.verifiedBootState ?: getString(R.string.value_unknown)
    }
}
