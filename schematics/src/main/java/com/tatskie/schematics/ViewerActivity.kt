package com.tatskie.schematics

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tatskie.schematics.databinding.ActivityViewerBinding
import org.json.JSONObject
import java.io.File

class ViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewerBinding
    private var index = -1
    private lateinit var entry: JSONObject
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var page = 0
    private var hdLoaded = false
    private var lastPage = -1
    private val pdfLock = Any()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        index = intent.getIntExtra("idx", -1)
        if (index < 0 && intent.data != null) {
            verifyShareIn()
            return
        }
        if (index < 0) {
            finish()
            return
        }
        try {
            entry = BoardStore.load(this).getJSONObject(index)
        } catch (e: Exception) {
            finish()
            return
        }

        binding.titleText.text = entry.optString("title", "")
        setFavIcon()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnFav.setOnClickListener {
            val favNow = BoardStore.toggleFav(this, index)
            entry.put("fav", favNow)
            setFavIcon()
            Toast.makeText(
                this,
                if (favNow) "Bookmarked ⭐" else "Bookmark removed",
                Toast.LENGTH_SHORT
            ).show()
        }

        val isPdf = entry.optString("type") == "pdf"
        binding.zoomView.onZoomChanged = { s ->
            binding.zoomText.text = "${(s * 100).toInt()}%"
        }
        binding.zoomView.onCanvasTap = {
            val show = binding.topBar.visibility == View.GONE
            listOf<View>(binding.topBar, binding.bottomBar, binding.zoomPad).forEach {
                it.visibility = if (show) View.VISIBLE else View.GONE
            }
        }
        binding.btnZoomFit.setOnClickListener { binding.zoomView.zoomTo(1f) }
        binding.btnZoomOne.setOnClickListener { binding.zoomView.zoomToScale(1f) }
        binding.btnZoomMinus.setOnClickListener {
            binding.zoomView.zoomTo((binding.zoomView.currentLevel() / 1.4f).coerceAtLeast(0.35f))
        }
        binding.btnZoomPlus.setOnClickListener {
            binding.zoomView.zoomTo(binding.zoomView.currentLevel() * 1.4f)
        }
        if (isPdf) {
            openRenderer()
            hdLoaded = false
            binding.zoomView.onIdleZoom = { lvl -> if (lvl >= 2.5f) loadHd { renderPdfPage(2f) } }
            showPage(0)
            binding.btnPagePrev.setOnClickListener { if (page > 0) showPage(page - 1) }
            binding.btnPageNext.setOnClickListener {
                val count = renderer?.pageCount ?: 1
                if (page < count - 1) showPage(page + 1)
            }
        } else {
            binding.pageText.text = ""
            binding.btnPagePrev.visibility = View.INVISIBLE
            binding.btnPageNext.visibility = View.INVISIBLE
            hdLoaded = false
            val f = File(BoardStore.boardsDir(this), entry.optString("file", ""))
            binding.zoomView.onIdleZoom = { lvl -> if (lvl >= 2.5f) loadHd { decodeFullRes(f) } }
            binding.zoomView.setBitmap(decodeSampled())
        }

        binding.dimBar.max = 100
        binding.dimBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (progress <= 0) {
                    binding.dimOverlay.visibility = View.GONE
                    binding.dimOverlay.alpha = 0f
                } else {
                    binding.dimOverlay.visibility = View.VISIBLE
                    binding.dimOverlay.alpha = progress / 100f * 0.8f
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setFavIcon() {
        binding.btnFav.text = if (entry.optBoolean("fav", false)) "⭐" else "☆"
    }

    private fun verifyShareIn() {
        val uri = intent.data ?: run { finish(); return }
        binding.titleText.text = uri.lastPathSegment?.substringAfterLast('/') ?: "Shared file"
        binding.btnFav.visibility = android.view.View.INVISIBLE
        binding.btnBack.setOnClickListener { finish() }
        val mime = contentResolver.getType(uri) ?: ""
        if (mime.contains("pdf")) {
            try {
                pfd = contentResolver.openFileDescriptor(uri, "r")
                renderer = pfd?.let { PdfRenderer(it) }
                hdLoaded = false
                binding.zoomView.onIdleZoom = { lvl -> if (lvl >= 2.5f) loadHd { renderPdfPage(2f) } }
                showPage(0)
                binding.btnPagePrev.setOnClickListener { if (page > 0) showPage(page - 1) }
                binding.btnPageNext.setOnClickListener {
                    val count = renderer?.pageCount ?: 1
                    if (page < count - 1) showPage(page + 1)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Cannot open PDF: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
                return
            }
        } else {
            binding.pageText.text = ""
            binding.btnPagePrev.visibility = View.INVISIBLE
            binding.btnPageNext.visibility = View.INVISIBLE
            val bmp = decodeStreamSampled(uri)
            if (bmp == null) {
                Toast.makeText(this, "Cannot open image", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            hdLoaded = false
            binding.zoomView.onIdleZoom = { lvl -> if (lvl >= 2.5f) loadHd { decodeStreamFull(uri) } }
            binding.zoomView.setBitmap(bmp)
        }
        binding.dimBar.max = 100
        binding.dimBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (progress <= 0) {
                    binding.dimOverlay.visibility = View.GONE
                    binding.dimOverlay.alpha = 0f
                } else {
                    binding.dimOverlay.visibility = View.VISIBLE
                    binding.dimOverlay.alpha = progress / 100f * 0.8f
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun decodeStreamSampled(uri: android.net.Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) null
            else {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = maxOf(bounds.outWidth / 3000, bounds.outHeight / 3000).coerceAtLeast(1)
                }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun openRenderer() {
        val f = File(BoardStore.boardsDir(this), entry.optString("file", ""))
        pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(pfd!!)
    }

    private fun showPage(p: Int) {
        val r = renderer ?: return
        page = p
        if (p != lastPage) {
            hdLoaded = false
            lastPage = p
        }
        val bmp = renderPdfPage(1f) ?: return
        binding.zoomView.setBitmap(bmp)
        binding.pageText.text = "${page + 1}/${r.pageCount}"
    }

    private fun renderPdfPage(mult: Float): Bitmap? {
        val r = renderer ?: return null
        synchronized(pdfLock) {
            return try {
                r.openPage(page).use { pdfPage ->
                    val w = pdfPage.width
                    val h = pdfPage.height
                    val scale = if (mult > 1f) {
                        minOf(
                            (3000.0 / maxOf(w, h)).coerceIn(1.0, 3.0) * 2.0,
                            (4600.0 / maxOf(w, h)).coerceIn(1.0, 5.0)
                        )
                    } else {
                        (3000.0 / maxOf(w, h)).coerceIn(1.0, 3.0)
                    }
                    val bmp = Bitmap.createBitmap(
                        (w * scale).toInt(),
                        (h * scale).toInt(),
                        if (mult > 1f) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                    )
                    bmp.eraseColor(Color.WHITE)
                    pdfPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun loadHd(loader: () -> Bitmap?) {
        val previewW = binding.zoomView.drawable?.intrinsicWidth ?: 0
        if (hdLoaded) return
        hdLoaded = true
        Thread {
            val full = loader() ?: return@Thread
            runOnUiThread {
                if (!isFinishing && !isDestroyed) binding.zoomView.setBitmapHd(full, previewW)
            }
        }.start()
    }

    private fun decodeFullRes(f: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 5500) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(f.absolutePath, opts)
    }

    private fun decodeStreamFull(uri: android.net.Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) null
            else {
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 5500) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeSampled(): Bitmap {
        val f = File(BoardStore.boardsDir(this), entry.optString("file", ""))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 2400 || bounds.outHeight / (sample * 2) >= 2400) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(f.absolutePath, opts)
            ?: throw IllegalStateException("Bitmap decode failed")
    }

    override fun onDestroy() {
        try {
            renderer?.close()
        } catch (e: Exception) {
        }
        try {
            pfd?.close()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }
}
