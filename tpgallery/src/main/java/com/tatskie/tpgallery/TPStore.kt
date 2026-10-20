package com.tatskie.tpgallery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object TPStore {
    private const val PREF = "testpoints"
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

    fun photosDir(context: Context): java.io.File {
        val dir = java.io.File(context.filesDir, "testpoints")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun import(context: Context, uri: android.net.Uri, title: String): JSONObject? {
        val mime = context.contentResolver.getType(uri) ?: ""
        val ext = when {
            mime.contains("png") || title.lowercase().endsWith(".png") -> "png"
            mime.contains("webp") || title.lowercase().endsWith(".webp") -> "webp"
            else -> "jpg"
        }
        val name = "${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}.$ext"
        val dst = java.io.File(photosDir(context), name)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            val entry = JSONObject()
            entry.put("title", title)
            entry.put("file", name)
            entry.put("fav", false)
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
        val f = java.io.File(photosDir(context), entry.optString("file"))
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
}
