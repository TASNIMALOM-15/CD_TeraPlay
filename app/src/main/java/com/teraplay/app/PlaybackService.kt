package com.teraplay.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Background playback (from background-playback-kit, adapted for video).
 * The ExoPlayer lives HERE, not in the Activity, so a video keeps playing (sound, notification,
 * lock-screen and headset buttons) when the screen is off, the app is minimised or closed.
 * The player screen only shows the picture and sends commands.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    companion object {
        /** true: keeps playing even if another app takes the audio/microphone. false: pauses (e.g. phone call). */
        const val PLAY_THROUGH_INTERRUPTIONS = true
    }

    override fun onCreate() {
        super.onCreate()

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(TeraBoxClient.UA)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Referer" to "https://www.terabox.com/"))
        val dataSource = DefaultDataSource.Factory(this, http)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, !PLAY_THROUGH_INTERRUPTIONS)
            .setHandleAudioBecomingNoisy(true)   // headphones unplugged: pause
            .setWakeMode(C.WAKE_MODE_NETWORK)    // screen off: keep CPU and Wi-Fi awake
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

        // tapping the notification returns to the player (same queue, nothing reloads)
        val open = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_RESUME, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val sessionActivity = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()

        PlaybackEngine.attach(this, player, dataSource)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** App swiped away from recents: keep playing if something is playing, otherwise stop the service. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null ||
            !player.playWhenReady ||
            player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        PlaybackEngine.detach()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}

/**
 * Everything about "which video is playing" lives here, so it keeps working with the screen off:
 * the queue, getting the stream (including the automatic copy from a share link into the account),
 * starting the next video when one ends, and saving the watch position.
 * The player screen talks to it through [Listener]; with no screen open nothing is shown, but it all continues.
 */
@OptIn(UnstableApi::class)
object PlaybackEngine {

    interface Listener {
        /** the video that is current (selected or auto-advanced) changed */
        fun onIndex(index: Int)
        fun onLoading(on: Boolean)

        /** replaces the status text; empty = hide it */
        fun onStatus(text: String)
        fun onLog(line: String)

        /** a library entry was updated by the engine (copied path, position, duration ...) */
        fun onEntry(e: LibEntry)

        /** loading failed; [revertQuality] is the quality to go back to (after a quality change), if any */
        fun onFailed(revertQuality: Int?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var app: Context? = null
    private var exo: ExoPlayer? = null
    private var factory: DataSource.Factory? = null
    private var loadJob: Job? = null
    private var token = 0
    private var fileSeq = 0
    private var lastFile: File? = null
    private var curRes = 0
    private var curDur = 0

    @Volatile var keys: List<String> = emptyList()
        private set

    @Volatile var index = 0
        private set

    /** key of the video the player really holds right now (null while nothing is loaded) */
    @Volatile var loadedKey: String? = null
        private set

    @Volatile var loading = false
        private set

    /** playback speed for videos that start from now on (the player screen sets it) */
    @Volatile var speed = 1f

    @Volatile var listener: Listener? = null

    fun clearListener(l: Listener) {
        if (listener === l) listener = null
    }

    // ------------------------------------------------------------ service side

    fun attach(ctx: Context, player: ExoPlayer, dataSource: DataSource.Factory) {
        app = ctx.applicationContext
        exo = player
        factory = dataSource
        player.addListener(playerListener)
    }

    fun detach() {
        loadJob?.cancel()
        main.removeCallbacks(saver)
        savePosition()
        exo?.removeListener(playerListener)
        exo = null
        loadedKey = null
        loading = false
        keys = emptyList()
        lastFile?.delete()
        lastFile = null
    }

    // ------------------------------------------------------------ commands (player screen)

    /**
     * Starts [newKeys] at [startIndex]. Returns true when that very video is already playing in the
     * background (the screen just attaches to it and nothing is loaded again).
     */
    fun start(newKeys: List<String>, startIndex: Int): Boolean {
        if (newKeys.isEmpty()) return false
        keys = newKeys.toList()
        index = startIndex.coerceIn(0, keys.lastIndex)
        val p = exo ?: return false
        val st = p.playbackState
        if (loadedKey == keys[index] && st != Player.STATE_IDLE && st != Player.STATE_ENDED) return true
        load(index, null, null)
        return false
    }

    /** Replace the queue without loading anything (the screen's queue is the source of truth). */
    fun setQueue(newKeys: List<String>, newIndex: Int) {
        if (newKeys.isEmpty()) return
        keys = newKeys.toList()
        index = newIndex.coerceIn(0, keys.lastIndex)
    }

    fun select(i: Int) {
        if (i !in keys.indices) return
        savePosition()
        load(i, null, null)
    }

    /** reload the current video (e.g. after a quality change), continuing at [resumeMs] */
    fun reload(resumeMs: Long?, revertQuality: Int?) {
        savePosition()
        load(index, resumeMs, revertQuality)
    }

    fun savePosition(finished: Boolean = false) {
        val p = exo ?: return
        val ctx = app ?: return
        val key = loadedKey ?: return
        if (p.playbackState == Player.STATE_IDLE && !finished) return
        val store = LibraryStore(ctx)
        val e = store.load().firstOrNull { it.key == key } ?: return
        val u = e.copy(
            lastPos = if (finished) 0L else p.currentPosition,
            lastWatched = System.currentTimeMillis(),
            duration = if (curDur > 0) curDur else e.duration,
            resolution = if (curRes > 0) curRes else e.resolution
        )
        store.update(u)
        listener?.onEntry(u)
    }

    // ------------------------------------------------------------ loading

    private fun quality(ctx: Context): Int =
        ctx.getSharedPreferences("teraplay", Context.MODE_PRIVATE).getInt("quality", 2)
            .coerceIn(0, Fmt.QUALITIES.size - 1)

    private fun load(i: Int, resumeMs: Long?, revertQuality: Int?) {
        val ctx = app ?: return
        val key = keys.getOrNull(i) ?: return
        index = i
        val myToken = ++token
        loadJob?.cancel()
        listener?.onIndex(i)

        val store = LibraryStore(ctx)
        val e = store.load().firstOrNull { it.key == key }
        if (e == null) {
            loading = false
            listener?.onLoading(false)
            listener?.onLog("✖ ${S.str(R.string.s076)}")
            return
        }

        if (e.localPath.isNotEmpty() && File(e.localPath).exists()) {
            // downloaded: play from the phone, no internet or account needed
            loading = false
            listener?.onLoading(false)
            listener?.onStatus("")
            val src = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(ctx))
                .createMediaSource(itemFor(e, Uri.fromFile(File(e.localPath))))
            begin(e, src, resumeMs ?: e.lastPos)
            return
        }

        val cookie = AccountStore(ctx).cookieFor(e.accountId)
        if (cookie == null) {
            loading = false
            listener?.onLoading(false)
            listener?.onStatus(S.str(R.string.s076))
            return
        }

        loading = true
        listener?.onLoading(true)
        listener?.onStatus(S.str(R.string.s077))
        val type = Fmt.QUALITIES[quality(ctx)].second
        val wl = BgSupport.wakeLock(ctx, "play")

        loadJob = scope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    TeraBoxClient(cookie) { line -> main.post { listener?.onLog(line) } }
                        .streamFor(e.surl, e.fsId, e.name, type, e.remotePath, TeraBoxClient.destDirFor(e.folder))
                }
                val latest = withContext(Dispatchers.IO) { store.load().firstOrNull { it.key == key } } ?: e
                val cur = latest.copy(remotePath = res.remotePath)
                withContext(Dispatchers.IO) { store.update(cur) }
                listener?.onEntry(cur)

