# Reel Blocker — 10-Pass Fidelity QA Report

**App under test:** v4.1-complete (versionCode 10) → **v4.1.1-qa (versionCode 11)** after fixes
**Date:** 19 Nov 2026
**Method:** ten distinct audit passes over the same finished app — source-level
tracing of every Kotlin file, string-level comparison against the shipped v1.2
APK, a simulation of the Circle codec, hand-recomputed stats examples, and
aapt/apksigner verification of the built packages. On-device behaviour is
static-verified only; anything that needs a phone is marked **needs phone test**
rather than guessed.

**Result summary:** 0 critical, 0 high, 0 medium, **4 low — all 4 fixed**,
8 info notes. Final APK: `ReelBlocker-QA.apk` (identical copies at
`ReelBlocker-Step1.apk` and `ReelBlocker-Final.apk`), versionCode 11 /
versionName `4.1.1-qa`, zero permissions, signing certificate unchanged
(SHA-256 `56c4d75c96b7ba9afd8cbca46a8fb44ff7a31fc2724d22da65fcab16a19e9bb7`).

---

## Pass 1 — Detection integrity

**Checked:** every rule list in `BlockerAccessibilityService` against the
v1.2-proven baseline, extracted as strings from the shipped
`ReelBlocker-Step1-v1.2.apk` dex; the evaluation order in
`onAccessibilityEvent`; the scope of the feed veto and the DM/Stories safes.

**Findings:** none.

- All list groups are identical to the v1.2 APK: `IG_STRONG` (6 IDs),
  `YT_STRONG` (3), `IG_WEAK_LOG_ONLY` (`clips_tab`), `YT_WEAK_LOG_ONLY`
  (`reel_recycler`, `shorts_video_cell`, `reel_progress_bar`),
  `IG_STRONG_SAFE` (5), `IG_FEED_IDS` (4), `YT_FEED_IDS` (2), plus the
  `reel_viewer_` / `story_` Stories contains-checks.
- A strong viewer ID sets `block = true` in the first `when` branch —
  the feed veto and both safes are structurally unreachable for it.
- Weak IDs are only appended to the debug-log rule string; they can never
  set `block`.
- The feed veto and safes are consulted only in the fallback branch, and
  the v1.2-dropped `fb:ig-reel-desc+rail` fallback is still absent.
- Info: `storiesSafe` also matches `story_tray` on the home feed — harmless,
  since it can only ever suppress a fallback, and the feed veto applies on
  that screen anyway.

**Verdict: CLEAN.**

## Pass 2 — Counting model

**Checked:** signature/scroll throttles, session start/end conditions, the
OFF-mode code path end to end, bypass counting, double-count risks.

**Findings:**

- **F2.1 (Low — FIXED):** the v4.1 enforcement returns (focus block /
  app limit) bounced the app Home without resetting the per-package
  `WatchState`. The next viewer entry could merge into the pre-bounce
  session and miss its entry count. Both `enforceFocusBlock` and
  `enforceAppLimit` now reset `inViewer`/`countedSig` for that package.
- OFF mode traced: `!enabled` lands in the count-only branch before any
  `doBlock` assignment; with protection off the allowance is forced to 0
  and the blocking branches are unreachable. It cannot block — verified by
  path, not by assumption.
- Signature counts are ≥1.2 s apart and a suppressed change does not move
  `countedSig`, so the settled video is still counted by a later event;
  scroll fallback fires only when there is no usable signature, ≥2.5 s apart.
- Session ends on: non-viewer screen, the other app coming forward, a block.
- Bypass ("Open anyway") videos count as watched, exactly as the README says.
- Info (documented estimates): re-entering the same video counts a fresh
  entry; a service restart can add one entry.

**Verdict: PASS after fix.**

## Pass 3 — Allowance edges

**Checked:** N=0, N>0 boundary, midnight rollover, per-app independence.

**Findings:** none.

