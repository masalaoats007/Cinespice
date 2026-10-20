# Cinespice UI specification

Implementation: October 2026 redesign. This describes the current source, not a
claim that the new screens passed native compilation or physical-device testing.
See [Android translation](ANDROID-UI.md) and [device checks](TOUCH-CHECKLIST.md).

## Navigation and space

- Header: CINESPICE refresh button, Favorites/Watchlist, Channels and Profile.
  A channel page has Back, the channel name, sorting and channel-only search.
- Footer: **Home / Search / Downloads**; hidden inside a channel. Three equal cells;
  48-point minimum targets, 3-point vertical padding. Header controls remain at
  least 44 points. Safe-area space is retained for the camera and home indicator.
- Content uses 20–24-point margins, adaptive grids (140-point minimum cards),
  a 1,200-point maximum content width, and 16–20-point section gaps.
- Scroll identity includes account, destination, collection and saved-list choice.
  Switching destinations starts at the top. Returning from a player preserves the
  underlying destination and scroll state. No shared offset between tabs.
- Outside taps dismiss the keyboard without cancelling button touches. Text
  inputs retain focus, and library/picker scrolling dismisses interactively.
- Cinespice refreshes an open collection or active search. Elsewhere it queues one
  newest page per selected source through the existing serial loader. It does not
  erase cached metadata, lists, progress or older-page cursors. Feedback appears
  below the header; errors remain visible. Refreshing does not crawl all history.

## Pages

| Page | Content and behavior |
| --- | --- |
| Home | Seven-slot swipe hero, Continue Watching (up to five), then one horizontal rail of up to ten files per selected channel; each channel name/arrow opens its page. |
| Favorites | Favorites and Watchlist segmented choices; empty guidance; sorting |
| Search | Universal input across all selected channels and groups. Empty guidance initially; only nonempty queries show cached and remote results; paging remains bounded |
| Downloads | Preparing, active and completed full downloads from selected sources; video menu exposes cancel/export; streaming fragments are excluded |
| Collection | One selected channel's files, scoped search, account/channel-specific persisted sorting and numbered pages of 30 loaded files; Load more adds available pages |
| Profile | Real account display name/username, palette, request-feature placeholder, storage/history cleanup, creator links, sign-out |

The source sheet has **My channels / Selected / All sources** scopes and shows
eligible channels/groups, owner-first, oldest available Telegram date first.
Ownership/date are not guessed. Typing searches
saved source metadata and Telegram's local/server chat search; resolve enough IDs
to find up to 30 actual groups/channels before applying the result cap, since
Telegram may mix people and bots into an unfiltered result page. Show progress,
empty and retry/error states in the sheet. Tap a row to select or deselect;
selected rows gain an independent arrow to browse. Scanning while visible is
gradual, 30 sources at a time. Closing the picker loads the first page for each
selected source; tapping a channel arrow opens its full collection page. A
collection page requests at most 30 video and 30 document messages per page.

### Saved lists and downloads

Long press a file to open its options. Cards have explicit Play and Info buttons.
File details have Back, Favorites/Watchlist controls and optional Wikidata lookup.
The context menu adds/removes **Favorites** or **Watchlist**, with saving feedback
and a heart/bookmark on the card. SQLite schema 3 stores membership by scoped
message ID (`chatID:messageID`), independently of history/cache state. A saved
remote search result gets metadata persisted with its membership. Deselecting a
source hides its items without deleting membership. Each Telegram account has its
own database. Custom named lists are a future extension, not implemented here.

Download is available on every file context menu. Unknown-size files offer a
Download action before playback; streaming errors also offer local playback via download.
Downloads show preparing/downloading/cancelling states. Completed state is saved
for later launches. Export uses the original filename through the iOS Files picker.
An active download queue is not persisted/reconciled across process termination;
retry incomplete downloads after restart. TDLib may evict a cached file; playback
always refreshes message/file state instead of blindly trusting stored paths.

