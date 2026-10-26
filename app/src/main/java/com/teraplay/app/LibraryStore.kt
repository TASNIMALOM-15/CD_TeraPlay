package com.teraplay.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LibEntry(
    val surl: String,
    val fsId: Long,
    val name: String,
    val folder: String,
    val size: Long,
    val duration: Int,          // seconds (0 = unknown until first play)
    val thumb: String,
    val addedAt: Long,
    val favorite: Boolean = false,
    val lastPos: Long = 0,      // ms
    val lastWatched: Long = 0,
    val resolution: Int = 0,    // video height, learned at playback
    val remotePath: String = "", // copy inside the user's own Terabox account
    val accountId: String = "",  // which saved account holds the copy
    val localPath: String = "",  // downloaded copy on this phone (offline playback)
    val disliked: Boolean = false // hidden from the library, listed in the Dislike playlist
) {
    /** Share-link videos: "surl:fsId". Files picked from an account's own storage have no surl. */
    val key: String get() = if (surl.isEmpty()) "acc$accountId:$fsId" else "$surl:$fsId"
}

/** Permanent storage: filesDir/library.json and filesDir/playlists.json. */
class LibraryStore(ctx: Context) {
    private val file = File(ctx.filesDir, "library.json")
    private val plFile = File(ctx.filesDir, "playlists.json")

    companion object {
        private val lock = Any()
    }

    fun load(): MutableList<LibEntry> = synchronized(lock) { loadUnlocked() }

    /** Everything except the videos in the Dislike playlist. */
    fun loadVisible(): List<LibEntry> = load().filter { !it.disliked }

    fun removeAll(entries: List<LibEntry>) = synchronized(lock) {
        val keys = entries.map { it.key }.toSet()
        saveUnlocked(loadUnlocked().filter { it.key !in keys })
        val pls = loadPlaylistsUnlocked()
        pls.values.forEach { l -> l.removeAll { it in keys } }
        savePlaylistsUnlocked(pls)
    }

