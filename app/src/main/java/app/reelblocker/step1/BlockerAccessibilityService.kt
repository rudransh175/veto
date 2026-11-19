package app.reelblocker.step1

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Step 1 core v1.2: detect ONLY the full-screen Reels / Shorts viewer and
 * bounce back. NEVER block a feed.
 *
 * v1.1 blocked the viewer (works) but also fired on the HOME FEED, where Back
 * exits the whole app. Feed screens contain Reels/Shorts elements: IG's
 * clips_tab is the bottom-nav icon on EVERY screen, YT home has a Shorts
 * shelf (reel_recycler / shorts_video_cell), and a normal feed video post
 * matched the fb:ig-reel-desc+rail fallback. v1.2 fixes:
 *  1. Weak IDs (clips_tab, reel_recycler, shorts_video_cell, reel_progress_bar)
 *     are log-only ("weak-only, ignored") — they never trigger a block.
 *  2. fb:ig-reel-desc+rail is dropped entirely.
 *  3. Explicit HOME/FEED veto: feed indicators present + no STRONG viewer ID
 *     => no block, and the veto is logged. The veto can never override a
 *     strong viewer ID (clips_viewer_*, reel_watch_fragment_root,
 *     shorts_player, reel_player_page_container) — those are viewer-only.
 * Kept from v1.1: block-match wins over DM/Stories safes for strong IDs, the
 * remaining viewer-specific fallbacks, and the throttled debug log.
 *
 * v3.2 adds watched counting + a daily allowance on top of the SAME
 * detection: the rule lists and the fallback/feedIndicator/collect/matchAny/
 * findRoot functions below are byte-identical to v1.2–v3.1. What changed is
 * only the event flow after detection (see onAccessibilityEvent): the
 * service no longer returns early when protection is OFF — it still detects
 * the viewer so it can COUNT watched videos (never blocks while off), and
 * when ON, an allowance of N lets the first N videos of the day play.
 *
 * v4.1 adds two SEPARATE mechanisms that run BEFORE (and independently of)
 * the viewer logic — they never touch the rule lists or the matching
 * functions, and they don't need the node tree at all:
 *  1. Whole-app daily time limits (Step 4b): every IG/YT event settles the
 *     foreground-time accounting (settleUsage); when protection is ON and
 *     today's observed usage reaches the app's minute limit, the app is
 *     bounced Home and the block screen shows its app-limit mode.
 *  2. Focus app blocking (Step 7b): while a focus session runs, the chosen
 *     apps are bounced Home on sight, whatever screen they're on — even
 *     when the main Protection switch is off (the session itself is the
 *     deliberate commitment).
 * typeWindowStateChanged was already in the accessibility config since
 * Step 1, so Piece 1 needs no config change.
 */
class BlockerAccessibilityService : AccessibilityService() {

    private var lastBlockMs = 0L
    private val lastLogMs = HashMap<String, Long>()
    private val lastLogIds = HashMap<String, Int>()

    /** v3.2 per-app viewing-session state for watched counting. In-memory
     *  only: a service restart just starts a fresh session (the entry count
     *  may then fire once more — part of why counting is an estimate). */
    private class WatchState {
        var inViewer = false
        var countedSig: Int? = null
        var lastCountMs = 0L
    }
    private val watchStates = HashMap<String, WatchState>()

    // ---- v4.1 state (Pieces 1 + 2; in-memory only, like WatchState) ----
    /** The IG/YT package whose events we most recently saw, and when the
     *  current usage-accumulation baseline started. A service restart just
     *  restarts the baseline — at most one capped chunk is ever lost. */
    private var fgPkg: String? = null
    private var fgSinceMs = 0L
    /** Last app-limit / focus-block enforcement per package (5 s throttle). */
    private val lastAppLimitEnforceMs = HashMap<String, Long>()
    private val lastFocusEnforceMs = HashMap<String, Long>()

