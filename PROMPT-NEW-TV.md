# One-shot prompt: set up an Android TV box

How to use: open Claude Code (or another coding agent with shell access) in an empty folder on a computer
that is on the same Wi-Fi as the TV, paste everything below the line, and fill in the INPUTS (only
OWNER_NAME matters). The agent then does the whole setup by itself; the only things it will ask you for are
the two physical steps in STEP 1. Every change is a disable (never an uninstall) and is reversible.

Status: built and verified on a Xiaomi Mi Box S (Android 9). This prompt has not been run on another model
yet, so expect to nudge it the first time. Read the README for what you get and the risks.

---

You are my autonomous engineer. Turn the Android TV / Google TV device on my home Wi-Fi into "<OWNER_NAME>'s
TV": an ad-free, glass-style home screen with the TV Hub app, phone remote (with typing), family-photo
wallpaper, Settings tile and boot logo. The reference build is on a Xiaomi Mi Box S. Everything is in the
open-source REPO below; clone it and reuse it, do not rewrite it. Do not ask me questions. The only things you may ask me for are the two physical steps
in STEP 1. Finish with evidence (screenshots / dumpsys), not claims.

## INPUTS (defaults, change only if I say so in my next message)
- OWNER_NAME = <YOUR NAME>  (boot logo and tile text read "<OWNER_NAME>'s TV"; if this is still the placeholder, use "My TV")
- REPO = https://github.com/ibrahimkhalilmasud/glass-tv-home  (public, MIT; clone it, never push to it)
- REMOVE_APPS = (none)  (extra apps I want disabled, by name; everything else I use stays)
- DOCK_ORDER = (automatic)  (apps in the order I want on the row; otherwise YouTube, Netflix, then the rest)
- REFERENCE = https://github.com/Gathin23/cleanyourtv  (MIT; the launcher + per-model debloat lists; use
  the revision recorded in the repo's README, currently baadd07a; read its AGENTS.md and PROMPT.md first)
- WEATHER = automatic city from the network (never a precise location). DASHBOARD_URL = empty; I set it
  later in TV Hub > Settings.
- RAM_OVERRIDE = none. If I write "RAM=1GB" (or similar) use that; otherwise read MemTotal from the TV.

