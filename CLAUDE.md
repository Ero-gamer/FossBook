# Astryxbook

Android wrapper around m.facebook.com (Kotlin, Jetpack Compose,
compose-webview-multiplatform, Media3). Package `com.eepiemi.materialbook`,
applicationId `com.astryx.book`. Fork-specific changes are documented in
`FORK_CHANGES.md` (upstream's own log is `CHANGE.md`); update it with every
user-visible change.

## Build, install, test (Windows / PowerShell)

- Release build for a device: `.\gradlew.bat assembleRelease "-PversionNameOverride=99.99.N"`.
  The quotes are required in PowerShell. A plain local build is `versionCode`
  13, lower than the installed app, so `adb install -r` needs the override
  (bump N for each install).
- Debug builds install as a separate app (`com.astryx.book.test`). Use it
  for device checks when the phone has an official (release-key) build:
  a local release APK won't install over it. `connectedDebugAndroidTest`
  uninstalls that app when it finishes (losing its Facebook login), so run
  instrumented tests before or after a device session, not in the middle.
- `.\gradlew.bat testDebugUnitTest` (use the variant task; `--tests` filtering
  doesn't work on plain `test`), `.\gradlew.bat connectedDebugAndroidTest`
  (needs a device), `.\gradlew.bat lintDebug`. CI (`ci.yml`) gates on
  `test`, `:app:lintDebug` and `connectedAndroidTest`. Run CI by hand on a branch with
  `gh workflow run ci.yml --ref <branch>`. The coverage report (`coverage.yml`) runs next
  to the release job and doesn't gate it.
- Pushing to `main` runs `create-release.yml` (which calls `ci.yml`); it
  auto-versions from conventional commits (`feat:` = minor bump) and
  publishes a release. A push touching only docs or repo tooling (`*.md`,
  `LICENSE`, `fastlane/`, `assets/`, `.github/`, `.gitignore`, `lint.xml`)
  skips it via `paths-ignore`.

## WebView and PiP code (`ui/screens/MaterialbookWV.kt`)

- compose-webview's navigator replays only its *last* event to a WebView that
  isn't attached yet. Never call `navigator.evaluateJavaScript` unconditionally
  at composition: guard on `state.loadingState is LoadingState.Finished` or a
  trigger counter `> 0`, or it replaces the initial `loadUrl` and the page
  never loads.
- Native -> JS uses Compose trigger counters/state passed from `MainActivity`
  into `MaterialbookWebView`; JS -> native goes through the `AstryxPage` message listener
  (`PageBridge.kt`, main Facebook frame only), with `PipBridge` etc. as the fallback on
  WebViews older than 83. New page -> app calls go there, not in a new `addJavascriptInterface`.
- PiP focus mode (`PIP_FOCUS_MODE_JS` / `PIP_RESTORE_MODE_JS`) was tuned
  through live DevTools sessions and is covered by `PipFocusModeJsTest`. Never
  restyle or resize the video's ancestor elements: Facebook's reels controller
  watches them and pauses the reel. Add new PiP behaviour as separate scripts
  and effects instead of editing focus mode.
- The page scripts in `res/raw` always run from the copy bundled in the APK
  (`bundledScripts.kt`); nothing is fetched at runtime, so script changes ship
  only with an app release.
- Messages in desktop mode opens `MessagesLayer` (`ui/screens/MessagesLayer.kt`), a
  second WebView with the desktop user agent over the main one. The main view never
  switches user agent for Messages; Facebook pages opened from a chat stay in the layer
  (`messagesLayerRoute`), but Facebook's own sections (Home, Friends, the Reels tab…) close
  the layer and open in the main view (`messagesLayerExit`; the desktop site navigates
  in-page, so `messages_layer_nav.js` reports URL changes too). Each WebView is a separate
  DevTools target. PiP effects act on
  `pipNavigator`/`pipState` (the layer's while it's open, see `PipTarget`), never on
  `navigator` directly.
- `m.facebook.com` redirects full loads of some pages (profiles) to
  `intent://…;package=com.facebook.katana;S.browser_fallback_url=…`; the interceptors open
  the web fallback (`intentFallbackUrl`). Never launch the app an `intent://` link names.
- `MainActivity` is `singleTask`: a link sent while the app runs arrives in `onNewIntent`
  and reaches the WebView as `openLinkRequest`. Start links are checked with `appLinkUrl`
  (the start page skips the request interceptor); outgoing links with `externalLinkAction`.
- `state.nativeWebView` throws until the WebView exists (it's created during layout, which
  doesn't happen with the screen off): guard it in effects.
- Instrumented tests reset the debug app's settings (not its Facebook login).
- On Android 12+ PiP auto-enters and `onUserLeaveHint` is NOT called (verified on API 34
  and 36), so anything that must run on every PiP entry goes on
  `onPictureInPictureModeChanged`/`isInPipMode`, not `pipEnteringTrigger`. Facebook's
  players pause the video on each resize; the keep-playing guard
  (`pipKeepPlayingActivateJs`) undoes that while in PiP.
- Page scripts use optional chaining (`?.`, Chrome 80+), so on a WebView older than
  that (e.g. the stock API 26 emulator image, Chrome 69) `scripts.js` and most others
  fail to parse. Keep new PiP scripts ES5-style; they also run there.
- All PiP, lock-screen audio and fullscreen logs use the tag `AstryxbookPiP`:
  `adb logcat AstryxbookPiP:D *:S`.
- WebView debugging is always on in debug builds; on a release build turn it on in
  Settings (tap the version at the bottom seven times, then *WebView debugging*). Keep the
  setting: live DevTools sessions against the release build are how most PiP fixes were
  found. Live debugging on the device: forward `localabstract:webview_devtools_remote_<pid>`
  and use the DevTools protocol (`/json`, `Runtime.evaluate`).

- Facebook's desktop player pauses videos less than 50% inside the viewport (an
  `IntersectionObserver` rule that outranks a user's Play). The PiP window is smaller
  than its player, so `pip_visibility.js` (a document-start script, both WebViews) reports
  the PiP video's player as fully visible while PiP is engaged. To see why Facebook
  pauses/plays, hook `require('CoreVideoPlayerAutoplayClientUtils').log` in DevTools: it
  names the deciding autoplay rule.
- "Keep screen on" uses the host view's `keepScreenOn`, not the window flag the fullscreen
  host adds and clears.
- Facebook's video viewer re-lays out only on a real fullscreen transition
  (`fullscreenchange`), not on `resize`/`orientationchange`, and writes its
  widths into inline styles. Chromium ends HTML5 fullscreen by itself when
  PiP starts from landscape (see `PIP_HOLD_FULLSCREENCHANGE_JS`).

## Settings, permissions and strings

- Every new settings key must be listed in `data/local/SettingsBackup.kt`, either as exported
  or in `NOT_EXPORTED` (developer options, device-specific state). `SettingsBackupTest`
  fails on a key in neither list.
- Camera and microphone go to web pages only through `webMediaGrant`
  (`utils/MediaPermissions.kt`, used by `onPermissionRequest` in `FilePicker.kt`): Facebook
  origins only, after the Android permission. Don't fall back to the library's grant-all.
- `MainActivity` is a `FragmentActivity` because app lock uses `BiometricPrompt`.
- New fork strings go in `brand_strings.xml`, `extra_strings.xml` or `privacy_strings.xml`
  (with the Hebrew copy in `values-iw`), not upstream's `strings.xml`.

## Facebook behaviour, not ours

Verify against Chrome/Opera on the phone before "fixing" these: Reels can
re-pause a resumed video; finished videos auto-advance (and leave
fullscreen). A real landscape page is broken on Facebook's side (reel skip
on rotation, oversized still frame after leaving fullscreen), which is why
phones are held in portrait except during fullscreen video and while the
Messages layer is open (`appOrientation`). See Known
limitations in `FORK_CHANGES.md`.

## Repo housekeeping

- Plan files (`*_PLAN.md`) and `spike.log` are working notes; don't commit them.
- The `stats` branch is data-only (weekly release download counts written by
  `download-stats.yml`); never merge it into `main`.
- On Windows, `git worktree remove` can fail with "Filename too long"; delete
  the folder with PowerShell, then `git worktree prune`.
