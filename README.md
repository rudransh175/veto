# Veto

**Veto** is a free, offline Android app that stops the scroll at the moment it starts: it blocks Instagram Reels and YouTube Shorts, gives you a daily allowance if you want one, and makes loosening your own rules deliberately slow.

*Formerly Reel Blocker — renamed to Veto for this first public release, **v1**. The Android package ID is unchanged (`app.reelblocker.step1`), so existing installs update in place with stats, settings and PIN preserved.*

*Versioning note: public releases count up from v1 (v1, v2, v3…). Internally, this build follows the 4.1.x development builds; in Android terms it is versionName `1.0`, versionCode `13` (kept above the development builds so it update-installs over them).*

No ads. No account. No subscription. No payment system. **Zero Android permissions in the manifest — not even INTERNET.** Everything is stored on the phone.

## What it does

- **Reels & Shorts blocker** — an Accessibility service watches only Instagram and YouTube. When the Reels or Shorts viewer opens, Veto bounces you out to a calm block screen. Home feeds, DMs, Stories and normal videos are not touched.
- **Breathing block screen** — real attempt count for today, a breathing ring, and a 5-second delay before “Go back” unlocks. “Open anyway” exists, but it buys only 60 seconds and breaks today’s streak.
- **Daily allowance** — set how many Reels/Shorts may play per app each day. `0` means block on sight. Watched videos are counted from on-screen cues, so counting is an estimate, and it keeps counting even while protection is off.
- **Whole-app daily time limits** — a minute budget for the Instagram or YouTube app itself. When the day’s tracked foreground time is used up, the app is closed until midnight.
- **Stats that stay local** — blocked/watched counts, estimated time given back, focus score, streaks and a 7-day view. Nothing is uploaded anywhere.
- **Settings lock** — turning protection off, raising an allowance/limit, changing the PIN or turning the lock off requires the PIN, a 60-second countdown and typing a confirmation phrase. Tightening a rule is always instant. The PIN is stored only as a salted SHA-256 hash; there is no PIN recovery by design.
- **Focus timer** — 15/25/50/90-minute sessions. While a session runs, the apps you choose (Instagram and/or YouTube) are blocked entirely, not just their short-video viewers.
- **Circle board (offline)** — a private, supportive leaderboard without a server: the app generates a stats code, you share it with your small circle however you like, and you paste their codes back. Boards rank by focus score and time saved; stale snapshots are labelled with their date.
- **Built for low battery** — event-driven Accessibility only: no polling loop, no VPN, no wake-lock, no constant foreground service, no screenshots/OCR/ML.

## Screenshots

_Screenshots placeholder — add captures of Home, the block screen, Focus, Shield and Circle here._

## Install

Veto works on Android 8.0+ (API 26+). Download `Veto.apk` from the GitHub Release titled **Veto** (tag `v1`), then use either method:

### Method 1 — Normal install

1. Open `Veto.apk` on the phone and allow installs from that source (browser/Files) when Android asks.
2. Install, open Veto, and follow the 3-page intro.
3. Enable the Accessibility service: Settings → Accessibility → Installed apps → **Veto** → ON → Allow.
4. **Android 13+ restricted settings:** if the Veto toggle is greyed out, first go to Settings → Apps → **Veto** → ⋮ menu (top right) → **Allow restricted settings**, confirm with PIN/biometric, then repeat step 3.
5. On Realme/ColorOS phones, so the system doesn’t kill it: Battery → **Unrestricted**, **Autostart** ON, and lock Veto in Recents.

#### If Play Protect blocks the install — read this first

On some phones — especially in India, where Play Protect’s enhanced fraud protection is aggressive — Play Protect refuses to install apps that use Accessibility when the APK was installed from a browser or chat app. That is a policy block on the *category* (any sideloaded Accessibility app), not a scan finding that Veto is harmful: the APK requests zero permissions and has no internet access at all.

Workaround, if you trust the source (this repository’s Release):

1. Play Store → profile icon → Play Protect → gear icon → turn **“Scan apps with Play Protect”** OFF. (If the toggle is greyed out, turn OFF Android 16 Advanced Protection first.)
2. Install `Veto.apk`.
3. Turn **“Scan apps with Play Protect”** back ON. If it later warns about Veto, review the warning and choose **Keep app** only if you installed the APK from this repository’s Release.

If you’d rather not touch Play Protect settings at all, use Method 2 instead.

### Method 2 — ADB install (no Play Protect fight)

You need a computer with Android platform-tools.

1. On the phone: Settings → About phone → tap **Build number** 7 times to enable Developer options.
2. Settings → Developer options → turn **USB debugging** ON.
3. Connect the phone by USB and accept the debugging prompt on the phone.
4. On the computer:
   ```bash
   adb install Veto.apk
   ```
   For updates later, keep your data and settings with:
   ```bash
   adb install -r Veto.apk
   ```
5. Open Veto and enable Accessibility as in Method 1 — **the Android 13+ restricted-settings step (Method 1, step 4) still applies after an ADB install** if the toggle is greyed out.

## Privacy

- The manifest declares **no permissions** (`aapt dump permissions` shows only the package line) and the app has **no INTERNET permission**.
- All counts, settings, the settings-lock PIN hash and Circle snapshots live in on-device app preferences.
- Circle has no backend. A Circle code contains only the stats shown in the app plus your chosen display name and the code’s date.
- The Circle code prefix remains `RB1.` from the app’s former name. It is invisible plumbing; changing it would break compatibility with codes already shared, for no user benefit.

## Build from source

Requirements: JDK 17 and Android SDK 35 / build-tools 35.0.0.

This repository contains the Gradle project files (`build.gradle.kts`, `settings.gradle.kts`, `app/build.gradle.kts`) — open it in Android Studio or use a local Gradle 8.x with the Android Gradle Plugin versions declared in the root build file. No Gradle wrapper is committed.

The original development build used `build.sh`, a manual aapt2 → kotlinc → d8 → zipalign → apksigner pipeline. Before using it on another machine, edit its `TOOLS` path to point at your local JDK/SDK/Kotlin install.

Signing note: **the debug keystore is not published** (it is git-ignored). Release APKs attached to GitHub Releases are signed with the project key used for those builds. If you build from source, `build.sh` generates your own local keystore — an APK signed with a different key will not update-install over the Release APK; uninstall first or keep using the Release APK for updates.

## QA

A 10-pass fidelity audit of the finished app is in [`QA-REPORT.md`](QA-REPORT.md): detection integrity, counting, allowance edges, lock gate, focus, app limits, stats math, UI fidelity, Circle codec and package/honesty checks. Result: 0 critical/high/medium findings; 4 low findings, all fixed.

## License

MIT — see [`LICENSE`](LICENSE).