## HARD RULES (these override everything)
1. Only my own local network (the laptop's active /24). Never probe other networks or the internet for TVs.
2. Disable, never uninstall: `pm disable-user --user 0 <pkg>`, ONE package per shell call. No root, no
   flashing, no factory/service menus, no factory reset. Never launch a package whose name says factory,
   production, fac, AT or test, not even to look.
3. Never disable: core Android (`android`, permission controller, package installer, ext services/shared,
   module metadata, `com.google.android.overlay.modules*`, `com.android.providers.*` except calendar,
   contacts, userdictionary, blockednumber, `com.android.systemui`, `.shell`, `.phone`, `.settings`,
   `com.android.tv.settings`), the TV/tuner/HDMI/input/CEC services, remote and Bluetooth pairing,
   keyboard, Google Play Store and Play Services, ANY update service (my rule), the Google Assistant,
   Chromecast built-in, the Android TV Remote service, and any streaming app that is not in REMOVE_APPS. If the setup wizard (`setupwraith`) owns a saved Wi-Fi network (check
   `dumpsys wifi | grep -o 'cname=[^ ]*'`), never disable it.
4. Back up before the first change. Disable in small batches and look at a screenshot after each. If Home
   or the remote stops working, re-enable the last batch immediately and report.
5. Never commit IPs, serial numbers, ADB keys, backups, screenshots, camera frames, signing keys or
   tokens. Commit messages carry no attribution lines.
6. Never say something works unless you saw it: screenshot, `dumpsys activity activities | grep
   mResumedActivity`, a command result. Black screenshots of Netflix/YouTube playback and of Android
   permission dialogs are normal (secure windows).
7. Fire TV, Samsung (Tizen), LG (webOS), Roku or an unknown OS: stop, say which and why, change nothing.

## STEP 0: laptop tools (install silently what is missing; I authorise it)
adb (platform-tools), JDK 17 or newer, Android SDK platform 34 + build-tools 34 (download Google's
command-line tools from dl.google.com and accept the SDK licence), Python 3 with Pillow and numpy, `gh`
logged in. Clone REPO into `./glass-tv-home` and REFERENCE into `./reference/cleanyourtv`, then read the
repo's README.md and `launcher-patch/`. On Windows with Git Bash use `MSYS_NO_PATHCONV=1`
for any adb path starting with `/` (otherwise paths get rewritten) and `git -c safe.directory='*'` on
drive F:. PowerShell is fine for everything else.

## STEP 1: find and connect (the only two things you may ask me for)
1. Scan the laptop's /24 read-only for TCP 5555, 6466/6467 (Android TV remote), 8008/8009 (Cast). Read
   Cast names from `http://<ip>:8008/setup/eureka_info`. Try `adb connect <ip>:5555` on every candidate.
2. If no device has 5555 open, ask me ONCE, in two lines: on the TV go Settings > Device Preferences >
   About > press OK on "Build" 7 times > back > Developer options > turn on USB debugging (and Network /
   ADB debugging if shown); keep the TV awake. If the TV only offers Wireless debugging, ask me for the
   pairing code and port, then `adb pair`, `adb connect`.
3. Tell me when the "Allow USB debugging?" prompt will appear; I tick Always allow and press OK.
4. If several Android TVs answer, set up each one in turn (skip any that already have `com.mytvhome.hub`
   unless I said "update") and keep a separate notes file for each. Print model, Android version,
   firmware, MemTotal, resolution. Gate: `ro.build.version.sdk` must be 26 or higher for the launcher
   (minSdk 26). Below 26: do STEP 3 and STEP 4 only and say the launcher is not possible.

## STEP 2: memory profile (adjust only memory-related settings; everything else stays identical)
Read MemTotal (or RAM_OVERRIDE) and set PROFILE:
- 2 GB or more: full profile (what the reference box uses: `wallpaper_true_color` true, 5-minute shuffle
  rotation, `advanced_effects` true, `fade_rows` true, blur on).
- 1 GB to under 2 GB: `wallpaper_true_color` false, rotation 15 minutes, `advanced_effects` false,
  TV Hub in no-blur mode (set the saved `no_blur` preference to 1).
- Under 1 GB: also `fade_rows` false, `dock_glass` false with `dock_opacity` 70, one static wallpaper
  (rotation 0), splash 2.5 s, weather shows current conditions only, 6 columns.
Report the MemTotal you read and the profile chosen. Do not otherwise ask or explain.

## STEP 3: back up (private, git-ignored `glass-tv-home/backup/<model>/`)
`pm list packages -f`, `pm list packages -d`, `pm list packages -3`, `getprop`, `settings list
global|secure|system`, the HOME resolver (`cmd package resolve-activity --brief -a
android.intent.action.MAIN -c android.intent.category.HOME`), the wifi owner list, and a screenshot of
the stock home (`adb exec-out screencap -p`). Write a restore script for exactly what you will disable.

## STEP 4: debloat
- If REFERENCE has `examples/<matching model or fingerprint>/debloat.txt`, start from it and read its
  header notes (updater behaviour, remote buttons, USB power, set-home-activity quirks). Otherwise
  classify `pm list packages` into telemetry/analytics, ad and content-row services, promo and
  auto-install packages, pre-loaded junk, and things to keep; for unknown package names read
  `dumpsys package <name>` first.
- Default remove list: telemetry and analytics, ad tracking (Alphonso), feedback and bug-report senders,
  the manufacturer's content/home/recommendation rows (for Xiaomi: PatchWall `com.mitv.tvhome.atv`,
  `com.mitv.tvhome.michannel`, `com.xm.webcontent`, `com.google.android.tvrecommendations`), partner
  auto-install configs, print spooler. Apps I might use (YouTube Music, TalkBack, SonyLIV, Play Games, Play
  Movies & TV, streaming apps) are removed ONLY if they are listed in REMOVE_APPS. Remove nothing else.
- Keep: everything in HARD RULE 3, Netflix, YouTube, Prime Video, Hotstar, Disney+, Apple TV, Plex, VLC,
  IPTV apps, Live Channels (hidden from the dock is fine).
- Order: batch 1 telemetry, batch 2 content/ad rows (note the time; after 5 minutes compare `pm list
  packages -d` with your list: if the manufacturer's updater re-enabled something, leave the updater alone
  per rule 3 and note it), batch 3 unused preloads. Stock launcher and screensaver go last (STEP 5).

## STEP 5: launcher (REFERENCE launcher, unchanged logic) and theme
1. Apply `glass-tv-home/launcher-patch/glass-ui.patch` to the REFERENCE checkout, copy
   `glass-tv-home/design/out/banner_*.png` into `launcher/app/src/main/res/drawable-nodpi/`, and extend
   `app_banners` in `res/values/config.xml` (STEP 6). Build: `./gradlew assembleRelease` (JDK 17+,
   `sdk.dir` in `local.properties`). The APK is signed with the laptop's debug key; keep
   a copy of the laptop's `~/.android/debug.keystore` in `glass-tv-home/keys/` (git-ignored) and reuse the SAME key on every TV so updates install over each other.
