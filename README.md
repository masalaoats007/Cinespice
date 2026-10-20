# Cinespice

A private Telegram video library for iPhone and iPad, built with SwiftUI, TDLib
and VideoLAN MobileVLCKit. Previously named Tevee. No backend, subscriptions or
analytics.

## Android APK

A native Android port is in [`android/`](android/README.md), with Cinespice branding,
Telegram login/library and embedded VLC. It targets Android 8.0+ and builds signed,
directly installable debug APKs for ARM64, 32-bit ARM and x86_64. Build/install steps,
Android differences and remaining device checks are in the Android README.
GitHub Actions workflow: **Build Cinespice Android APK**.

## Current status

The user reports real Telegram streaming works. Revision `41e002f` also has a
[successful macOS build](https://github.com/Thinkmasalablog/Tevee/actions/runs/38937494876).
The UI and player changes in this update still require a new macOS build and signed-device
verification. Implemented features below describe the code; they do not imply every
format, screen size or failure scenario has been tested on an iPhone.

### UI redesign

- Five compact tabs: Home, Favorites, Search, Downloads, History; one channel picker.
- Persistent Favorites and Watchlist, four palettes (two dark/two light), title and
  filename-year sorting. Latest releases is the default; unknown years stay last.
- Empty search until typing; independent destination scroll positions; five recent
  history cards. Tapping Cinespice refreshes the current collection/search or queues
  one newest page per selected source.
- Seven hero slots: recently added, daily pick, four visibly unavailable catalogue
  trends, last searched. No metadata provider is connected yet.
- Completed download status persists. Player options use compact dark panels.
- Profile shows real account name/username, themes, storage and creator links.
  Request feature is a coming-later label, not an active submission button.

Read the [iOS UI specification](docs/UI-README.md) and [Android UI guide without
Liquid Glass](docs/ANDROID-UI.md). The materials use supported SwiftUI APIs; native
iOS 27 Liquid Glass rendering is not claimed. New native/device validation remains
outstanding. SQLite schema 3 preserves existing metadata/history; older app builds
will reject the upgraded database instead of overwriting it.

### Touch feedback update

Custom controls now use at least 44-point targets with visible press/disabled
states. Video gestures are separated from the player controls, and play/pause
updates immediately. Download, sign-in, history and export actions show pending
work. See the [button audit and device checklist](docs/TOUCH-CHECKLIST.md).
Physical touch behavior and native builds for this update remain unverified.

## Connect and choose your videos

1. Enter your Telegram API ID/hash from `my.telegram.org`, then your own account's
   phone number, login code and any requested two-step/email verification.
2. Open the **Channels icon** in the header and select the channels/groups you want to browse.
3. Tap the arrow beside a selected source to browse, or search by title/filename, then tap a video to play.

### Account and session

- TDLib stores its encrypted login session. API credentials and the encryption key
  are stored in iOS Keychain. Reopening normally restores login; revoked sessions,
  sign-out or unavailable secure storage can require authentication again.
- Account-specific SQLite databases store the library, selections, paging cursors
  and watch history, Favorites and Watchlist. Renaming Tevee to Cinespice keeps the bundle ID
  `app.tevee.ios`, Keychain service and storage paths stable for upgrade continuity.
- Channels/groups are supported. Personal chats and Saved Messages are currently
  excluded from browsing, following the selected-source requirement.

### Collections, paging and search

- The picker discovers main/archive channels and groups gradually in batches of 30
  while visible. Search/filter available sources and show only selected ones.
- Owned channels sort first, oldest available Telegram date first. Telegram's date
  can represent joining rather than the original creation date.
- Collection pages request up to 30 video messages plus 30 documents. Video
  documents are recognized by MIME type or filename extension. Images, text,
  standalone audio and other documents are excluded.
- Scroll near the end for another page; failures expose a retry. Sparse document
  pages retain their server cursor instead of incorrectly marking the channel done.
- Search queries Telegram within selected channels/groups, including files not yet
  cached. Each batch makes at most four requests, rotating across sources/filters.
  Results merge with cached title/filename matches. Telegram's tokenization rules
  apply; arbitrary substring matching across all remote filenames is not guaranteed.
- Local metadata and thumbnail identities are cached so browsing can reuse them.
  Channels are not fully downloaded or indexed all at once.

## Watch with VLC

- **Immediate tap feedback:** Loading media… appears with the filename, an animated
  indicator and Cancel. Duplicate taps do not start additional players. The player
  then shows Opening media… while VLC opens the media. Opening no longer waits
  for a redundant file request or the library snapshot refresh.
- **Controls:** down arrow closes back to the library/search; bottom play/pause,
  ±10-second buttons, double-tap seeking, orange timeline and elapsed/remaining time.
  Controls hide during playback and can be locked/unlocked.
- **Tracks:** separate Audio/Subtitles tabs, embedded track selection, subtitle Off,
  external audio/subtitle files, and audio/subtitle delay in 0.25-second steps
  within ±30 seconds. External subtitles include SRT, ASS/SSA, VTT, SUB/IDX and
  other VLC-supported types; actual decoding depends on the file and framework.
- **Subtitle size:** Smallest, Small, Normal, Large and Largest, saved for future
  videos. Text renderer changes may not affect bitmap or authored styled subtitles.
- **Speed:** 0.25×, 0.5×, 0.75×, 1×, 1.25×, 1.5×, 1.75×, 2×, 3× and 4×.
- **Picture:** Fit preserves the camera/home-indicator safe areas; Fill crops to the
  viewport. Aspect overrides: 16:9, 4:3 and 21:9. Screen edges are opt-in.
- **Manual rotation:** one tap switches locked portrait/landscape; moving the phone
  does not rotate the player. iPhone/iPad supported; iPad currently runs fullscreen.
- **Buffering:** later spinner display depends on playback time actually stalling
  for two seconds, rather than VLC's buffering flag alone.
- **History:** progress saves periodically and on pause/close. Continue Watching,
  start from beginning, mark watched and remove history are available.
- **Return to app:** backgrounding pauses and retains the player while the process
  remains alive; returning resumes previously playing media. Force-quit or iOS
  termination starts fresh navigation but retains stored login/library/history.
  Background streaming/download survival is not guaranteed.

This is an embedded VLC player with Cinespice controls. The entire VLC application
and every one of its options are not included. See the [VLC inspection and option
map](docs/VLC-PLAYER.md) for implemented controls and compatibility details.

### Telegram streaming and downloads

TDLib provides file ranges, not a public playable URL. A local HTTP range bridge
feeds VLC, using 1 MB reads and bounded 8 MB read-ahead. Sequential reads reuse an
active download window; seeking can retarget it. Per-file reads are serialized to
respect TDLib's single active download range.

Use **Download first** if streaming fails or seeking is unreliable. Full downloads
use priority 32, TDLib's maximum documented scheduling priority; this does not
promise faster bandwidth. During a full download, playback waits for completion.
Completed videos play locally and can be exported with **Save to Files…**, retaining
the original filename. Download cancellation is available from the video's menu. The Downloads tab shows
full downloads from selected sources. Incomplete queues are not restored by this
update after process termination; retry the download when needed.
Format/codec, audio and subtitle support depends on VLC and the particular file;
not every possible file is guaranteed playable.

## Storage and privacy

- Thumbnail cache: 40 MB disk budget, 32 MB decoded-image memory budget. Artwork can
  load without downloading an entire video.
- Settings can clear downloaded media, artwork or watch history separately, and
  sign out of Telegram. Cache cleanup does not delete Telegram cloud messages.
- Library metadata, cursors, selections and history persist locally. Subtitle size
  is a local app preference. External track copies are temporary for playback.
- No private credentials, API secrets or Telegram session data belong in Git.

## Build and install

Requires macOS and full Xcode. Minimum deployment target: **iOS 16**. The verified
historical runner used Xcode 16.4 / iOS 18.5 SDK; iOS 27-specific SDK behavior and
appearance are not verified by Linux checks.

The existing workflow resolves pinned TDLibFramework `1.8.68-c15d3f5a` and
MobileVLCKit `3.7.4`, runs the core tests, builds and packages an unsigned IPA.
It checks the built plist's name, bundle ID and version before packaging.

1. Publish the desired commit, then run **Actions → Build Cinespice IPA** for that
   branch. Inspect **Cinespice-build-diagnostics** if it fails.
2. Select a successful run for that exact commit. Download and unzip the
   **Cinespice-unsigned-ipa** artifact to obtain `Cinespice-unsigned.ipa`.
3. Sign/install with your configured SideStore or LiveContainer workflow. The
   unsigned IPA cannot install directly; the signing tool's requirements apply.

LiveContainer's “Unknown - app.tevee.ios” label means a missing version, not author
metadata. Explicit version fields plus the packaging check address it in new builds.
Install the updated IPA to verify the Cinespice name/icon and version display.

### Local macOS build

```bash
brew install xcodegen cocoapods
xcodegen generate --spec project.yml
xcodebuild -resolvePackageDependencies -project Tevee.xcodeproj -scheme Tevee
pod install --project-directory=.
xcodebuild \
  -workspace Tevee.xcworkspace \
  -scheme Tevee \
  -configuration Release \
  -sdk iphoneos \
  -destination 'generic/platform=iOS' \
  -derivedDataPath build/DerivedData \
  CODE_SIGNING_ALLOWED=NO \
  build
python3 scripts/verify-app-metadata.py build/DerivedData/Build/Products/Release-iphoneos/Tevee.app/Info.plist
```

Use `Tevee.xcworkspace` after installing pods. Internal target/scheme names remain
Tevee. Commit dependency lockfiles from a successful macOS build for full resolution
reproducibility. Historical failure explanations: [Build history](docs/BUILD-HISTORY.md).

## Validation and remaining device checks

Run `swift test` for the portable core (Linux also needs SQLite headers/library).
It covers synthetic HTTP ranges, range serialization, video decoding, discovery,
search/paging, persistence/account boundaries, thumbnail budgets, spinner behavior
saved-list schema upgrades, release sorting, completed-download metadata,
and subtitle size values. It does not build SwiftUI/UIKit/Network/MobileVLCKit.

For this update, verify a single play tap, cancellation followed by another tap,
closing during opening/playback/landscape, live subtitle resizing and retained size,
external tracks, resume and small/large device layouts in the signed app. Measure
startup time and throughput before claiming a performance gain. Real network loss,
large collections, cache cleanup and seek tests remain part of the
[device protocol](docs/INTEGRATION.md#real-iphone-verification-protocol).

## Next updates and upstream research

Catalogue matching, grouped quality versions, TV seasons/episodes and further
player ideas are recorded in [nextupdare.md](nextupdare.md) for later discussion.

- [VLC player inspection](docs/VLC-PLAYER.md): exact upstream revisions, UI/option
  inventory and guarded subtitle renderer compatibility.
- [Integration audit](docs/INTEGRATION.md): Telegram/TDLib reader strategy,
  TeleSpeed's Android-only private hooks, and Nuvio's HTTP/torrent architecture.
- The app icon vector is `Design/AppIcon.svg`; committed PNGs need no build tool.
  Regenerate with `python3 scripts/generate-app-icon.py` using FFmpeg/librsvg.

## Licenses

Review notices and obligations for TDLibFramework/TDLib and MobileVLCKit. Telegram,
VLC-iOS, TeleSpeed and Nuvio source checkouts were inspected as references; their
full applications or plugin systems are not copied into Cinespice.

## Build editions and updated library

See [Build editions](docs/BUILD-EDITIONS.md) for the configured edition (phone login without API entry), standard edition, one-time GitHub secret setup, channel pagination and optional Wikidata lookup. Generate the build resource before XcodeGen/Gradle.
