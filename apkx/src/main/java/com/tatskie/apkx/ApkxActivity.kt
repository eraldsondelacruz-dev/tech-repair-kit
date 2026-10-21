package com.tatskie.apkx

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile

class ApkxActivity : AppCompatActivity() {

    private lateinit var pm: PackageManager
    private lateinit var list: ListView
    private lateinit var search: EditText
    private var apps: MutableList<Pkg> = mutableListOf()
    private var shown: MutableList<Pkg> = mutableListOf()

    private data class Pkg(
        val label: String,
        val pkg: String,
        val versionName: String,
        val versionCode: Long,
        val source: String,
        val splits: Array<String>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apkx)
        pm = packageManager
        list = findViewById(R.id.apps)
        search = findViewById(R.id.search)
        load()

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase() ?: ""
                shown = apps.filter {
                    it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q)
                }.toMutableList()
                render()
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        list.setOnItemClickListener { _, _, pos, _ -> menu(shown[pos]) }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        apps = mutableListOf()
        val flags =
            PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS
        val packs = try {
            pm.getInstalledPackages(flags)
        } catch (e: Exception) {
            null
        }
        packs?.forEach { p ->
            runCatching {
                val ai = pm.getApplicationInfo(p.packageName, 0)
                apps.add(
                    Pkg(
                        label = pm.getApplicationLabel(ai).toString(),
                        pkg = p.packageName,
                        versionName = p.versionName ?: "?",
                        versionCode = p.longVersionCode,
                        source = ai.sourceDir ?: "",
                        splits = ai.splitSourceDirs ?: emptyArray()
                    )
                )
            }
        }
        apps.sortBy { it.label.lowercase() }
        shown = apps.toMutableList()
        render()
    }

    private fun render() {
        val rows = shown.map { "${it.label}\n${it.pkg}  v${it.versionName} (${it.versionCode})" }
        @Suppress("DEPRECATION")
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows) {
            override fun getView(pos: Int, cv: android.view.View?, parent: ViewGroup): android.view.View {
                val v = super.getView(pos, cv, parent) as TextView
                v.textSize = 13f
                return v
            }
        }
        list.adapter = adapter
    }

    private fun menu(p: Pkg) {
        val items = arrayOf(
            "Extract ALL apk pieces (base + splits)",
            "Juice report (scan engine inside)",
            "Copy pm path list",
            "Share base apk"
        )
        AlertDialog.Builder(this)
            .setTitle("${p.label}\n${p.pkg}")
            .setItems(items) { _, w ->
                when (w) {
                    0 -> extract(p)
                    1 -> juice(p)
                    2 -> copyPaths(p)
                    3 -> shareBase(p)
                }
            }
            .show()
    }

    private fun outDir(p: Pkg): File =
        File(getExternalFilesDir(null), "apks/${p.pkg}").apply { mkdirs() }

    private fun extract(p: Pkg) {
        val dir = outDir(p)
        val copied = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val paths = listOf(p.source) + p.splits.toList()

        for (src in paths) {
            if (src.isBlank()) continue
            val name = File(src).name
            val dst = File(dir, name)
            if (copyDirect(src, dst)) copied.add(name)
            else if (copyViaSu(src, dst)) copied.add(name)
            else failed.add(name)
        }

        val msg = buildString {
            append("Saved to:\n${dir.path}\n\n")
            if (copied.isNotEmpty()) append("OK: ${copied.joinToString()}\n")
            if (failed.isNotEmpty()) append("FAILED: ${failed.joinToString()}\n(needs root — open Termux: su, then cp the paths)")
        }
        AlertDialog.Builder(this).setTitle("Extraction done").setMessage(msg).show()
    }

    private fun copyDirect(src: String, dst: File): Boolean = try {
        FileInputStream(src).use { i -> FileOutputStream(dst).use { o -> i.copyTo(o, 1 shl 20) } }
        dst.length() > 0
    } catch (e: Exception) {
        dst.delete()
        false
    }

    private fun copyViaSu(src: String, dst: File): Boolean = try {
        val pr = Runtime.getRuntime().exec(arrayOf("su", "-c", "cp '${src}' '${dst.absolutePath}'"))
        pr.waitFor()
        pr.destroy()
        dst.length() > 0
    } catch (e: Exception) {
        false
    }

    private fun juice(p: Pkg) {
        val apkFile = pickApk(p)
        if (apkFile == null) {
            Toast.makeText(this, "Extract first, then juice again!", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            ZipFile(apkFile).use { z ->
                val es = z.entries().asSequence().toList()
                val names = es.map { it.name }
                val sv = { key: String -> names.count { it.contains(key, ignoreCase = true) } }
                val engine = mutableListOf<String>()
                if (sv("libflutter.so") > 0) engine.add("Flutter/Dart (libapp.so x${sv("libapp.so")})")
                if (sv("libunity.so") > 0) engine.add("Unity")
                if (sv("libreactnativejni.so") > 0) engine.add("React Native")
                if (sv("libcordova.so") > 0) engine.add("Cordova")
                if (sv("libpdfium.so") > 0) engine.add("PDF viewer (pdfium)")
                if (sv("libopencv") > 0) engine.add("OpenCV image engine")
                if (sv("libcardio") > 0) engine.add("card.io camera scanner (payment SDK)")
                if (sv("libskia") > 0) engine.add("Skia graphics")
                if (sv("libc++_shared.so") > 0) engine.add("native C++ (libc++)")
                if (sv("libsuperuser") > 0) engine.add("root helper lib")
                val dex = names.count { it.endsWith(".dex") }
                val assets = names.count { it.startsWith("assets/") }
                val sos = names.count { it.endsWith(".so") }
                val abis = names.filter { it.startsWith("lib/") && it.endsWith(".so") }
                    .mapNotNull { n -> n.split("/").getOrNull(1) }.distinct()
                val nativeBig = es.filter { it.name.endsWith(".so") }
                    .sortedByDescending { it.size }.take(5)
                    .joinToString("\n") { "${it.name.replace("lib/", "lib/")}  (${fmt(it.size)})" }
                val report = buildString {
                    appendLine("ENGINE: ${if (engine.isEmpty()) "plain Java/Kotlin (no native fuzz)" else engine.joinToString(" + ")}")
                    appendLine("dex files: $dex   .so files: $sos   asset entries: $assets")
                    appendLine("ABIs: ${abis.joinToString()}")
                    appendLine()
                    appendLine("Fat native libs:")
                    appendLine(nativeBig)
                    appendLine()
                    appendLine("Offline bundle hint: asset entries $assets → ${if (assets > 500) "BIG bundle (data packed inside!)" else "small packer (server-fed app?)"}")
                }
                AlertDialog.Builder(this)
                    .setTitle("Juice: ${p.label}")
                    .setMessage(report)
                    .show()
            }
        }.onFailure {
            Toast.makeText(this, "Zip read failed: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickApk(p: Pkg): File? {
        val dir = outDir(p)
        val copied = File(dir, File(p.source).name)
        if (copied.isFile) return copied
        return if (copyDirect(p.source, copied) || copyViaSu(p.source, copied)) copied else null
    }

    private fun copyPaths(p: Pkg) {
        val text = (listOf(p.source) + p.splits.toList()).joinToString("\n")
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("pm path", text))
        Toast.makeText(this, "pm path list copied → paste to me!", Toast.LENGTH_LONG).show()
    }

    private fun shareBase(p: Pkg) {
        val f = pickApk(p) ?: run {
            Toast.makeText(this, "Extract first!", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Share base apk"))
    }

    private fun fmt(b: Long): String = when {
        b >= (1 shl 20) -> "%.1f MB".format(b / 1048576.0)
        b >= 1024 -> "%.1f KB".format(b / 1024.0)
        else -> "$b B"
    }
}
