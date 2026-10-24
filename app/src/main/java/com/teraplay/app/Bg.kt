package com.teraplay.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * App-wide background + glass settings (saved locally). Every BackdropView and every glass
 * surface listens to this object, so changes apply live, without restarting.
 */
object Bg {

    interface Listener {
        fun onBgChanged()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun notifyChanged() {
        listeners.forEach { it.onBgChanged() }
    }

    // ---- saved settings
    var type = "preset"       // "preset" | "image"
    var preset = 0
    var blur = 6              // 0..25   background blur
    var dim = 20              // 0..80   brightness overlay (scrim) in %
    var glass = 25            // 5..50   glass transparency in %
    var fit = "cover"         // cover | contain | center
    var dark = true

    // ---- computed
    @Volatile var baseBitmap: Bitmap? = null     // sharp, downscaled picture (preview + "center" size)
    @Volatile var bgBitmap: Bitmap? = null       // what the app background shows (blur applied)
    @Volatile var glassBitmap: Bitmap? = null    // strongly blurred copy that glass surfaces "see through"
    @Volatile var luminance = 0.2f               // 0 = black, 1 = white (average of the picture)

    private lateinit var app: Context
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var gen = 0

    private val PRESETS = arrayOf(
        intArrayOf(0xFF02121A.toInt(), 0xFF0A3A4A.toInt(), 0xFF4FB3D0.toInt(), 0xFF0B6E6E.toInt()), // aurora
        intArrayOf(0xFF1A0B1F.toInt(), 0xFF5A1E3C.toInt(), 0xFFFF8A5B.toInt(), 0xFFC2457A.toInt()), // sunset
        intArrayOf(0xFF0E0A24.toInt(), 0xFF2A1B6B.toInt(), 0xFF9B7BFF.toInt(), 0xFF3D7BFF.toInt()), // violet
        intArrayOf(0xFF04140F.toInt(), 0xFF0D4A3A.toInt(), 0xFF4DF0B0.toInt(), 0xFF1B8F7A.toInt()), // emerald
        intArrayOf(0xFF05070C.toInt(), 0xFF101826.toInt(), 0xFF3A4C6E.toInt(), 0xFF1E2A44.toInt())  // midnight
    )
    val presetCount: Int get() = PRESETS.size
    fun presetColors(i: Int): IntArray = PRESETS[i.coerceIn(0, PRESETS.size - 1)]

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val p = app.getSharedPreferences("bg", Context.MODE_PRIVATE)
        type = p.getString("type", "preset") ?: "preset"
        preset = p.getInt("preset", 0).coerceIn(0, PRESETS.size - 1)
        blur = p.getInt("blur", 6).coerceIn(0, 25)
        dim = p.getInt("dim", 20).coerceIn(0, 80)
        glass = p.getInt("glass", 25).coerceIn(5, 50)
        fit = p.getString("fit", "cover") ?: "cover"
        dark = p.getBoolean("dark", true)
        rebuild(true)
    }

    private fun save() {
        app.getSharedPreferences("bg", Context.MODE_PRIVATE).edit()
            .putString("type", type).putInt("preset", preset).putInt("blur", blur)
            .putInt("dim", dim).putInt("glass", glass).putString("fit", fit)
            .putBoolean("dark", dark).apply()
    }

    // ---- setters (all apply live)
    fun updateBlur(v: Int) {
        blur = v.coerceIn(0, 25)
        save()
        rebuild(false)
    }

    fun updateDim(v: Int) {
        dim = v.coerceIn(0, 80)
        save()
        notifyChanged()
    }

    fun updateGlass(v: Int) {
        glass = v.coerceIn(5, 50)
        save()
        notifyChanged()
    }

    fun updateFit(v: String) {
        fit = v
        save()
        notifyChanged()
    }

    fun updateDark(v: Boolean) {
        dark = v
        save()
        notifyChanged()
    }

    fun updatePreset(i: Int) {
        type = "preset"
        preset = i
        save()
        rebuild(true)
    }

    /** Background settings back to default (the dark/light choice is kept). */
    fun reset() {
        File(app.filesDir, "bg_image.jpg").delete()
        type = "preset"; preset = 0; blur = 6; dim = 20; glass = 25; fit = "cover"
        save()
        rebuild(true)
    }

