package app.reelblocker.step1

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * v3.3 settings-lock gate — the one reusable loosening flow.
 *
 * Gate-flow choice: a dedicated Activity (not an overlay in MainActivity).
 * It gets its own lifecycle and back stack, survives tab switches in the
 * caller, and the pending change is applied HERE on success — callers just
 * refresh from prefs on resume, so a discarded gate can never half-apply.
 *
 * Modes:
 *  - MODE_SET_PIN: two PIN entries → PIN stored (salted hash), lock ON.
 *    Tightening, so no gate steps.
 *  - MODE_GATE: PIN → 60 s countdown → typed phrase → pending change applied
 *    (ACTION_TURN_OFF / ACTION_ALLOWANCE / ACTION_LOCK_OFF, and since
 *    v4.1 ACTION_APP_LIMIT — raising a whole-app daily time limit).
 *  - MODE_CHANGE_PIN: the gate, then two new-PIN entries.
 *
 * Rules:
 *  - Wrong PIN: shake + error. 5 wrong attempts (persisted in Prefs, so
 *    reopening the gate doesn't reset them) = a 30 s wait before retry.
 *  - The countdown cannot be skipped: leaving the activity mid-countdown
 *    (onPause without finishing) aborts the whole loosening — nothing is
 *    applied and the caller re-syncs its switches from prefs on resume.
 *  - Phrase match: trimmed, case-insensitive, otherwise exact.
 *  - There is no PIN recovery by design (see the set-PIN blurb + README).
 *  - v4.1.2: launchMode singleTop + an ignoring onNewIntent — a second
 *    launch while a flow is running can't stack a duplicate gate or
 *    restart the countdown; the first flow's pending change survives.
 */
class LockGateActivity : Activity() {

    private var mode: String = MODE_SET_PIN
    private var action: String = ""
    private var allowIg: Boolean = true
    private var allowValue: Int = 0
    // v4.1: pending whole-app time-limit raise (ACTION_APP_LIMIT).
    private var limitIg: Boolean = true
    private var limitValue: Int = 0

    private var step = STEP_PIN
    private lateinit var col: LinearLayout
    private var countdownEndMs = 0L

    // PIN step views
    private lateinit var pinInput: EditText
    private lateinit var pinError: TextView
    private lateinit var pinWait: TextView
    private lateinit var pinConfirm: Button

    // Countdown step views
    private lateinit var countNum: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            when (step) {
                STEP_PIN -> if (::pinWait.isInitialized) updatePinWait()
                STEP_COUNTDOWN -> {
                    val remain = countdownEndMs - System.currentTimeMillis()
                    if (remain <= 0) showPhraseStep()
                    else if (::countNum.isInitialized)
                        countNum.text = ((remain + 999) / 1000).toString()
                }
            }
            if (!isFinishing) handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SET_PIN
        action = intent.getStringExtra(EXTRA_ACTION) ?: ""
        allowIg = intent.getBooleanExtra(EXTRA_ALLOW_IG, true)
        allowValue = intent.getIntExtra(EXTRA_ALLOW_VALUE, 0)
        limitIg = intent.getBooleanExtra(EXTRA_LIMIT_IG, true)
        limitValue = intent.getIntExtra(EXTRA_LIMIT_VALUE, 0)

        // Guard: gating with no active lock makes no sense (e.g. a stale
        // launch). Prefs.lockActive() already treats on-without-PIN as off.
        if (mode != MODE_SET_PIN && !Prefs.lockActive(this)) {
            finish()
            return
        }

        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        Ui.applySystemBarInsets(col)
        setContentView(col)

        if (mode == MODE_SET_PIN) showNewPinStep(firstTime = true)
        else showPinStep()
        handler.post(ticker)
    }

    /**
     * v4.1.2 re-entry guard: with launchMode="singleTop", a second launch
     * while this gate is already on top (e.g. a double-tapped gated switch
     * in MainActivity) is delivered here instead of stacking a second gate.
     * It is deliberately IGNORED: the in-progress flow keeps its original
     * mode/action extras, its current step and its countdown end time —
     * the first flow's pending change is the one that can ever be applied.
     * (Not calling setIntent either, so nothing re-reads the new extras.)
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Intentionally ignored — see above.
    }

    override fun onPause() {
        super.onPause()
        // Leaving mid-countdown cancels the loosening outright. (Finishing
        // normally — apply, cancel button, back — is already a discard or
        // an apply, never a skip.)
        if (step == STEP_COUNTDOWN && !isFinishing) finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    // ================= STEP 1 — PIN =================

    private fun showPinStep() {
        step = STEP_PIN
        col.removeAllViews()
        col.addView(titleText("Settings lock"))
        col.addView(subText("Enter your PIN ${actionDescription()}."))
        pinInput = pinField("PIN")
        col.addView(pinInput)
        pinError = errorText()
        col.addView(pinError)
        pinWait = subText("").apply { setTextColor(Ui.RED) }
        col.addView(pinWait)
        pinConfirm = primaryButton("Continue") { submitPin() }
        col.addView(pinConfirm)
        col.addView(cancelLink())
        updatePinWait()
    }

    private fun actionDescription(): String = when {
        mode == MODE_CHANGE_PIN -> "to change your PIN"
        action == ACTION_TURN_OFF -> "to turn protection OFF"
        action == ACTION_ALLOWANCE ->
            "to raise the ${if (allowIg) "Instagram Reels" else "YouTube Shorts"} allowance to $allowValue"
        action == ACTION_APP_LIMIT ->
            "to raise the ${if (limitIg) "Instagram" else "YouTube"} app time limit to $limitValue min/day"
        action == ACTION_LOCK_OFF -> "to turn the settings lock OFF"
        else -> "to continue"
    }

    /** Returns true while a wrong-PIN wait is still running. */
    private fun updatePinWait(): Boolean {
        val remain = Prefs.lockWaitUntil(this) - System.currentTimeMillis()
        val waiting = remain > 0
        pinConfirm.isEnabled = !waiting
        pinConfirm.alpha = if (waiting) 0.4f else 1f
        pinWait.visibility = if (waiting) View.VISIBLE else View.GONE
        if (waiting) pinWait.text = "Too many wrong PINs — try again in ${(remain + 999) / 1000} s."
        return waiting
    }

    private fun submitPin() {
        if (updatePinWait()) return // still serving a wrong-PIN wait
        val pin = pinInput.text.toString()
        if (!Prefs.verifyPin(this, pin)) {
            val n = Prefs.lockWrongCount(this) + 1
            if (n >= MAX_WRONG) {
                Prefs.setLockWrongCount(this, 0)
                Prefs.setLockWaitUntil(this, System.currentTimeMillis() + WRONG_WAIT_MS)
                showPinError("Too many wrong tries. Wait 30 seconds.")
            } else {
                Prefs.setLockWrongCount(this, n)
                showPinError("Wrong PIN — ${MAX_WRONG - n} tries left before a wait.")
            }
            shake(pinInput)
            pinInput.text.clear()
            updatePinWait()
            return
        }
        Prefs.setLockWrongCount(this, 0)
        showCountdownStep()
    }

    private fun showPinError(msg: String) {
        pinError.text = msg
        pinError.visibility = View.VISIBLE
    }

    // ================= STEP 2 — 60 s countdown =================

    private fun showCountdownStep() {
        step = STEP_COUNTDOWN
        countdownEndMs = System.currentTimeMillis() + COUNTDOWN_MS
        col.removeAllViews()
        col.addView(titleText("One minute"))
        col.addView(subText(
            "Loosening is slow on purpose. Stay on this screen — " +
                "leaving cancels the change and nothing is applied."))
        countNum = TextView(this).apply {
            text = "60"
            textSize = 64f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            gravity = Gravity.CENTER
            setPadding(0, dp(36), 0, dp(36))
        }
        col.addView(countNum)
        col.addView(cancelLink())
    }

    // ================= STEP 3 — typed phrase =================

    private fun showPhraseStep() {
        step = STEP_PHRASE
        col.removeAllViews()
        col.addView(titleText("Type it to mean it"))
        col.addView(subText("Type this phrase exactly to apply the change:"))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = Ui.glass(this@LockGateActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        card.addView(TextView(this).apply {
            text = "\"$PHRASE\""
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            setTextColor(Ui.TEXT)
            gravity = Gravity.CENTER
        })
        col.addView(card)

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textSize = 16f
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.FAINT)
            hint = "Type the phrase"
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#1C1C1E"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        col.addView(input)
        val err = errorText()
        col.addView(err)
        col.addView(primaryButton("Apply the change") {
            val typed = input.text.toString().trim()
            if (typed.equals(PHRASE, ignoreCase = true)) {
                if (mode == MODE_CHANGE_PIN) showNewPinStep(firstTime = false)
                else {
                    applyPending()
                    finish()
                }
            } else {
                err.text = "That doesn't match — check the wording and try again."
                err.visibility = View.VISIBLE
                shake(input)
            }
        })
        col.addView(cancelLink())
    }

    // ================= PIN set / change (two entries) =================

    private fun showNewPinStep(firstTime: Boolean) {
        step = STEP_NEWPIN
        col.removeAllViews()
        if (firstTime) {
            col.addView(titleText("Set your PIN"))
            col.addView(subText(
                "Pick a 4–6 digit PIN. Turning the lock ON is instant — after that, " +
                    "loosening anything (protection off, a bigger allowance, this lock) " +
                    "takes the PIN, a 60-second wait and a typed phrase.\n\n" +
                    "Forgot your PIN later? There is NO recovery. The only reset is " +
                    "clearing this app's data or reinstalling — and that erases all " +
                    "your stats too."))
        } else {
            col.addView(titleText("Choose a new PIN"))
            col.addView(subText("4–6 digits. The new PIN replaces the old one."))
        }
        val pin1 = pinField("New PIN")
        val pin2 = pinField("Repeat PIN")
        col.addView(pin1)
        col.addView(pin2)
        val err = errorText()
        col.addView(err)
        col.addView(primaryButton(if (firstTime) "Turn on lock" else "Set new PIN") {
            val a = pin1.text.toString()
            val b = pin2.text.toString()
            when {
                !a.matches(Regex("\\d{4,6}")) -> {
                    err.text = "PIN must be 4–6 digits."; err.visibility = View.VISIBLE; shake(pin1)
                }
                a != b -> {
                    err.text = "The two PINs don't match."; err.visibility = View.VISIBLE; shake(pin2)
                }
                else -> {
                    Prefs.setPin(this, a)
                    if (firstTime) Prefs.setLockEnabled(this, true)
                    finish()
                }
            }
        })
        col.addView(cancelLink())
    }

    // ================= apply =================

    /** Runs only after the full gate. The change lands in Prefs here; the
     *  caller's onResume re-reads prefs and repaints (switches included). */
    private fun applyPending() {
        when (action) {
            ACTION_TURN_OFF -> Prefs.setEnabled(this, false)
            ACTION_ALLOWANCE -> Prefs.setAllowance(this, allowIg, allowValue)
            ACTION_APP_LIMIT -> Prefs.setAppLimitMin(this, limitIg, limitValue)
            ACTION_LOCK_OFF -> Prefs.clearPinAndLock(this)
        }
    }

    // ================= small view helpers (same language as the app) =================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun titleText(s: String) = TextView(this).apply {
        text = s; textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Ui.TEXT)
    }

    private fun subText(s: String) = TextView(this).apply {
        text = s; textSize = 13.5f; setTextColor(Ui.MUTED)
        setLineSpacing(0f, 1.4f)
        setPadding(0, dp(8), 0, 0)
    }

    private fun errorText() = TextView(this).apply {
        textSize = 13f; setTextColor(Ui.RED)
        setPadding(0, dp(10), 0, 0)
        visibility = View.GONE
    }

    private fun pinField(hintText: String) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(6))
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(Ui.TEXT)
        setHintTextColor(Ui.FAINT)
        hint = hintText
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor("#1C1C1E"))
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) }
    }

    private fun primaryButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.BLACK)
        Ui.pressable(this)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
            setColor(Color.WHITE)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(20) }
        setOnClickListener { onClick() }
    }

    private fun cancelLink() = TextView(this).apply {
        text = "Cancel"
        textSize = 13f
        setTextColor(Ui.FAINT)
        gravity = Gravity.CENTER
        setPadding(0, dp(18), 0, dp(6))
        isClickable = true
        Ui.pressable(this)
        setOnClickListener { finish() }
    }

    private fun shake(v: View) {
        ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0f, 12f, -12f, 8f, -8f, 0f).apply {
            duration = 300
            start()
        }
    }

    companion object {
        const val PHRASE = "I am choosing to loosen my guard"

        private const val EXTRA_MODE = "mode"
        private const val EXTRA_ACTION = "action"
        private const val EXTRA_ALLOW_IG = "allow_ig"
        private const val EXTRA_ALLOW_VALUE = "allow_value"
        private const val EXTRA_LIMIT_IG = "limit_ig"
        private const val EXTRA_LIMIT_VALUE = "limit_value"

        const val MODE_SET_PIN = "set_pin"
        const val MODE_GATE = "gate"
        const val MODE_CHANGE_PIN = "change_pin"

        const val ACTION_TURN_OFF = "turn_off"
        const val ACTION_ALLOWANCE = "allowance"
        const val ACTION_APP_LIMIT = "app_limit"
        const val ACTION_LOCK_OFF = "lock_off"

        private const val STEP_PIN = 0
        private const val STEP_COUNTDOWN = 1
        private const val STEP_PHRASE = 2
        private const val STEP_NEWPIN = 3

        private const val COUNTDOWN_MS = 60_000L
        private const val MAX_WRONG = 5
        private const val WRONG_WAIT_MS = 30_000L

        fun launchSetPin(ctx: Context) {
            ctx.startActivity(Intent(ctx, LockGateActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_SET_PIN))
        }

        fun launchChangePin(ctx: Context) {
            ctx.startActivity(Intent(ctx, LockGateActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_CHANGE_PIN))
        }

        fun launchTurnOff(ctx: Context) = launchGate(ctx, ACTION_TURN_OFF, true, 0)

        fun launchLockOff(ctx: Context) = launchGate(ctx, ACTION_LOCK_OFF, true, 0)

        fun launchAllowance(ctx: Context, ig: Boolean, value: Int) =
            launchGate(ctx, ACTION_ALLOWANCE, ig, value)

        /** v4.1: raising a whole-app time limit goes through the same gate. */
        fun launchAppLimit(ctx: Context, ig: Boolean, value: Int) {
            ctx.startActivity(Intent(ctx, LockGateActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_GATE)
                .putExtra(EXTRA_ACTION, ACTION_APP_LIMIT)
                .putExtra(EXTRA_LIMIT_IG, ig)
                .putExtra(EXTRA_LIMIT_VALUE, value))
        }

        private fun launchGate(ctx: Context, action: String, ig: Boolean, value: Int) {
            ctx.startActivity(Intent(ctx, LockGateActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_GATE)
                .putExtra(EXTRA_ACTION, action)
                .putExtra(EXTRA_ALLOW_IG, ig)
                .putExtra(EXTRA_ALLOW_VALUE, value))
        }
    }
}