- N=0: first viewer detection blocks; a blocked attempt is never counted
  as watched.
- N>0: the check is `watchedToday >= allowance` evaluated *before* the new
  video is counted — the video that reaches N counts and plays in full,
  the block lands on video N+1, and that video is not counted. Matches the
  README word for word.
- All counters are date-keyed (`d_`/`w_yyyy-MM-dd_…`), so rollover at
  midnight is inherent; IG and YT keys are fully independent.
- Info: lowering an allowance mid-day below today's watched count makes
  the very next video block — intended tightening behaviour.

**Verdict: CLEAN.**

## Pass 4 — Lock gate

**Checked:** every loosening and tightening path in the UI, gate abort
semantics, wrong-PIN throttle persistence, PIN storage.

**Findings:** none blocking; two info notes.

- Gated while the lock is on: protection OFF (one shared switch listener
  serves both Home and Shield switches), allowance raise (stepper + chips),
  app-limit raise (stepper + chips, v4.1 `ACTION_APP_LIMIT`), PIN change
  (gate → new-PIN step), lock off.
- Instant, never gated: protection ON, allowance/app-limit lowering,
  setting the first PIN, turning the lock on.
- The pending change is applied only inside `LockGateActivity.applyPending()`,
  reachable only from phrase-step success. Leaving mid-countdown finishes
  the activity without applying (`onPause` guard); back/cancel at any step
  discards. The caller's switch snaps back immediately and re-syncs from
  prefs on resume — a discarded gate cannot half-apply.
- Wrong-PIN throttle is persisted (`lock_wrong_n`, `lock_wait_until`):
  5 wrong → count resets and a 30 s wait starts; a correct PIN resets the
  count. Closing and reopening the gate resets neither.
- PIN storage: a grep of every `putString` in `Prefs` confirms only
  `pin_salt` and `pin_hash` (SHA-256 of salt+PIN, fresh random salt per
  set/change) are ever written; verification uses `MessageDigest.isEqual`;
  the PIN is never written to the debug log.
- Info 1: focus app-blocking toggles and ending a focus session are
  deliberately **not** gated (documented design — the v4.1 focus test
  script relies on mid-session toggle changes).
- Info 2: `LockGateActivity` has no `launchMode` guard — double-tapping a
  gated control could stack two gate activities. Harmless (each requires
  the full flow; applying the same pending change twice is idempotent),
  recorded as a recommendation below.

**Verdict: CLEAN.**

## Pass 5 — Focus

**Checked:** exactly-once session recording, early-end semantics, focus
app-blocking (incl. with Protection OFF), remaining-time text.

**Findings:**

