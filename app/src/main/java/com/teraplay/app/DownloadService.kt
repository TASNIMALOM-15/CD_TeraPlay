package com.teraplay.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Downloads videos one after another (foreground service, so it keeps going when the app is closed).
 * The stream is a list of small .ts segments; they are saved back to back into one playable .ts file.
 */
class DownloadService : Service() {

    companion object {
        const val EXTRA_KEY = "key"
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 77
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var pending: PendingList
    private val client = OkHttpClient()
    private var running = false
    private lateinit var nm: NotificationManager
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        pending = PendingList(this, "dl")
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String, percent: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(S.str(R.string.dl_notif_title))
            .setContentText(text)
            .setProgress(100, percent, percent <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification(S.str(R.string.dl_preparing), 0)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            // Android refused a foreground start from the background; the queue stays saved
            stopSelf()
            return START_NOT_STICKY
        }
        intent?.getStringExtra(EXTRA_KEY)?.let { pending.add(it) }
        pending.all().forEach { Downloads.progress.putIfAbsent(it, 0) }
        synchronized(this) {
            if (!running) {
                running = true
                scope.launch { work() }
            }
        }
        return START_STICKY
    }

    private suspend fun work() {
        val wl = BgSupport.wakeLock(this, "download")
        try {
            while (true) {
                val key = pending.first()
                if (key == null) {
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
                    download(key)
                } catch (ex: Exception) {
                    Downloads.errors[key] = ex.message ?: ex.toString()
                } finally {
                    pending.remove(key)
                    Downloads.progress.remove(key)
                    Downloads.changed()
                }
            }
        } finally {
            BgSupport.release(wl)
        }
    }

    private fun download(key: String) {
        val store = LibraryStore(this)
        val e = store.load().firstOrNull { it.key == key } ?: return
        if (Downloads.isDownloaded(e)) return
        val cookie = AccountStore(this).cookieFor(e.accountId)
            ?: throw TeraBoxException(S.str(R.string.s076))
        val q = getSharedPreferences("teraplay", MODE_PRIVATE).getInt("quality", 2)
            .coerceIn(0, Fmt.QUALITIES.size - 1)

        val res = TeraBoxClient(cookie) { }.streamFor(
            e.surl, e.fsId, e.name, Fmt.QUALITIES[q].second, e.remotePath, TeraBoxClient.destDirFor(e.folder)
        )
        if (res.m3u8.contains("#EXT-X-KEY")) throw TeraBoxException(S.str(R.string.dl_encrypted))
        val segs = res.m3u8.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (segs.isEmpty() || segs.any { !it.startsWith("http") }) {
            throw TeraBoxException(S.str(R.string.dl_bad_playlist))
        }

        val out = Downloads.fileFor(this, key)
        val part = File(out.parentFile, out.name + ".part")
        try {
            part.outputStream().buffered().use { os ->
                segs.forEachIndexed { i, url ->
                    os.write(fetchSegment(url))
                    val pct = ((i + 1) * 100) / segs.size
                    if (Downloads.progress[key] != pct) {
                        Downloads.progress[key] = pct
                        Downloads.changed()
                        val now = System.currentTimeMillis()
                        if (now - lastNotify > 1000) {
                            lastNotify = now
                            nm.notify(NOTIF_ID, notification(e.name, pct))
                        }
                    }
                }
            }
            if (!part.renameTo(out)) throw IOException("rename failed")
        } catch (ex: Exception) {
            part.delete()
            throw ex
        }

        var seconds = 0.0
        Regex("#EXTINF:([0-9.]+)").findAll(res.m3u8).forEach { seconds += it.groupValues[1].toDouble() }
        val latest = store.load().firstOrNull { it.key == key } ?: e
        store.update(
            latest.copy(
                localPath = out.path,
                remotePath = res.remotePath,
                duration = if (latest.duration > 0) latest.duration else Math.round(seconds).toInt()
            )
        )
    }

    private fun fetchSegment(url: String): ByteArray {
        var last: Exception? = null
        for (attempt in 1..3) {
            try {
                val req = Request.Builder().url(url)
                    .header("User-Agent", TeraBoxClient.UA)
                    .header("Referer", "https://www.terabox.com/")
                    .build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    return r.body!!.bytes()
                }
            } catch (ex: Exception) {
                last = ex
                Thread.sleep(800L * attempt)
            }
        }
        throw last ?: IOException("segment failed")
    }

    override fun onDestroy() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }
}
