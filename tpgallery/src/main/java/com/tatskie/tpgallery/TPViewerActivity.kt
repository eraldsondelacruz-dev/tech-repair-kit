package com.tatskie.tpgallery

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tatskie.tpgallery.databinding.ActivityTpviewerBinding
import org.json.JSONArray
import java.io.File

class TPViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTpviewerBinding
    private var index = -1
    private var entry: org.json.JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTpviewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        index = intent.getIntExtra("idx", -1)
        val arr = TPStore.load(this)
        entry = if (index in 0 until arr.length()) arr.getJSONObject(index) else null

        val e = entry
        if (e == null) {
            Toast.makeText(this, "Entry missing", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.titleText.text = e.optString("title", "")
        renderFav(e.optBoolean("fav", false))

        val imgFile = File(TPStore.photosDir(this), e.optString("file", ""))
        if (imgFile.exists()) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imgFile.absolutePath, opts)
            val sample = maxOf(
                opts.outWidth / 3000,
                opts.outHeight / 3000,
                1
            ).coerceAtLeast(1)
            val bmp = BitmapFactory.decodeFile(
                imgFile.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
            binding.zoomView.setImageBitmap(bmp)
        } else {
            Toast.makeText(this, "File missing", Toast.LENGTH_SHORT).show()
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnFav.setOnClickListener {
            val newFav = TPStore.toggleFav(this, index)
            renderFav(newFav)
        }

        binding.dimBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (progress == 0) {
                    binding.dimOverlay.visibility = View.GONE
                } else {
                    binding.dimOverlay.visibility = View.VISIBLE
                    binding.dimOverlay.alpha = progress / 100.0f * 0.85f
                }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
    }

    private fun renderFav(fav: Boolean) {
        binding.btnFav.text = if (fav) "★" else "☆"
        binding.btnFav.setTextColor(if (fav) getColor(R.color.accent) else getColor(R.color.textMain))
    }
}
