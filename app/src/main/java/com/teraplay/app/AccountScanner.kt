package com.teraplay.app

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds every video stored in an account (all folders) and puts them in the library, so adding an account
 * is enough to see its videos on Home. Videos already in the library (same account + same place) are skipped.
 * The work itself runs inside [WorkService] (background, survives closing the app).
 */
object AccountScanner {

    /** accountId -> videos found so far, while a scan is queued or running */
    val running = ConcurrentHashMap<String, Int>()
    val errors = ConcurrentHashMap<String, String>()

    @Volatile
    var onChange: (() -> Unit)? = null

    fun start(ctx: Context, accountId: String) {
        WorkService.enqueueScan(ctx, accountId)
    }

    /** Blocking; called from the background service. */
    fun run(app: Context, accountId: String, progress: (Int) -> Unit) {
        running.putIfAbsent(accountId, 0)
        errors.remove(accountId)
        try {
            val acc = AccountStore(app).load().firstOrNull { it.id == accountId } ?: return
            val store = LibraryStore(app)
            val known = store.load().filter { it.remotePath.isNotEmpty() }
                .map { (it.accountId.ifEmpty { acc.id }) + "|" + it.remotePath }.toHashSet()
            val client = TeraBoxClient(acc.cookie) { }
            client.prepare()

            val pending = mutableListOf<LibEntry>()
            var found = 0
            val queue = ArrayDeque<Pair<String, Int>>()
            queue.add("/" to 0)
            var lastNotify = 0L
            while (queue.isNotEmpty() && found < 20000) {
                val (dir, depth) = queue.removeFirst()
                val list = try {
                    client.listRemote(dir)
                } catch (e: Exception) {
                    if (dir == "/") throw e else emptyList()
                }
                for (f in list) {
                    if (f.isDir) {
                        if (depth < 12) queue.add(f.path to depth + 1)
                    } else if (f.isVideo() && (acc.id + "|" + f.path) !in known) {
                        pending.add(entryFor(acc, f))
                        found++
                    }
                }
                running[accountId] = found
                if (pending.size >= 100) {
                    store.addEntries(pending.toList())
                    pending.clear()
                }
                val now = System.currentTimeMillis()
                if (now - lastNotify > 1500) {
                    lastNotify = now
                    progress(found)
                    onChange?.invoke()
                }
            }
            if (pending.isNotEmpty()) store.addEntries(pending.toList())
        } catch (e: Exception) {
            errors[accountId] = e.message ?: e.toString()
        } finally {
            running.remove(accountId)
            ThumbCache.prefetchAll(app, LibraryStore(app).load())
            DurationFetcher.start(app)
            onChange?.invoke()
        }
    }

    /** Library entry for a file that already sits in the account (no copying needed). */
    fun entryFor(acc: Account, f: ShareFile): LibEntry {
        val dir = f.path.substringBeforeLast('/', "")
        val rel = if (dir.startsWith("/TeraPlay")) dir.removePrefix("/TeraPlay") else dir
        return LibEntry(
            surl = "",
            fsId = f.fsId,
            name = f.name,
            folder = rel.ifEmpty { "/" },
            size = f.size,
            duration = f.duration,
            thumb = f.thumb,
            addedAt = System.currentTimeMillis(),
            remotePath = f.path,
            accountId = acc.id
        )
    }
}
