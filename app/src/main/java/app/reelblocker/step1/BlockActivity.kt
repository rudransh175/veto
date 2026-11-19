package app.reelblocker.step1

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * v3.0 block screen (from the approved mockup): shown right after the service
 * bounces the user out of a Reels/Shorts viewer. Breathing ring, the real
 * attempt count, a 5-second pause before "Go back" unlocks, and an honest
 * escape hatch — "Open anyway" grants that app a 60-second bypass and breaks
 * today's streak.
 *
 * Relaunch behaviour: launchMode singleTop + CLEAR_TOP/SINGLE_TOP flags from
 * the service, so a repeat block while this screen is open lands in
 * onNewIntent and just updates the count (the countdown is NOT restarted).
 *
 * v3.2: when the block is a daily-allowance hit (EXTRA_LIMIT > 0), the copy
 * switches to "That's your N for today." + the midnight-reset line; with no
 * allowance set, the original attempt copy below is unchanged.
 *
 * v4.1: two more modes via EXTRA_MODE, reusing the same layout and the
 * same 5-second Go-back countdown:
 *  - MODE_APPLIMIT (Step 4b): the whole app hit its daily minute limit.
 *  - MODE_FOCUS (Step 7b): a focus session is running and this app is
 *    one of the chosen ones.
 * Neither shows "Open anyway": for app limits the deliberate path is the
 * settings-lock gate, and for focus it's ending the session in the app.
 *
 * v4.1.2: in MODE_FOCUS the "(M:SS left)" text is LIVE — recomputed from
 * Prefs.focusEndMs every second while this screen is shown (the intent
 * extra is only the first paint / a fallback), and if the session ends
 * while the screen is up the subline says so.
 */
class BlockActivity : Activity() {

