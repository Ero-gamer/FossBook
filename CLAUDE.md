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
  To keep the app (and its login), install with `installDebug installDebugAndroidTest` and run
  `adb shell am instrument -w [-e class <fqcn>] com.astryx.book.test.test/androidx.test.runner.AndroidJUnitRunner`.
- `installDebug` installs on every connected device: with the phone plugged in too, set
  `ANDROID_SERIAL` (or use `adb -e`) so the debug app doesn't land on the user's phone.
- `.\gradlew.bat testDebugUnitTest` (use the variant task; `--tests` filtering
  doesn't work on plain `test`), `.\gradlew.bat connectedDebugAndroidTest`
  (needs a device), `.\gradlew.bat lintDebug`. CI (`ci.yml`) gates on
  `test`, `:app:lintDebug` and `connectedAndroidTest`. Run CI by hand on a branch with
  `gh workflow run ci.yml --ref <branch>`. The coverage report (`coverage.yml`) runs next
  to the release job and doesn't gate it.
- Release builds are minified (R8 full mode); debug builds and the tests aren't. A class or
  constructor something creates by reflection needs a keep rule in `proguard-rules.pro` (v1.25.1
  and v1.25.2 crashed at launch: WorkManager's Room database). The instrumented-tests job also
  launches the release APK (`.github/scripts/release-smoke-test.sh`); locally, check a release
  build with `assembleRelease` and the same script, passing the APK path.
- Kotlin sources: write bidirectional marks and icon-font glyphs as `\u` escapes, never as the
  characters themselves (lint `BidiSpoofing` fails the build on literal bidi marks).
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
- A lost renderer process (`RendererGoneClient`, `utils/RendererRecovery.kt`) replaces the
  WebView: the main view re-keys its `WebView` and reloads through the navigator, the Messages
  layer is re-keyed at its page. Per-WebView setup belongs in `onCreated` (it runs again for
  the replacement), not in effects that hold on to the first WebView. To try it on a debug
  build, send `Page.crash` over DevTools.
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
- Remove ads (`adblock.js`): a reel is an ad when one of its spans *is* the label, e.g.
  `<span class="f19">Ad</span>` at the reel's bottom left (English "Ad" even with Facebook in
  Hebrew, read on a phone). Feed ads are found by their label's colour instead. To read a real
  reel ad, turn *Remove ads* off (otherwise the reel is emptied first).
- Facebook plays most videos through Media Source Extensions, so a `<video>`'s
  `currentSrc` is `blob:`. The mobile site's player (`data-mcomponent="MVideo"`) carries
  the whole MP4 in `data-video-url`; downloads (`download_content.js`) and the lock-screen
  audio hand-off (`PIP_HANDOFF_ARM_JS`) use that.
- *Wrap text when zoomed* (`text_wrap.js`, main view only, with pinch to zoom on) narrows text
  blocks to the visual viewport after a pinch settles. m.facebook.com is laid out on the
  server: text is `white-space: pre` with the line breaks in the text, and every box has a
  fixed inline `height` (overlays use negative margins as tall as what they cover, so
  `height: auto` breaks them). The script joins the server's breaks (canvas-measured),
  switches to `pre-wrap` and adds each block's growth to the fixed heights around it, up to
  the first content-sized box. A test WebView can't pinch, so `TextWrapJsTest` fakes
  `visualViewport`; on the emulator, CDP `Input.synthesizePinchGesture` on the WebView target
  does a real pinch (its x/y must be inside the visual viewport, in CSS px).
- m.facebook.com cancels WebView's `textZoom` on every load (it divides its font-size variables
  by it). *Page zoom* (`page_zoom.js`, document-start, main view) zooms whole pages instead: it
  rewrites the viewport `<meta>`s and Facebook's `vpd` cookie (the viewport its server lays pages
  out for). Page scripts that read `visualViewport.scale` must count from
  `__astryxPageZoomScale()`, not 1. Zooming out needs `useWideViewPort`.
- m.facebook.com has no URL for Feeds (`?sk=h_chr` redirects to Home). *Start on Feeds* opens the
  menu at `/bookmarks/#astryx-feeds` and `open_feeds.js` presses its Feeds entry, found by its icon.
- *Facebook notifications* (`notifications/FacebookCountsWorker.kt`, WorkManager, 15 min) fetches
  `m.facebook.com/notifications/` with the WebView's cookies and reads the tab bar's English
  `aria-label`s ("notifications, 3 new, 4 of 5"). Without WebView's navigation headers
  (`Sec-Fetch-*` etc.) Facebook answers 400. A periodic job can't be forced early (WorkManager
  delays it); turning the setting off and on runs it at once. Logs `Facebook counts:` under
  `Astryxbook`.
- Sharing photos/videos into Facebook (`utils/ShareMedia.kt`, `share_media.js`): the
  `ShareToFeed`/`ShareToStory` activity-aliases; files are copied to the cache and handed to the page
  through the `AstryxShare` listener. Feed: the mobile Photo button's click on its hidden file input
  is answered with the files. Story: the desktop creator in the Messages layer (m.facebook.com sends
  stories to the app), one file per story, laid out wide (`useWideViewPort` + a viewport rewrite).
  Nothing ever presses Post/Share: when testing, discard drafts. `am start --eu` can't grant a gallery
  item or pass several files; serve test files through the app's own FileProvider instead.
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
