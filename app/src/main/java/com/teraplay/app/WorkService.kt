package com.teraplay.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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

        fun enqueueImport(ctx: Context, link: String) {
            PendingList(ctx, "work").add("I|$link")
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
        }
    }

    private fun runJob(job: String) {
        val kind = job.substringBefore('|')
        val arg = job.substringAfter('|')
        if (kind == "I") {
            ImportState.begin()
            val summary = try {
                Importer.run(
                    arg, AccountStore(this), LibraryStore(this),
                    { ImportState.log(it) },
                    { d, t, text ->
                        ImportState.progress(d, t, text)
                        postProgress(S.str(R.string.s063, d, t, text), d, t)
                    }
                )
            } catch (e: Exception) {
                "✖ ${e.message ?: e.toString()}"
            }
            ThumbCache.prefetchAll(this, LibraryStore(this).load())
            DurationFetcher.start(this)
            ImportState.finish(summary)
        } else if (kind == "S") {
            val accName = AccountStore(this).load().firstOrNull { it.id == arg }?.name ?: ""
            nm.notify(NOTIF_ID, notification(S.str(R.string.scan_status, accName, 0), 0, 0))
            AccountScanner.run(this, arg) { found ->
                postProgress(S.str(R.string.scan_status, accName, found), 0, 0)
            }
        }
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }
}
