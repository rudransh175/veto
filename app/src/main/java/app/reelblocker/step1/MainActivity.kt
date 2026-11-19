package app.reelblocker.step1

import android.accessibilityservice.AccessibilityServiceInfo
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/**
 * v3.0 full app: four tabs (Home / Stats / Focus / Shield) in one Activity,
 * flat black + glass cards, per the approved E/F mockups.
 *
 *  - Home:   date + streak pill, E triple rings with a real-value legend,
 *            hero number, protection glass card, per-app rows.
 *  - Stats:  7-day bars (today pink), per-app week totals, streaks, score.
 *  - Focus:  countdown-ring timer; sessions stored by end-timestamp so they
 *            survive leaving the tab or the app.
 *  - Shield: the Step 2 protection card, Realme checklist and debug log,
 *            moved here unchanged in behaviour.
 *
 * Detection still lives entirely in BlockerAccessibilityService.
 *
 * v3.1 polish: system-bar insets on the root (nothing draws under the clock
 * any more), ring sizes derived from screen width, tab-switch fade/rise,
 * Home ring sweep + hero count-up, press-scale on buttons/chips.
 *
 * v3.2 allowance: Shield gains the "Daily allowance" card (steppers + preset
 * chips per app, live watched state); Home per-app rows show watched ·
 * stopped and an off-mode "still counting" line; Stats adds watched totals.
 *
 * v3.3 settings lock: loosening (protection OFF, allowance up, lock off,
 * PIN change) is routed through LockGateActivity when the lock is on;
 * tightening stays instant. Shield gains the "Settings lock" card.
 *
 * v4.0 final: a fifth tab, Circle — the private supportive board with NO
 * server (snapshot codes are copied/pasted by hand; see Prefs), and a
 * first-run 3-page onboarding shown before the tabs until completed
 * (Shield has a "Replay intro" link that resets it).
 *
 * v4.1 complete: the two remaining original-plan pieces. Shield gains the
 * "App time limits" card (Step 4b — a daily minute limit for the whole
 * Instagram/YouTube app, steppers of 15 min + 0/30/60/120 chips, a live
 * "Used today" line; raising a limit is loosening, so with the lock on
 * it goes through the same gate as allowances). Focus gains two toggles
 * (Step 7b — block Instagram / YouTube entirely while a session runs;
 * the service enforces it, the timer here just shows it).
 */
class MainActivity : Activity() {

    // Tabs
    private lateinit var tabViews: List<View>
    private lateinit var tabLabels: List<TextView>
    private var currentTab = 0

    // Home
    private lateinit var streakPill: TextView
    private lateinit var rings: RingsView
    private lateinit var legendAttempts: TextView
    private lateinit var legendSaved: TextView
    private lateinit var legendStreak: TextView
    private lateinit var heroNum: TextView
    private lateinit var heroSentence: TextView
    private lateinit var pillBlocked: TextView
    private lateinit var pillSaved: TextView
    private lateinit var pillScore: TextView
    private lateinit var homeIgRow: TextView
    private lateinit var homeYtRow: TextView
    private lateinit var homeUnattr: TextView
    private lateinit var homeOffNote: TextView

    // Stats
    private lateinit var statsSentence: TextView
    private lateinit var bars: BarsView
    private lateinit var barValues: List<TextView>
    private lateinit var barDays: List<TextView>
    private lateinit var statsIg: TextView
    private lateinit var statsYt: TextView
    private lateinit var statsIgW: TextView
    private lateinit var statsYtW: TextView
    private lateinit var statsSaved: TextView
    private lateinit var statsUnattr: TextView
    private lateinit var statsScore: TextView
    private lateinit var statsLongest: TextView
    private lateinit var statsTotal: TextView

    // Focus
    private lateinit var focusRing: RingView
    private lateinit var focusTime: TextView
    private lateinit var focusBtn: Button
    private lateinit var focusStatus: TextView
    private lateinit var focusWeek: TextView
    private val chipViews = HashMap<Int, TextView>()
    private var focusCompleteMsg = false
    private var focusNote: String? = null
    // Focus app blocking (v4.1, Step 7b)
    private lateinit var focusBlockIgSw: Switch
    private lateinit var focusBlockYtSw: Switch

    // Shield — daily allowance (v3.2)
    private lateinit var allowIgNum: TextView
    private lateinit var allowYtNum: TextView
    private lateinit var allowIgState: TextView
    private lateinit var allowYtState: TextView
    private val allowChipsIg = HashMap<Int, TextView>()
    private val allowChipsYt = HashMap<Int, TextView>()

    // Shield — whole-app time limits (v4.1, Step 4b)
    private lateinit var appLimitIgNum: TextView
    private lateinit var appLimitYtNum: TextView
    private lateinit var appLimitIgState: TextView
    private lateinit var appLimitYtState: TextView
    private val appLimitChipsIg = HashMap<Int, TextView>()
    private val appLimitChipsYt = HashMap<Int, TextView>()

    // Shield — settings lock (v3.3)
    private lateinit var lockState: TextView
    private lateinit var lockBtnPrimary: Button
    private lateinit var lockBtnSecondary: Button

    // Circle (v4.0)
    private lateinit var circleNameInput: EditText
    private lateinit var circleCodeView: TextView
    private lateinit var circleAddInput: EditText
    private lateinit var circleAddStatus: TextView
    private lateinit var circleBoard: LinearLayout
    private lateinit var circleEmpty: TextView

    // Onboarding (v4.0, first run only)
    private var inOnboarding = false
    private var obIndex = 0
    private lateinit var obPages: List<View>
    private lateinit var obDots: TextView
    private lateinit var obNext: Button
    private lateinit var obA11yStatus: TextView

    // Shield
    private lateinit var a11yIcon: TextView
    private lateinit var a11yState: TextView
    private lateinit var battIcon: TextView
    private lateinit var battState: TextView
    private lateinit var autoIcon: TextView
    private lateinit var recentsIcon: TextView
    private lateinit var autoCheck: CheckBox
    private lateinit var recentsCheck: CheckBox
    private lateinit var logView: TextView

    // Protection cards (Home + Shield each hold a switch for the same pref)
    private lateinit var homeSwitch: Switch
    private lateinit var shieldSwitch: Switch
    private lateinit var homeProtTitle: TextView
    private lateinit var homeProtSub: TextView
    private lateinit var shieldProtTitle: TextView
    private lateinit var shieldProtSub: TextView
    private var syncingSwitch = false

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (Prefs.recordFocusIfDone(this@MainActivity)) focusCompleteMsg = true
            updateFocusUI()
            handler.postDelayed(this, 500)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Screen width in dp — v3.1 responsive sizing is derived from this. */
    private fun screenWDp() =
        resources.displayMetrics.widthPixels / resources.displayMetrics.density

