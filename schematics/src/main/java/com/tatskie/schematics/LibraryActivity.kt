package com.tatskie.schematics

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tatskie.schematics.databinding.ActivityLibraryBinding
import org.json.JSONObject

class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSync.setOnClickListener { syncNow() }
        binding.btnConfig.setOnClickListener { showConfig() }

        binding.searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                renderList()
            }
        })
        renderList()
        showLane()
    }

    private fun showLane() {
        val lane = if (LibraryManager.getLane(this) == LibraryManager.LANE_TELEGRAM) {
            "Telegram bot"
        } else {
            "GitHub CDN"
        }
        binding.laneLabel.text = "Lane: $lane  •  library v${LibraryManager.cachedVersion(this)}"
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun rowFor(entry: JSONObject): TextView {
        val name = entry.optString("name")
        val title = entry.optString("title", name)
        val status = LibraryManager.statusText(this, name, entry.optString("md5"))
        val size = entry.optLong("size", 0)
        val row = TextView(this)
        row.text = buildString {
            append(title)
            if (size > 0) append("  ·  ${LibraryManager.syncSize(size)}")
            append("\n")
            append(status)
        }
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        row.setTextColor(getColor(R.color.textMain))
        row.setPadding(dp(20), dp(14), dp(20), dp(14))
        row.gravity = Gravity.CENTER_VERTICAL
        row.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.card)))
        row.setOnClickListener { open(entry) }
        row.setOnLongClickListener {
            confirmDelete(entry)
            true
        }
        return row
    }

    private fun renderList() {
        val files = LibraryManager.cachedManifest(this)
        val query = binding.searchBox.text?.toString()?.trim()?.lowercase() ?: ""
        binding.libList.removeAllViews()
        val groups = LinkedHashMap<String, MutableList<JSONObject>>()
        for (i in 0 until files.length()) {
            val e = files.getJSONObject(i)
            val title = e.optString("title").lowercase()
            val name = e.optString("name").lowercase()
            if (query.isNotEmpty() && !title.contains(query) && !name.contains(query)) continue
            val folderName = if (e.has("folder") && e.optString("folder").isNotEmpty()) {
                e.optString("folder")
            } else {
                "Unassigned"
            }
            groups.getOrPut(folderName) { mutableListOf() }.add(e)
        }
        for (f in groups.keys.sorted()) {
            val list = groups[f]!!
            binding.libList.addView(headerFor("$f  ·  ${list.size}"))
            for (e in list) binding.libList.addView(rowFor(e))
        }
        binding.emptyState.visibility =
            if (binding.libList.childCount == 0) View.VISIBLE else View.GONE
    }

    private fun headerFor(headerText: String): TextView = TextView(this).apply {
        text = headerText
        textSize = 16f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(getColor(R.color.accent))
        setPadding(dp(20), dp(16), dp(20), dp(6))
    }

    private fun open(entry: JSONObject) {
        val name = entry.optString("name")
        val title = entry.optString("title", name)
        val src = LibraryManager.mirror(this, name)
        if (!src.exists()) {
            Toast.makeText(this, "Sync first — not on device yet", Toast.LENGTH_SHORT).show()
            return
        }
        var idx = BoardStore.indexOfTitle(this, title)
        if (idx < 0) {
            val folder = if (entry.has("folder") && entry.optString("folder").isNotEmpty()) {
                entry.optString("folder")
            } else {
                ""
            }
            BoardStore.importFile(this, src, title, name, folder) ?: run {
                Toast.makeText(this, "Import failed", Toast.LENGTH_SHORT).show()
                return
            }
            idx = BoardStore.indexOfTitle(this, title)
        }
        if (idx >= 0) {
            startActivity(Intent(this, ViewerActivity::class.java).putExtra("idx", idx))
        }
    }

    private fun confirmDelete(entry: JSONObject) {
        val name = entry.optString("name")
        AlertDialog.Builder(this, R.style.SchematicsTheme)
            .setTitle("Remove from device")
            .setMessage("${entry.optString("title", name)}\n\nBoards already imported stay in your library.")
            .setPositiveButton("Remove") { _, _ ->
                LibraryManager.delete(this, name)
                renderList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun syncNow() {
        binding.btnSync.isEnabled = false
        binding.syncStatus.text = getString(R.string.sync_running)
        Thread {
            try {
                val n = LibraryManager.sync(this) { msg ->
                    runOnUiThread { binding.syncStatus.text = msg }
                }
                runOnUiThread {
                    binding.btnSync.isEnabled = true
                    binding.syncStatus.text = getString(R.string.sync_done, n)
                    renderList()
                    showLane()
                    Toast.makeText(this, "Synced $n update(s)", Toast.LENGTH_SHORT).show()
                }
            } catch (ex: Exception) {
                runOnUiThread {
                    binding.btnSync.isEnabled = true
                    binding.syncStatus.text = getString(R.string.sync_failed, ex.message ?: "error")
                }
            }
        }.start()
    }

    private fun showConfig() {
        val lane = LibraryManager.getLane(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
        }
        val rg = RadioGroup(this)
        val rbGithub = RadioButton(this)
        rbGithub.text = getString(R.string.lane_chip_github)
        val rbTelegram = RadioButton(this)
        rbTelegram.text = getString(R.string.lane_chip_telegram)
        rg.addView(rbGithub)
        rg.addView(rbTelegram)
        rg.check(if (lane == LibraryManager.LANE_TELEGRAM) rbTelegram.id else rbGithub.id)
        val etUrl = EditText(this).apply {
            hint = getString(R.string.library_hint_github_url)
            setText(LibraryManager.getManifestUrl(this@LibraryActivity))
        }
        val etToken = EditText(this).apply {
            hint = getString(R.string.library_hint_bot_token)
            setText(LibraryManager.getBotToken(this@LibraryActivity))
        }
        val etFileId = EditText(this).apply {
            hint = getString(R.string.library_hint_file_id)
            setText(LibraryManager.getManifestFileId(this@LibraryActivity))
        }
        fun label(labelText: String): TextView = TextView(this).apply {
            text = labelText
            textSize = 12f
            setTextColor(getColor(R.color.textDim))
        }
        box.addView(rg)
        box.addView(label("Manifest source (GitHub CDN)"))
        box.addView(etUrl)
        box.addView(label("Bot token (Telegram lane)"))
        box.addView(etToken)
        box.addView(label("library.json file_id (Telegram lane)"))
        box.addView(etFileId)
        AlertDialog.Builder(this, R.style.SchematicsTheme)
            .setTitle("Auto-Library lane")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                LibraryManager.setLane(
                    this,
                    if (rbTelegram.isChecked) LibraryManager.LANE_TELEGRAM else LibraryManager.LANE_GITHUB
                )
                LibraryManager.setManifestUrl(this, etUrl.text.toString().trim())
                LibraryManager.setBotToken(this, etToken.text.toString().trim())
                LibraryManager.setManifestFileId(this, etFileId.text.toString().trim())
                renderList()
                showLane()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
