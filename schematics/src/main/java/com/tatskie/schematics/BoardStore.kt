package com.tatskie.schematics

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object BoardStore {
    private const val PREF = "boards"
    private const val KEY = "entries"

    fun load(context: Context): JSONArray {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    fun save(context: Context, entries: JSONArray) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, entries.toString())
            .apply()
    }

    fun boardsDir(context: Context): java.io.File {
        val dir = java.io.File(context.filesDir, "boards")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun import(context: Context, uri: android.net.Uri, title: String): JSONObject? {
        val mime = context.contentResolver.getType(uri) ?: ""
        val isPdf = mime.contains("pdf") || title.lowercase().endsWith(".pdf")
        val ext = when {
            isPdf -> "pdf"
            mime.contains("png") || title.lowercase().endsWith(".png") -> "png"
            mime.contains("webp") || title.lowercase().endsWith(".webp") -> "webp"
            else -> "jpg"
        }
        val name = "${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}.$ext"
        val dst = java.io.File(boardsDir(context), name)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            val entry = JSONObject()
            entry.put("title", title)
            entry.put("file", name)
            entry.put("type", if (isPdf) "pdf" else "img")
            entry.put("fav", false)
            if (isPdf) {
                var pages = 1
                try {
                    android.os.ParcelFileDescriptor.open(
                        dst,
                        android.os.ParcelFileDescriptor.MODE_READ_ONLY
                    ).use { pfd ->
                        val r = android.graphics.pdf.PdfRenderer(pfd)
                        pages = r.pageCount
                        r.close()
                    }
                } catch (e: Exception) {
                    pages = 1
                }
                entry.put("pages", pages)
            }
            val arr = load(context)
            val newArr = JSONArray()
            newArr.put(entry)
            for (i in 0 until arr.length()) newArr.put(arr.getJSONObject(i))
            save(context, newArr)
            entry
        } catch (e: Exception) {
            dst.delete()
            null
        }
    }

    fun delete(context: Context, index: Int) {
        val arr = load(context)
        if (index < 0 || index >= arr.length()) return
        val entry = arr.getJSONObject(index)
        val f = java.io.File(boardsDir(context), entry.optString("file"))
        if (f.exists()) f.delete()
        val newArr = JSONArray()
        for (i in 0 until arr.length()) if (i != index) newArr.put(arr.getJSONObject(i))
        save(context, newArr)
    }

    fun toggleFav(context: Context, index: Int): Boolean {
        val arr = load(context)
        if (index < 0 || index >= arr.length()) return false
        val entry = arr.getJSONObject(index)
        val newFav = !entry.optBoolean("fav", false)
        entry.put("fav", newFav)
        save(context, arr)
        return newFav
    }

    fun importFile(context: Context, src: java.io.File, title: String, libSrc: String): JSONObject? {
        val lower = src.name.lowercase()
        val isPdf = lower.endsWith(".pdf")
        val ext = if (lower.contains('.')) lower.substringAfterLast('.') else "png"
        val name = "${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}.$ext"
        val dst = java.io.File(boardsDir(context), name)
        return try {
            src.copyTo(dst, overwrite = true)
            val entry = JSONObject()
            entry.put("title", title)
            entry.put("file", name)
            entry.put("type", if (isPdf) "pdf" else "img")
            entry.put("fav", false)
            entry.put("libSrc", libSrc)
            if (isPdf) {
                var pages = 1
                try {
                    android.os.ParcelFileDescriptor.open(
                        dst,
                        android.os.ParcelFileDescriptor.MODE_READ_ONLY
                    ).use { pfd ->
                        val r = android.graphics.pdf.PdfRenderer(pfd)
                        pages = r.pageCount
                        r.close()
                    }
                } catch (e: Exception) {
                    pages = 1
                }
                entry.put("pages", pages)
            }
            val arr = load(context)
            val newArr = JSONArray()
            newArr.put(entry)
            for (i in 0 until arr.length()) newArr.put(arr.getJSONObject(i))
            save(context, newArr)
            entry
        } catch (e: Exception) {
            dst.delete()
            null
        }
    }

    fun indexOfTitle(context: Context, title: String): Int {
        val arr = load(context)
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("title", "") == title) return i
        }
        return -1
    }
}