- **F5.1 (Low — FIXED, copy):** the Focus tab subtitle ("Reels + Shorts
  stay blocked. This is your deep-work timer.") and the "During focus"
  row ("Reels + Shorts locked") still described the v3.0 behaviour,
  although v4.1 closes the chosen apps entirely. Copy updated to match
  what the toggles actually do.
- `recordFocusIfDone` records exactly once: the `focus_recorded_end == end`
  guard plus removal of `focus_end_ms`/`focus_total_ms` makes the
  onResume call and the 500 ms ticker call safe against each other.
- Early end (`cancelFocus`) drops the session unrecorded; the UI shows
  "Session ended early — not counted."
- Focus blocking is checked in the service *before* the protection-gated
  app-limit check and has no `isEnabled` condition — it applies with
  Protection OFF, as documented. 5 s per-package throttle.
- Remaining time is computed at enforcement (`focusEndMs − now`) and
  formatted as ceil-seconds `M:SS`, producing the README's
  "(24:37 left)" format.
- Info: a completed session is recorded under the day `recordFocusIfDone`
  next runs (normally the same day). A session ending at 23:58 but first
  observed when the app is opened the next morning lands on the new day —
  a cosmetic stats nuance, noted below.

**Verdict: PASS after fix.**

## Pass 6 — App limits

**Checked:** usage settle/chunk-cap logic, day attribution, enforcement
throttle, Open-anyway visibility in the new block modes, display math.

**Findings:** none.

- `settleUsage` credits the previously-foreground package with the elapsed
  time since the last event, clamped to [0, 60 s], then re-baselines.
  Chunks land on the day in progress at settle time — both exactly as the
  README describes.
- Enforcement requires protection ON + limit > 0 + today's usage ≥ limit,
  throttled to once per 5 s per package, logs `action=applimit`.
- "Open anyway" is `GONE` in both `MODE_APPLIMIT` and `MODE_FOCUS`,
  in `onCreate` and in `onNewIntent`; the viewer mode keeps it.
- Display uses floor minutes (`usageMs / 60_000`) while enforcement
  compares exact ms — the display never overstates usage.
- Info: usage accrues in every mode including Protection OFF (by design;
  the Shield state line says limits aren't enforced while off).
- Info: a long event-quiet stretch inside the app (e.g. one long video,
  no touches) under-counts by design — the price of the 60 s cap, and
  documented in the README.

**Verdict: CLEAN.**

## Pass 7 — Stats math

**Checked:** focus score, streak rules, time saved, week sums, legacy
counter handling, per-app splits — with hand-recomputed examples.

**Findings:** none. Hand checks:

- Focus score: today 12 blocks, previous-7 sum 35 → avg 5.0 → over 7.0 →
  **93**. Code: `(100 − max(0, 12 − 5.0).roundToInt()).coerceIn(0,100)` = 93 ✓.
  Extremes: 0 vs avg 0 → 100; 130 vs avg 0 → clamped 0 ✓.
- Time saved: 12 × `MINUTES_PER_BLOCK` (3) = **36 min** ✓.
- Streak: today broken by "Open anyway", yesterday and the day before
  qualify → `currentStreak` starts from yesterday and returns **2** ✓.
  `longestStreak` scans 400 days ✓.
- Legacy seeding: with 17 pre-v3 blocks today, the first v3 `recordBlock`
  sets legacy 18 and seeds `total_all` = (18−1)+1 = **18** ✓ — the all-time
  total neither loses the pre-update blocks nor double-counts the new one.
  `blocksOn` takes `max(per-app sum, legacy)` only on the legacy counter's
  own date, and `todayUnattributed` can never go negative.
- Week sums: Stats per-app totals use the raw per-app keys and show the
  unattributed remainder only when > 0; the Circle self row's stopped
  total is the same `last7Days` sum Stats shows ✓.
- `focusThisWeek` sums `fs_<date>_n`/`_min` over the last 7 days ✓.

**Verdict: CLEAN.**

## Pass 8 — UI/UX fidelity

**Checked:** system-bar insets on every surface, glass drawable
consistency, the 5-tab bar, text sizes against the v3.1 rules, empty
states, contrast.

**Findings:**

- **F8.1 (Low — FIXED):** inactive tab labels were `#777777` on black —
  ≈4.7:1 at 12 sp. Now `Ui.FAINT` (`#8E8E93`, ≈6.5:1), the app's existing
  faint tone.
- Insets: `Ui.applySystemBarInsets` is applied on all five surfaces —
  MainActivity root, onboarding root, BlockActivity, LockGateActivity,
  SplashActivity ✓.
- Glass: every card uses `Ui.glass` (translucent fill, 1 px border, top
  highlight, 18 dp radius); text fields are intentionally solid `#1C1C1E`
  in both MainActivity and LockGateActivity — consistent.
- Sizes follow the v3.1 rules: tab titles 24 sp, hero 52 sp, focus clock
  46 sp, Home rings 36 % of width clamped 120–170 dp, Focus ring 62 %
  clamped 200–280 dp ✓.
- Empty states present: Home zero-day sentence, Stats zero-week sentence,
  Circle "Just you so far…", debug-log placeholder, Focus with no history
  shows "0 · 0 min" (plain but present).
- The 5 tab labels are weight-1 at 12 sp — they fit on typical ≥360 dp
  widths by layout inspection; **needs phone test** on a narrow device.
- Info: stale Circle rows colour the whole detail line red — deliberate
  ("stale must look stale"), per the v4.0 spec.

**Verdict: PASS after fix.**

## Pass 9 — Circle codec

**Checked:** encode/decode round-trip, garbage rejection, own-code
detection, replace-on-refresh, stale rule, name sanitisation, member cap.

**Findings:**

- **F9.1 (Low — FIXED):** pasting your *own older* code — copied before
  your stats changed, so the exact-string own-code check missed it —
  added a stale snapshot of yourself to the board as if you were a
  friend. A decoded code whose name matches your display name is now
  called out as your own code and not added. (This also makes the README
  claim "Pasting your own code is called out, not added" true for old
  codes, not just the current one.)
- Round-trip verified by simulating the exact format
  (`RB1.` + Base64 of `1|name|stopped|watched|savedMin|score|streak|yyyy-MM-dd`):
  all 8 fields survive, and the decode index mapping (f[2..6] →
  stopped/watched/savedMin/score/streak) is correct.
- Garbage is rejected at every layer: missing prefix, undecodable Base64,
  wrong version field, non-numeric fields, unparseable date → `null` →
  honest error line, no crash. (Android's Base64 decoder is lenient about
  stray characters, but the structural checks still reject junk payloads.)
- Replace-on-refresh: `addCircleMember` removes any same-name row
  (case-insensitive) before appending ✓.
- Stale rule: `genDate.isBefore(today − 7)` — strictly more than 7 days
  old, matching the README's "older than 7 days" ✓.
- Sanitisation: `sanitizeName` strips control characters (including both
  storage separators `\u0001`/`\u0002`), pipes and line breaks, caps at
  24 chars, and is applied both when the name is set and when a code is
  decoded — a crafted name cannot corrupt the member list or the code.
- Cap: `takeLast(30)` — a 31st distinct name silently drops the oldest
  member. Documented in the README ("Up to 30 members are kept");
  a warning would be nicer (recommendation below).

**Verdict: PASS after fix.**

## Pass 10 — Package & honesty

**Checked:** permissions, certificate continuity, version fields, every
numeric claim in the README against the code, install/update path,
debug-log usefulness.

**Findings:** none blocking; one wording nit (info).

- `aapt dump permissions` on the final QA APK: **zero permissions**.
- Certificate SHA-256 `56c4d75c…` is identical on the v1.2 baseline APK,
  the v3.0 build, v4.1 and the v4.1.1-qa rebuild — the update-install path
  (and all saved prefs/stats) is preserved across every version.
- Version fields agree between `AndroidManifest.xml` and
  `app/build.gradle.kts` (10 / `4.1-complete` before QA; both bumped to
  11 / `4.1.1-qa` with the fixes). The compiled accessibility config in
  the APK matches the source (both packages, event types, 100 ms timeout).
- README numeric claims vs code — all verified: 3 min per block
  (`MINUTES_PER_BLOCK`), 60 s usage chunk cap (`USAGE_CHUNK_CAP_MS`),
  5 s enforcement throttles (`ENFORCE_THROTTLE_MS`), 5 s Go-back countdown
  (BlockActivity `COUNTDOWN_MS`), 60 s gate countdown, 30 s wait after
  5 wrong PINs (`WRONG_WAIT_MS` / `MAX_WRONG`), 60 s "Open anyway" bypass
  (`BYPASS_MS`), 800 ms block cooldown, 1.2 s signature / 2.5 s scroll
  counting throttles, 40-entry debug log (`LOG_MAX`), 800-node BFS cap
  (`MAX_NODES`), 30-member Circle cap (`CIRCLE_MAX`), 15-min app-limit
  steps with Off/30/60/120 chips, ~700 ms splash sweep / ~1.1 s total,
  650 ms ring sweep.
- Debug log: blocks always log; counts, `action=applimit` and
  `action=focusblock` each write a line; other events are throttled
  (2 s + ID-set change). The Shield card tells the user to screenshot it —
  the diagnostic loop from the v1.x days still works.
- Info (wording): the README's focus test script calls the block screen's
  remaining time "live (MM:SS left)". It is a snapshot taken at
  enforcement, refreshed on each re-enforcement (≤5 s) — behaviour is
  fine, the word "live" is slightly generous.

**Verdict: CLEAN.**

---

## Fixes applied in v4.1.1-qa (versionCode 11)

| # | Pass | Fix |
|---|------|-----|
| F2.1 | 2 | App-limit / focus enforcement now resets the watched-counting session for that package, so the next viewer entry counts fresh. |
| F5.1 | 5 | Focus tab subtitle and "During focus" row copy updated to describe whole-app blocking (v4.1 behaviour), not the old reels-only wording. |
| F8.1 | 8 | Inactive tab labels `#777777` → `Ui.FAINT` `#8E8E93` (≈4.7:1 → ≈6.5:1 on black). |
| F9.1 | 9 | A pasted code carrying your own display name is called out as your own code and never added — closes the stale-self-snapshot hole. |

Rebuilt with the project `build.sh` pipeline and re-verified after the
fixes: clean build, zero permissions, same signing certificate, version
11 / `4.1.1-qa` in both manifest and Gradle. No detection ID list or
matching semantic was changed by any fix.

## What's wrong / remaining risks

1. **Nothing in v4.1 or v4.1.1 has run on a phone yet.** All ten passes are
   code/static verification. The app-limit settle behaviour, the focus
   bounce, and the four QA fixes need the README test scripts run on the
   realme before they're believed. *(needs phone test)*
2. **App-limit usage can under-count in real use.** A long event-quiet
   stretch inside Instagram/YouTube (one long video, no interaction) is
   capped at 60 s per settlement, so a limit can fire late. Bounded and
   documented, but it's the softest number in the app.
3. **Focus sessions are stamped with the day they're recorded, not the
   day they ended** (Pass 5 info) — a cross-midnight session can land on
   the wrong day's stats.
4. **The whole app stands on IG/YT view IDs.** Detection, counting and
   the safes all key off the current apps' internal resource IDs; an
   Instagram or YouTube update can shift them without notice. The debug
   log is the early-warning system, but a break would be silent until
   someone looks.
5. **Watched counting is an estimate by construction** — identical
   consecutive captions merge into one count, re-entering a video counts
   it again. The UI and README both say "estimate"; the risk is only if
   the number is ever treated as exact.

## What could be better (prioritised)

1. **Run the phone tests** — v4.1 test scripts (app limit + focus) and a
   quick regression of the four QA fixes. Highest value per minute;
   everything else on this list is polish by comparison.
2. **Give `LockGateActivity` a re-entry guard** (`launchMode="singleTop"`
   or an already-running check) so double-taps can't stack gates.
3. **Record focus sessions under their end date** instead of the date
   they're next observed — a small `Prefs` change that removes the
   cross-midnight stats nuance.
4. **Tick the focus block screen's remaining time live** while it's shown
   (BlockActivity already runs a countdown handler; recompute from
   `Prefs.focusEndMs`) — and drop the word "live" from the README script
   until it does.
5. **Warn before the Circle cap drops someone** — when adding a 31st
   member, say who will be removed, or raise the cap.
6. **Consider `UsageStatsManager` as an optional cross-check** for
   app-limit usage. It would need the PACKAGE_USAGE_STATS special access
   — a real trade-off against the zero-permission design, so it's a
   decision to make explicitly, not a silent upgrade.
7. **Stamp the debug log with the app version** (one header line when the
   log is cleared/first written) so screenshots sent back are
   self-identifying.
