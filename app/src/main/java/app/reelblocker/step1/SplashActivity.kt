package app.reelblocker.step1

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * v3.1 splash / loading screen — the LAUNCHER entry point. Pure black
 * (windowBackground is black in the theme, so no white flash), one ring
 * sweeping in over ~700 ms while "VETO" fades/rises beneath it,
 * then hands off to MainActivity at ~1.1 s total. No network, no extra
 * artificial delay — the animation IS the loading screen.
 */
class SplashActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var navigated = false
    private var ringAnimator: ValueAnimator? = null

    private val go = Runnable {
        if (navigated || isFinishing) return@Runnable
        navigated = true
        startActivity(Intent(this, MainActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
        }
        Ui.applySystemBarInsets(root)

        val ring = RingView(this, Ui.PINK).apply { fraction = 0f }
        val ringDp = 96
        root.addView(ring, LinearLayout.LayoutParams(dp(ringDp), dp(ringDp)))

        val label = TextView(this).apply {
            text = "VETO"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.35f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(22), 0, 0)
            alpha = 0f
            translationY = dp(8).toFloat()
        }
        root.addView(label)
        setContentView(root)

        ringAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            interpolator = DecelerateInterpolator()
            addUpdateListener { ring.fraction = it.animatedValue as Float }
            start()
        }
        label.animate().alpha(1f).translationY(0f)
            .setStartDelay(150).setDuration(600).start()

        handler.postDelayed(go, TOTAL_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(go)
        ringAnimator?.cancel()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TOTAL_MS = 1_100L
    }
}
