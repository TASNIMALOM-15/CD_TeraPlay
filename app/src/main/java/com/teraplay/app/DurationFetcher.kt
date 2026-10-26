package com.teraplay.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fills in the length of every library video that does not have one yet, in the background:
 * the stream playlist of the video lists all segment lengths, so nothing has to be played.
 */
object DurationFetcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    /** Called (from a background thread) every few videos and at the end. */
    @Volatile
    var onUpdate: (() -> Unit)? = null

    fun start(ctx: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        scope.launch {
            try {
                val store = LibraryStore(app)
                val todo = store.load().filter { it.duration <= 0 && it.remotePath.isNotEmpty() }
                if (todo.isEmpty()) return@launch
                val accounts = AccountStore(app).load().associateBy { it.id }
                val clients = HashMap<String, TeraBoxClient?>()
                var done = 0
                var fails = 0
                for (e in todo) {
                    val acc = accounts[e.accountId] ?: accounts.values.firstOrNull() ?: continue
                    val client = clients.getOrPut(acc.id) {
                        try {
                            TeraBoxClient(acc.cookie) { }.also { it.prepare() }
                        } catch (ex: Exception) {
                            null
                        }
                    } ?: continue
                    val sec = try {
                        client.durationOf(e.remotePath)
                    } catch (ex: Exception) {
                        0
                    }
                    if (sec > 0) {
                        store.setDuration(e.key, sec)
                        done++
                        if (done % 5 == 0) onUpdate?.invoke()
                    } else {
                        fails++
                        if (fails >= 8) break
                    }
                    Thread.sleep(120)
                }
            } catch (ex: Exception) {
                // try again next time
            } finally {
                running.set(false)
                onUpdate?.invoke()
            }
        }
    }
}
