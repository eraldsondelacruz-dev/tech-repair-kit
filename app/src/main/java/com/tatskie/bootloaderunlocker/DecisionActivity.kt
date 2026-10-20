package com.tatskie.bootloaderunlocker

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tatskie.bootloaderunlocker.databinding.ActivityDecisionBinding

class DecisionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDecisionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDecisionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.evaluateButton.setOnClickListener { evaluate() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun evaluate() {
        val strongGoals = listOf(
            binding.goalRoot, binding.goalRom, binding.goalKernel,
            binding.goalRecovery, binding.goalDev
        ).count { it.isChecked }

        val lightGoals = listOf(binding.goalBloat, binding.goalPrivacy).count { it.isChecked }
        val curious = binding.goalCurious.isChecked
        val anything = strongGoals + lightGoals + (if (curious) 1 else 0)

        val device = DeviceInfoDetector.detect()

        val verdict: String
        val explanation: String
        val colorRes: Int

        when {
            anything == 0 -> {
                verdict = getString(R.string.value_unknown)
                explanation = "Select at least one goal above so we can advise you."
                colorRes = R.color.statusUnknown
            }
            strongGoals > 0 -> {
                verdict = "Yes - unlocking is worth it for you"
                explanation = "You want things that genuinely require an unlocked bootloader " +
                    "(root, custom ROM, kernel, recovery or development). There is no other way " +
                    "to do these, so unlocking makes sense if you accept the risks below."
                colorRes = R.color.statusUnlocked
            }
            lightGoals > 0 -> {
                verdict = "Maybe - try simpler options first"
                explanation = "Removing bloatware and blocking ads can often be done without " +
                    "unlocking (for example with ADB or private DNS). Unlock only if those are " +
                    "not enough for you."
                colorRes = R.color.statusUnknown
            }
            else -> {
                verdict = "Probably not - only if you accept the risks"
                explanation = "Curiosity alone is rarely worth wiping your data and losing " +
                    "official updates and banking apps. Unlocking also cannot be undone safely " +
                    "unless your firmware is fully stock."
                colorRes = R.color.statusLocked
            }
        }

        val deviceNote = buildString {
            when (device.oemUnlockSupported) {
                false -> append("\n\nWarning: this device reports that OEM unlocking is NOT supported, so unlocking may be impossible.")
                null -> append("\n\nNote: we could not confirm whether your device supports OEM unlocking.")
                true -> Unit
            }
            if (device.lockState == LockState.UNLOCKED) {
                append("\n\nYour bootloader is already UNLOCKED.")
            }
        }

        binding.verdictText.text = verdict
        binding.verdictText.setTextColor(ContextCompat.getColor(this, colorRes))
        binding.verdictExplanation.text = explanation + deviceNote
        binding.resultCard.visibility = android.view.View.VISIBLE
    }
}