    private class Snapshot(
        val ids: Set<String>,
        val texts: Set<String>,
        val descs: Set<String>,
        val nodeCount: Int,
        val rootClass: String,
        val homeSelected: Boolean
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // v3.2: no early return when protection is OFF — detection still runs
        // so watched videos get counted. Blocking is gated further down.
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != IG && pkg != YT) return

        // ---- v4.1 mechanisms (whole-app limits + focus blocking) ----
        // These run on the package alone, before the node tree is read,
        // and return early when they enforce — the viewer logic below is
        // untouched by them. Usage accounting runs in EVERY mode (it's
        // what the Shield "Used today" line shows, and it must already
        // be counting when protection gets switched on mid-day).
        val v41now = System.currentTimeMillis()
        val v41ig = pkg == IG
        settleUsage(pkg, v41now)
        // Piece 2 — focus blocks the chosen apps entirely. Applies
        // whenever a session is active, even with Protection off.
        if (Prefs.focusActive(this) &&
            (if (v41ig) Prefs.focusBlockIg(this) else Prefs.focusBlockYt(this)) &&
            v41now - (lastFocusEnforceMs[pkg] ?: 0L) >= ENFORCE_THROTTLE_MS
        ) {
            enforceFocusBlock(pkg, v41now)
            return
        }
        // Piece 1 — whole-app daily time limit (Protection ON only).
        val appLimitMin = Prefs.appLimitMin(this, v41ig)
        if (Prefs.isEnabled(this) && appLimitMin > 0 &&
            Prefs.usageTodayMs(this, v41ig) >= appLimitMin * 60_000L &&
            v41now - (lastAppLimitEnforceMs[pkg] ?: 0L) >= ENFORCE_THROTTLE_MS
        ) {
            enforceAppLimit(pkg, appLimitMin, v41now)
            return
        }

        val root = findRoot(pkg) ?: return
        val snap = collect(root)
        val cls = event.className?.toString() ?: ""

        val strongBlock = matchAny(snap.ids, if (pkg == IG) IG_STRONG else YT_STRONG)
        // Weak IDs are noted for the log only — they NEVER trigger a block (v1.2).
        val weakBlock = matchAny(snap.ids, if (pkg == IG) IG_WEAK_LOG_ONLY else YT_WEAK_LOG_ONLY)
        val strongSafe = pkg == IG && matchAny(snap.ids, IG_STRONG_SAFE) != null
        val storiesSafe = pkg == IG && snap.ids.any { it.contains("reel_viewer_") || it.contains("story_") }
        val feedVeto = feedIndicator(pkg, snap)

        var rule: String? = null
        var block = false
        when {
            // Strong viewer IDs are viewer-only: nothing vetoes them, not even the feed veto.
            strongBlock != null -> { rule = "id-strong:$strongBlock"; block = true }
            else -> {
                val fb = fallback(pkg, snap, cls)
                if (fb != null) {
                    rule = fb
                    when {
                        // Home/feed veto beats every non-strong match.
                        feedVeto != null -> rule += " (veto:home-feed:$feedVeto)"
                        // DM screens stop fallbacks; Stories stop fallbacks except
                        // the Reels/Friends header, which Stories never shows.
                        strongSafe || (storiesSafe && fb != "fb:ig-reels-friends-header") ->
                            rule += " (safe)"
                        else -> block = true
                    }
                }
                if (weakBlock != null) {
                    val note = "weak-only:$weakBlock ignored" +
                        if (feedVeto != null) " (veto:home-feed:$feedVeto)" else ""
                    rule = if (rule != null) "$rule | $note" else note
                }
            }
        }

        // ---- v3.2 event flow: watched counting + daily allowance ----
        // `block` above means exactly "the Reels/Shorts viewer is on screen"
        // (a strong ID, or a fallback that survived the feed veto and safes).
        val now = System.currentTimeMillis()
        var action = "none"
        val ig = pkg == IG
        val enabled = Prefs.isEnabled(this)
        val st = watchStates.getOrPut(pkg) { WatchState() }
        // A different app coming to the front ends the other app's session.
        for ((p, s) in watchStates) if (p != pkg) { s.inViewer = false; s.countedSig = null }

