package com.teraplay.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.lang.ref.WeakReference
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Full-window background: the chosen picture (fit mode, blur) + readability scrim. */
class BackdropView(ctx: Context, attrs: AttributeSet?) : View(ctx, attrs), Bg.Listener {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Bg.addListener(this)
    }

    override fun onDetachedFromWindow() {
        Bg.removeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onBgChanged() {
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Bg.baseColor())
        val bm = Bg.bgBitmap
        if (bm != null && width > 0 && height > 0) {
            c.save()
            c.concat(Bg.fitMatrix(bm, width.toFloat(), height.toFloat()))
            c.drawBitmap(bm, 0f, 0f, paint)
            c.restore()
        }
        c.drawColor(Bg.scrimColor())
    }
}

/**
 * The "liquid glass" surface: a rounded shape that shows a blurred copy of the app background
 * (sampled at its own screen position), a translucent tint, a top sheen, a glass border and,
 * when selected, a soft glowing highlight.
 */
class GlassDrawable(
    private val host: View,
    private val radiusPx: Float,   // < 0 = fully rounded
    selected: Boolean
) : Drawable() {

    var selected: Boolean = selected
        set(v) {
            field = v
            invalidateSelf()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val loc = IntArray(2)
    private val bloc = IntArray(2)
    private var bdRef: WeakReference<View>? = null
    private val density = host.resources.displayMetrics.density
    private var radius = 0f

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        val w = rect.width()
        val h = rect.height()
        radius = if (radiusPx < 0f) min(w, h) / 2f else min(radiusPx, min(w, h) / 2f)
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        sheen.shader = LinearGradient(
            0f, rect.top, 0f, rect.top + h * 0.6f,
            Color.argb(80, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP
        )
        glow.shader = RadialGradient(
            rect.centerX(), rect.bottom, max(w, h) * 0.7f,
            Color.argb(120, 90, 200, 250), Color.argb(0, 90, 200, 250), Shader.TileMode.CLAMP
        )
        stroke.strokeWidth = density
        stroke.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            Color.argb(170, 255, 255, 255), Color.argb(40, 255, 255, 255), Shader.TileMode.CLAMP
        )
    }

    private fun findBackdrop(): View? {
        val cached = bdRef?.get()
        if (cached != null && cached.isAttachedToWindow) return cached
        val v = host.rootView.findViewById<View>(R.id.backdrop)
        bdRef = if (v != null) WeakReference(v) else null
        return v
    }

    override fun draw(c: Canvas) {
        if (rect.isEmpty) return
        val dark = Bg.dark
        c.save()
        c.clipPath(path)

        // 1) blurred background seen through the glass
        val gb = Bg.glassBitmap
        val bd = findBackdrop()
        if (gb != null && bd != null && bd.width > 0 && bd.height > 0) {
            host.getLocationOnScreen(loc)
            bd.getLocationOnScreen(bloc)
            c.save()
            c.translate((bloc[0] - loc[0]).toFloat(), (bloc[1] - loc[1]).toFloat())
            c.drawColor(Bg.baseColor())
            c.save()
            c.concat(Bg.fitMatrix(gb, bd.width.toFloat(), bd.height.toFloat()))
            c.drawBitmap(gb, 0f, 0f, bmp)
            c.restore()
            c.drawColor(Bg.scrimColor())
            c.restore()
        } else {
            c.drawColor(if (dark) 0xFF18222E.toInt() else 0xFFE4ECF3.toInt())
        }

        // 2) translucent tint (strength = glass transparency setting)
        val g = Bg.glass / 100f
        var a = if (dark) 0.08f + g * 0.6f else 0.30f + g * 1.0f
        if (selected) a += 0.16f
        a = a.coerceIn(0f, 0.92f)
        fill.color = Color.argb((a * 255).toInt(), 255, 255, 255)
        c.drawPath(path, fill)

        // 3) top reflection, and the soft glow of the selected pill
        c.drawPath(path, sheen)
        if (selected) c.drawPath(path, glow)
        c.restore()

        // 4) subtle glass highlight border
        val inset = density / 2f
        c.drawRoundRect(
            rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset,
            radius, radius, stroke
        )
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Puts a [GlassDrawable] on a view and keeps it refreshed when the background changes. */
class GlassBinder(private val v: View, radiusPx: Float, selected: Boolean) : Bg.Listener {
    val drawable = GlassDrawable(v, radiusPx, selected)

    init {
        v.background = drawable
    }

    fun attach() {
        Bg.addListener(this)
    }

    fun detach() {
        Bg.removeListener(this)
    }

    override fun onBgChanged() {
        v.invalidate()
    }

    companion object {
        fun from(v: View, attrs: AttributeSet?): GlassBinder {
            var r = -1f
            var sel = false
            if (attrs != null) {
                val a = v.context.obtainStyledAttributes(attrs, R.styleable.Glass)
                r = a.getDimension(R.styleable.Glass_glassRadius, -1f)
                sel = a.getBoolean(R.styleable.Glass_glassSelected, false)
                a.recycle()
            }
            return GlassBinder(v, r, sel)
        }
    }
}

open class GlassFrameLayout @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    FrameLayout(ctx, attrs) {
    private val binder = GlassBinder.from(this, attrs)

    var glassSelected: Boolean
        get() = binder.drawable.selected
        set(v) {
            binder.drawable.selected = v
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        binder.attach()
    }

    override fun onDetachedFromWindow() {
        binder.detach()
        super.onDetachedFromWindow()
    }
}

open class GlassLinearLayout @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    LinearLayout(ctx, attrs) {
    private val binder = GlassBinder.from(this, attrs)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        binder.attach()
    }

    override fun onDetachedFromWindow() {
        binder.detach()
        super.onDetachedFromWindow()
    }
}

open class GlassTextView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    AppCompatTextView(ctx, attrs) {
    private val binder = GlassBinder.from(this, attrs)

    var glassSelected: Boolean
        get() = binder.drawable.selected
        set(v) {
            binder.drawable.selected = v
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        binder.attach()
    }

    override fun onDetachedFromWindow() {
        binder.detach()
        super.onDetachedFromWindow()
    }
}

/** Floating glass tab bar: icons only, with a liquid illuminated pill that slides to the chosen tab. */
class GlassNavBar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    GlassFrameLayout(ctx, attrs) {

    private val indicator = GlassFrameLayout(ctx, null)
    private val row = LinearLayout(ctx)
    private val icons = ArrayList<ImageView>()
    private var outline = IntArray(0)
    private var filled = IntArray(0)
    private var onSelect: ((Int) -> Unit)? = null
    private var index = 0
    private var anim: ValueAnimator? = null
    private val d = resources.displayMetrics.density
    private val inset = (6 * d).toInt()

    init {
        indicator.glassSelected = true
        addView(indicator, LayoutParams(0, 0))
        row.orientation = LinearLayout.HORIZONTAL
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setup(outlineRes: IntArray, filledRes: IntArray, names: Array<String>, listener: (Int) -> Unit) {
        outline = outlineRes
        filled = filledRes
        onSelect = listener
        row.removeAllViews()
        icons.clear()
        for (i in outlineRes.indices) {
            val iv = ImageView(context)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            val p = (17 * d).toInt()
            iv.setPadding(p, p, p, p)
            iv.contentDescription = names[i]
            iv.setOnClickListener {
                selectIndex(i, true)
                onSelect?.invoke(i)
            }
            row.addView(iv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            icons.add(iv)
        }
        selectIndex(index, false)
    }

    private fun itemWidth(): Float = if (icons.isEmpty()) 0f else width.toFloat() / icons.size

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val iw = itemWidth().toInt()
        if (iw > 0) {
            val lp = LayoutParams(iw - 2 * inset, h - 2 * inset)
            lp.leftMargin = inset
            lp.topMargin = inset
            indicator.layoutParams = lp
            indicator.translationX = index * itemWidth()
        }
    }

    fun selectIndex(i: Int, animate: Boolean) {
        if (i !in icons.indices) return
        index = i
        val icon = context.getColor(R.color.glass_icon)
        icons.forEachIndexed { k, iv ->
            iv.setImageResource(if (k == i) filled[k] else outline[k])
            iv.setColorFilter(icon)
            iv.alpha = if (k == i) 1f else 0.62f
        }
        val to = i * itemWidth()
        val from = indicator.translationX
        anim?.cancel()
        if (!animate || width == 0) {
            indicator.translationX = to
            indicator.scaleX = 1f
            indicator.scaleY = 1f
        } else {
            val va = ValueAnimator.ofFloat(0f, 1f)
            va.duration = 440
            va.interpolator = DecelerateInterpolator(1.6f)
            va.addUpdateListener {
                val t = it.animatedValue as Float
                val bump = sin(PI * t).toFloat()
                indicator.translationX = from + (to - from) * t
                indicator.scaleX = 1f + 0.20f * bump
                indicator.scaleY = 1f - 0.07f * bump
            }
            anim = va
            va.start()
        }
    }
}

object Glass {
    /** Edge-to-edge window: the background shows behind the status/navigation bars; [content] gets the insets. */
    fun setup(activity: AppCompatActivity, content: View) {
        val w = activity.window
        WindowCompat.setDecorFitsSystemWindows(w, false)
        w.statusBarColor = Color.TRANSPARENT
        w.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        val c = WindowInsetsControllerCompat(w, w.decorView)
        c.isAppearanceLightStatusBars = !Bg.dark
        c.isAppearanceLightNavigationBars = !Bg.dark
    }
}
