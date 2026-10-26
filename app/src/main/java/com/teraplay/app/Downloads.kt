package com.teraplay.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** What is being downloaded right now, plus helpers for the downloaded files on the phone. */
object Downloads {
    /** library key -> percent, while queued or downloading */
    val progress = ConcurrentHashMap<String, Int>()

    /** library key -> last error message */
    val errors = ConcurrentHashMap<String, String>()

    @Volatile
    var onChange: (() -> Unit)? = null

    /** set after files were deleted from an account, so the Files tab reloads its list */
    @Volatile
    var remoteChanged = false

    fun changed() {
        onChange?.invoke()
    }

    fun isActive(key: String): Boolean = progress.containsKey(key)

    fun dir(ctx: Context): File {
        val d = ctx.getExternalFilesDir("downloads") ?: File(ctx.filesDir, "downloads")
        d.mkdirs()
        return d
    }

    fun fileFor(ctx: Context, key: String): File = File(dir(ctx), Fmt.md5(key) + ".ts")

    fun isDownloaded(e: LibEntry): Boolean = e.localPath.isNotEmpty() && File(e.localPath).exists()

    fun enqueue(ctx: Context, key: String) {
        if (progress.containsKey(key)) return
        progress[key] = 0
        errors.remove(key)
        PendingList(ctx, "dl").add(key)
        startService(ctx)
        changed()
    }

    private fun startService(ctx: Context) {
        ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
    }

    /** Called when the app opens: continues downloads that were cut short. */
    fun resumeIfNeeded(ctx: Context) {
        val pending = PendingList(ctx, "dl").all()
        if (pending.isNotEmpty()) {
            pending.forEach { progress.putIfAbsent(it, 0) }
            startService(ctx)
        }
    }

    /** Deletes the file from the phone (the library entry stays). */
    fun delete(store: LibraryStore, e: LibEntry) {
        if (e.localPath.isNotEmpty()) File(e.localPath).delete()
        store.update(e.copy(localPath = ""))
        changed()
    }
}