    /** Copies a picture picked from the gallery into app storage and uses it as background. */
    fun importImage(ctx: Context, uri: Uri): Boolean {
        return try {
            val cr = ctx.contentResolver
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var ss = 1
            while (max(o.outWidth, o.outHeight) / ss > 2400) ss *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = ss
            var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return false

            val orient = try {
                cr.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
            val deg = when (orient) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (deg != 0f) {
                val m = Matrix()
                m.postRotate(deg)
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            bmp = shrink(bmp, 1600)
            File(ctx.filesDir, "bg_image.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            type = "image"
            save()
            rebuild(true)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---- building the bitmaps (background thread)
    private fun rebuild(reloadBase: Boolean) {
        val g = ++gen
        exec.execute {
            try {
                if (reloadBase || baseBitmap == null) baseBitmap = loadBase()
                val base = baseBitmap ?: return@execute
                if (g != gen) return@execute
                val bgB = blurBitmap(base, blur)
                val gl = blurBitmap(base, 14)
                val lum = sampleLuma(base)
                main.post {
                    bgBitmap = bgB
                    glassBitmap = gl
                    luminance = lum
                    notifyChanged()
                }
            } catch (e: Throwable) {
                // keep the old bitmaps
            }
        }
    }

    private fun loadBase(): Bitmap {
        if (type == "image") {
            val f = File(app.filesDir, "bg_image.jpg")
            if (f.exists()) {
                val b = BitmapFactory.decodeFile(f.path)
                if (b != null) return shrink(b, 1080)
            }
        }
        return makePreset(preset)
    }

    private fun shrink(b: Bitmap, maxSide: Int): Bitmap {
        val m = max(b.width, b.height)
        if (m <= maxSide) return b
        val s = maxSide.toFloat() / m
        return Bitmap.createScaledBitmap(
            b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true
        )
    }

    private fun makePreset(i: Int): Bitmap {
        val p = presetColors(i)
        val w = 360
        val h = 780
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), p[0], p[1], Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        val glow1 = p[2] and 0x00FFFFFF
        paint.shader = RadialGradient(w * 0.85f, h * 0.10f, w * 0.95f, glow1 or (0xA0 shl 24), glow1, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        val glow2 = p[3] and 0x00FFFFFF
        paint.shader = RadialGradient(w * 0.10f, h * 0.92f, w * 1.0f, glow2 or (0x80 shl 24), glow2, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        return bmp
    }

    /** Cheap blur: repeated halving then a small bilinear result (drawn stretched with filtering). */
    private fun blurBitmap(src: Bitmap, level: Int): Bitmap {
        if (level <= 0) return src
        val w = max(4, src.width / (1 + level))
        val h = max(4, src.height / (1 + level))
        var cur = src
        while (cur.width / 2 >= w && cur.height / 2 >= h) {
            cur = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
        }
        return Bitmap.createScaledBitmap(cur, w, h, true)
    }

    private fun sampleLuma(b: Bitmap): Float {
        val s = Bitmap.createScaledBitmap(b, 16, 16, true)
        var sum = 0.0
        for (y in 0 until 16) for (x in 0 until 16) {
            val c = s.getPixel(x, y)
            sum += 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
        }
        return (sum / (256.0 * 255.0)).toFloat()
    }

    // ---- drawing helpers
    fun baseColor(): Int = if (dark) 0xFF0A1016.toInt() else 0xFFEAF0F6.toInt()

    /** Scrim strength: the user's slider, but never less than what keeps text readable on this picture. */
    fun scrimAlpha(): Float {
        val user = dim / 100f
        val l = max(luminance, 0.01f)
        val auto = if (dark) max(0f, 1f - 0.30f / l) else max(0f, (0.70f - l) / max(1f - l, 0.01f))
        return min(0.9f, max(user, auto))
    }

    fun scrimColor(): Int {
        val a = (scrimAlpha() * 255).toInt()
        return if (dark) Color.argb(a, 0, 0, 0) else Color.argb(a, 255, 255, 255)
    }

    fun fitMatrix(bm: Bitmap, w: Float, h: Float): Matrix {
        val m = Matrix()
        val bw = bm.width.toFloat()
        val bh = bm.height.toFloat()
        val s = when (fit) {
            "contain" -> min(w / bw, h / bh)
            "center" -> {
                val base = baseBitmap
                if (base != null) base.width / bw else 1f
            }
            else -> max(w / bw, h / bh)
        }
        m.setScale(s, s)
        m.postTranslate((w - bw * s) / 2f, (h - bh * s) / 2f)
        return m
    }
}