    private var pkg: String = ""
    private var limit = 0
    // v4.1 modes (MODE_ATTEMPT is the original viewer block).
    private var mode: String = MODE_ATTEMPT
    private var limitMin = 0
    private var remainMs = 0L
    private lateinit var headline: TextView
    private lateinit var subline: TextView
    private lateinit var goBackBtn: Button
    private lateinit var openAnywayView: TextView
    private lateinit var breath: BreathView
    private var shownAtMs = 0L
    private var countdownDone = false

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val remain = COUNTDOWN_MS - (System.currentTimeMillis() - shownAtMs)
            if (remain <= 0) {
                countdownDone = true
                goBackBtn.isEnabled = true
                goBackBtn.text = "Go back"
                styleGoBack()
            } else {
                goBackBtn.text = "Go back (${(remain + 999) / 1000})"
                handler.postDelayed(this, 200)
            }
        }
    }

    // v4.1.2: focus mode only — refreshes the remaining-time subline from
    // Prefs.focusEndMs once a second for as long as this screen is alive.
    private val focusTicker = object : Runnable {
        override fun run() {
            if (mode != MODE_FOCUS || isFinishing) return
            updateFocusSubline()
            handler.postDelayed(this, 1000)
        }
    }

    /** (Re)starts the 1 s focus refresh; safe to call repeatedly. */
    private fun syncFocusTicker() {
        handler.removeCallbacks(focusTicker)
        if (mode == MODE_FOCUS) handler.post(focusTicker)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pkg = intent.getStringExtra(EXTRA_PKG) ?: ""
        readModeExtras(intent)
        shownAtMs = System.currentTimeMillis()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        // v3.1: same edge-to-edge treatment as MainActivity — black behind
        // the bars, content padded clear of them (was a fixed 64dp guess).
        Ui.applySystemBarInsets(root)

        breath = BreathView(this)
        root.addView(breath, LinearLayout.LayoutParams(dp(120), dp(120)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        headline = TextView(this).apply {
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, 0)
        }
        root.addView(headline)

        subline = TextView(this).apply {
            textSize = 14f
            setTextColor(Ui.MUTED)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.35f)
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(subline)
        limit = intent.getIntExtra(EXTRA_LIMIT, 0)
        updateHeadline(intent.getIntExtra(EXTRA_COUNT, 0), limit)

        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        goBackBtn = Button(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            isEnabled = false
            Ui.pressable(this)
            setOnClickListener { goHome() }
        }
        styleGoBack()
        root.addView(goBackBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        openAnywayView = TextView(this).apply {
            text = "Open anyway — costs your streak"
            textSize = 13f
            setTextColor(Ui.FAINT)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(6))
            isClickable = true
            Ui.pressable(this)
            setOnClickListener { openAnyway() }
            // v4.1: the app-limit and focus modes have no escape hatch here.
            visibility = if (mode == MODE_ATTEMPT) View.VISIBLE else View.GONE
        }
        root.addView(openAnywayView)

        setContentView(root)
        handler.post(ticker)
        syncFocusTicker()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pkg = intent.getStringExtra(EXTRA_PKG) ?: pkg
        limit = intent.getIntExtra(EXTRA_LIMIT, limit)
        readModeExtras(intent)
        if (::openAnywayView.isInitialized)
            openAnywayView.visibility = if (mode == MODE_ATTEMPT) View.VISIBLE else View.GONE
        updateHeadline(intent.getIntExtra(EXTRA_COUNT, 0), limit)
        syncFocusTicker()
    }

    /** v4.1: mode + its extras (app-limit minutes / focus time left). */
    private fun readModeExtras(intent: Intent) {
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_ATTEMPT
        limitMin = intent.getIntExtra(EXTRA_LIMIT_MIN, limitMin)
        remainMs = intent.getLongExtra(EXTRA_REMAIN_MS, remainMs)
    }

    private fun appName(): String =
        if (pkg == "com.google.android.youtube") "YouTube" else "Instagram"

    override fun onResume() {
        super.onResume()
        breath.start()
    }

    override fun onPause() {
        breath.stop()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(focusTicker)
        super.onDestroy()
    }

    /** Back during the countdown would skip the pause — ignore it until unlocked. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (countdownDone) goHome()
    }

    private fun updateHeadline(count: Int, limit: Int) {
        when (mode) {
            // v4.1 Step 4b: the whole app used up its daily minutes.
            MODE_APPLIMIT -> {
                headline.text = "${appName()}'s done for today."
                subline.text = "You set $limitMin min/day — it resets at midnight."
            }
            // v4.1 Step 7b: a focus session is running.
            MODE_FOCUS -> {
                headline.text = "Focus is running."
                updateFocusSubline()
            }
            else -> {
                if (limit > 0) {
                    // v3.2 daily-limit hit: the allowance ran out, not just an attempt.
                    headline.text = "That's your $limit for today."
                    subline.text = "Daily limit reached — it resets at midnight."
                } else {
                    headline.text = if (count > 0) "That's attempt $count today." else "Caught you."
                    subline.text = "The urge peaks for about ninety seconds.\nBreathe with the circle — it's already fading."
                }
            }
        }
    }

    /**
     * v4.1.2: the focus subline, computed LIVE from Prefs.focusEndMs (the
     * service's EXTRA_REMAIN_MS snapshot is only a fallback for the brief
     * moment prefs might not carry the session). Called on first paint,
     * on onNewIntent, and once a second by focusTicker while shown. When
     * the end passes while the screen is up, the line says the session
     * has ended instead of counting into negative time.
     */
    private fun updateFocusSubline() {
        val endMs = Prefs.focusEndMs(this)
        val leftMs = if (endMs > 0L) endMs - System.currentTimeMillis() else remainMs
        subline.text = if (leftMs > 0) {
            val secs = (leftMs + 999) / 1000
            "${appName()} stays closed until your session ends " +
                "(${"%d:%02d".format(secs / 60, secs % 60)} left)."
        } else {
            "Your focus session has ended — ${appName()} is open again."
        }
    }

    private fun styleGoBack() {
        val enabled = goBackBtn.isEnabled
        goBackBtn.setTextColor(if (enabled) Color.BLACK else Ui.FAINT)
        goBackBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
            setColor(if (enabled) Color.WHITE else Color.parseColor("#2C2C2E"))
        }
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
        finish()
    }

    private fun openAnyway() {
        if (pkg.isNotEmpty()) {
            Prefs.setBypass(this, pkg, System.currentTimeMillis() + BYPASS_MS)
            Prefs.markBroken(this)
        }
        finish() // back to the app the user was in; the service skips it for 60 s
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Ring that gently scales 0.85→1.0→0.85 over ~4 s. Cheap: one animator, redraws one small view. */
    private class BreathView(ctx: Context) : View(ctx) {
        private var pulse = 0.85f
        private var animator: ValueAnimator? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        fun start() {
            if (animator != null) return
            animator = ValueAnimator.ofFloat(0.85f, 1.0f).apply {
                duration = 2000
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun stop() {
            animator?.cancel()
            animator = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val stroke = width / 14f
            paint.strokeWidth = stroke
            val r = (width / 2f - stroke) * pulse
            val rect = RectF(width / 2f - r, height / 2f - r, width / 2f + r, height / 2f + r)
            paint.color = Ui.TRACK
            canvas.drawArc(rect, 0f, 360f, false, paint)
            paint.color = Ui.PINK
            canvas.drawArc(rect, -90f, 120f, false, paint)
        }
    }

    companion object {
        const val EXTRA_COUNT = "count"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_LIMIT = "limit"
        // v4.1 modes + their extras.
        const val EXTRA_MODE = "mode"
        const val EXTRA_LIMIT_MIN = "limit_min"
        const val EXTRA_REMAIN_MS = "remain_ms"
        const val MODE_ATTEMPT = "attempt"
        const val MODE_APPLIMIT = "applimit"
        const val MODE_FOCUS = "focus"
        private const val COUNTDOWN_MS = 5_000L
        private const val BYPASS_MS = 60_000L
    }
}
