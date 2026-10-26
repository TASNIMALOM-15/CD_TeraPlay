package com.teraplay.app

import android.content.Context
import android.os.PowerManager
import java.util.concurrent.CopyOnWriteArrayList

/** A small list of jobs saved on disk, so work that was cut short (app killed, phone restarted) can continue. */
class PendingList(ctx: Context, private val name: String) {
    private val prefs = ctx.applicationContext.getSharedPreferences("pending", Context.MODE_PRIVATE)

    companion object {
        private val LOCK = Any()
    }

    fun all(): List<String> = synchronized(LOCK) {
        (prefs.getString(name, "") ?: "").split('\n').filter { it.isNotEmpty() }
    }

    fun add(item: String): Boolean = synchronized(LOCK) {
        val l = all()
        if (item in l) false else {
            save(l + item)
            true
        }
    }

    fun first(): String? = all().firstOrNull()

    fun remove(item: String) = synchronized(LOCK) {
        save(all().filter { it != item })
    }

    private fun save(l: List<String>) {
        prefs.edit().putString(name, l.joinToString("\n")).apply()
    }
}

object BgSupport {
    /** Keeps the CPU running while a long job works (released when the job is done). */
    fun wakeLock(ctx: Context, tag: String): PowerManager.WakeLock {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TeraPlay:$tag")
        wl.setReferenceCounted(false)
        wl.acquire(6 * 60 * 60 * 1000L)
        return wl
    }

    fun release(wl: PowerManager.WakeLock?) {
        try {
            if (wl != null && wl.isHeld) wl.release()
        } catch (e: Exception) {
            // ignore
        }
    }

    fun ignoringBatteryOptimizations(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }
}

/** What the background import is doing right now; the Home screen shows it whenever it is visible. */
object ImportState {
    @Volatile var running = false
    @Volatile var done = 0
    @Volatile var total = 0
    @Volatile var text = ""

    /** result of the last finished import (shown once) */
    @Volatile var lastSummary: String? = null
    val lines = CopyOnWriteArrayList<String>()
    val errors = CopyOnWriteArrayList<String>()

    @Volatile
    var onChange: (() -> Unit)? = null
    private var last = 0L

    fun changed(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - last > 400) {
            last = now
            onChange?.invoke()
        }
    }

    fun begin() {
        running = true
        done = 0
        total = 0
        text = ""
        errors.clear()
        lines.clear()
        changed(true)
    }

    fun log(line: String) {
        if (line.startsWith("✖")) errors.add(line)
        lines.add(line)
        while (lines.size > 5) lines.removeAt(0)
        changed()
    }

    fun progress(d: Int, t: Int, tx: String) {
        done = d
        total = t
        text = tx
        changed()
    }

    fun finish(summary: String) {
        running = false
        lastSummary = summary + (if (errors.isEmpty()) "" else "\n" + errors.take(3).joinToString("\n"))
        changed(true)
    }
}