### Sorting and metadata truthfulness

Default: **Latest releases**. Other choices: Earliest releases, Title A–Z, Title
Z–A, Recently added, Oldest uploads. Channels persist their own account-scoped
preference, used by both the channel page and its home rail. Lists, downloads and
search keep the global preference. Continue Watching stays chronological.

Wikidata lookup shows optional suggestions and does not overwrite file identity
or sort metadata. Release sorting uses **filename year
hints**, with the displayed title as fallback only when there is no filename.
A standalone year from 1880–2099 is accepted; ambiguous multiple years and
embedded numbers such as `12024p` or `S02E2024` are rejected. These hints can be
wrong (e.g. a film named “1917”) and are labeled as filename years. Unknown years
always follow known years in either direction. Ties use upload date, chat ID and
message ID. Upload time is never represented as release time. Sorting applies to
loaded results; fetching all Telegram history merely to sort is not performed.

### Seven hero positions

1. Most recently added cached video (Telegram upload time).
2. Daily pseudo-random pick, deterministic for a UTC day and unchanged cached
   library; recomputed on foregrounding. Adding/removing cached titles may change it.
3–6. Four explicit **catalogue not connected** placeholders, not fabricated trends.
7. Representative result from the last completed nonempty search in this app
   session; hidden if its source is deselected. It is not persisted search history.

No OMDb/TMDb/trending connection, catalogue posters or quality grouping is implied.
Slots may show the same video in a small library. No automatic carousel motion.

## Color and materials

| Palette | Mode | Background | Surface | Accent |
| --- | --- | --- | --- | --- |
| Ember | Dark | `#101014` | `#202026` | `#FF774A` |
| Midnight | Dark | `#0D1424` | `#1C2940` | `#91ADFF` |
| Pearl | Light | `#F8F4EF` | `#E9E2DA` | `#A63D25` |
| Sage | Light | `#F1F6F2` | `#DFEAE2` | `#226347` |

Dark text tokens: primary 96% white, secondary 69% white. Light text tokens:
primary 9% white, secondary 36% white. Artwork overlays always use white text on
dark gradients, regardless of palette. Persist preference under `cinespice.theme`.
System lists retain native row treatment; their surrounding background is themed.

Header/footer/search and the opening overlay use supported SwiftUI materials.
This is a glass-inspired design, **not verified native iOS 27 Liquid Glass**.
Minimum deployment remains iOS 16; last verified historical SDK was iOS 18.5 with
Xcode 16.4. No speculative SDK APIs or dependency updates were introduced. Native
rendering, contrast, Reduce Transparency and large text require device checks.

## Player

Keep the video black-backed in every palette. Preserve manual one-tap orientation,
camera-safe Fit, timeline, ±10 seconds, play/pause, tracks, speed, picture and lock.
Options use a dark `#141414` panel with an orange outline, not a full system sheet.
Portrait panel width is capped at 420 points; landscape at 340, bottom trailing.
The scrollable options area is capped at 32% of available portrait height or 60%
of landscape height, plus a compact title/close row and padding. Long option lists
scroll inside the panel. Outside tap or X dismisses; no full-screen dim layer.

All existing subtitle sizes, track selection/import, delays, picture ratios and
speed settings remain. Player controls still cover part of the picture while
visible. Exact VLC application UI/feature parity is not claimed. Visible branding
is Cinespice; creator credit is “Created by Masalaots · @notmasalaoats”. Profile
links use camera/paperplane symbols for Instagram/Telegram (not copied brand art).
Dependency/source/licensing documentation remains in the repository.

## Acceptance before release

Run the new native build, then the [device checklist](TOUCH-CHECKLIST.md) on a
notched iPhone, small iPhone and iPad, portrait and locked landscape. Verify all
four palettes, keyboard interactions, independent scrolling, upgrade persistence,
completed download restart/export, seven hero slots and player options. Portable
unit tests do not establish visual correctness or iOS touch behavior.
