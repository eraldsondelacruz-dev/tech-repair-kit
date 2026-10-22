package com.tatskie.schematics

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object LibraryManager {
    const val LANE_GITHUB = 0
    const val LANE_TELEGRAM = 1

    private const val PREF = "library_cfg"
    private const val KEY_LANE = "lane"
    private const val KEY_URL = "manifest_url"
    private const val KEY_TOKEN = "bot_token"
    private const val KEY_FILE_ID = "manifest_file_id"
    private const val KEY_STATE = "mirror_state"
    private const val KEY_CACHE = "manifest_cache"
    private const val KEY_VERSION = "manifest_version"

    const val DEF_MANIFEST =
        "https://raw.githubusercontent.com/eraldsondelacruz-dev/tech-repair-kit/main/schematics/library.json"

    private fun cfg(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun getLane(ctx: Context): Int = cfg(ctx).getInt(KEY_LANE, LANE_GITHUB)

    fun setLane(ctx: Context, lane: Int) {
        cfg(ctx).edit().putInt(KEY_LANE, lane).apply()
    }

    fun getManifestUrl(ctx: Context): String = cfg(ctx).getString(KEY_URL, DEF_MANIFEST) ?: DEF_MANIFEST

    fun setManifestUrl(ctx: Context, url: String) {
        cfg(ctx).edit().putString(KEY_URL, url).apply()
    }

    fun getBotToken(ctx: Context): String = cfg(ctx).getString(KEY_TOKEN, "") ?: ""

    fun setBotToken(ctx: Context, token: String) {
        cfg(ctx).edit().putString(KEY_TOKEN, token).apply()
    }

    fun getManifestFileId(ctx: Context): String = cfg(ctx).getString(KEY_FILE_ID, "") ?: ""

    fun setManifestFileId(ctx: Context, id: String) {
        cfg(ctx).edit().putString(KEY_FILE_ID, id).apply()
    }

    fun mirrorDir(ctx: Context): File {
        val dir = File(ctx.filesDir, "library")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun mirror(ctx: Context, name: String): File = File(mirrorDir(ctx), name)

    private fun state(ctx: Context): JSONObject {
        val raw = cfg(ctx).getString(KEY_STATE, "{}") ?: "{}"
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun saveState(ctx: Context, st: JSONObject) {
        cfg(ctx).edit().putString(KEY_STATE, st.toString()).apply()
    }

    fun cachedManifest(ctx: Context): JSONArray {
        val raw = cfg(ctx).getString(KEY_CACHE, "[]") ?: "[]"
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    fun cachedVersion(ctx: Context): Int = cfg(ctx).getInt(KEY_VERSION, 0)

    fun md5Of(f: File): String {
        val md = MessageDigest.getInstance("MD5")
        FileInputStream(f).use { ins ->
            val buf = ByteArray(65536)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        val sb = StringBuilder()
        for (b in md.digest()) {
            val v = b.toInt() and 0xFF
            sb.append(String.format("%02x", v))
        }
        return sb.toString()
    }

    fun syncSize(size: Long): String = when {
        size >= 1048576L -> String.format("%.1f MB", size / 1048576.0)
        size >= 1024L -> String.format("%.0f KB", size / 1024.0)
        else -> "${size} B"
    }

    fun statusText(ctx: Context, name: String, manifestMd5: String): String {
        val dst = mirror(ctx, name)
        if (!dst.exists()) return "Not on device — tap to fetch"
        val md5 = if (manifestMd5.isNotEmpty()) md5Of(dst) else ""
        return if (manifestMd5.isNotEmpty() && md5 != manifestMd5) "Update available — resync"
        else "On device — offline ready"
    }

    fun sync(ctx: Context, onLog: (String) -> Unit): Int {
        val man = fetchManifest(ctx)
        val files = man.optJSONArray("files") ?: JSONArray()
        val version = man.optInt("version", 0)
        val st = state(ctx)
        var count = 0
        for (i in 0 until files.length()) {
            val e = files.getJSONObject(i)
            val name = e.optString("name")
            val wantMd5 = e.optString("md5")
            if (name.isEmpty()) continue
            val dst = mirror(ctx, name)
            val currentMd5 = if (dst.exists()) md5Of(dst) else ""
            if (currentMd5.isNotEmpty() && (wantMd5.isEmpty() || currentMd5 == wantMd5)) {
                mark(st, name, version, currentMd5, dst.length())
                continue
            }
            val url = e.optString("url")
            if (url.isEmpty()) continue
            onLog("Fetching ${e.optString("title", name)} …")
            val resolved = resolveUrl(ctx, url)
            val tmp = File(mirrorDir(ctx), "$name.part")
            try {
                fetchTo(resolved, tmp)
                val got = md5Of(tmp)
                if (wantMd5.isNotEmpty() && got != wantMd5) {
                    throw IllegalStateException("MD5 mismatch for $name")
                }
                if (dst.exists() && !dst.delete()) throw IllegalStateException("Cannot replace $name")
                if (!tmp.renameTo(dst)) throw IllegalStateException("Cannot move $name")
                mark(st, name, version, got, dst.length())
                count++
                onLog("Saved ${e.optString("title", name)}")
            } catch (ex: Exception) {
                tmp.delete()
                throw ex
            }
        }
        saveState(ctx, st)
        cfg(ctx).edit()
            .putString(KEY_CACHE, files.toString())
            .putInt(KEY_VERSION, version)
            .apply()
        return count
    }

    fun delete(ctx: Context, name: String) {
        val st = state(ctx)
        st.remove(name)
        saveState(ctx, st)
        mirror(ctx, name).delete()
    }

    private fun mark(st: JSONObject, name: String, version: Int, md5: String, size: Long) {
        val o = st.optJSONObject(name) ?: JSONObject()
        o.put("ver", version).put("md5", md5).put("size", size)
        st.put(name, o)
    }

    private fun resolveUrl(ctx: Context, url: String): String {
        if (url.startsWith("tg:")) {
            val token = getBotToken(ctx)
            if (token.isEmpty()) throw IllegalStateException("Telegram lane needs bot token")
            val fileId = url.removePrefix("tg:")
            val info = fetchJson("https://api.telegram.org/bot$token/getFile?file_id=$fileId")
            val path = info.getJSONObject("result").optString("file_path")
            return "https://api.telegram.org/file/bot$token/$path"
        }
        return url
    }

    private fun fetchManifest(ctx: Context): JSONObject {
        if (getLane(ctx) == LANE_TELEGRAM) {
            val token = getBotToken(ctx)
            val fileId = getManifestFileId(ctx)
            if (token.isEmpty() || fileId.isEmpty()) {
                throw IllegalStateException("Telegram lane needs bot token + manifest file id")
            }
            val info = fetchJson("https://api.telegram.org/bot$token/getFile?file_id=$fileId")
            val path = info.getJSONObject("result").optString("file_path")
            return JSONObject(fetchString("https://api.telegram.org/file/bot$token/$path"))
        }
        return JSONObject(fetchString(getManifestUrl(ctx)))
    }

    private fun fetchString(urlStr: String): String {
        val c = open(urlStr)
        try {
            val text = c.inputStream.bufferedReader().use { it.readText() }
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun fetchJson(urlStr: String): JSONObject = JSONObject(fetchString(urlStr))

    private fun fetchTo(urlStr: String, dst: File) {
        val c = open(urlStr)
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            c.inputStream.use { ins ->
                dst.outputStream().use { out -> ins.copyTo(out) }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun open(urlStr: String): HttpURLConnection {
        val c = URL(urlStr).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "TatskieAutoLibrary/1.0")
        return c
    }
}