    private fun loadUnlocked(): MutableList<LibEntry> {
        val out = mutableListOf<LibEntry>()
        if (!file.exists()) return out
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    LibEntry(
                        surl = o.getString("surl"),
                        fsId = o.getLong("fsId"),
                        name = o.getString("name"),
                        folder = o.optString("folder", ""),
                        size = o.optLong("size", 0),
                        duration = o.optInt("duration", 0),
                        thumb = o.optString("thumb", ""),
                        addedAt = o.optLong("addedAt", 0),
                        favorite = o.optBoolean("favorite", false),
                        lastPos = o.optLong("lastPos", 0),
                        lastWatched = o.optLong("lastWatched", 0),
                        resolution = o.optInt("resolution", 0),
                        remotePath = o.optString("remotePath", ""),
                        accountId = o.optString("accountId", ""),
                        localPath = o.optString("localPath", ""),
                        disliked = o.optBoolean("disliked", false)
                    )
                )
            }
        } catch (e: Exception) {
            // corrupted file: start empty
        }
        return out
    }

    private fun saveUnlocked(list: List<LibEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("surl", it.surl).put("fsId", it.fsId).put("name", it.name)
                    .put("folder", it.folder).put("size", it.size).put("duration", it.duration)
                    .put("thumb", it.thumb).put("addedAt", it.addedAt)
                    .put("favorite", it.favorite).put("lastPos", it.lastPos)
                    .put("lastWatched", it.lastWatched).put("resolution", it.resolution)
                    .put("remotePath", it.remotePath).put("accountId", it.accountId)
                    .put("localPath", it.localPath).put("disliked", it.disliked)
            )
        }
        file.writeText(arr.toString())
    }

    /** Adds new videos (duplicates skipped). Returns how many were really added. */
    fun addAll(surl: String, files: List<ShareFile>, accountOf: Map<Long, String> = emptyMap()): Int = synchronized(lock) {
        val list = loadUnlocked()
        var added = 0
        val now = System.currentTimeMillis()
        for (f in files) {
            if (list.any { it.surl == surl && it.fsId == f.fsId }) continue
            val folder = f.path.substringBeforeLast('/', "").ifEmpty { "/" }
            list.add(LibEntry(surl, f.fsId, f.name, folder, f.size, f.duration, f.thumb, now, accountId = accountOf[f.fsId] ?: ""))
            added++
        }
        saveUnlocked(list)
        added
    }

    fun update(e: LibEntry) = synchronized(lock) {
        val list = loadUnlocked()
        val i = list.indexOfFirst { it.key == e.key }
        if (i >= 0) {
            list[i] = e
            saveUnlocked(list)
        }
    }

    /** Adds ready-made entries (e.g. picked in the Files tab); existing keys are skipped. */
    fun addEntries(entries: List<LibEntry>): Int = synchronized(lock) {
        val list = loadUnlocked()
        var added = 0
        for (e in entries) {
            val i = list.indexOfFirst { it.key == e.key }
            if (i >= 0) {
                if (list[i].thumb.isEmpty() && e.thumb.isNotEmpty()) list[i] = list[i].copy(thumb = e.thumb)
                continue
            }
            list.add(e)
            added++
        }
        saveUnlocked(list)
        added
    }

    /** Files-tab browsing: entries of [accountId] that have no thumbnail yet get the one Terabox just sent. */
    fun fillThumbs(accountId: String, files: List<ShareFile>) = synchronized(lock) {
        val withThumb = files.filter { it.thumb.isNotEmpty() }.associateBy { it.path }
        if (withThumb.isNotEmpty()) {
            val list = loadUnlocked()
            var changed = false
            for (i in list.indices) {
                val e = list[i]
                if (e.accountId != accountId || e.thumb.isNotEmpty() || e.remotePath.isEmpty()) continue
                val f = withThumb[e.remotePath] ?: continue
                list[i] = e.copy(thumb = f.thumb)
                changed = true
            }
            if (changed) saveUnlocked(list)
        }
    }

    fun setDuration(key: String, seconds: Int) = synchronized(lock) {
        val list = loadUnlocked()
        val i = list.indexOfFirst { it.key == key }
        if (i >= 0 && list[i].duration <= 0) {
            list[i] = list[i].copy(duration = seconds)
            saveUnlocked(list)
        }
    }

    fun updateMany(updates: List<LibEntry>) = synchronized(lock) {
        val byKey = updates.associateBy { it.key }
        saveUnlocked(loadUnlocked().map { byKey[it.key] ?: it })
    }

    fun remove(e: LibEntry) = synchronized(lock) {
        saveUnlocked(loadUnlocked().filter { it.key != e.key })
        val pls = loadPlaylistsUnlocked()
        pls.values.forEach { it.remove(e.key) }
        savePlaylistsUnlocked(pls)
    }

    // ------------------------------------------------------------ playlists

    fun loadPlaylists(): LinkedHashMap<String, MutableList<String>> =
        synchronized(lock) { loadPlaylistsUnlocked() }

    private fun loadPlaylistsUnlocked(): LinkedHashMap<String, MutableList<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        if (!plFile.exists()) return out
        try {
            val o = JSONObject(plFile.readText())
            val names = o.keys()
            while (names.hasNext()) {
                val n = names.next()
                val arr = o.getJSONArray(n)
                val l = mutableListOf<String>()
                for (i in 0 until arr.length()) l.add(arr.getString(i))
                out[n] = l
            }
        } catch (e: Exception) {
            // ignore
        }
        return out
    }

    private fun savePlaylistsUnlocked(pls: Map<String, List<String>>) {
        val o = JSONObject()
        pls.forEach { (n, l) -> o.put(n, JSONArray(l)) }
        plFile.writeText(o.toString())
    }

    fun addToPlaylist(name: String, key: String) = synchronized(lock) {
        val pls = loadPlaylistsUnlocked()
        val l = pls.getOrPut(name) { mutableListOf() }
        if (!l.contains(key)) l.add(key)
        savePlaylistsUnlocked(pls)
    }

    fun createPlaylist(name: String) = synchronized(lock) {
        val pls = loadPlaylistsUnlocked()
        pls.getOrPut(name) { mutableListOf() }
        savePlaylistsUnlocked(pls)
    }

    fun removeFromPlaylist(name: String, key: String) = synchronized(lock) {
        val pls = loadPlaylistsUnlocked()
        pls[name]?.remove(key)
        savePlaylistsUnlocked(pls)
    }

    fun deletePlaylist(name: String) = synchronized(lock) {
        val pls = loadPlaylistsUnlocked()
        pls.remove(name)
        savePlaylistsUnlocked(pls)
    }
}
