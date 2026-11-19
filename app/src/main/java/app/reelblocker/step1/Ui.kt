package app.reelblocker.step1

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View

/**
 * v3.0 design language (from the approved E/F mockups):
 * flat black background, Opal-calm typography, and GLASS cards — translucent
 * white fill + 1px translucent border + a subtle top highlight line. Glass is
 * for cards only: no gradient backgrounds, no glow.
 */
object Ui {
    val BG = Color.BLACK
    val TEXT = Color.WHITE
    val MUTED = Color.parseColor("#BCBBC0")
    val FAINT = Color.parseColor("#8E8E93")
    val PINK = Color.parseColor("#FA114F")   // E accent — attempts ring, today's bar
    val GREEN = Color.parseColor("#A6FF00")  // minutes-saved ring
    val CYAN = Color.parseColor("#04C7DD")   // streak ring
    val FGREEN = Color.parseColor("#30D158") // focus ring / on-state
    val RED = Color.parseColor("#FF453A")
    val TRACK = Color.parseColor("#2C2C2E")
    val BAR = Color.parseColor("#3A3A3C")

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
    fun dpF(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    /** Glass card background: #14FFFFFF fill, #2AFFFFFF 1px border, top highlight. */
    fun glass(ctx: Context, radiusDp: Int = 18): Drawable =
        GlassDrawable(dpF(ctx, radiusDp.toFloat()), dpF(ctx, 1f), dpF(ctx, 10f))

    /**
     * v3.1 edge-to-edge fix: pad [view] by the system-bar insets (status bar
     * top, navigation bar bottom) on top of its existing padding. The view's
     * own background still fills the whole window, so black extends behind
     * the bars while content never slides underneath them. Framework API
     * only — no AndroidX.
     */
    fun applySystemBarInsets(view: View) {
        val pl = view.paddingLeft
        val pt = view.paddingTop
        val pr = view.paddingRight
        val pb = view.paddingBottom
        view.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            val bars = insets.systemWindowInsets
            v.setPadding(pl + bars.left, pt + bars.top, pr + bars.right, pb + bars.bottom)
            insets
        }
        view.requestApplyInsets()
    }

    /** Subtle 0.97 press scale for buttons/chips. Native StateListAnimator, zero background cost. */
    fun pressable(view: View) {
        fun anim(prop: String, value: Float) = ObjectAnimator.ofFloat(view, prop, value).apply {
            duration = 110
        }
        val pressed = AnimatorSet().apply {
            playTogether(anim(View.SCALE_X.name, 0.97f), anim(View.SCALE_Y.name, 0.97f))
        }
        val released = AnimatorSet().apply {
            playTogether(anim(View.SCALE_X.name, 1f), anim(View.SCALE_Y.name, 1f))
        }
        view.stateListAnimator = StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), released)
        }
    }
}

/** Rounded-rect drawable: translucent fill, thin border, 1px highlight near the top edge. */
class GlassDrawable(
    private val radius: Float,
    private val strokeWidth: Float,
    private val highlightInset: Float
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x14FFFFFF; style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2AFFFFFF; style = Paint.Style.STROKE; strokeWidth = this@GlassDrawable.strokeWidth
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x30FFFFFF; style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val rect = RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        // Top highlight line, clipped to the card shape, inset from the corners.
        val path = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(
            rect.left + highlightInset, rect.top + strokeWidth * 0.5f,
            rect.right - highlightInset, rect.top + strokeWidth * 1.5f, highlightPaint
        )
        canvas.restore()
        val half = strokeWidth / 2f
        val inner = RectF(rect.left + half, rect.top + half, rect.right - half, rect.bottom - half)
        canvas.drawRoundRect(inner, radius, radius, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha; strokePaint.alpha = alpha; highlightPaint.alpha = alpha
    }

    override fun setColorFilter(filter: android.graphics.ColorFilter?) {
        fillPaint.colorFilter = filter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * E-style triple activity rings. Fractions are set from real data by the
 * caller (see MainActivity for the denominators); this view only draws.
 * Ring order outer→inner: attempts (pink), minutes saved (green), streak (cyan).
 */
class RingsView(ctx: Context) : View(ctx) {
    /** Target fractions (real data). Drawing uses [displayFractions], which
     *  the intro animation sweeps from 0 up to these. */
    var fractions = floatArrayOf(0f, 0f, 0f)
        set(value) {
            field = value
            if (intro == null) displayFractions = value.copyOf()
            invalidate()
        }

    private var displayFractions = floatArrayOf(0f, 0f, 0f)
    private var intro: android.animation.ValueAnimator? = null

    /** v3.1: sweep all three rings 0 → target over ~650 ms, ease-out. Runs
     *  only while Home is on screen; cancelled if a new one starts. */
    fun playIntro() {
        intro?.cancel()
        val target = fractions
        intro = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                displayFractions = FloatArray(3) { i -> target.getOrElse(i) { 0f } * t }
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    displayFractions = target.copyOf()
                    intro = null
                    invalidate()
                }
            })
            start()
        }
    }

    private val colors = intArrayOf(Ui.PINK, Ui.GREEN, Ui.CYAN)
    private val tracks = intArrayOf(
        Color.parseColor("#330A18"), Color.parseColor("#233300"), Color.parseColor("#06333A")
    )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = width / 11f
        paint.strokeWidth = stroke
        var inset = stroke / 2f + 1f
        for (i in 0..2) {
            val rect = RectF(inset, inset, width - inset, height - inset)
            paint.color = tracks[i]
            canvas.drawArc(rect, 0f, 360f, false, paint)
            val f = displayFractions.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            if (f > 0f) {
                paint.color = colors[i]
                canvas.drawArc(rect, -90f, 360f * f, false, paint)
            }
            inset += stroke + stroke * 0.45f
        }
    }
}

/** Single ring (Focus countdown). Fraction 0–1, drawn clockwise from top. */
class RingView(ctx: Context, private val ringColor: Int) : View(ctx) {
    var fraction = 1f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = width / 16f
        paint.strokeWidth = stroke
        val rect = RectF(stroke / 2f + 1f, stroke / 2f + 1f, width - stroke / 2f - 1f, height - stroke / 2f - 1f)
        paint.color = Ui.TRACK
        canvas.drawArc(rect, 0f, 360f, false, paint)
        if (fraction > 0f) {
            paint.color = ringColor
            canvas.drawArc(rect, -90f, 360f * fraction, false, paint)
        }
    }
}

/** 7-day bar chart. Bars only — values/day labels are TextViews underneath. */
class BarsView(ctx: Context) : View(ctx) {
    var values = IntArray(7)
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return
        val max = maxOf(values.maxOrNull() ?: 0, 1)
        val gap = width * 0.035f
        val barW = (width - gap * (values.size + 1)) / values.size
        val radius = barW * 0.18f
        for (i in values.indices) {
            val h = if (values[i] == 0) height * 0.03f
                else height * 0.12f + (height * 0.85f) * (values[i].toFloat() / max)
            val left = gap + i * (barW + gap)
            paint.color = if (i == values.lastIndex) Ui.PINK else Ui.BAR
            val rect = RectF(left, height - h, left + barW, height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }
}
