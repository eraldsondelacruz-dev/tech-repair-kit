package com.tatskie.schematics

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.tatskie.schematics.databinding.ActivitySchematicsBinding
import org.json.JSONArray

class SchematicsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySchematicsBinding
    private val entries = JSONArray()
    private val visibleIndices = mutableListOf<Int>()

    private fun seedDemo() {
        val arr = BoardStore.load(this)
        try {
            val src = assets.open("demo_board.png")
            val assetLen = src.available().toLong()
            src.close()
            val dst = java.io.File(BoardStore.boardsDir(this), "demo_board.png")
            val stale = dst.exists() && dst.length() != assetLen
            if (!dst.exists() || stale) {
                assets.open("demo_board.png").use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
            }
            if (arr.length() == 0) {
                val entry = org.json.JSONObject()
                entry.put("title", "Demo board — pinch me!")
                entry.put("file", "demo_board.png")
                entry.put("type", "img")
                entry.put("fav", false)
                val newArr = org.json.JSONArray()
                newArr.put(entry)
                for (i in 0 until arr.length()) newArr.put(arr.getJSONObject(i))
                BoardStore.save(this, newArr)
            } else if (stale) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("file") == "demo_board.png") {
                        o.put(
                            "title",
                            "Demo board — pinch me! (v2 sharp)"
                        )
                        arr.put(i, o)
                    }
                }
                BoardStore.save(this, arr)
            }
        } catch (e: Exception) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySchematicsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        seedDemo()

        binding.btnImportFile.setOnClickListener { pickFiles() }
        binding.btnImportFolder.setOnClickListener { pickFolder() }
        binding.btnAutoLibrary.setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }

        binding.searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                renderList()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        entries.let { }
        renderList()
    }

    private fun reload() {
        val arr = BoardStore.load(this)
        for (i in entries.length() - 1 downTo 0) entries.remove(i)
        for (i in 0 until arr.length()) entries.put(arr.getJSONObject(i))
        renderList()
    }

    private fun renderList() {
        reloadQuiet()
        val query = binding.searchBox.text?.toString()?.trim()?.lowercase() ?: ""
        visibleIndices.clear()
        binding.boardList.removeAllViews()
        for (i in 0 until entries.length()) {
            val obj = entries.getJSONObject(i)
            val title = obj.optString("title", "").lowercase()
            if (query.isNotEmpty() && !title.contains(query)) continue
            visibleIndices.add(i)
        }
        visibleIndices.sortByDescending { entries.getJSONObject(it).optBoolean("fav", false) }
        val favIdx = mutableListOf<Int>()
        val groups = LinkedHashMap<String, MutableList<Int>>()
        for (idx in visibleIndices) {
            val obj = entries.getJSONObject(idx)
            if (obj.optBoolean("fav", false)) {
                favIdx.add(idx)
            } else {
                val folderName = if (obj.has("folder") && obj.optString("folder").isNotEmpty()) {
                    obj.optString("folder")
                } else {
                    "Boards"
                }
                groups.getOrPut(folderName) { mutableListOf() }.add(idx)
            }
        }
        for (i in favIdx) addRow(i)
        for (f in groups.keys.sorted()) {
            val list = groups[f]!!
            binding.boardList.addView(headerFor("$f  ·  ${list.size}"))
            for (i in list) addRow(i)
        }
        binding.emptyState.visibility =
            if (visibleIndices.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun addRow(idx: Int) {
        val obj = entries.getJSONObject(idx)
        val isPdf = obj.optString("type") == "pdf"
        val fav = obj.optBoolean("fav", false)
        val label = buildString {
            if (fav) append("★ ")
            else append("☆ ")
            append(obj.optString("title", ""))
            if (isPdf) append("  (PDF, ")
                .append(obj.optInt("pages", 1))
                .append(" pages)")
        }
        val row = TextView(this)
        row.text = label
        row.textSize = 15f
        row.setTextColor(getColor(R.color.textMain))
        row.setPadding(28, 40, 28, 40)
        row.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.card)))
        row.gravity = Gravity.CENTER_VERTICAL
        row.setOnClickListener { openViewer(idx) }
        row.setOnLongClickListener {
            confirmDelete(idx)
            true
        }
        binding.boardList.addView(row)
    }

    private fun headerFor(headerText: String): TextView = TextView(this).apply {
        text = headerText
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(getColor(R.color.accent))
        setPadding(28, 36, 28, 8)
    }

    private fun reloadQuiet() {
        val arr = BoardStore.load(this)
        for (i in entries.length() - 1 downTo 0) entries.remove(i)
        for (i in 0 until arr.length()) entries.put(arr.getJSONObject(i))
    }

    private fun openViewer(index: Int) {
        val intent = Intent(this, ViewerActivity::class.java)
        intent.putExtra("idx", index)
        startActivity(intent)
    }

    private fun confirmDelete(index: Int) {
        val entry = entries.getJSONObject(index)
        AlertDialog.Builder(this, R.style.SchematicsTheme)
            .setTitle("Delete board")
            .setMessage(entry.optString("title", ""))
            .setPositiveButton("Delete") { _, _ ->
                BoardStore.delete(this, index)
                renderList()
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            val mimeTypes = arrayOf("image/*", "application/pdf")
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        }
        startActivityForResult(intent, REQ_FILES)
    }

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, REQ_FOLDER)
    }

    @Deprecated("Legacy pattern kept for project codebase")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        when (requestCode) {
            REQ_FILES -> {
                val uris = mutableListOf<Uri>()
                data?.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                } ?: data?.data?.let { uris.add(it) }
                var ok = 0
                for (uri in uris) {
                    val name = queryName(uri) ?: continue
                    if (BoardStore.import(this, uri, name) != null) ok++
                }
                Toast.makeText(
                    this,
                    "Imported $ok / ${uris.size} board(s)",
                    Toast.LENGTH_SHORT
                ).show()
                reload()
            }
            REQ_FOLDER -> {
                data?.data?.let { treeUri ->
                    val folder = DocumentFile.fromTreeUri(this, treeUri)
                    if (folder == null) {
                        Toast.makeText(this, "Folder unreadable", Toast.LENGTH_SHORT).show()
                        return
                    }
                    val files = folder.listFiles()
                    var ok = 0
                    for (f in files) {
                        val n = f.name ?: continue
                        if (!isSchematicFile(n) || !f.canRead()) continue
                        if (BoardStore.import(this, f.uri, n) != null) ok++
                        if (ok >= 200) break
                    }
                    Toast.makeText(
                        this,
                        "Imported $ok file(s) from folder",
                        Toast.LENGTH_SHORT
                    ).show()
                    reload()
                }
            }
        }
    }

    private fun isSchematicFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".pdf")
    }

    private fun queryName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx)
        }
        return name
    }

    companion object {
        private const val REQ_FILES = 41
        private const val REQ_FOLDER = 42
    }
}
