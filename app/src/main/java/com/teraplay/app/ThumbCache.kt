package com.teraplay.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps every thumbnail as a file in filesDir/thumbs so it still shows without internet
 * (Terabox thumbnail links expire, so the picture itself is saved the first time we can).
 */
object ThumbCache {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    private val client = OkHttpClient()

    /** at most 3 thumbnails download at the same time (thousands at once would freeze the phone) */
    private val gate = Semaphore(3)

    /** names of the files that already exist, so checking a thumbnail never touches the disk */
    private val names = ConcurrentHashMap.newKeySet<String>()
    private val nameOfKey = ConcurrentHashMap<String, String>()
    @Volatile private var indexed = false

    private fun dir(ctx: Context): File {
        val d = File(ctx.applicationContext.filesDir, "thumbs")
        d.mkdirs()
        return d
    }

    private fun nameFor(key: String): String = nameOfKey.getOrPut(key) { Fmt.md5(key) + ".img" }

    /** Reads the list of saved thumbnails once (background thread, at app start). */
    fun warmUp(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            try {
                dir(app).list()?.forEach { if (it.endsWith(".img")) names.add(it) }
            } catch (e: Exception) {
                // ignore
            }
            indexed = true
        }.start()
    }

    fun fileForKey(ctx: Context, key: String): File = File(dir(ctx), nameFor(key))

    fun file(ctx: Context, e: LibEntry): File = fileForKey(ctx, e.key)

    fun isCachedKey(ctx: Context, key: String): Boolean {
        if (names.contains(nameFor(key))) return true
        if (indexed) return false
        val f = fileForKey(ctx, key)   // very first moments only, before the index is ready
        return f.exists() && f.length() > 0
    }

    fun isCached(ctx: Context, e: LibEntry): Boolean = isCachedKey(ctx, e.key)

    /** Downloads one thumbnail in the background (no-op if cached, offline, or already running). */
    fun ensureKey(ctx: Context, key: String, url: String) {
        if (url.isEmpty() || isCachedKey(ctx, key)) return
        if (!inflight.add(key)) return
        val app = ctx.applicationContext
        scope.launch {
            try {
                gate.withPermit {
                    val req = Request.Builder().url(url).header("User-Agent", TeraBoxClient.UA).build()
                    client.newCall(req).execute().use { r ->
                        val body = r.body
                        if (r.isSuccessful && body != null) {
                            val bytes = body.bytes()
                            if (bytes.isNotEmpty()) {
                                val target = fileForKey(app, key)
                                val tmp = File(target.parentFile, target.name + ".tmp")
                                tmp.writeBytes(bytes)
                                if (tmp.renameTo(target)) names.add(target.name)
                            }
                        }
                    }
                }
            } catch (ex: Exception) {
                // offline or expired link: try again next time
            } finally {
                inflight.remove(key)
            }
        }
    }

    fun ensure(ctx: Context, e: LibEntry) = ensureKey(ctx, e.key, e.thumb)

    /** True if a frame is (almost) completely black, i.e. not worth keeping as a thumbnail. */
    fun isMostlyBlack(b: Bitmap): Boolean {
        val s = Bitmap.createScaledBitmap(b, 8, 8, true)
        var sum = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            val c = s.getPixel(x, y)
            sum += (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
        }
        return sum / 64 < 12
    }

    /** Saves a frame grabbed from the player as this video's thumbnail. */
    fun saveBitmap(ctx: Context, e: LibEntry, src: Bitmap) {
        val app = ctx.applicationContext
        scope.launch {
            try {
                val w = 480
                val h = (src.height * (w.toFloat() / src.width)).toInt().coerceAtLeast(1)
                val out = Bitmap.createScaledBitmap(src, w, h, true)
                val target = file(app, e)
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                if (tmp.renameTo(target)) names.add(target.name)
            } catch (ex: Exception) {
                // ignore
            }
        }
    }

    /** Saves the thumbnails of every entry that still lacks one. */
    fun prefetchAll(ctx: Context, entries: List<LibEntry>) {
        entries.forEach { ensure(ctx, it) }
    }
}