                val file = File(ctx.cacheDir, "s${++fileSeq}.m3u8")
                file.writeText(res.m3u8)
                val f = factory ?: return@launch
                val src = HlsMediaSource.Factory(f).createMediaSource(itemFor(cur, Uri.fromFile(file)))
                begin(cur, src, resumeMs ?: cur.lastPos)
                lastFile?.delete()
                lastFile = file
                listener?.onStatus("")
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                listener?.onFailed(revertQuality)
                listener?.onLog("✖ ${ex.message ?: ex.toString()}")
            } finally {
                BgSupport.release(wl)
                if (myToken == token) {
                    loading = false
                    listener?.onLoading(false)
                }
            }
        }
    }

    private fun itemFor(e: LibEntry, uri: Uri): MediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMediaId(e.key)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(e.name)
                    .setArtist(S.str(R.string.app_name))
                    .build()
            )
            .build()

    private fun begin(e: LibEntry, src: MediaSource, resumeMs: Long) {
        val p = exo ?: return
        loadedKey = e.key
        curRes = e.resolution
        curDur = e.duration
        p.setMediaSource(src, if (resumeMs > 5000) resumeMs else 0L)
        p.prepare()
        p.setPlaybackSpeed(speed)
        p.playWhenReady = true
    }

    // ------------------------------------------------------------ player events

    private val saver = object : Runnable {
        override fun run() {
            savePosition()
            main.postDelayed(this, 5000)
        }
    }

    private fun updateSaver() {
        main.removeCallbacks(saver)
        if (exo?.isPlaying == true) main.postDelayed(saver, 5000)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                val d = exo?.duration ?: 0L
                if (d > 0) curDur = (d / 1000).toInt()
            } else if (state == Player.STATE_ENDED) {
                onEnded()
            }
            updateSaver()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateSaver()
            if (!isPlaying) savePosition()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height > 0) curRes = videoSize.height
        }

        override fun onPlayerError(error: PlaybackException) {
            listener?.onLog(S.str(R.string.s075, error.errorCodeName, error.cause?.message ?: ""))
        }
    }

    /** One video ended: remember it as watched and go to the next one - works with the screen off. */
    private fun onEnded() {
        savePosition(finished = true)
        if (index + 1 < keys.size) load(index + 1, null, null)
    }
}
