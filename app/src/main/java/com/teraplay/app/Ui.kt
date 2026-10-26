package com.teraplay.app

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import coil.dispose
import coil.load
import java.util.Locale

object Fmt {
    fun size(b: Long): String = when {
        b <= 0 -> ""
        b >= 1L shl 40 -> String.format(Locale.US, "%.2f TB", b / (1024.0 * 1024 * 1024 * 1024))
        b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
        else -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024))
    }

    fun dur(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun meta(e: LibEntry): String = listOfNotNull(
        size(e.size).ifEmpty { null },
        if (e.duration > 0) dur(e.duration) else null
    ).joinToString(" · ")

    fun md5(s: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { String.format("%02x", it) }

    /** (label, Terabox stream type) */
    val QUALITIES = listOf(
        "240p" to "M3U8_AUTO_240",
        "360p" to "M3U8_AUTO_360",
        "480p" to "M3U8_AUTO_480",
        "720p" to "M3U8_AUTO_720",
        "1080p" to "M3U8_AUTO_1080"
    )

    val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f, 4f)

    fun speed(f: Float): String = (if (f % 1f == 0f) f.toInt().toString() else f.toString()) + "x"

    /** Milliseconds as m:ss or h:mm:ss ("--:--" when unknown). */
    fun time(ms: Long): String = if (ms < 0) "--:--" else dur((ms / 1000).toInt())

    /** "Ep2" sorts before "Ep10". */
    fun nat(s: String): String =
        s.lowercase().replace(Regex("\\d+")) { it.value.padStart(8, '0') }
}

object Ui {
    /** Fills any of: thumb, title, meta, duration, favBadge, progress that exist in [v]. */
    fun bindCard(v: View, e: LibEntry, showProgress: Boolean) {
        v.findViewById<TextView?>(R.id.title)?.text = e.name
        v.findViewById<TextView?>(R.id.meta)?.text = Fmt.meta(e)

        val d: TextView? = v.findViewById(R.id.duration)
        if (d != null) {
            if (e.duration > 0) {
                d.text = Fmt.dur(e.duration)
                d.visibility = View.VISIBLE
            } else {
                d.visibility = View.GONE
            }
        }

        val sb: TextView? = v.findViewById(R.id.sizeBadge)
        if (sb != null) {
            val t = Fmt.size(e.size)
            if (t.isEmpty()) {
                sb.visibility = View.GONE
            } else {
                sb.text = t
                sb.visibility = View.VISIBLE
            }
        }

        v.findViewById<View?>(R.id.favBadge)?.visibility = if (e.favorite) View.VISIBLE else View.GONE

        val pb: ProgressBar? = v.findViewById(R.id.progress)
        if (pb != null) {
            if (showProgress && e.lastPos > 0 && e.duration > 0) {
                pb.visibility = View.VISIBLE
                pb.max = 1000
                pb.progress = ((e.lastPos * 1000) / (e.duration * 1000L)).toInt().coerceIn(0, 1000)
            } else {
                pb.visibility = View.GONE
            }
        }

        val img: ImageView? = v.findViewById(R.id.thumb)
        if (img != null) {
            val ctx = v.context
            if (ThumbCache.isCached(ctx, e)) {
                img.load(ThumbCache.file(ctx, e)) { crossfade(true) }
            } else if (e.thumb.isNotEmpty()) {
                img.load(e.thumb) { crossfade(true) }
                ThumbCache.ensure(ctx, e)
            } else {
                img.dispose()
                img.setImageDrawable(null)
            }
        }
    }
}

/** ImageView that is always 16:9 relative to its width. */
class RatioImageView(c: Context, a: AttributeSet?) : ImageView(c, a) {
    override fun onMeasure(w: Int, h: Int) {
        val ww = MeasureSpec.getSize(w)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(ww, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(ww * 9 / 16, MeasureSpec.EXACTLY)
        )
    }
}

/** FrameLayout that is 16:9, or fills the available height when [fill] is true (fullscreen). */
class RatioFrameLayout(c: Context, a: AttributeSet?) : FrameLayout(c, a) {
    var fill = false
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(w: Int, h: Int) {
        if (fill) {
            val hh = MeasureSpec.getSize(h)
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(hh, MeasureSpec.EXACTLY))
        } else {
            val ww = MeasureSpec.getSize(w)
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(ww * 9 / 16, MeasureSpec.EXACTLY))
        }
    }
}
