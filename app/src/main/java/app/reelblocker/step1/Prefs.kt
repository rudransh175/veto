package app.reelblocker.step1

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * All storage for the app (one SharedPreferences file, on-device only).
 *
 * v3.0 data model:
 *  - Legacy same-day counter (Step 1/2): keys "count"/"count_date". Still
 *    maintained so the old number never goes backwards.
 *  - Per-day per-app blocks: "d_yyyy-MM-dd_ig" / "d_yyyy-MM-dd_yt" (Int).
 *    History starts at the v3 install — earlier days simply read as 0.
 *  - "total_all": all-time blocks. Seeded once from the legacy counter
 *    (today's pre-v3 blocks) the first time v3 records a block.
 *  - "last_block_ms" / "last_block_app" ("ig"/"yt"): most recent block.
 *  - "broken_yyyy-MM-dd": user tapped "Open anyway" that day — the day no
 *    longer counts toward streaks.
 *  - "bypass_ig" / "bypass_yt": epoch ms until which the service must not
 *    block that package (the 60 s "Open anyway" grace window).
 *  - Focus: "focus_end_ms"/"focus_total_ms" describe the running session;
 *    completed sessions land in "fs_yyyy-MM-dd_n" / "fs_yyyy-MM-dd_min".
 *  - v3.3 settings lock: "lock_enabled", "pin_salt" / "pin_hash" (salted
 *    SHA-256, never the PIN itself), "lock_wrong_n" / "lock_wait_until"
 *    (the gate's wrong-PIN throttle).
 *  - v3.2 allowance: "allow_ig" / "allow_yt" (Int, default 0 = block all).
 *    Watched counts (an on-screen ESTIMATE, see README): "w_yyyy-MM-dd_ig" /
 *    "w_yyyy-MM-dd_yt". Watched is counted in both protection modes; blocks
 *    are never counted as watched.
 *  - v4.0 Circle + onboarding: "circle_name", "circle_members" (the
 *    imported friends, delimited — see the Circle section below) and
 *    "onboarded". The Circle has NO server: snapshots travel as text
 *    codes ("RB1." + Base64), generated and parsed here.
 *  - v4.1 whole-app time limits (Step 4b): "app_limit_ig_min" /
 *    "app_limit_yt_min" (Int minutes per day, 0 = no limit) and the
 *    foreground usage the service observed, "usage_ms_yyyy-MM-dd_ig" /
 *    "usage_ms_yyyy-MM-dd_yt" (Long ms — accumulated in ≤60 s chunks,
 *    see the service).
 *  - v4.1 focus app blocking (Step 7b): "focus_block_ig" /
 *    "focus_block_yt" (Boolean, default true) — while a focus session
 *    runs, the chosen apps are closed entirely, not just their viewers.
 *
 * Derived numbers (documented in README-INSTALL.md):
 *  - Time saved (est.) = blocks × 3 minutes.
 *  - Focus score = 100 − max(0, today's attempts − average attempts over the
 *    previous 7 days), clamped 0–100.
 *  - Streak = consecutive days with ≥1 block and no "Open anyway", counting
 *    back from today (or from yesterday if today doesn't qualify yet).
 */

/** One row of the Circle board: a weekly snapshot, yours or a friend's. */
data class CircleMember(
    val name: String,
    val stopped: Int,           // reels/shorts stopped, last 7 days
    val watched: Int,           // watched (est.), last 7 days
    val savedMin: Int,          // time given back (est.), last 7 days
    val score: Int,             // focus score on the day the snapshot was made
    val streak: Int,            // current streak on that day
    val genDate: LocalDate,     // day the snapshot/code was made
    val importedDate: LocalDate // day it landed on this phone (self = today)
)
object Prefs {
    private const val FILE = "step1"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_COUNT = "count"
    private const val KEY_COUNT_DATE = "count_date"
    private const val KEY_TOTAL = "total_all"
    private const val KEY_LAST_MS = "last_block_ms"
    private const val KEY_LAST_APP = "last_block_app"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- ON/OFF switch ---

    fun isEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean(KEY_ENABLED, on).apply()

    // --- Step 2 checklist manual ticks ---

    private const val KEY_AUTOSTART = "chk_autostart"
    private const val KEY_RECENTS_LOCK = "chk_recents_lock"

    fun autostartDone(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AUTOSTART, false)
    fun setAutostartDone(ctx: Context, done: Boolean) =
        sp(ctx).edit().putBoolean(KEY_AUTOSTART, done).apply()

    fun recentsLockDone(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_RECENTS_LOCK, false)
    fun setRecentsLockDone(ctx: Context, done: Boolean) =
        sp(ctx).edit().putBoolean(KEY_RECENTS_LOCK, done).apply()

    // --- Block recording / per-day counts ---

    private fun igKey(date: LocalDate) = "d_${date}_ig"
    private fun ytKey(date: LocalDate) = "d_${date}_yt"

    /** Raw per-app count recorded by v3 for one day (no legacy merge). */
    fun blocksOnApp(ctx: Context, date: LocalDate, ig: Boolean): Int =
        sp(ctx).getInt(if (ig) igKey(date) else ytKey(date), 0)

    /**
     * Total blocks on a day. For the day stored in the legacy counter, the
     * legacy value wins if it's higher — those blocks happened before v3
     * existed, so their app split was never recorded.
     */
    fun blocksOn(ctx: Context, date: LocalDate): Int {
        val p = sp(ctx)
        var n = p.getInt(igKey(date), 0) + p.getInt(ytKey(date), 0)
        if (p.getString(KEY_COUNT_DATE, "") == date.toString())
            n = maxOf(n, p.getInt(KEY_COUNT, 0))
        return n
    }

    fun blockedToday(ctx: Context): Int = blocksOn(ctx, LocalDate.now())

    /** Today's blocks that v3 can't attribute to an app (pre-update blocks). */
    fun todayUnattributed(ctx: Context): Int {
        val today = LocalDate.now()
        return blockedToday(ctx) - blocksOnApp(ctx, today, true) - blocksOnApp(ctx, today, false)
    }

    /** Records one block. Returns today's displayed total (see blocksOn). */
    fun recordBlock(ctx: Context, ig: Boolean): Int {
        val p = sp(ctx)
        val today = LocalDate.now()
        val s = today.toString()
        val e = p.edit()
        e.putInt(if (ig) igKey(today) else ytKey(today),
            p.getInt(if (ig) igKey(today) else ytKey(today), 0) + 1)
        // Keep the legacy counter in sync.
        val legacy = if (p.getString(KEY_COUNT_DATE, "") == s) p.getInt(KEY_COUNT, 0) + 1 else 1
        e.putString(KEY_COUNT_DATE, s).putInt(KEY_COUNT, legacy)
        // All-time: first v3 block seeds from today's pre-v3 legacy blocks.
        val total = if (p.contains(KEY_TOTAL)) p.getInt(KEY_TOTAL, 0) + 1
            else (if (p.getString(KEY_COUNT_DATE, "") == s) legacy - 1 else 0) + 1
        e.putInt(KEY_TOTAL, total)
        e.putLong(KEY_LAST_MS, System.currentTimeMillis())
        e.putString(KEY_LAST_APP, if (ig) "ig" else "yt")
        e.apply()
        return blocksOn(ctx, today)
    }

    /** Legacy single-counter increment (Step 1/2 behaviour; kept for compat). */
    fun incrementBlocked(ctx: Context): Int {
        val p = sp(ctx)
        val today = LocalDate.now().toString()
        val next = if (p.getString(KEY_COUNT_DATE, "") == today) p.getInt(KEY_COUNT, 0) + 1 else 1
        p.edit().putString(KEY_COUNT_DATE, today).putInt(KEY_COUNT, next).apply()
        return next
    }

    fun totalAllTime(ctx: Context): Int {
        val p = sp(ctx)
        if (p.contains(KEY_TOTAL)) return p.getInt(KEY_TOTAL, 0)
        // Before any v3 block: all we know about is the legacy day counter.
        return if (p.getString(KEY_COUNT_DATE, "") == LocalDate.now().toString())
            p.getInt(KEY_COUNT, 0) else 0
    }

    fun lastBlockMs(ctx: Context): Long = sp(ctx).getLong(KEY_LAST_MS, 0L)

    /** "ig" / "yt" / null — which app the most recent block was in. */
    fun lastBlockApp(ctx: Context): String? = sp(ctx).getString(KEY_LAST_APP, null)

    // --- Time saved / averages / focus score ---

    const val MINUTES_PER_BLOCK = 3

    fun minutesSaved(blocks: Int): Int = blocks * MINUTES_PER_BLOCK

    /** Mean attempts over the 7 days before [date] (missing days = 0). */
    fun avgAttemptsPrev7(ctx: Context, date: LocalDate = LocalDate.now()): Double {
        var sum = 0
        for (i in 1..7) sum += blocksOn(ctx, date.minusDays(i.toLong()))
        return sum / 7.0
    }

    /** The 7 days ending today, oldest first. */
    fun last7Days(ctx: Context): List<Pair<LocalDate, Int>> {
        val today = LocalDate.now()
        return (6 downTo 0).map { off ->
            val d = today.minusDays(off.toLong())
            d to blocksOn(ctx, d)
        }
    }

    /** 100 − attempts above your recent daily average, clamped 0–100. */
    fun focusScore(ctx: Context, date: LocalDate = LocalDate.now()): Int {
        val over = blocksOn(ctx, date) - avgAttemptsPrev7(ctx, date)
        return (100 - maxOf(0.0, over).roundToInt()).coerceIn(0, 100)
    }

    // --- Streaks ("Open anyway" days don't count) ---

    fun markBroken(ctx: Context, date: LocalDate = LocalDate.now()) =
        sp(ctx).edit().putBoolean("broken_$date", true).apply()

    fun isBroken(ctx: Context, date: LocalDate): Boolean =
        sp(ctx).getBoolean("broken_$date", false)

    private fun qualifies(ctx: Context, date: LocalDate): Boolean =
        blocksOn(ctx, date) >= 1 && !isBroken(ctx, date)

    fun currentStreak(ctx: Context): Int {
        var d = LocalDate.now()
        if (!qualifies(ctx, d)) d = d.minusDays(1) // today hasn't counted yet
        var n = 0
        while (qualifies(ctx, d)) { n++; d = d.minusDays(1) }
        return n
    }

    fun longestStreak(ctx: Context): Int {
        var best = 0; var run = 0
        var d = LocalDate.now()
        repeat(400) {
            if (qualifies(ctx, d)) { run++; if (run > best) best = run } else run = 0
            d = d.minusDays(1)
        }
        return best
    }

    // --- Daily allowance (v3.2) ---

    /** How many reels/shorts per day may play before blocking starts. 0 = block all. */
    fun allowance(ctx: Context, ig: Boolean): Int =
        sp(ctx).getInt(if (ig) "allow_ig" else "allow_yt", 0)

    fun setAllowance(ctx: Context, ig: Boolean, n: Int) =
        sp(ctx).edit().putInt(if (ig) "allow_ig" else "allow_yt", n.coerceIn(0, 999)).apply()

    // --- Watched counting (v3.2) ---
    // An ESTIMATE built from what's on screen (viewer entry + caption/username
    // signature changes), not from the apps themselves. See README-INSTALL.md.

    private fun watchedKey(date: LocalDate, ig: Boolean) = "w_${date}_${if (ig) "ig" else "yt"}"

    fun watchedOn(ctx: Context, date: LocalDate, ig: Boolean): Int =
        sp(ctx).getInt(watchedKey(date, ig), 0)

    fun watchedToday(ctx: Context, ig: Boolean): Int = watchedOn(ctx, LocalDate.now(), ig)

    /** Records one watched video. Returns today's watched total for that app. */
    fun recordWatched(ctx: Context, ig: Boolean): Int {
        val p = sp(ctx)
        val key = watchedKey(LocalDate.now(), ig)
        val next = p.getInt(key, 0) + 1
        p.edit().putInt(key, next).apply()
        return next
    }

    /** Watched total over the last 7 days including today. */
    fun watchedThisWeek(ctx: Context, ig: Boolean): Int {
        val today = LocalDate.now()
        var sum = 0
        for (i in 0..6) sum += watchedOn(ctx, today.minusDays(i.toLong()), ig)
        return sum
    }

    // --- Whole-app daily time limits (v4.1, Step 4b) ---
    // A limit in minutes for the Instagram / YouTube APP itself (feed,
    // search, everything), separate from the reels/shorts allowance.
    // 0 = no limit. Usage is the foreground time the accessibility
    // service actually observed, accumulated event-by-event in chunks
    // capped at 60 s (see BlockerAccessibilityService.settleUsage) —
    // it is NOT Android's own usage-stats number.

    /** Minutes per day the whole app may be used. 0 = no limit. */
    fun appLimitMin(ctx: Context, ig: Boolean): Int =
        sp(ctx).getInt(if (ig) "app_limit_ig_min" else "app_limit_yt_min", 0)

    fun setAppLimitMin(ctx: Context, ig: Boolean, minutes: Int) =
        sp(ctx).edit()
            .putInt(if (ig) "app_limit_ig_min" else "app_limit_yt_min",
                minutes.coerceIn(0, 1440))
            .apply()

    private fun usageKey(date: LocalDate, ig: Boolean) =
        "usage_ms_${date}_${if (ig) "ig" else "yt"}"

    fun usageMsOn(ctx: Context, date: LocalDate, ig: Boolean): Long =
        sp(ctx).getLong(usageKey(date, ig), 0L)

    fun usageTodayMs(ctx: Context, ig: Boolean): Long =
        usageMsOn(ctx, LocalDate.now(), ig)

    /** Adds observed foreground time to today's usage for one app. The
     *  whole chunk lands on the day in progress when it's settled. */
    fun addUsageMs(ctx: Context, ig: Boolean, deltaMs: Long) {
        if (deltaMs <= 0L) return
        val p = sp(ctx)
        val key = usageKey(LocalDate.now(), ig)
        p.edit().putLong(key, p.getLong(key, 0L) + deltaMs).apply()
    }

    // --- Focus app blocking (v4.1, Step 7b) ---
    // While a focus session runs, the chosen apps are closed entirely.
    // This is its own commitment (the session was started deliberately),
    // so it applies even when the main Protection switch is off. Ending
    // the session early is the deliberate exit and is NOT lock-gated.

    fun focusBlockIg(ctx: Context): Boolean = sp(ctx).getBoolean("focus_block_ig", true)
    fun setFocusBlockIg(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean("focus_block_ig", on).apply()

    fun focusBlockYt(ctx: Context): Boolean = sp(ctx).getBoolean("focus_block_yt", true)
    fun setFocusBlockYt(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean("focus_block_yt", on).apply()

    // --- "Open anyway" bypass ---

    private fun bypassKey(pkg: String) =
        if (pkg == "com.instagram.android") "bypass_ig" else "bypass_yt"

    fun bypassUntil(ctx: Context, pkg: String): Long = sp(ctx).getLong(bypassKey(pkg), 0L)

    fun setBypass(ctx: Context, pkg: String, untilMs: Long) =
        sp(ctx).edit().putLong(bypassKey(pkg), untilMs).apply()

    // --- Focus timer ---

    private const val KEY_FOCUS_END = "focus_end_ms"
    private const val KEY_FOCUS_TOTAL = "focus_total_ms"
    private const val KEY_FOCUS_RECORDED = "focus_recorded_end"
    private const val KEY_FOCUS_DUR = "focus_dur"
    private const val KEY_FOCUS_UNSEEN = "focus_done_unseen"

    fun focusDuration(ctx: Context): Int = sp(ctx).getInt(KEY_FOCUS_DUR, 25)
    fun setFocusDuration(ctx: Context, minutes: Int) =
        sp(ctx).edit().putInt(KEY_FOCUS_DUR, minutes).apply()

    fun startFocus(ctx: Context, minutes: Int) {
        val now = System.currentTimeMillis()
        sp(ctx).edit()
            .putLong(KEY_FOCUS_TOTAL, minutes * 60_000L)
            .putLong(KEY_FOCUS_END, now + minutes * 60_000L)
            .apply()
    }

    fun focusEndMs(ctx: Context): Long = sp(ctx).getLong(KEY_FOCUS_END, 0L)
    fun focusTotalMs(ctx: Context): Long = sp(ctx).getLong(KEY_FOCUS_TOTAL, 0L)
    fun focusActive(ctx: Context): Boolean = focusEndMs(ctx) > System.currentTimeMillis()

    /** User ended early: session is dropped, never recorded. */
    fun cancelFocus(ctx: Context) =
        sp(ctx).edit().remove(KEY_FOCUS_END).remove(KEY_FOCUS_TOTAL).apply()

    /**
     * If a session's end time has passed and it hasn't been recorded yet,
     * record it exactly once. Returns true when this call recorded a
     * session.
     *
     * v4.1.2: the session is stamped under the day it ENDED (the date of
     * focus_end_ms), not the day this call happens to observe it — a
     * session that ended at 23:58 but is first seen the next morning
     * counts on its end date. The exactly-once guard is unchanged.
     */
    fun recordFocusIfDone(ctx: Context): Boolean {
        val p = sp(ctx)
        val end = p.getLong(KEY_FOCUS_END, 0L)
        if (end == 0L || end > System.currentTimeMillis()) return false
        if (p.getLong(KEY_FOCUS_RECORDED, 0L) == end) {
            p.edit().remove(KEY_FOCUS_END).remove(KEY_FOCUS_TOTAL).apply()
            return false
        }
        val endDate = Instant.ofEpochMilli(end).atZone(ZoneId.systemDefault()).toLocalDate()
        val mins = (p.getLong(KEY_FOCUS_TOTAL, 0L) / 60_000L).toInt()
        p.edit()
            .putLong(KEY_FOCUS_RECORDED, end)
            .putInt("fs_${endDate}_n", p.getInt("fs_${endDate}_n", 0) + 1)
            .putInt("fs_${endDate}_min", p.getInt("fs_${endDate}_min", 0) + mins)
            .putBoolean(KEY_FOCUS_UNSEEN, true)
            .remove(KEY_FOCUS_END).remove(KEY_FOCUS_TOTAL)
            .apply()
        return true
    }

    /** A completed session the user hasn't seen the "complete" state for yet. */
    fun focusDoneUnseen(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_FOCUS_UNSEEN, false)
    fun clearFocusDoneUnseen(ctx: Context) =
        sp(ctx).edit().putBoolean(KEY_FOCUS_UNSEEN, false).apply()

    /** (sessions, minutes) over the last 7 days including today. */
    fun focusThisWeek(ctx: Context): Pair<Int, Int> {
        val p = sp(ctx)
        val today = LocalDate.now()
        var n = 0; var mins = 0
        for (i in 0..6) {
            val d = today.minusDays(i.toLong())
            n += p.getInt("fs_${d}_n", 0)
            mins += p.getInt("fs_${d}_min", 0)
        }
        return n to mins
    }

    // --- Settings lock (v3.3) ---
    // When the lock is ON, loosening (protection off, allowance up, lock
    // off, PIN change) runs the LockGateActivity flow; tightening is always
    // instant. Only a salted SHA-256 hash of the PIN is ever stored — the
    // PIN itself never touches disk. There is NO PIN recovery by design:
    // forgetting it means clearing app data / reinstalling (stats included).

    private const val KEY_LOCK_ENABLED = "lock_enabled"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_LOCK_WRONG = "lock_wrong_n"
    private const val KEY_LOCK_WAIT = "lock_wait_until"

    fun lockEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_LOCK_ENABLED, false)

    fun setLockEnabled(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean(KEY_LOCK_ENABLED, on).apply()

    fun pinIsSet(ctx: Context): Boolean =
        !sp(ctx).getString(KEY_PIN_HASH, null).isNullOrEmpty()

    /** The lock only bites when it's on AND a PIN exists. On-without-PIN
     *  (shouldn't happen — a PIN is required to enable) is treated as off. */
    fun lockActive(ctx: Context): Boolean = lockEnabled(ctx) && pinIsSet(ctx)

    private fun pinHashHex(salt: String, pin: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest((salt + pin).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Sets or replaces the PIN — a fresh random salt every time. */
    fun setPin(ctx: Context, pin: String) {
        val saltBytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(saltBytes)
        val salt = saltBytes.joinToString("") { "%02x".format(it) }
        sp(ctx).edit()
            .putString(KEY_PIN_SALT, salt)
            .putString(KEY_PIN_HASH, pinHashHex(salt, pin))
            .apply()
    }

    fun verifyPin(ctx: Context, pin: String): Boolean {
        val p = sp(ctx)
        val salt = p.getString(KEY_PIN_SALT, null) ?: return false
        val stored = p.getString(KEY_PIN_HASH, null) ?: return false
        return java.security.MessageDigest.isEqual(
            pinHashHex(salt, pin).toByteArray(Charsets.UTF_8),
            stored.toByteArray(Charsets.UTF_8))
    }

    /** Lock off (through the gate): PIN is removed along with the lock. */
    fun clearPinAndLock(ctx: Context) =
        sp(ctx).edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_PIN_SALT)
            .putBoolean(KEY_LOCK_ENABLED, false)
            .apply()

    // Wrong-PIN throttle for the gate (5 wrong = 30 s wait). Persisted, so
    // closing and reopening the gate doesn't reset the count or the wait.
    fun lockWrongCount(ctx: Context): Int = sp(ctx).getInt(KEY_LOCK_WRONG, 0)
    fun setLockWrongCount(ctx: Context, n: Int) =
        sp(ctx).edit().putInt(KEY_LOCK_WRONG, n).apply()
    fun lockWaitUntil(ctx: Context): Long = sp(ctx).getLong(KEY_LOCK_WAIT, 0L)
    fun setLockWaitUntil(ctx: Context, ms: Long) =
        sp(ctx).edit().putLong(KEY_LOCK_WAIT, ms).apply()

    // --- Circle board + onboarding (v4.0) ---
    // The Circle is a private, supportive board with NO server and NO
    // internet permission: each person shares a snapshot "code" and pastes
    // friends' codes in. Codes are snapshots — they go stale, so the board
    // shows the date each one was made and greys out anything over 7 days
    // old. Ranking is positive only (focus score, then time saved).

    private const val KEY_CIRCLE_NAME = "circle_name"
    private const val KEY_CIRCLE_MEMBERS = "circle_members"
    private const val KEY_ONBOARDED = "onboarded"
    private const val MEMBER_SEP = "\u0002"
    private const val FIELD_SEP = "\u0001"
    private const val CIRCLE_MAX = 30

    fun circleName(ctx: Context): String =
        sp(ctx).getString(KEY_CIRCLE_NAME, null)?.takeIf { it.isNotBlank() } ?: "You"

    fun setCircleName(ctx: Context, name: String) =
        sp(ctx).edit().putString(KEY_CIRCLE_NAME, sanitizeName(name)).apply()

    fun onboarded(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ONBOARDED, false)

    fun setOnboarded(ctx: Context, done: Boolean) =
        sp(ctx).edit().putBoolean(KEY_ONBOARDED, done).apply()

    /** Names travel inside codes and the member list, so strip the field
     *  separators, pipes and line breaks, and cap the length. */
    fun sanitizeName(raw: String): String {
        val clean = raw.replace(Regex("[\u0000-\u001F\u007F|]"), " ").trim()
            .replace(Regex("\\s+"), " ")
        return clean.take(24).ifEmpty { "You" }
    }

    /** Your own live board row, from the same numbers the Stats tab shows. */
    fun myCircleMember(ctx: Context): CircleMember {
        val stopped = last7Days(ctx).sumOf { it.second }
        val today = LocalDate.now()
        return CircleMember(
            name = circleName(ctx),
            stopped = stopped,
            watched = watchedThisWeek(ctx, true) + watchedThisWeek(ctx, false),
            savedMin = minutesSaved(stopped),
            score = focusScore(ctx, today),
            streak = currentStreak(ctx),
            genDate = today,
            importedDate = today
        )
    }

    // --- Circle codes ---
    // Format: "RB1." + Base64("1|name|stopped|watched|savedMin|score|streak|yyyy-MM-dd")

    fun encodeMyCode(ctx: Context): String {
        val m = myCircleMember(ctx)
        val payload = listOf("1", m.name, m.stopped, m.watched, m.savedMin,
            m.score, m.streak, m.genDate).joinToString("|")
        return "RB1." + android.util.Base64.encodeToString(
            payload.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
    }

    /** Parses a pasted code — it may sit inside surrounding share text, so
     *  the "RB1." token is located first. Returns null on anything that
     *  isn't a well-formed RB1 code. */
    fun decodeCode(raw: String): CircleMember? {
        val start = raw.indexOf("RB1.")
        if (start < 0) return null
        val token = raw.substring(start).split(Regex("\\s")).first()
        val payload = try {
            String(android.util.Base64.decode(
                token.removePrefix("RB1."), android.util.Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) { return null }
        val f = payload.split("|")
        if (f.size != 8 || f[0] != "1") return null
        val nums = ArrayList<Int>()
        for (i in 2..6) nums.add(f[i].toIntOrNull() ?: return null)
        val date = try { LocalDate.parse(f[7]) } catch (e: Exception) { return null }
        return CircleMember(
            name = sanitizeName(f[1]),
            stopped = nums[0].coerceIn(0, 1_000_000),
            watched = nums[1].coerceIn(0, 1_000_000),
            savedMin = nums[2].coerceIn(0, 1_000_000),
            score = nums[3].coerceIn(0, 100),
            streak = nums[4].coerceIn(0, 10_000),
            genDate = date,
            importedDate = LocalDate.now()
        )
    }

    // --- Imported members, stored as one delimited string ---

    fun circleMembers(ctx: Context): List<CircleMember> {
        val raw = sp(ctx).getString(KEY_CIRCLE_MEMBERS, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<CircleMember>()
        for (entry in raw.split(MEMBER_SEP)) {
            val f = entry.split(FIELD_SEP)
            if (f.size != 8) continue
            try {
                out.add(CircleMember(
                    name = f[0],
                    stopped = f[1].toInt(), watched = f[2].toInt(),
                    savedMin = f[3].toInt(), score = f[4].toInt(), streak = f[5].toInt(),
                    genDate = LocalDate.parse(f[6]), importedDate = LocalDate.parse(f[7])
                ))
            } catch (e: Exception) { /* skip a corrupt entry */ }
        }
        return out
    }

    private fun saveCircleMembers(ctx: Context, members: List<CircleMember>) {
        val raw = members.joinToString(MEMBER_SEP) { m ->
            listOf(m.name, m.stopped, m.watched, m.savedMin, m.score, m.streak,
                m.genDate, m.importedDate).joinToString(FIELD_SEP)
        }
        sp(ctx).edit().putString(KEY_CIRCLE_MEMBERS, raw).apply()
    }

    /** Adds a friend — or replaces their old snapshot when the name
     *  matches, so a fresh code from the same person updates their row. */
    fun addCircleMember(ctx: Context, m: CircleMember) {
        val list = circleMembers(ctx)
            .filterNot { it.name.equals(m.name, ignoreCase = true) }
            .toMutableList()
        list.add(m)
        saveCircleMembers(ctx, list.takeLast(CIRCLE_MAX))
    }

    /**
     * v4.1.2: if adding a NEW distinct name would push the board past
     * CIRCLE_MAX, returns the name of the oldest member who would be
     * dropped (the list is append-ordered and addCircleMember keeps the
     * last CIRCLE_MAX, so it's the first remaining row). Returns null
     * when nobody would be dropped — including when [name] merely
     * replaces an existing member's snapshot.
     */
    fun circleEvictionCandidate(ctx: Context, name: String): String? {
        val list = circleMembers(ctx)
            .filterNot { it.name.equals(name, ignoreCase = true) }
        return if (list.size >= CIRCLE_MAX) list.first().name else null
    }

    fun removeCircleMember(ctx: Context, name: String) =
        saveCircleMembers(ctx, circleMembers(ctx)
            .filterNot { it.name.equals(name, ignoreCase = true) })

    // --- Debug log (v1.1): rolling last 40 entries, one string per entry. ---

    private const val KEY_LOG = "debug_log"
    private const val LOG_SEP = "\u0001"
    private const val LOG_MAX = 40

    fun addLog(ctx: Context, entry: String) {
        val p = sp(ctx)
        val entries = (p.getString(KEY_LOG, "") ?: "").split(LOG_SEP).filter { it.isNotEmpty() }
        p.edit().putString(KEY_LOG, (entries + entry).takeLast(LOG_MAX).joinToString(LOG_SEP)).apply()
    }

    fun logText(ctx: Context): String =
        (sp(ctx).getString(KEY_LOG, "") ?: "")
            .split(LOG_SEP).filter { it.isNotEmpty() }.joinToString("\n")

    fun clearLog(ctx: Context) =
        sp(ctx).edit().remove(KEY_LOG).apply()
}