        if (!block) {
            // Non-viewer screen (feed, DMs, profile…): any viewing session is over.
            st.inViewer = false
            st.countedSig = null
        } else {
            // Is this event a NEW video? Entry counts 1; after that, a changed
            // caption/username signature counts the next one. With no usable
            // signature, scroll events count instead (throttled).
            val sig = videoSignature(snap)
            val entered = !st.inViewer
            st.inViewer = true
            var isNewVideo = false
            var countKind = ""
            when {
                entered -> { isNewVideo = true; countKind = "entry" }
                sig != null && st.countedSig != null && sig != st.countedSig &&
                    now - st.lastCountMs >= SIG_MIN_INTERVAL_MS ->
                    { isNewVideo = true; countKind = "next" }
                sig == null && event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
                    now - st.lastCountMs >= SCROLL_COUNT_THROTTLE_MS ->
                    { isNewVideo = true; countKind = "scroll" }
            }
            if (sig != null && (isNewVideo || st.countedSig == null)) st.countedSig = sig

            val bypassed = now < Prefs.bypassUntil(this, pkg)
            val allowance = if (enabled) Prefs.allowance(this, ig) else 0
            var doBlock = false
            when {
                // Protection OFF, or the "Open anyway" grace window: count
                // what plays, never block, never launch the block screen.
                !enabled || bypassed -> {
                    if (isNewVideo) {
                        val n = Prefs.recordWatched(this, ig)
                        st.lastCountMs = now
                        logCount(pkg, n, countKind, now)
                        action = "count"
                    } else if (bypassed) action = "skip-bypass"
                }
                // Allowance left: count the video and let it play. The video
                // that REACHES the limit is never interrupted — the block
                // lands on the NEXT new video, when watched >= allowance
                // already holds before it is counted.
                allowance > 0 -> {
                    if (isNewVideo) {
                        if (Prefs.watchedToday(this, ig) >= allowance) {
                            doBlock = true // past the limit; not counted as watched
                        } else {
                            val n = Prefs.recordWatched(this, ig)
                            st.lastCountMs = now
                            logCount(pkg, n, countKind, now)
                            action = "count"
                        }
                    }
                }
                // Allowance 0: the original behaviour — block on sight, and a
                // blocked attempt is never counted as watched.
                else -> doBlock = true
            }

            if (doBlock) {
                if (now - lastBlockMs >= COOLDOWN_MS) {
                    lastBlockMs = now
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    val todayCount = Prefs.recordBlock(this, ig)
                    action = "BACK"
                    st.inViewer = false
                    st.countedSig = null
                    // Belt and braces (v3.0): Back already bounced the user out;
                    // the block screen adds the pause + breathing moment on top.
                    // If Android refuses the background launch, the bounce stands.
                    try {
                        startActivity(Intent(this, BlockActivity::class.java).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                            )
                            putExtra(BlockActivity.EXTRA_COUNT, todayCount)
                            putExtra(BlockActivity.EXTRA_PKG, pkg)
                            // v3.2: >0 = this block is a daily-limit hit, so the
                            // block screen shows the limit copy instead.
                            putExtra(BlockActivity.EXTRA_LIMIT, allowance)
                        })
                    } catch (e: Exception) { /* bounce already happened */ }
                } else {
                    action = "skip-cooldown"
                    // Forget the session so the next event re-detects this
                    // video as an entry and retries the block once the
                    // cooldown has passed, instead of adopting it as counted.
                    st.inViewer = false
                }
            }
        }

        logEvent(pkg, cls, snap, rule, action, now)
    }

    // ================= v4.1 helpers (Pieces 1 + 2) =================

    /**
     * Foreground-time accounting, settled on every IG/YT event: the
     * previously-foreground package is credited with the time since the
     * last event, then [pkg] becomes the foreground package with a fresh
     * baseline. Event-driven only — no timers, no polling.
     *
     * The accessibility config subscribes ONLY to Instagram and YouTube,
     * so time spent in other apps fires no events here and would
     * otherwise be credited to whichever of the two was last seen. The
     * chunk cap (60 s) bounds that error to at most one capped chunk,
     * at the price of under-counting a long, event-quiet stretch inside
     * the app itself. A chunk is credited to the day in progress when
     * it settles, so a chunk straddling midnight lands on the new day.
     */
    private fun settleUsage(pkg: String, now: Long) {
        val prev = fgPkg
        if (prev != null && fgSinceMs > 0L) {
            val chunk = (now - fgSinceMs).coerceIn(0L, USAGE_CHUNK_CAP_MS)
            if (chunk > 0L) Prefs.addUsageMs(this, prev == IG, chunk)
        }
        fgPkg = pkg
        fgSinceMs = now
    }

    /** Piece 1 enforcement: app is over its daily minute limit. Home +
     *  the block screen's app-limit mode (no "Open anyway" there — the
     *  settings-lock gate is the deliberate way to raise the limit). */
    private fun enforceAppLimit(pkg: String, limitMin: Int, now: Long) {
        lastAppLimitEnforceMs[pkg] = now
        // QA fix (v4.1.1): the bounce ends any viewing session too — reset
        // the counting state so the next viewer entry counts fresh instead
        // of merging into the pre-bounce session.
        watchStates[pkg]?.let { it.inViewer = false; it.countedSig = null }
        performGlobalAction(GLOBAL_ACTION_HOME)
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(now))
        Prefs.addLog(this, "$time ${if (pkg == IG) "IG" else "YT"} rule=- action=applimit limit_min=$limitMin")
        try {
            startActivity(Intent(this, BlockActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(BlockActivity.EXTRA_PKG, pkg)
                putExtra(BlockActivity.EXTRA_MODE, BlockActivity.MODE_APPLIMIT)
                putExtra(BlockActivity.EXTRA_LIMIT_MIN, limitMin)
            })
        } catch (e: Exception) { /* the Home bounce already happened */ }
    }

    /** Piece 2 enforcement: a focus session is running and this app is
     *  one of the chosen ones. Home + the block screen's focus mode
     *  (again no "Open anyway" — ending the session in the app is the
     *  deliberate exit, and it is not lock-gated). */
    private fun enforceFocusBlock(pkg: String, now: Long) {
        lastFocusEnforceMs[pkg] = now
        // QA fix (v4.1.1): same session reset as enforceAppLimit.
        watchStates[pkg]?.let { it.inViewer = false; it.countedSig = null }
        performGlobalAction(GLOBAL_ACTION_HOME)
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(now))
        Prefs.addLog(this, "$time ${if (pkg == IG) "IG" else "YT"} rule=- action=focusblock")
        val remainMs = Prefs.focusEndMs(this) - now
        try {
            startActivity(Intent(this, BlockActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(BlockActivity.EXTRA_PKG, pkg)
                putExtra(BlockActivity.EXTRA_MODE, BlockActivity.MODE_FOCUS)
                putExtra(BlockActivity.EXTRA_REMAIN_MS, remainMs)
            })
        } catch (e: Exception) { /* the Home bounce already happened */ }
    }

    /** Visible text / desc / class-name fallbacks. Viewer-specific only (v1.2:
     *  fb:ig-reel-desc+rail dropped — a normal feed video post matched it). */
    private fun fallback(pkg: String, s: Snapshot, eventClass: String): String? {
        val classes = (eventClass + " " + s.rootClass).lowercase()
        if (pkg == IG) {
            if (s.texts.contains("Reels") && s.texts.contains("Friends"))
                return "fb:ig-reels-friends-header"
            if (classes.contains("clips")) return "fb:ig-class-clips"
        } else {
            if (classes.contains("reel")) return "fb:yt-class-reel"
            if (s.descs.any { it.contains("Shorts player", true) } ||
                s.texts.any { it.contains("Shorts player", true) }) return "fb:yt-shorts-player-label"
        }
        return null
    }

    /** Home/feed indicators for the veto. Returns the indicator name or null.
     *  Only consulted when no strong viewer ID matched, so it can never
     *  override the full-screen viewer. */
    private fun feedIndicator(pkg: String, s: Snapshot): String? {
        if (pkg == IG) {
            matchAny(s.ids, IG_FEED_IDS)?.let { return "id:$it" }
            if (s.texts.contains("Instagram") || s.descs.contains("Instagram"))
                return "ig-wordmark-header"
        } else {
            matchAny(s.ids, YT_FEED_IDS)?.let { return "id:$it" }
            // Shorts shelf on the home screen: "Shorts" header + Home tab selected.
            if ((s.texts.contains("Shorts") || s.descs.contains("Shorts")) && s.homeSelected)
                return "shorts-shelf+home-tab"
        }
        return null
    }

    private fun findRoot(pkg: String): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { return it }
        // Fallback: some events arrive with no active-window root on realme.
        val wins = windows ?: return null
        return wins.firstOrNull { it.root?.packageName?.toString() == pkg }?.root
            ?: wins.singleOrNull()?.root
    }

    /** BFS over visible nodes only, capped — keeps CPU/battery near zero. */
    private fun collect(root: AccessibilityNodeInfo): Snapshot {
        val ids = HashSet<String>(); val texts = HashSet<String>(); val descs = HashSet<String>()
        var homeSelected = false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (!queue.isEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            if (node.isVisibleToUser) {
                node.viewIdResourceName?.let { ids.add(it) }
                node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts.add(it.take(50)) }
                node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { descs.add(it.take(50)) }
                if (node.isSelected && (node.text?.toString()?.trim() == "Home" ||
                        node.contentDescription?.toString()?.contains("Home") == true))
                    homeSelected = true
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return Snapshot(ids, texts, descs, visited, root.className?.toString() ?: "", homeSelected)
    }

    /** Throttled: blocks always log; other events at most 1/2s per package, and only when the ID set changed. */
    private fun logEvent(pkg: String, cls: String, s: Snapshot, rule: String?, action: String, now: Long) {
        val idsHash = s.ids.hashCode()
        if (action != "BACK") {
            if (now - (lastLogMs[pkg] ?: 0L) < LOG_THROTTLE_MS) return
            if (lastLogIds[pkg] == idsHash) return
        }
        lastLogMs[pkg] = now; lastLogIds[pkg] = idsHash
        val tag = if (pkg == IG) "IG" else "YT"
        val idList = s.ids.sorted().take(25).joinToString(",") + if (s.ids.size > 25) " +${s.ids.size - 25}" else ""
        val txt = (s.texts + s.descs).take(8).joinToString(" / ")
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(now))
        Prefs.addLog(this, "$time $tag cls=$cls nodes=${s.nodeCount} rule=${rule ?: "-"} action=$action ids=[$idList] txt=[$txt]")
    }

    /**
     * v3.2 video signature: a hash of the visible text that changes when the
     * video changes — username, caption, music line. UI chrome (tab labels,
     * the "Reels"/"Friends" headers) is excluded because it never changes,
     * and timers / plain counts are excluded because they change on their
     * own. Null when nothing usable is on screen; counting then falls back
     * to scroll events. An ESTIMATE by construction — see README.
     */
    private fun videoSignature(s: Snapshot): Int? {
        val cands = (s.texts + s.descs).filter { t ->
            t.length >= 3 && t !in SIG_CHROME &&
                !SIG_TIMER.matches(t) && !t.all { it.isDigit() || it in ",.KM " }
        }.sorted()
        return if (cands.isEmpty()) null else cands.hashCode()
    }

    /** v3.2: exactly one debug-log line per counted video (entry / next / scroll). */
    private fun logCount(pkg: String, watchedToday: Int, kind: String, now: Long) {
        val tag = if (pkg == IG) "IG" else "YT"
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(now))
        Prefs.addLog(this, "$time $tag rule=- action=count kind=$kind watched_today=$watchedToday")
    }

    private fun matchAny(ids: Set<String>, subs: List<String>): String? {
        for (id in ids) for (sub in subs) if (id.contains(sub)) return sub
        return null
    }

    override fun onInterrupt() {}

    companion object {
        private const val IG = "com.instagram.android"
        private const val YT = "com.google.android.youtube"
        private const val MAX_NODES = 800
        private const val COOLDOWN_MS = 800L
        private const val LOG_THROTTLE_MS = 2000L

        // v3.2 counting: a signature change counts at most once per 1.2 s (a
        // single swipe produces several transient snapshots — they merge,
        // because a suppressed change does NOT move countedSig, so a later
        // event still counts the settled video). Scroll-fallback counts are
        // throttled to one per 2.5 s.
        private const val SIG_MIN_INTERVAL_MS = 1200L
        private const val SCROLL_COUNT_THROTTLE_MS = 2500L

        // v4.1: usage settles in chunks capped at 60 s (see settleUsage),
        // and each v4.1 enforcement fires at most once per 5 s per package.
        private const val USAGE_CHUNK_CAP_MS = 60_000L
        private const val ENFORCE_THROTTLE_MS = 5_000L
        private val SIG_TIMER = Regex("""\d{1,2}:\d{2}(:\d{2})?""")
        private val SIG_CHROME = setOf(
            "Reels", "Friends", "Home", "Search", "Create", "Profile", "You",
            "Shorts", "Subscriptions", "Library", "Explore", "Notifications",
            "Messages", "Follow", "Following", "Like", "Comment", "Share", "Save"
        )

        // Strong = the viewer itself; wins over every safe.
        private val IG_STRONG = listOf(
            "clips_viewer_view_pager", "clip_viewer_view_pager", "clips_viewer_media_container",
            "clips_swipe_refresh_container", "clips_video_container", "reels_viewer_root"
        )
        // Weak = tab/shelf hints present on feed screens too. v1.2: LOG ONLY,
        // never actionable — clips_tab is on every IG screen and reel_recycler /
        // shorts_video_cell are the Shorts shelf on YT home.
        private val IG_WEAK_LOG_ONLY = listOf("clips_tab")
        private val YT_WEAK_LOG_ONLY = listOf(
            "reel_recycler", "shorts_video_cell", "reel_progress_bar"
        )
        // Strong safe = DM screens that cannot co-occur with the Reels viewer.
        // v1.0's weak safes ("main_feed", "feed_timeline", "story_", "direct_") are GONE
        // as safes; feed_timeline/main_feed return in v1.2 only as feed-veto
        // indicators, which apply solely when no strong viewer ID matched.
        private val IG_STRONG_SAFE = listOf(
            "thread_view", "direct_thread", "direct_inbox", "inbox_thread", "thread_fragment"
        )
        // Feed indicators for the home/feed veto (non-strong matches only).
        private val IG_FEED_IDS = listOf(
            "feed_timeline", "main_feed", "stories_tray", "story_tray"
        )
        private val YT_FEED_IDS = listOf("home_recycler", "app_home")

        private val YT_STRONG = listOf(
            "reel_watch_fragment_root", "shorts_player", "reel_player_page_container"
        )
        // No YT safe list: v1.0's "watch_player"/"app_home_recycler" could sit in
        // the tree on almost any YouTube screen and veto Shorts detection.
        // (app_home returns in v1.2 only inside YT_FEED_IDS for the feed veto,
        // which by construction cannot override a strong viewer ID.)
    }
}
