package com.teraplay.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs link imports (copying into the account) and account scans in the background, one after another.
 * It is a foreground service, so it keeps going when the app is closed; the job list is saved on disk,
 * so after a kill the unfinished job starts again (every job skips what is already done).
 */
class WorkService : Service() {

    companion object {
        private const val CHANNEL = "work"
        private const val NOTIF_ID = 78
        private const val MAX_TRIES = 6

        fun enqueueImport(ctx: Context, link: String) {
            PendingList(ctx, "work").add("I|$link")
            start(ctx)
        }

        /** Finds every video inside a folder of an account and adds it to the library (Files tab, long-press on a folder). */
        fun enqueueFolder(ctx: Context, accountId: String, path: String) {
            PendingList(ctx, "work").add("D|$accountId\t$path")
            start(ctx)
        }

        /** Deletes the given videos from Terabox, the phone and the library (Dislike playlist, "delete all"). */
        fun enqueuePurge(ctx: Context, keys: List<String>) {
            PurgeState.running = true
            PendingList(ctx, "work").add("X|" + keys.joinToString("\t"))
            start(ctx)
        }

        /**
         * Dislike -> the file moves into a "Dislike" folder inside its own folder on Terabox;
         * restoring the video moves it back. Decided from the video's current state when the job runs.
         */
        fun enqueueMove(ctx: Context, key: String) {
            PendingList(ctx, "work").add("M|$key")
            start(ctx)
        }

        fun enqueueScan(ctx: Context, accountId: String) {
            if (AccountScanner.running.putIfAbsent(accountId, 0) != null) return
            PendingList(ctx, "work").add("S|$accountId")
            AccountScanner.onChange?.invoke()
            start(ctx)
        }

        /** Also called at app start: continues jobs that were interrupted. */
        fun resumeIfNeeded(ctx: Context) {
            if (PendingList(ctx, "work").first() != null) start(ctx)
        }

        private fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, WorkService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false
    private lateinit var nm: NotificationManager
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Background tasks", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String, done: Int, total: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(S.str(R.string.bg_work_title))
            .setContentText(text)
            .setProgress(total, done, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun postProgress(text: String, done: Int, total: Int) {
        val now = System.currentTimeMillis()
        if (now - lastNotify > 1000) {
            lastNotify = now
            nm.notify(NOTIF_ID, notification(text, done, total))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification(S.str(R.string.s062), 0, 0)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            // Android refused a foreground start from the background; the unfinished jobs stay saved
            stopSelf()
            return START_NOT_STICKY
        }
        synchronized(this) {
            if (!running) {
                running = true
                scope.launch { work() }
            }
        }
        return START_STICKY
    }

    private fun work() {
        val pending = PendingList(this, "work")
        val wl = BgSupport.wakeLock(this, "work")
        val wifi = BgSupport.wifiLock(this, "work")
        try {
            while (true) {
                val job = pending.first()
                if (job == null) {
                    synchronized(this) {
                        if (pending.first() == null) {
                            running = false
                            stopForeground(true)
                            stopSelf()
                            return
                        }
                    }
                    continue
                }
                try {
                    runJob(job)
                } catch (e: Exception) {
                    ImportState.log("✖ ${e.message ?: e.toString()}")
                } finally {
                    pending.remove(job)
                }
            }
        } finally {
            BgSupport.release(wl)
            BgSupport.releaseWifi(wifi)
        }
    }

    private fun runJob(job: String) {
        val kind = job.substringBefore('|')
        val arg = job.substringAfter('|')
        if (kind == "I") {
            ImportState.begin()
            var summary = ""
            var attempt = 0
            while (true) {
                attempt++
                var retry = false
                summary = try {
                    val s = Importer.run(
                        arg, AccountStore(this), LibraryStore(this),
                        { ImportState.log(it) },
                        { d, t, text ->
                            ImportState.progress(d, t, text)
                            postProgress(S.str(R.string.s063, d, t, text), d, t)
                        }
                    )
                    retry = Importer.needsRetry
                    s
                } catch (e: Exception) {
                    // network trouble (not a clear "wrong cookie / no space" answer) is worth another try
                    retry = e !is TeraBoxException
                    "✖ ${e.message ?: e.toString()}"
                }
                if (!retry || attempt >= MAX_TRIES) break
                // phone went to sleep / lost the network: wait, then continue where it stopped
                // (files that were already copied are skipped, so nothing is copied twice)
                val msg = S.str(R.string.bg_retry, attempt, MAX_TRIES)
                ImportState.log("↻ $msg")
                nm.notify(NOTIF_ID, notification(msg, 0, 0))
                waitForNetwork(30_000L * attempt)
            }
            ThumbCache.prefetchAll(this, LibraryStore(this).load())
            DurationFetcher.start(this)
            ImportState.finish(summary)
        } else if (kind == "D") {
            val accId = arg.substringBefore('\t')
            val dir = arg.substringAfter('\t')
            val acc = AccountStore(this).load().firstOrNull { it.id == accId } ?: return
            nm.notify(NOTIF_ID, notification(S.str(R.string.s042), 0, 0))
            val store = LibraryStore(this)
            val found = mutableListOf<ShareFile>()
            collectVideos(TeraBoxClient(acc.cookie) { }, dir, 0, found)
            val lib = store.load()
            val entries = found.map { f ->
                lib.firstOrNull { it.accountId == acc.id && it.remotePath == f.path }
                    ?: AccountScanner.entryFor(acc, f)
            }
            val n = store.addEntries(entries)
            ThumbCache.prefetchAll(this, store.load())
            DurationFetcher.start(this)
            ImportState.lastSummary = S.str(R.string.s043, n)
            ImportState.changed(true)
        } else if (kind == "M") {
            val store = LibraryStore(this)
            val e = store.load().firstOrNull { it.key == arg } ?: return
            if (e.remotePath.isEmpty()) return   // never copied: nothing on Terabox to move
            val cookie = AccountStore(this).cookieFor(e.accountId) ?: return
            val dir = e.remotePath.substringBeforeLast('/', "")
            val inDislike = dir.substringAfterLast('/') == "Dislike"
            val dest = when {
                e.disliked && !inDislike -> "$dir/Dislike"
                !e.disliked && inDislike -> dir.substringBeforeLast('/', "")
                else -> return
            }
            try {
                val newPath = TeraBoxClient(cookie) { }.moveFile(e.remotePath, dest)
                val cur = store.load().firstOrNull { it.key == arg } ?: e
                store.update(cur.copy(remotePath = newPath))
                Downloads.remoteChanged = true
            } catch (ex: Exception) {
                ImportState.log("✖ ${ex.message ?: ex.toString()}")
            }
        } else if (kind == "X") {
            try {
                val keys = arg.split('\t').filter { it.isNotEmpty() }.toHashSet()
                val items = LibraryStore(this).load().filter { it.key in keys }
                nm.notify(NOTIF_ID, notification(S.str(R.string.dis_deleting), 0, 0))
                val failed = purge(items)
                Downloads.remoteChanged = true
                PurgeState.summary = S.str(R.string.dis_done, items.size - failed, failed)
            } finally {
                PurgeState.running = false
                PurgeState.onChange?.invoke()
            }
        } else if (kind == "S") {
            val accName = AccountStore(this).load().firstOrNull { it.id == arg }?.name ?: ""
            nm.notify(NOTIF_ID, notification(S.str(R.string.scan_status, accName, 0), 0, 0))
            AccountScanner.run(this, arg) { found ->
                postProgress(S.str(R.string.scan_status, accName, found), 0, 0)
            }
        }
    }

    /** Waits [minMs], then keeps waiting (up to 10 minutes more) until the phone is online. */
    private fun waitForNetwork(minMs: Long) {
        try {
            Thread.sleep(minMs)
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val end = System.currentTimeMillis() + 10 * 60_000L
            while (System.currentTimeMillis() < end) {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
                Thread.sleep(5_000)
            }
        } catch (e: InterruptedException) {
            // service is stopping
        } catch (e: Exception) {
            // ignore: just try again
        }
    }

    private fun collectVideos(c: TeraBoxClient, dir: String, depth: Int, out: MutableList<ShareFile>) {
        for (f in c.browse(dir)) {
            if (f.isDir) {
                if (depth < 6 && out.size < 3000) collectVideos(c, f.path, depth + 1, out)
            } else if (f.isVideo()) {
                out.add(f)
            }
        }
    }

    /** Removes the videos from their Terabox account(s), from the phone and from the library. Returns how many failed. */
    private fun purge(items: List<LibEntry>): Int {
        val store = LibraryStore(this)
        val accounts = AccountStore(this).load()
        var failed = 0
        val removed = mutableListOf<LibEntry>()
        val byAcc = items.groupBy { e -> accounts.firstOrNull { it.id == e.accountId }?.id ?: accounts.firstOrNull()?.id ?: "" }
        for ((accId, list) in byAcc) {
            removed.addAll(list.filter { it.remotePath.isEmpty() })   // never copied: nothing on Terabox to delete
            val remote = list.filter { it.remotePath.isNotEmpty() }
            if (remote.isEmpty()) continue
            val acc = accounts.firstOrNull { it.id == accId }
            if (acc == null) {
                failed += remote.size
                continue
            }
            val client = TeraBoxClient(acc.cookie) { }
            for (chunk in remote.chunked(40)) {
                try {
                    client.deletePaths(chunk.map { it.remotePath })
                    removed.addAll(chunk)
                } catch (ex: Exception) {
                    failed += chunk.size
                }
            }
        }
        removed.forEach { if (it.localPath.isNotEmpty()) java.io.File(it.localPath).delete() }
        store.removeAll(removed)
        return failed
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }
}

/** State of the background "delete everything" job, so the Playlist tab can show the result when it is visible. */
object PurgeState {
    @Volatile var running = false
    @Volatile var summary: String? = null

    @Volatile
    var onChange: (() -> Unit)? = null
}
