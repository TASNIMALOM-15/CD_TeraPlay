package com.teraplay.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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
    val key: String = if (surl.isEmpty()) "acc$accountId:$fsId" else "$surl:$fsId"

    /** sort key (Ep2 before Ep10), computed once instead of at every comparison */
    val natKey: String = Fmt.nat(name)
}

/**
 * Permanent storage: filesDir/library.json and filesDir/playlists.json.
 *
 * The whole library is kept in memory (read from disk once); changes are written back to disk a moment later,
 * in one go, on a background thread. This keeps the screens fast even with thousands of videos.
 */
class LibraryStore(ctx: Context) {

    companion object {
        private val lock = Any()
        private var libFile: File? = null
        private var plFile: File? = null
        private var cache: MutableList<LibEntry>? = null
        private var plCache: LinkedHashMap<String, MutableList<String>>? = null
        private var libDirty = false
        private var plDirty = false
        private val writer = Executors.newSingleThreadScheduledExecutor()
        private var pendingWrite: ScheduledFuture<*>? = null

        /** Goes up with every change; screens compare it to know whether they must redraw. */
        @Volatile
        var version = 0L

        /** Reads the library into memory (call from a background thread at app start). */
        fun warmUp(ctx: Context) {
            LibraryStore(ctx).load()
            LibraryStore(ctx).loadPlaylists()
        }

        /** Writes pending changes to disk right now (e.g. when the screen goes away). */
        fun flush() {
            synchronized(lock) { pendingWrite?.cancel(false) }
            persistNow()
        }

        private fun schedulePersist() {
            pendingWrite?.cancel(false)
            pendingWrite = writer.schedule({ persistNow() }, 400, TimeUnit.MILLISECONDS)
        }

        private fun persistNow() {
            var lib: List<LibEntry>? = null
            var pl: Map<String, List<String>>? = null
            synchronized(lock) {
                if (libDirty) {
                    lib = ArrayList(cache ?: emptyList())
                    libDirty = false
                }
                if (plDirty) {
                    pl = (plCache ?: LinkedHashMap()).mapValues { it.value.toList() }
                    plDirty = false
                }
            }
            try {
                val l = lib
                val lf = libFile
                if (l != null && lf != null) {
                    val arr = JSONArray()
                    l.forEach {
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
                    val tmp = File(lf.path + ".tmp")
                    tmp.writeText(arr.toString())
                    tmp.renameTo(lf)
                }
                val p = pl
                val pf = plFile
                if (p != null && pf != null) {
                    val o = JSONObject()
                    p.forEach { (n, items) -> o.put(n, JSONArray(items)) }
                    val tmp = File(pf.path + ".tmp")
                    tmp.writeText(o.toString())
                    tmp.renameTo(pf)
                }
            } catch (e: Exception) {
                // try again with the next change
            }
        }
    }

    init {
        synchronized(lock) {
            if (libFile == null) {
                val dir = ctx.applicationContext.filesDir
                libFile = File(dir, "library.json")
                plFile = File(dir, "playlists.json")
            }
        }
    }

    /** A snapshot copy: safe to loop over while other threads change the library. */
    fun load(): MutableList<LibEntry> = synchronized(lock) { ArrayList(loadUnlocked()) }

    /** Everything except the videos in the Dislike playlist. */
    fun loadVisible(): List<LibEntry> = load().filter { !it.disliked }

    fun removeAll(entries: List<LibEntry>) = synchronized(lock) {
        val keys = entries.map { it.key }.toSet()
        saveUnlocked(loadUnlocked().filter { it.key !in keys })
        val pls = loadPlaylistsUnlocked()
        pls.values.forEach { l -> l.removeAll { it in keys } }
        savePlaylistsUnlocked(pls)
    }

    /** The live in-memory list (only use inside synchronized(lock)). */
    private fun loadUnlocked(): MutableList<LibEntry> {
        cache?.let { return it }
        val out = mutableListOf<LibEntry>()
        val f = libFile
        if (f != null && f.exists()) {
            try {
                val arr = JSONArray(f.readText())
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
        }
        cache = out
        return out
    }

    private fun saveUnlocked(list: List<LibEntry>) {
        cache = ArrayList(list)
        libDirty = true
        version++
        schedulePersist()
    }

    /** Adds new videos (duplicates skipped). Returns how many were really added. */
    fun addAll(surl: String, files: List<ShareFile>, accountOf: Map<Long, String> = emptyMap()): Int = synchronized(lock) {
        val list = loadUnlocked()
        var added = 0
        val now = System.currentTimeMillis()
        val have = HashSet<String>(list.size * 2)
        list.forEach { have.add(it.key) }
        for (f in files) {
            if (!have.add("$surl:${f.fsId}")) continue
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
        val index = HashMap<String, Int>(list.size * 2)
        list.forEachIndexed { i, x -> index[x.key] = i }
        for (e in entries) {
            val i = index[e.key]
            if (i != null) {
                if (list[i].thumb.isEmpty() && e.thumb.isNotEmpty()) list[i] = list[i].copy(thumb = e.thumb)
                continue
            }
            index[e.key] = list.size
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

    fun loadPlaylists(): LinkedHashMap<String, MutableList<String>> = synchronized(lock) {
        val copy = LinkedHashMap<String, MutableList<String>>()
        loadPlaylistsUnlocked().forEach { (n, l) -> copy[n] = l.toMutableList() }
        copy
    }

    /** The live in-memory playlists (only use inside synchronized(lock)). */
    private fun loadPlaylistsUnlocked(): LinkedHashMap<String, MutableList<String>> {
        plCache?.let { return it }
        val out = LinkedHashMap<String, MutableList<String>>()
        val f = plFile
        if (f != null && f.exists()) {
            try {
                val o = JSONObject(f.readText())
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
        }
        plCache = out
        return out
    }

    private fun savePlaylistsUnlocked(pls: Map<String, List<String>>) {
        val copy = LinkedHashMap<String, MutableList<String>>()
        pls.forEach { (n, l) -> copy[n] = l.toMutableList() }
        plCache = copy
        plDirty = true
        version++
        schedulePersist()
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