    private var heroAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v4.0: first run shows the 3-page intro before any tabs exist.
        if (Prefs.onboarded(this)) buildMainUI() else buildOnboarding()
    }

    private fun buildMainUI() {
        inOnboarding = false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        // v3.1: black still fills the whole window (behind both system bars),
        // but ALL content — tabs and the bottom tab bar alike — is padded
        // clear of the status bar and navigation bar.
        Ui.applySystemBarInsets(root)
        val content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(content)

        tabViews = listOf(buildHome(), buildStats(), buildFocus(), buildShield(), buildCircle())
        tabViews.forEach { content.addView(it) }

        // --- Bottom tab bar ---
        root.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#222222"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        })
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.BG)
        }
        tabLabels = listOf("Home", "Stats", "Focus", "Shield", "Circle").mapIndexed { i, name ->
            TextView(this).apply {
                text = name
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(13), 0, dp(15))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { showTab(i) }
            }
        }
        tabLabels.forEach { tabBar.addView(it) }
        root.addView(tabBar)

        setContentView(root)
        showTab(0)
    }

    override fun onResume() {
        super.onResume()
        // v4.0: during onboarding there are no tabs to refresh — just keep
        // the Accessibility status line on page 2 honest.
        if (inOnboarding) { refreshObA11y(); return }
        if (Prefs.recordFocusIfDone(this)) focusCompleteMsg = true
        refreshCurrent()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun showTab(i: Int) {
        currentTab = i
        tabViews.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        // v3.1: incoming tab fades in and rises slightly (~200 ms, view-only).
        tabViews[i].apply {
            alpha = 0f
            translationY = dp(12).toFloat()
            animate().alpha(1f).translationY(0f).setDuration(200).start()
        }
        tabLabels.forEachIndexed { idx, v ->
            val active = idx == i
            // QA fix (v4.1.1): #777777 on black is only ~4.7:1 at 12sp;
            // Ui.FAINT (#8E8E93) is ~6.5:1 and already the app's faint tone.
            v.setTextColor(if (active) Ui.TEXT else Ui.FAINT)
            v.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        if (i == TAB_FOCUS && Prefs.focusDoneUnseen(this)) {
            focusCompleteMsg = true
            Prefs.clearFocusDoneUnseen(this)
        }
        refreshCurrent()
    }

    private fun refreshCurrent() {
        when (currentTab) {
            TAB_HOME -> refreshHome()
            TAB_STATS -> refreshStats()
            TAB_FOCUS -> updateFocusUI()
            TAB_SHIELD -> refreshShield()
            TAB_CIRCLE -> refreshCircle()
        }
    }

    // ================= HOME =================

    private fun buildHome(): View {
        val col = tabColumn()

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(text(dateLine(), 13f, Ui.MUTED).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        streakPill = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Color.parseColor("#3A3A3A"))
            }
        }
        topRow.addView(streakPill)
        col.addView(topRow)

        // Rings + legend (design E)
        val ringRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, dp(4))
        }
        rings = RingsView(this)
        // v3.1: ring diameter ≈ 36% of the actual screen width (clamp 120–170dp),
        // so the layout sets itself on any screen instead of one fixed size.
        val ringDp = (screenWDp() * 0.36f).coerceIn(120f, 170f).toInt()
        ringRow.addView(rings, LinearLayout.LayoutParams(dp(ringDp), dp(ringDp)))
        val legend = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
        }
        legendAttempts = legendLine(legend, Ui.PINK)
        legendSaved = legendLine(legend, Ui.GREEN)
        legendStreak = legendLine(legend, Ui.CYAN)
        ringRow.addView(legend)
        col.addView(ringRow)

        // Hero (design F: one number, one sentence)
        heroNum = text("0", 52f, Ui.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        col.addView(heroNum)
        col.addView(text("attempts blocked today", 12f, Ui.MUTED).apply { gravity = Gravity.CENTER })
        heroSentence = text("", 13.5f, Color.parseColor("#E8E8EA")).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), 0)
            setLineSpacing(0f, 1.4f)
        }
        col.addView(heroSentence)

        val pills = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(2))
        }
        pillBlocked = pill(); pillSaved = pill(); pillScore = pill()
        pills.addView(pillBlocked); pills.addView(pillSaved); pills.addView(pillScore)
        col.addView(pills)

        // Protection card
        val prot = protectionCard(isHome = true)
        col.addView(prot)

        // v3.2: protection OFF still counts watched videos (service + the
        // Accessibility permission are what make counting possible at all).
        homeOffNote = text("", 12f, Ui.MUTED).apply {
            setPadding(dp(2), dp(10), 0, 0)
            visibility = View.GONE
        }
        col.addView(homeOffNote)

        // Per-app rows
        homeIgRow = appRow(col, "Instagram Reels")
        homeYtRow = appRow(col, "YouTube Shorts")
        homeUnattr = text("", 11.5f, Ui.FAINT).apply { setPadding(0, dp(8), 0, 0) }
        col.addView(homeUnattr)

        return scroll(col)
    }

    private fun refreshHome() {
        val today = LocalDate.now()
        val attempts = Prefs.blocksOn(this, today)
        val streak = Prefs.currentStreak(this)
        streakPill.text = if (streak > 0) "$streak day streak" else "No streak yet"

        // Ring fractions (denominators documented in README):
        //  attempts: today vs goal = max(ceil(7-day avg), today, 1)
        //  saved:    today's est. minutes vs the best day's in the last 7
        //  streak:   fills at a 7-day streak
        val goal = maxOf(ceil(Prefs.avgAttemptsPrev7(this)).toInt(), attempts, 1)
        val savedToday = Prefs.minutesSaved(attempts)
        val bestSaved = maxOf(Prefs.last7Days(this).maxOf { Prefs.minutesSaved(it.second) }, 1)
        rings.fractions = floatArrayOf(
            attempts.toFloat() / goal,
            savedToday.toFloat() / bestSaved,
            minOf(streak, 7) / 7f
        )
        rings.playIntro()
        legendAttempts.text = "$attempts/$goal attempts stopped"
        legendSaved.text = "$savedToday min saved (est.)"
        legendStreak.text = "$streak day streak"

        // v3.1: hero number counts up 0 → value over the same ~650 ms window
        // as the ring sweep.
        heroAnimator?.cancel()
        heroAnimator = ValueAnimator.ofInt(0, attempts).apply {
            duration = 650
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { heroNum.text = (it.animatedValue as Int).toString() }
            start()
        }
        val lastMs = Prefs.lastBlockMs(this)
        heroSentence.text = when {
            attempts == 0 -> "No attempts yet today."
            lastMs == 0L -> "Some of these were blocked before this update — time and app weren't recorded."
            else -> {
                val app = if (Prefs.lastBlockApp(this) == "yt") "YouTube Shorts" else "Instagram Reels"
                val fmt = if (isToday(lastMs)) SimpleDateFormat("h:mm a", Locale.US)
                    else SimpleDateFormat("EEE h:mm a", Locale.US)
                "$savedToday minutes given back (est.). Last blocked ${fmt.format(Date(lastMs))} · $app."
            }
        }
        pillBlocked.text = "$attempts blocked"
        pillSaved.text = "$savedToday min saved"
        pillScore.text = "Score ${Prefs.focusScore(this)}"

        homeIgRow.text = "${Prefs.watchedToday(this, true)} watched · " +
            "${Prefs.blocksOnApp(this, today, true)} stopped"
        homeYtRow.text = "${Prefs.watchedToday(this, false)} watched · " +
            "${Prefs.blocksOnApp(this, today, false)} stopped"
        val countingOnly = !Prefs.isEnabled(this) && isServiceEnabled()
        homeOffNote.visibility = if (countingOnly) View.VISIBLE else View.GONE
        if (countingOnly) homeOffNote.text = "Still counting what you watch."
        val unattr = Prefs.todayUnattributed(this)
        homeUnattr.text = if (unattr > 0)
            "+$unattr blocked before the per-app update — app not recorded." else ""
        homeUnattr.visibility = if (unattr > 0) View.VISIBLE else View.GONE

        syncProtection(homeSwitch, homeProtTitle, homeProtSub)
    }

    // ================= STATS =================

    private fun buildStats(): View {
        val col = tabColumn()
        col.addView(text("This week", 24f, Ui.TEXT, bold = true))
        statsSentence = text("", 13f, Ui.MUTED).apply {
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(0f, 1.45f)
        }
        col.addView(statsSentence)

        val chartCard = glassCard()
        chartCard.addView(text("ATTEMPTS PER DAY", 11f, Ui.FAINT))
        bars = BarsView(this)
        chartCard.addView(bars, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(96)).apply { topMargin = dp(12) })
        barValues = (0..6).map {
            TextView(this).apply {
                textSize = 11f; setTextColor(Ui.MUTED); gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        }
        chartCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
            barValues.forEach { addView(it) }
        })
        barDays = (0..6).map {
            TextView(this).apply {
                textSize = 10f; setTextColor(Ui.FAINT); gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        }
        chartCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            barDays.forEach { addView(it) }
        })
        col.addView(chartCard)

        val totals = glassCard()
        statsIg = totalsRow(totals, "Instagram Reels")
        statsYt = totalsRow(totals, "YouTube Shorts")
        statsIgW = totalsRow(totals, "Instagram Reels watched (est.)")
        statsYtW = totalsRow(totals, "YouTube Shorts watched (est.)")
        statsSaved = totalsRow(totals, "Time given back (est.)")
        col.addView(totals)
        statsUnattr = text("", 11.5f, Ui.FAINT).apply { setPadding(dp(4), dp(2), 0, 0) }
        col.addView(statsUnattr)

        statsScore = plainRow(col, "Focus score today")
        statsLongest = plainRow(col, "Longest streak")
        statsTotal = plainRow(col, "Blocked all-time")
        return scroll(col)
    }

    private fun refreshStats() {
        val week = Prefs.last7Days(this)
        val total = week.sumOf { it.second }
        val best = week.maxByOrNull { it.second }
        statsSentence.text = if (total == 0) "No attempts recorded in the last 7 days yet."
            else "$total attempts stopped in the last 7 days. Best day: " +
                "${best!!.first.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)}, ${best.second}."

        bars.values = week.map { it.second }.toIntArray()
        week.forEachIndexed { i, (date, count) ->
            barValues[i].text = count.toString()
            barDays[i].text = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)
                .take(1).uppercase(Locale.US)
        }

        val today = LocalDate.now()
        var igWeek = 0; var ytWeek = 0
        for (i in 0..6) {
            val d = today.minusDays(i.toLong())
            igWeek += Prefs.blocksOnApp(this, d, true)
            ytWeek += Prefs.blocksOnApp(this, d, false)
        }
        statsIg.text = igWeek.toString()
        statsYt.text = ytWeek.toString()
        statsIgW.text = Prefs.watchedThisWeek(this, true).toString()
        statsYtW.text = Prefs.watchedThisWeek(this, false).toString()
        statsSaved.text = fmtMinutes(Prefs.minutesSaved(total))
        val unattr = total - igWeek - ytWeek
        statsUnattr.text = if (unattr > 0)
            "$unattr of these were blocked before the per-app update — app not recorded." else ""
        statsUnattr.visibility = if (unattr > 0) View.VISIBLE else View.GONE

        statsScore.text = Prefs.focusScore(this).toString()
        statsLongest.text = "${Prefs.longestStreak(this)} days"
        statsTotal.text = Prefs.totalAllTime(this).toString()
    }

    // ================= FOCUS =================

    private fun buildFocus(): View {
        val col = tabColumn()
        col.addView(text("Focus", 24f, Ui.TEXT, bold = true))
        col.addView(text("Your deep-work timer. The apps you pick below close completely while it runs.", 13f, Ui.MUTED).apply {
            setPadding(0, dp(6), 0, 0)
        })

        // v3.1: timer ring ≈ 62% of screen width (clamp 200–280dp).
        val focusRingDp = (screenWDp() * 0.62f).coerceIn(200f, 280f).toInt()
        val ringBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(focusRingDp + 16)).apply { topMargin = dp(16) }
        }
        focusRing = RingView(this, Ui.FGREEN)
        ringBox.addView(focusRing, FrameLayout.LayoutParams(dp(focusRingDp), dp(focusRingDp), Gravity.CENTER))
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        focusTime = text("25:00", 46f, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER }
        center.addView(focusTime)
        center.addView(text("DEEP WORK", 10f, Ui.FAINT).apply { gravity = Gravity.CENTER })
        ringBox.addView(center, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        col.addView(ringBox)

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        for (d in listOf(15, 25, 50, 90)) {
            val chip = TextView(this).apply {
                text = "$d min"
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                Ui.pressable(this)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener {
                    if (!Prefs.focusActive(this@MainActivity)) {
                        Prefs.setFocusDuration(this@MainActivity, d)
                        focusNote = null
                        updateFocusUI()
                    }
                }
            }
            chipViews[d] = chip
            chips.addView(chip)
        }
        col.addView(chips)

        // v4.1 (Step 7b): while a session runs, the chosen apps are closed
        // entirely by the service — these switches only choose which ones.
        focusBlockIgSw = focusBlockRow(col, "Block Instagram during focus",
            Prefs.focusBlockIg(this)) { Prefs.setFocusBlockIg(this@MainActivity, it) }
        focusBlockYtSw = focusBlockRow(col, "Block YouTube during focus",
            Prefs.focusBlockYt(this)) { Prefs.setFocusBlockYt(this@MainActivity, it) }

        focusBtn = Button(this).apply {
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
            setOnClickListener {
                if (Prefs.focusActive(this@MainActivity)) {
                    Prefs.cancelFocus(this@MainActivity)
                    focusNote = "Session ended early — not counted."
                    focusCompleteMsg = false
                } else {
                    Prefs.startFocus(this@MainActivity, Prefs.focusDuration(this@MainActivity))
                    focusNote = null
                    focusCompleteMsg = false
                }
                updateFocusUI()
            }
        }
        col.addView(focusBtn)
        focusStatus = text("", 13f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        col.addView(focusStatus)

        plainRow(col, "During focus", "Picked apps stay closed")
        focusWeek = plainRow(col, "Sessions this week")
        return scroll(col)
    }

    /** v4.1: one "Block <app> during focus" row — label left, switch right. */
    private fun focusBlockRow(
        parent: LinearLayout, label: String, initial: Boolean, onChange: (Boolean) -> Unit
    ): Switch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(10), dp(2), 0)
        }
        row.addView(text(label, 13.5f, Ui.TEXT).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = Switch(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        row.addView(sw)
        parent.addView(row)
        return sw
    }

    private fun updateFocusUI() {
        val now = System.currentTimeMillis()
        val end = Prefs.focusEndMs(this)
        val total = Prefs.focusTotalMs(this)
        val running = end > now
        val dur = Prefs.focusDuration(this)

        // v4.1: keep the app-blocking toggles honest with prefs (they only
        // change here, but re-syncing costs nothing and can't loop — the
        // listener just writes the same value back).
        if (::focusBlockIgSw.isInitialized) {
            val v = Prefs.focusBlockIg(this)
            if (focusBlockIgSw.isChecked != v) focusBlockIgSw.isChecked = v
            val w = Prefs.focusBlockYt(this)
            if (focusBlockYtSw.isChecked != w) focusBlockYtSw.isChecked = w
        }

        for ((d, chip) in chipViews) {
            val selected = d == dur
            chip.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(if (selected) Color.WHITE else Color.parseColor("#1C1C1E"))
            }
            chip.setTextColor(if (selected) Color.BLACK else Ui.MUTED)
            chip.alpha = if (running) 0.4f else 1f
        }

        if (running && total > 0) {
            val remain = end - now
            focusTime.text = fmtClock(remain)
            focusRing.fraction = remain.toFloat() / total
            focusBtn.text = "End session"
            focusStatus.text = "Ends at ${SimpleDateFormat("h:mm a", Locale.US).format(Date(end))}"
        } else {
            focusTime.text = "%d:00".format(dur)
            focusRing.fraction = 1f
            focusBtn.text = "Start focus"
            focusStatus.text = when {
                focusCompleteMsg -> "Session complete — nice work."
                focusNote != null -> focusNote
                else -> ""
            }
        }
        val (n, mins) = Prefs.focusThisWeek(this)
        focusWeek.text = "$n · ${fmtMinutes(mins)}"
    }

    // ================= SHIELD =================

    private fun buildShield(): View {
        val col = tabColumn()
        col.addView(text("Shield", 24f, Ui.TEXT, bold = true))

        col.addView(protectionCard(isHome = false))

        // --- Daily allowance (v3.2): how many may play per day while
        // protection is on; 0 = the original block-everything behaviour. ---
        val allowCard = glassCard()
        allowCard.addView(text("Daily allowance", 16f, Ui.TEXT, bold = true))
        allowCard.addView(text(
            "Reels/Shorts that may play each day while protection is on — after that, blocking starts. " +
                "Counting is an estimate from what's on screen. Changes save instantly.",
            12f, Ui.MUTED).apply {
            setPadding(0, dp(2), 0, dp(2))
            setLineSpacing(0f, 1.35f)
        })
        val (igNum, igState) = allowanceSection(allowCard, "Instagram Reels", true, allowChipsIg)
        allowIgNum = igNum; allowIgState = igState
        val (ytNum, ytState) = allowanceSection(allowCard, "YouTube Shorts", false, allowChipsYt)
        allowYtNum = ytNum; allowYtState = ytState
        col.addView(allowCard)

        // --- App time limits (v4.1, Step 4b): a daily minute budget for
        // the whole app, not just its viewer. 0 = no limit. Usage is the
        // foreground time the service observed (see README for the
        // chunking honesty note). ---
        val appLimitCard = glassCard()
        appLimitCard.addView(text("App time limits", 16f, Ui.TEXT, bold = true))
        appLimitCard.addView(text(
            "A daily budget for the whole app — feed, search, everything. When it's used up " +
                "and protection is on, the app closes until midnight. Time counts only while " +
                "Accessibility is on. Changes save instantly; raising a limit is loosening, " +
                "so the settings lock gates it.",
            12f, Ui.MUTED).apply {
            setPadding(0, dp(2), 0, dp(2))
            setLineSpacing(0f, 1.35f)
        })
        val (alIgNum, alIgState) = appLimitSection(appLimitCard, "Instagram", true, appLimitChipsIg)
        appLimitIgNum = alIgNum; appLimitIgState = alIgState
        val (alYtNum, alYtState) = appLimitSection(appLimitCard, "YouTube", false, appLimitChipsYt)
        appLimitYtNum = alYtNum; appLimitYtState = alYtState
        col.addView(appLimitCard)
        plainRow(col, "Block delay", "Instant")

        // --- Settings lock (v3.3): loosening gated, tightening instant ---
        val lockCard = glassCard()
        lockCard.addView(text("Settings lock", 16f, Ui.TEXT, bold = true))
        lockState = text("", 12.5f, Ui.MUTED).apply {
            setPadding(0, dp(2), 0, 0)
            setLineSpacing(0f, 1.35f)
        }
        lockCard.addView(lockState)
        lockCard.addView(text(
            "This slows you down inside the app. It can't stop uninstalling the app " +
                "or turning off Accessibility in Android settings.",
            11.5f, Ui.FAINT).apply {
            setPadding(0, dp(4), 0, dp(4))
            setLineSpacing(0f, 1.35f)
        })
        lockBtnPrimary = Button(this).apply {
            text = "Set PIN & turn on lock"
            Ui.pressable(this)
            setOnClickListener {
                if (Prefs.lockActive(this@MainActivity)) LockGateActivity.launchChangePin(this@MainActivity)
                else LockGateActivity.launchSetPin(this@MainActivity)
            }
        }
        lockCard.addView(lockBtnPrimary)
        lockBtnSecondary = Button(this).apply {
            text = "Turn off lock"
            Ui.pressable(this)
            setOnClickListener {
                if (Prefs.lockActive(this@MainActivity)) LockGateActivity.launchLockOff(this@MainActivity)
            }
        }
        lockCard.addView(lockBtnSecondary)
        col.addView(lockCard)

        // --- Realme survival checklist (Step 2, behaviour unchanged) ---
        val checkCard = glassCard()
        checkCard.addView(text("Keep it alive on Realme", 16f, Ui.TEXT, bold = true))
        checkCard.addView(text("Realme kills background apps — these keep the blocker running.", 12f, Ui.MUTED).apply {
            setPadding(0, dp(2), 0, dp(6))
        })
        a11yIcon = icon(); a11yState = stateLine()
        checkCard.addView(checkRow(a11yIcon, "Accessibility", a11yState, Button(this).apply {
            text = "Open"
            Ui.pressable(this)
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }))
        battIcon = icon(); battState = stateLine()
        checkCard.addView(checkRow(battIcon, "Battery unrestricted", battState, Button(this).apply {
            text = "Open"
            Ui.pressable(this)
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e: ActivityNotFoundException) {
                    openAppDetails()
                }
            }
        }))
        autoIcon = icon()
        autoCheck = CheckBox(this).apply {
            isChecked = Prefs.autostartDone(this@MainActivity)
            buttonTintList = ColorStateList.valueOf(Ui.FGREEN)
            setOnCheckedChangeListener { _, done -> Prefs.setAutostartDone(this@MainActivity, done); refreshShield() }
        }
        checkCard.addView(checkRow(autoIcon, "Autostart ON",
            text("Settings → Apps → Veto → allow Auto launch. Can't be checked automatically — tick when done.", 12f, Ui.MUTED),
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(Button(this@MainActivity).apply {
                    text = "Open"
                    Ui.pressable(this)
                    setOnClickListener { openAutostartSettings() }
                })
                addView(autoCheck)
            }))
        recentsIcon = icon()
        recentsCheck = CheckBox(this).apply {
            isChecked = Prefs.recentsLockDone(this@MainActivity)
            buttonTintList = ColorStateList.valueOf(Ui.FGREEN)
            setOnCheckedChangeListener { _, done -> Prefs.setRecentsLockDone(this@MainActivity, done); refreshShield() }
        }
        checkCard.addView(checkRow(recentsIcon, "Locked in Recents",
            text("Recents → Veto card → tap ⋮ / long-press → Lock. Tick when done.", 12f, Ui.MUTED),
            recentsCheck))
        col.addView(checkCard)

        // --- Debug log (Step 1, kept) ---
        val logCard = glassCard()
        logCard.addView(text("Debug log", 16f, Ui.TEXT, bold = true))
        logCard.addView(text("If blocking misses, screenshot this and send it back.", 12f, Ui.MUTED))
        logView = TextView(this).apply { textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(Ui.MUTED) }
        logCard.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240))
            addView(logView)
        })
        logCard.addView(Button(this).apply {
            text = "Clear log"
            Ui.pressable(this)
            setOnClickListener { Prefs.clearLog(this@MainActivity); refreshLog() }
        })
        col.addView(logCard)

        // v4.0: replay the first-run intro (resets the onboarded flag).
        col.addView(Button(this).apply {
            text = "Replay intro"
            Ui.pressable(this)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
            setOnClickListener {
                Prefs.setOnboarded(this@MainActivity, false)
                startActivity(Intent(this@MainActivity, MainActivity::class.java))
                finish()
            }
        })

        col.addView(text("v${versionName()} · no internet, no account", 11f, Ui.FAINT).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
        })
        return scroll(col)
    }

    private fun refreshShield() {
        syncProtection(shieldSwitch, shieldProtTitle, shieldProtSub)
        refreshAllowance()
        refreshAppLimits()
        refreshLockCard()
        val a11y = isServiceEnabled()
        setIcon(a11yIcon, a11y)
        a11yState.text = if (a11y) "Enabled" else "Not enabled — tap Open"
        a11yState.setTextColor(if (a11y) Ui.FGREEN else Ui.RED)

        val battOk = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(packageName)
        setIcon(battIcon, battOk)
        battState.text = if (battOk) "Unrestricted" else "Optimised — open the list, find Veto, choose Don't optimize"
        battState.setTextColor(if (battOk) Ui.FGREEN else Ui.RED)

        setIcon(autoIcon, Prefs.autostartDone(this))
        setIcon(recentsIcon, Prefs.recentsLockDone(this))
        refreshLog()
    }

    private fun refreshLog() {
        val log = Prefs.logText(this)
        logView.text = log.ifEmpty { "(empty — open Instagram / YouTube, then come back here)" }
    }

    // ================= CIRCLE (v4.0) =================
    // The private supportive board. NO server, NO internet permission:
    // your stats travel as a text code (Prefs.encodeMyCode) that you copy
    // or share; friends' codes are pasted in and stored as snapshots.
    // Ranked by focus score, then time saved — supportive framing only.

    private fun buildCircle(): View {
        val col = tabColumn()
        col.addView(text("Circle", 24f, Ui.TEXT, bold = true))
        col.addView(text(
            "A private board for you and your people — ranked by focus score and time won back. " +
                "No server, no account: you swap snapshot codes, and the board updates.",
            12.5f, Ui.MUTED).apply {
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(0f, 1.4f)
        })

        // --- Your code ---
        val youCard = glassCard()
        youCard.addView(text("Your code", 16f, Ui.TEXT, bold = true))
        youCard.addView(text("Display name", 12f, Ui.FAINT).apply { setPadding(0, dp(10), 0, dp(4)) })
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        circleNameInput = EditText(this).apply {
            setSingleLine(true)
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.FAINT)
            hint = "Your name"
            textSize = 14f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = fieldBg()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        nameRow.addView(circleNameInput)
        nameRow.addView(Button(this).apply {
            text = "Save"
            Ui.pressable(this)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            setOnClickListener {
                Prefs.setCircleName(this@MainActivity, circleNameInput.text.toString())
                refreshCircle()
            }
        })
        youCard.addView(nameRow)
        youCard.addView(text("My code — send this to your circle:", 12f, Ui.FAINT).apply {
            setPadding(0, dp(12), 0, dp(4))
        })
        circleCodeView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Ui.MUTED)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = fieldBg()
        }
        youCard.addView(circleCodeView)
        val codeBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        codeBtns.addView(Button(this).apply {
            text = "Copy my code"
            Ui.pressable(this)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(
                    "Veto code", Prefs.encodeMyCode(this@MainActivity)))
                circleAddStatus.text = "Your code is copied — send it to your circle."
                circleAddStatus.setTextColor(Ui.FGREEN)
            }
        })
        codeBtns.addView(Button(this).apply {
            text = "Share code"
            Ui.pressable(this)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(8) }
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, Prefs.encodeMyCode(this@MainActivity))
                }
                startActivity(Intent.createChooser(send, "Share your Veto code"))
            }
        })
        youCard.addView(codeBtns)
        col.addView(youCard)

        // --- Add a friend ---
        val addCard = glassCard()
        addCard.addView(text("Add a friend", 16f, Ui.TEXT, bold = true))
        circleAddInput = EditText(this).apply {
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.FAINT)
            hint = "Paste a friend's code (starts with RB1.)"
            textSize = 13f
            minLines = 2
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = fieldBg()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        addCard.addView(circleAddInput)
        addCard.addView(Button(this).apply {
            text = "Add to board"
            Ui.pressable(this)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { addFriendFromInput() }
        })
        circleAddStatus = text("", 12f, Ui.MUTED).apply { setPadding(0, dp(6), 0, 0) }
        addCard.addView(circleAddStatus)
        col.addView(addCard)

        // --- The board ---
        col.addView(text("This week's board", 16f, Ui.TEXT, bold = true).apply {
            setPadding(dp(2), dp(22), 0, 0)
        })
        val boardCard = glassCard()
        (boardCard.layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
        circleBoard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        boardCard.addView(circleBoard)
        col.addView(boardCard)
        circleEmpty = text("Just you so far — share your code with your circle.", 12.5f, Ui.MUTED).apply {
            setPadding(dp(4), dp(12), 0, 0)
        }
        col.addView(circleEmpty)
        col.addView(text(
            "Codes are snapshots from the day they're made — swap fresh codes to update the board.",
            11.5f, Ui.FAINT).apply {
            setPadding(dp(4), dp(6), 0, 0)
            setLineSpacing(0f, 1.4f)
        })
        return scroll(col)
    }

    private fun refreshCircle() {
        if (!::circleBoard.isInitialized) return
        if (!circleNameInput.isFocused) {
            val n = Prefs.circleName(this)
            if (circleNameInput.text.toString() != n) circleNameInput.setText(n)
        }
        circleCodeView.text = Prefs.encodeMyCode(this)

        circleBoard.removeAllViews()
        val me = Prefs.myCircleMember(this)
        val members = Prefs.circleMembers(this)
        // Positive ranking only: focus score first, time saved breaks ties.
        val rows = (members.map { it to false } + listOf(me to true))
            .sortedWith(compareByDescending<Pair<CircleMember, Boolean>> { it.first.score }
                .thenByDescending { it.first.savedMin })
        rows.forEachIndexed { i, (m, self) ->
            circleRow(i + 1, m, self)
            if (i < rows.lastIndex) circleBoard.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#1F1F22"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })
        }
        circleEmpty.visibility = if (members.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun circleRow(rank: Int, m: CircleMember, self: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(9), 0, dp(9))
        }
        val line1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line1.addView(text("$rank", 14f, Ui.FAINT, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(dp(26), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        line1.addView(text(if (self) "${m.name} (you)" else m.name, 14.5f,
            if (self) Ui.PINK else Ui.TEXT, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        line1.addView(text("Score ${m.score}", 13.5f, Ui.TEXT, bold = true))
        if (!self) {
            line1.addView(text("✕", 14f, Ui.FAINT).apply {
                setPadding(dp(12), 0, 0, 0)
                Ui.pressable(this)
                setOnClickListener { confirmRemoveMember(m.name) }
            })
        }
        row.addView(line1)
        // Stale snapshots must look stale: date shown always, red + tagged
        // once the snapshot is more than 7 days old.
        val stale = m.genDate.isBefore(LocalDate.now().minusDays(7))
        val upd = "updated ${fmtDay(m.genDate)}" + if (stale) " · stale" else ""
        row.addView(text(
            "${fmtMinutes(m.savedMin)} saved · ${m.streak}d streak · ${m.stopped} stopped · " +
                "${m.watched} watched · $upd",
            11.5f, if (stale) Ui.RED else Ui.MUTED).apply {
            setPadding(dp(26), dp(3), 0, 0)
        })
        circleBoard.addView(row)
    }

    private fun addFriendFromInput() {
        val raw = circleAddInput.text.toString()
        if (raw.contains(Prefs.encodeMyCode(this))) {
            circleAddStatus.text = "That's your own code — send it to your circle instead."
            circleAddStatus.setTextColor(Ui.RED)
            return
        }
        val m = Prefs.decodeCode(raw)
        if (m == null) {
            circleAddStatus.text =
                "That doesn't look like a Veto code — it should start with RB1."
            circleAddStatus.setTextColor(Ui.RED)
            return
        }
        // QA fix (v4.1.1): an OLD code of your own (stats changed since, so
        // the exact-string check above missed it) carries your display name —
        // adding it would put a stale snapshot of yourself on the board as
        // if you were a friend. Names are the board's identity key, so a
        // code with your name on it is treated as yours.
        if (m.name.equals(Prefs.circleName(this), ignoreCase = true)) {
            circleAddStatus.text = "That's your own code — send it to your circle instead."
            circleAddStatus.setTextColor(Ui.RED)
            return
        }
        // v4.1.2: the board keeps at most 30 members and adding a new one
        // silently dropped the oldest. Warn first, naming who would go —
        // cancelling leaves the list (and the pasted code) untouched.
        // Replacing an existing member's snapshot never triggers this.
        val evicted = Prefs.circleEvictionCandidate(this, m.name)
        if (evicted != null) {
            AlertDialog.Builder(this)
                .setTitle("Board is full")
                .setMessage("Adding ${m.name} will remove $evicted — your board keeps 30.")
                .setPositiveButton("Add anyway") { _, _ -> completeCircleAdd(m) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        completeCircleAdd(m)
    }

    private fun completeCircleAdd(m: CircleMember) {
        Prefs.addCircleMember(this, m)
        circleAddInput.text.clear()
        circleAddStatus.text = "${m.name} is on the board."
        circleAddStatus.setTextColor(Ui.FGREEN)
        refreshCircle()
    }

    private fun confirmRemoveMember(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Remove $name?")
            .setMessage("Their row leaves your board. You can add them again with a fresh code.")
            .setPositiveButton("Remove") { _, _ ->
                Prefs.removeCircleMember(this, name)
                refreshCircle()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fieldBg() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(Color.parseColor("#1C1C1E"))
        setStroke(dp(1), Color.parseColor("#2C2C2E"))
    }

    private fun fmtDay(d: LocalDate): String =
        SimpleDateFormat("d MMM", Locale.US).format(
            Date.from(d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()))

    // ================= ONBOARDING (v4.0, first run only) =================
    // Three swipe-less pages shown instead of the tabs until finished:
    // what it does → turn on Accessibility → Realme survival summary.
    // "Replay intro" in Shield clears the flag and lands back here.

    private fun buildOnboarding() {
        inOnboarding = true
        obIndex = 0
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }
        Ui.applySystemBarInsets(root)
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(frame)

        // Page 1 — what it does
        val p1 = obPage()
        p1.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(RingView(this@MainActivity, Ui.PINK).apply { fraction = 0.8f },
                LinearLayout.LayoutParams(dp(84), dp(84)))
        })
        p1.addView(text("Veto", 26f, Ui.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        })
        p1.addView(obBody("Reels and Shorts get stopped the moment they open. " +
            "Your DMs, Stories, feed and the rest of Instagram and YouTube stay exactly as they are."))

        // Page 2 — turn on Accessibility
        val p2 = obPage()
        p2.addView(obTitle("One switch does the work"))
        p2.addView(obBody("Android only lets an app see which screen is open through " +
            "Accessibility — that's the one permission Veto needs. " +
            "No internet, no account, nothing leaves this phone."))
        p2.addView(Button(this).apply {
            text = "Open Accessibility settings"
            Ui.pressable(this)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) }
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        obA11yStatus = text("", 13f, Ui.MUTED).apply { setPadding(0, dp(12), 0, 0) }
        p2.addView(obA11yStatus)
        p2.addView(text("If the Veto toggle there is greyed out: Settings → Apps → " +
            "Veto → ⋮ menu → Allow restricted settings, then come back and try again.",
            11.5f, Ui.FAINT).apply {
            setPadding(0, dp(10), 0, 0)
            setLineSpacing(0f, 1.4f)
        })

        // Page 3 — Realme survival summary
        val p3 = obPage()
        p3.addView(obTitle("Keep it alive"))
        p3.addView(obBody("Realme phones kill background apps. Three quick things keep the " +
            "blocker running — the Shield tab has the full checklist with shortcuts:\n\n" +
            "•  Autostart ON\n•  Battery: Unrestricted\n•  Lock Veto in Recents"))
        p3.addView(obBody("You can finish them right after you start."))

        obPages = listOf(p1, p2, p3)
        obPages.forEach { frame.addView(it) }

        obDots = text("", 13f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(10))
        }
        root.addView(obDots)
        obNext = Button(this).apply {
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
            ).apply { leftMargin = dp(20); rightMargin = dp(20); bottomMargin = dp(20) }
            setOnClickListener {
                if (obIndex < 2) showObPage(obIndex + 1) else {
                    Prefs.setOnboarded(this@MainActivity, true)
                    buildMainUI()
                }
            }
        }
        root.addView(obNext)
        setContentView(root)
        showObPage(0)
        refreshObA11y()
    }

    private fun obPage() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(28), dp(40), dp(28), dp(16))
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        visibility = View.GONE
    }

    private fun obTitle(s: String) = text(s, 21f, Ui.TEXT, bold = true)

    private fun obBody(s: String) = text(s, 14f, Ui.MUTED).apply {
        setPadding(0, dp(12), 0, 0)
        setLineSpacing(0f, 1.5f)
    }

    private fun showObPage(i: Int) {
        obIndex = i
        obPages.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        obDots.text = (0..2).joinToString("   ") { if (it == obIndex) "●" else "○" }
        obNext.text = if (obIndex == 2) "Start" else "Next"
        if (obIndex == 1) refreshObA11y()
    }

    private fun refreshObA11y() {
        if (!::obA11yStatus.isInitialized) return
        val on = isServiceEnabled()
        obA11yStatus.text = if (on) "✓ Accessibility is on — you're set."
            else "Accessibility is not on yet."
        obA11yStatus.setTextColor(if (on) Ui.FGREEN else Ui.MUTED)
    }

    // ================= shared pieces =================

    /** Glass card with the protection title/sub + the main Blocking switch. */
    private fun protectionCard(isHome: Boolean): LinearLayout {
        val card = glassCard()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = text("", 17f, Ui.TEXT, bold = true)
        val sub = text("", 12.5f, Ui.MUTED)
        texts.addView(title); texts.addView(sub)
        row.addView(texts)
        val sw = Switch(this).apply {
            scaleX = 1.3f; scaleY = 1.3f
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Ui.FGREEN, Color.parseColor("#7F1D1D"))
            )
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, Color.parseColor("#FECACA"))
            )
            setOnCheckedChangeListener { _, on ->
                if (!syncingSwitch) {
                    if (!on && Prefs.lockActive(this@MainActivity)) {
                        // v3.3: turning protection OFF is loosening — with
                        // the lock on it goes through the gate. The switch
                        // snaps back on; the pref changes only if the gate
                        // completes (LockGateActivity applies it, and this
                        // activity re-syncs from prefs on resume).
                        syncingSwitch = true
                        isChecked = true
                        syncingSwitch = false
                        LockGateActivity.launchTurnOff(this@MainActivity)
                    } else {
                        Prefs.setEnabled(this@MainActivity, on)
                        refreshCurrent()
                        if (currentTab != TAB_SHIELD) refreshShieldSwitchOnly()
                    }
                }
            }
        }
        row.addView(sw)
        card.addView(row)
        if (isHome) { homeSwitch = sw; homeProtTitle = title; homeProtSub = sub }
        else { shieldSwitch = sw; shieldProtTitle = title; shieldProtSub = sub }
        return card
    }

    /** Push the current pref state into one protection card without looping listeners. */
    private fun syncProtection(sw: Switch, title: TextView, sub: TextView) {
        val on = Prefs.isEnabled(this)
        val a11y = isServiceEnabled()
        syncingSwitch = true
        sw.isChecked = on
        syncingSwitch = false
        when {
            !a11y -> {
                title.text = "Setup needed"
                title.setTextColor(Color.parseColor("#FDE68A"))
                sub.text = "Accessibility is OFF — blocking can't work. Fix it in the Shield tab checklist."
                sub.setTextColor(Color.parseColor("#FDE68A"))
            }
            on -> {
                title.text = "Protection on"
                title.setTextColor(Ui.TEXT)
                sub.text = "Blocking Reels & Shorts viewers"
                sub.setTextColor(Ui.MUTED)
            }
            else -> {
                title.text = "Protection OFF"
                title.setTextColor(Ui.RED)
                sub.text = "Reels & Shorts are NOT being blocked."
                sub.setTextColor(Ui.RED)
            }
        }
    }

    private fun refreshShieldSwitchOnly() {
        if (::shieldSwitch.isInitialized) syncProtection(shieldSwitch, shieldProtTitle, shieldProtSub)
    }

    private fun isServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun openAppDetails() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    /** Realme/ColorOS startup-app list if present, else our App Info page. */
    private fun openAutostartSettings() {
        val candidates = listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
        )
        for (cn in candidates) {
            try {
                startActivity(Intent().setComponent(cn)); return
            } catch (e: Exception) { /* try next */ }
        }
        openAppDetails()
    }

    private fun versionName(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    // --- small view helpers (ponytail: no layouts, no libs) ---

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun tabColumn() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        // v3.1: one rhythm everywhere — 16dp side margins, 16dp top/bottom.
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    private fun scroll(col: LinearLayout) = ScrollView(this).apply {
        setBackgroundColor(Ui.BG)
        addView(col)
        visibility = View.GONE
    }

    private fun glassCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = Ui.glass(this@MainActivity)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) }
    }

    private fun pill() = TextView(this).apply {
        textSize = 11.5f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Ui.TEXT)
        setPadding(dp(11), dp(7), dp(11), dp(7))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
            setColor(Color.parseColor("#1C1C1E"))
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(7) }
    }

    /** Legend line next to the rings; returns the value TextView. */
    private fun legendLine(parent: LinearLayout, color: Int): TextView {
        val v = text("", 13f, color, bold = true).apply { setPadding(0, dp(5), 0, dp(5)) }
        parent.addView(v)
        return v
    }

    /** Home per-app row: name left, count view returned on the right. */
    private fun appRow(parent: LinearLayout, name: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(11), dp(2), 0)
        }
        row.addView(text(name, 14f, Ui.TEXT).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val v = text("", 13f, Ui.MUTED)
        row.addView(v)
        parent.addView(row)
        return v
    }

    /** Stats card row: name left, bold value right (value view returned). */
    private fun totalsRow(parent: LinearLayout, name: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(7), 0, dp(7))
        }
        row.addView(text(name, 14f, Ui.TEXT).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val v = text("", 14f, Ui.TEXT, bold = true)
        row.addView(v)
        parent.addView(row)
        return v
    }

    /** Plain label/value row under the cards; returns the value view. */
    private fun plainRow(parent: LinearLayout, name: String, value: String = ""): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(12), dp(2), 0)
        }
        row.addView(text(name, 13.5f, Ui.TEXT).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val v = text(value, 12.5f, Ui.MUTED)
        row.addView(v)
        parent.addView(row)
        return v
    }

    // --- Daily allowance controls (v3.2) ---

    /** One app's allowance block: name + −/number/+ stepper row, a live
     *  watched-state line, and preset chips (0/5/10/20/50). Returns the
     *  number and state views so refreshAllowance() can update them. */
    private fun allowanceSection(
        parent: LinearLayout, name: String, ig: Boolean, chips: HashMap<Int, TextView>
    ): Pair<TextView, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(text(name, 14.5f, Ui.TEXT, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val num = text("0", 17f, Ui.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(stepperBtn("−") {
            applyAllowance(ig, Prefs.allowance(this@MainActivity, ig) - 1)
        })
        row.addView(num)
        row.addView(stepperBtn("+") {
            applyAllowance(ig, Prefs.allowance(this@MainActivity, ig) + 1)
        })
        parent.addView(row)

        val state = text("", 12f, Ui.MUTED).apply { setPadding(0, dp(4), 0, 0) }
        parent.addView(state)

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(2))
        }
        for (v in listOf(0, 5, 10, 20, 50)) {
            val chip = TextView(this).apply {
                text = v.toString()
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                Ui.pressable(this)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(7) }
                setOnClickListener {
                    applyAllowance(ig, v)
                }
            }
            chips[v] = chip
            chipRow.addView(chip)
        }
        parent.addView(chipRow)
        return num to state
    }

    private fun stepperBtn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 16f
        Ui.pressable(this)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(40))
        setOnClickListener { onClick() }
    }

    /** v3.3: raising an allowance is loosening — with the lock on it goes
     *  through the gate with the target as the pending value, and the UI
     *  keeps showing the old value until the gate completes (onResume →
     *  refreshShield repaints either way). Lowering or re-picking the same
     *  value is tightening and applies instantly. */
    private fun applyAllowance(ig: Boolean, target: Int) {
        if (target > Prefs.allowance(this, ig) && Prefs.lockActive(this)) {
            LockGateActivity.launchAllowance(this, ig, target)
        } else {
            Prefs.setAllowance(this, ig, target)
            refreshAllowance()
        }
    }

    // --- App time limit controls (v4.1, Step 4b) ---

    /** One app's time-limit block: name + −/minutes/+ stepper (15-minute
     *  steps), a live "Used today" line, and preset chips (0/30/60/120).
     *  Returns the number and state views for refreshAppLimits(). */
    private fun appLimitSection(
        parent: LinearLayout, name: String, ig: Boolean, chips: HashMap<Int, TextView>
    ): Pair<TextView, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        row.addView(text(name, 14.5f, Ui.TEXT, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val num = text("0", 17f, Ui.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        row.addView(stepperBtn("−") {
            applyAppLimit(ig, Prefs.appLimitMin(this@MainActivity, ig) - 15)
        })
        row.addView(num)
        row.addView(stepperBtn("+") {
            applyAppLimit(ig, Prefs.appLimitMin(this@MainActivity, ig) + 15)
        })
        parent.addView(row)

        val state = text("", 12f, Ui.MUTED).apply { setPadding(0, dp(4), 0, 0) }
        parent.addView(state)

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(2))
        }
        for (v in listOf(0, 30, 60, 120)) {
            val chip = TextView(this).apply {
                text = if (v == 0) "Off" else v.toString()
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                Ui.pressable(this)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(7) }
                setOnClickListener { applyAppLimit(ig, v) }
            }
            chips[v] = chip
            chipRow.addView(chip)
        }
        parent.addView(chipRow)
        return num to state
    }

    /** Same lock rule as allowances: raising a limit is loosening and is
     *  gated when the lock is on; lowering applies instantly. */
    private fun applyAppLimit(ig: Boolean, target: Int) {
        val clamped = target.coerceIn(0, 1440)
        if (clamped > Prefs.appLimitMin(this, ig) && Prefs.lockActive(this)) {
            LockGateActivity.launchAppLimit(this, ig, clamped)
        } else {
            Prefs.setAppLimitMin(this, ig, clamped)
            refreshAppLimits()
        }
    }

    private fun refreshAppLimits() {
        if (!::appLimitIgNum.isInitialized) return
        appLimitIgNum.text = "${Prefs.appLimitMin(this, true)} min"
        appLimitYtNum.text = "${Prefs.appLimitMin(this, false)} min"
        appLimitIgState.text = appLimitState(true)
        appLimitYtState.text = appLimitState(false)
        styleAllowChips(appLimitChipsIg, Prefs.appLimitMin(this, true))
        styleAllowChips(appLimitChipsYt, Prefs.appLimitMin(this, false))
    }

    private fun appLimitState(ig: Boolean): String {
        val usedMin = (Prefs.usageTodayMs(this, ig) / 60_000L).toInt()
        val n = Prefs.appLimitMin(this, ig)
        return when {
            !Prefs.isEnabled(this) ->
                "Used today: $usedMin min · protection off — limits not enforced"
            n > 0 -> "Used today: $usedMin of $n min"
            else -> "Used today: $usedMin min · no limit set"
        }
    }

    private fun refreshLockCard() {
        if (!::lockState.isInitialized) return
        if (Prefs.lockActive(this)) {
            lockState.text = "On — turning protection off, raising an allowance or an app " +
                "time limit, changing the PIN or turning this lock off takes your PIN, a " +
                "60-second wait, and a typed phrase. Tightening is always instant."
            lockBtnPrimary.text = "Change PIN"
            lockBtnSecondary.text = "Turn off lock"
            lockBtnSecondary.visibility = View.VISIBLE
        } else {
            lockState.text = "Off — every setting changes instantly right now."
            lockBtnPrimary.text = "Set PIN & turn on lock"
            lockBtnSecondary.visibility = View.GONE
        }
    }

    private fun refreshAllowance() {
        if (!::allowIgNum.isInitialized) return
        allowIgNum.text = Prefs.allowance(this, true).toString()
        allowYtNum.text = Prefs.allowance(this, false).toString()
        allowIgState.text = allowanceState(true)
        allowYtState.text = allowanceState(false)
        styleAllowChips(allowChipsIg, Prefs.allowance(this, true))
        styleAllowChips(allowChipsYt, Prefs.allowance(this, false))
    }

    private fun allowanceState(ig: Boolean): String {
        val watched = Prefs.watchedToday(this, ig)
        val n = Prefs.allowance(this, ig)
        return when {
            !Prefs.isEnabled(this) -> "Counting only (protection off) — watched today: $watched"
            n > 0 -> "Watched today: $watched of $n"
            else -> "Watched today: $watched · allowance 0 — every one is stopped"
        }
    }

    private fun styleAllowChips(chips: HashMap<Int, TextView>, selected: Int) {
        for ((v, chip) in chips) {
            val on = v == selected
            chip.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(if (on) Color.WHITE else Color.parseColor("#1C1C1E"))
            }
            chip.setTextColor(if (on) Color.BLACK else Ui.MUTED)
        }
    }

    private fun icon() = TextView(this).apply {
        textSize = 20f; typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(30), LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun setIcon(view: TextView, done: Boolean) {
        view.text = if (done) "✓" else "○"
        view.setTextColor(if (done) Ui.FGREEN else Ui.RED)
    }

    private fun stateLine() = text("", 12f, Ui.MUTED)

    private fun checkRow(icon: TextView, label: String, sub: TextView, control: View): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        row.addView(icon)
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(6), 0, dp(8), 0)
        }
        mid.addView(text(label, 15f, Ui.TEXT, bold = true))
        mid.addView(sub)
        row.addView(mid)
        row.addView(control)
        return row
    }

    private fun dateLine(): String =
        SimpleDateFormat("EEEE d MMM", Locale.US).format(Date())

    private fun isToday(ms: Long): Boolean {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return fmt.format(Date(ms)) == fmt.format(Date())
    }

    private fun fmtMinutes(mins: Int): String =
        if (mins < 60) "$mins min" else "${mins / 60} h ${mins % 60} min"

    private fun fmtClock(ms: Long): String {
        val secs = (ms + 999) / 1000
        return "%d:%02d".format(secs / 60, secs % 60)
    }

    companion object {
        private const val TAB_HOME = 0
        private const val TAB_STATS = 1
        private const val TAB_FOCUS = 2
        private const val TAB_SHIELD = 3
        private const val TAB_CIRCLE = 4
    }
}