2. Install order (Home guard): `pm install -r`; push `theme/config.json` (adjusted by PROFILE) to
   `/sdcard/Android/data/com.gathin.tvhome/files/config.json` and the `theme/wallpapers/` pictures to
   `.../files/wallpapers/` (keep `CREDITS.md` in the repo); `cmd package compile -m speed -f
   com.gathin.tvhome`; `am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n
   com.gathin.tvhome/.MainActivity` and require "Status: ok". Only then: disable the stock launcher
   (`com.google.android.tvlauncher` on Google TV; on other builds the package that
   `resolve-activity` named before), then `cmd package set-home-activity --user 0
   com.gathin.tvhome/.MainActivity` (Android 9 needs the component form).
   TRAP: `set-home-activity` prints Success while the stock launcher still wins (priority 2). Trust only
   `dumpsys activity activities | grep mResumedActivity` after a Home key press. If Home is not
   com.gathin.tvhome, re-enable the stock launcher and report.
3. Theme (final reference values): `dock_apps` DOCK_ORDER if given, otherwise YouTube, Netflix, then every other
   installed streaming app (Hotstar, Prime Video, Disney+, Apple TV, Plex, IPTV players), then `com.mytvhome.hub`,
   the other installed apps, and `com.mytvhome.settings` LAST; `columns` 8; `dock_row`, `single_row`, `dock_band_all`, `fill_dock` true;
   `rows_below_dock` 0; `dock_glass` true, `dock_opacity` 30; `icon_only_buttons`, `icon_only_inputs`
   true; `library_button` true, `show_library_pill` false, `app_library` true; `launcher_settings_button`
   true; `wallpaper` true with captions off, `wallpaper_dim` 8; `uniform_tiles` false; `ambient_glow` and
   `app_glow` false; `focus_overshoot` false; `clock_wallpaper` true; `live_tv_package` empty on boxes
   without a tuner. SEED `library_apps` with every app that should be on Home, otherwise a theme push
   makes the launcher drop apps from Home (this happened).
4. Clock screensaver: `settings put secure screensaver_components com.gathin.tvhome/.ClockDream` and
   `screensaver_default_component` the same; then disable `com.google.android.backdrop`.
5. Do not enable accessibility key helpers (they cost volume-key repeat).
6. Reboot once. Confirm: boots in about a minute, Home is the new launcher, ADB reconnects, the disabled
   list is unchanged. D-pad test: top bar, Down to the dock, OK opens YouTube, Home returns.

