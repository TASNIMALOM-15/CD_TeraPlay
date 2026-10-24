package com.teraplay.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps every thumbnail as a file in filesDir/thumbs so it still shows without internet
 * (Terabox thumbnail links expire, so the picture itself is saved the first time we can).
 */
object ThumbCache {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    private val client = OkHttpClient()

    fun file(ctx: Context, e: LibEntry): File {
        val dir = File(ctx.filesDir, "thumbs")
        dir.mkdirs()
        val md = MessageDigest.getInstance("MD5").digest(e.key.toByteArray())
        val name = md.joinToString("") { String.format("%02x", it) }
        return File(dir, "$name.img")
    }

    fun isCached(ctx: Context, e: LibEntry): Boolean {
        val f = file(ctx, e)
        return f.exists() && f.length() > 0
    }

    /** Downloads one thumbnail in the background (no-op if cached, offline, or already running). */
    fun ensure(ctx: Context, e: LibEntry) {
        if (e.thumb.isEmpty() || isCached(ctx, e)) return
        if (!inflight.add(e.key)) return
        val app = ctx.applicationContext
        scope.launch {
            try {
                val req = Request.Builder().url(e.thumb).header("User-Agent", TeraBoxClient.UA).build()
                client.newCall(req).execute().use { r ->
                    val body = r.body
                    if (r.isSuccessful && body != null) {
                        val bytes = body.bytes()
                        if (bytes.isNotEmpty()) {
                            val target = file(app, e)
                            val tmp = File(target.parentFile, target.name + ".tmp")
                            tmp.writeBytes(bytes)
                            tmp.renameTo(target)
                        }
                    }
                }
            } catch (ex: Exception) {
                // offline or expired link: try again next time
            } finally {
                inflight.remove(e.key)
            }
        }
    }

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
                tmp.renameTo(target)
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
