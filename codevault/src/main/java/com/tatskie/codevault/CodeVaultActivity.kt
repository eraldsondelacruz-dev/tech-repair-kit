package com.tatskie.codevault

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tatskie.codevault.databinding.ActivityCodevaultBinding
import com.google.android.material.button.MaterialButton

class CodeVaultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCodevaultBinding
    private var activeBrand = "All"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCodevaultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                renderList()
                renderChips()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        renderList()
        renderChips()
    }

    private fun renderChips() {
        binding.brandChips.removeAllViews()
        for (brand in CodeVault.brands()) {
            val chip = MaterialButton(this, null, if (brand == activeBrand)
                com.google.android.material.R.attr.materialButtonStyle
            else
                com.google.android.material.R.attr.materialButtonOutlinedStyle)
            chip.text = brand
            chip.textSize = 12f
            chip.isAllCaps = false
            chip.setPadding(40, 8, 40, 8)
            chip.setOnClickListener {
                activeBrand = brand
                renderList()
                renderChips()
            }
            binding.brandChips.addView(chip)
        }
    }

    private fun renderList() {
        binding.codeList.removeAllViews()
        val query = binding.searchBox.text?.toString()?.lowercase()?.trim() ?: ""
        val rows = CodeVault.codes.filter { code ->
            val brandOk = activeBrand == "All" || code.brand == activeBrand
            val queryOk = query.isEmpty() ||
                code.title.lowercase().contains(query) ||
                code.dial.lowercase().contains(query) ||
                code.brand.lowercase().contains(query)
            brandOk && queryOk
        }
        for (code in rows) addRow(code)
        if (rows.isEmpty()) {
            val empty = TextView(this)
            empty.text = "No codes match."
            empty.setTextColor(getColor(R.color.textDim))
            empty.gravity = Gravity.CENTER
            empty.setPadding(32, 48, 32, 48)
            binding.codeList.addView(empty)
        }
    }

    private fun addRow(code: Code) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(28, 32, 28, 32)
        val bg = android.graphics.drawable.GradientDrawable().apply {
            setColor(getColor(if (code.danger) R.color.card else R.color.card))
           cornerRadius = 28f
            if (code.danger) setStroke(2, getColor(R.color.danger))
        }
        row.background = bg

        val title = TextView(this)
        title.text = code.title
        title.textSize = 15f
        title.setTextColor(getColor(if (code.danger) R.color.danger else R.color.textMain))
        title.typeface = Typeface.DEFAULT_BOLD
        row.addView(title)

        val dial = TextView(this)
        dial.text = "${code.brand}  •  ${code.dial}" + if (code.danger) "  ⚠ WIPE" else ""
        dial.textSize = 13f
        dial.setTextColor(getColor(R.color.textDim))
        dial.typeface = Typeface.MONOSPACE
        dial.setPadding(0, 6, 0, 0)
        row.addView(dial)

        row.setOnClickListener { showActions(code) }
        binding.codeList.addView(row)
    }

    private fun showActions(code: Code) {
        val items = if (code.danger) arrayOf("Copy code", "Share") else arrayOf("Copy code", "Dial", "Share")
        AlertDialog.Builder(this, R.style.CVTheme)
            .setTitle(code.title)
            .setMessage(code.dial)
            .setItems(items) { _, which ->
                when (items[which]) {
                    "Copy code" -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("code", code.dial))
                    }
                    "Dial" -> {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + code.dial))
                        try { startActivity(intent) } catch (e: Exception) { }
                    }
                    "Share" -> {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "${code.title} (${code.brand}): ${code.dial}")
                        }
                        startActivity(Intent.createChooser(send, "Share code"))
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