## STEP 6: glass app tiles
Run `OWNER_NAME=<name> python glass-tv-home/design/make_tiles.py` (Outfit font from the launcher; supersampled gradients, brand
glow, curved glass highlight, bright rim, bold wordmark). Tiles exist for YouTube, Netflix, Hotstar,
Purple IPTV, IPTV, Live Channels, Play Store, Prime Video, VLC and TV Hub. For EVERY other app in the dock
add a function in the same style (brand-tinted deep gradient, glow, label at the same size, my own
drawing, never a copied logo; pick the colour from the app's icon or its brand), add it to `TILES`,
regenerate, copy the PNGs into the launcher, and add `package=banner_name` lines to `app_banners`.
Rebuild and reinstall the launcher. Check by screenshot that every dock tile is the glass style and its
label is readable; adjust sizes if not. Commit the new tile code to REPO.

## STEP 7: TV Hub app (`glass-tv-home/hub-app`, package `com.mytvhome.hub`)
Build, install, then `pm grant com.mytvhome.hub android.permission.WRITE_SECURE_SETTINGS` (needed so phone
photo uploads can write into the launcher). Open it. It must show: clock and date, live weather with a
3-day forecast for the automatic city, four cards (Agents Dashboard, Phone remote, Send a photo,
Settings), the glass Outfit styling, and obey the remote (focus ring, Back closes panels).
- Phone remote: the card shows a QR code for `http://<tv ip>:8090/r#<token>`. A background service
  (started at boot by the boot receiver) runs the server and talks to the box's OWN adbd on
  127.0.0.1:5555 (Android 9 forbids apps injecting keys elsewhere). Test by decoding the QR from a
  screenshot (OpenCV `QRCodeDetector`), then `curl -X POST -H "X-Remote: <token>" -d home
  http://<tv ip>:8090/key`, `-d <package> .../app`, and verify `mResumedActivity` changes. Verify bad
  input (`x;reboot`, unknown key) is refused and a wrong token returns 403. Verify it still works after a
  reboot without opening TV Hub. If the TV uses Wireless debugging on another port, make the client use
  that port.
- Send a photo: the card shows the address and a 6-digit code; the phone page resizes to 1920x1080 and
  uploads; the picture appears in Launcher settings > Wallpaper as `zz-phone-<time>.jpg`. Test with one
  of the theme pictures, then delete the test file. `tools/add-photo.ps1` does the same from the laptop.
- Type on TV: the phone page has a text box; it sends `{"del":N,"add":"..."}` to `/text`, which runs
  `input keyevent 67` N times and `input text '<escaped>'` (spaces as `%s`; ASCII only, refuse the rest).
  Test it inside YouTube: Home > guest (never pick an account) > up > RIGHT to the Search pill (up alone
  lands on the microphone and starts voice search, which pollutes the field) > OK, then POST `/text` and
  check the text in the field by screenshot.
- Voice and gestures (v1.1): before building run `python glass-tv-home/tools/fetch_mediapipe.py` (22 MB, checksum
  verified) and `node --test glass-tv-home/tests/js/web.test.js` (must pass). TV Hub then serves the phone page
  on http :8090 and https :8091 (self-signed, generated on the box; NOT 8443, Chromecast owns that). One QR code
  (the http link) is shown; the page offers a banner that moves to https for the microphone and camera. Verify
  with `curl -sk https://<tv ip>:8091/r` and by driving the page in a headless browser against the TV: type
  "go home", "open netflix", "open settings" into the command box and check `mResumedActivity` each time. Check
  the Phone remote panel says whether the TV has a camera (`CameraManager`); if it does, test "Use the TV's
  camera" and say plainly that you could not test it if no camera is attached. Never claim gestures or voice
  work on a live phone unless you saw it.
- Settings tile: build `glass-tv-home/settings-app` (`com.mytvhome.settings`, one activity that opens
  `Settings.ACTION_SETTINGS`), install it, add its glass tile (`banner_settings`) to the launcher
  `app_banners`, and make it the LAST entry of `dock_apps` (put every dock app in explicit order).
- System update: open `am start -a android.settings.SYSTEM_UPDATE_SETTINGS`, screenshot it, and report what
  it says. Never disable the updater; do not claim a firmware is final unless the TV says so.
- Agents Dashboard: leave the URL empty. Say in the report that it is set under TV Hub > Settings.

## STEP 8: boot logo and settings glass
The boot receiver starts `SplashActivity` (assets/splash.html: glass orb "M", "Mike's TV", "Welcome
home", about 4 s). Replace "Mike" and the letter "M" in splash.html with OWNER_NAME and its initial, and run
`OWNER_NAME=<name> python glass-tv-home/design/make_tiles.py` so the tile and icon say the same. Test by rebooting and capturing the
screen as soon as `mResumedActivity` is the splash (`adb shell am start` of it is not allowed, it is
private). The glass Launcher settings page comes from the launcher patch; open it from the gear and take a
screenshot.

## STEP 9: final verification (all with evidence in `glass-tv-home/shots/<model>/`, git-ignored)
Home screen; dock navigation and OK launching two apps; Home returns; Launcher settings glass page;
Wallpaper picker with a phone-uploaded photo selected and shown full screen (then set back to the theme
default); TV Hub screens; QR remote commands; reboot (boot logo seen, Home correct, disabled list intact,
port 8090 open); `adb shell dumpsys meminfo` free RAM before and after; list what you could not verify
(DRM video, standby wake, real phone camera) honestly.

## STEP 10: notes
Write `devices/<model>.md` locally (model, Android version, firmware, RAM, profile, packages disabled,
quirks; NO IPs, serials, Wi-Fi names or account names). Do not push anything to REPO. If I ask you to share
the notes, prepare a pull request from my own fork. Never commit backups, screenshots, keys or APKs.

## STEP 11: report to me (short)
What was done per step; MemTotal and profile; what I must do (accept debugging prompt once, scan the QR
code to try the phone remote, set the dashboard URL, send my family photo); rollback command; the
security note: USB debugging must stay on for the phone remote, network ADB is plain text, never forward
port 8090 or 5555 on the router; limitations (no firmware update exists for old boxes, standby drops
Wi-Fi so ADB is unreachable until the TV is woken with the remote).

## KNOWN TRAPS (learned the hard way)
- Git Bash rewrites `/sdcard/...` paths: set `MSYS_NO_PATHCONV=1`. Android shell commands that run long
  (`am start -W` on a crashing app) can hang the laptop's adb call: use short timeouts and check logcat.
- `InputStream.readAllBytes()` and similar newer Java APIs crash on Android 9: use plain loops.
- ADB goes offline when the box sleeps; reconnect with `adb connect`, and after `error: closed` run
  `adb kill-server`.
- Pushing the theme again resets the Wallpaper choice and can drop apps from Home unless `library_apps`
  is seeded.
- A WebView on a 1080p TV reports a 960 px wide viewport at 2x density: lay out at 1920 and zoom
  (the TV Hub pages already do).
- Tile PNGs must be copied into the launcher AFTER the last `make_tiles.py` run, then rebuild.
- The launcher is plain Java with a 1 GB-class memory budget: no blur, no video, no polling in it. The
  TV Hub weather polls every 30 minutes only while the Hub is open.
