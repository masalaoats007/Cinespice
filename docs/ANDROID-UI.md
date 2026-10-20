# Cinespice Android UI implementation guide

The Android implementation lives in `android/` (Kotlin/Compose + existing Java repositories and libVLC).
See [build instructions and validation limits](../android/README.md).
Use [the iOS UI specification](UI-README.md) as the behavior/data contract. Reuse
its exact four palette hex values, saved-list rules, sorting and seven hero roles.

## Translate layout, not platform effects

Use Jetpack Compose + Material 3. **No Liquid Glass or blur**. Use opaque palette
surfaces for the compact top bar, navigation bar, menus and sheets, with subtle
1dp outlines and at most 2dp tonal elevation. Keep player panels opaque dark.
Use 16–24dp content margins, 16–18dp poster/hero corner radii, and 48dp minimum
touch targets. Honor status/navigation bars, display cutouts and font scaling.

- Compact top app bar: CINESPICE (refresh), Favorites/Watchlist, Channels and Profile.
  Channel screens show Back, channel name, channel sorting and scoped search.
- Bottom navigation: Home, Search, Downloads. Use a navigation rail on larger
  windows; hide both forms of global navigation inside a channel.
- Adaptive poster grid: about 140dp minimum card width, 16dp gaps, centered content
  capped around 1200dp. Continue Watching uses a horizontal landscape-card rail.
- Seven-position `HorizontalPager`, dots and accessible page descriptions. No
  autoplay or fake trending data. Preserve explicit provider placeholders.
- Use standard Android text/fonts with the same hierarchy: compact bold wordmark,
  24sp page headings, 13–14sp card titles, subdued 11–13sp metadata. Support user font scale.

## State and interactions

Each destination/collection owns a separate scroll state. The current iOS behavior
starts a destination at the top when switching, while closing playback preserves
the underlying screen. Never reuse one LazyListState across all tabs. Use scoped
ViewModels/SavedStateHandle so configuration changes do not lose the query or
active screen. Keep account/library/history in durable local storage.

Only Channels opens source selection. Display owner-first channels/groups with a selection control
and separate browse arrow on selected rows. Discover gradually;
loading source metadata must not trigger downloads from every channel. Closing
the picker loads one initial page for each selected source. Home shows a row of
up to ten files for each selected channel. The channel name/arrow opens its
page, with channel-scoped search and a persisted account/channel sort preference
that also controls the home row. Search remains universal across selected
sources.

Favorites has Favorites/Watchlist segmented tabs. Long press a file to access save/remove,
play/resume, start over, download/cancel, export and history actions. Use scoped
message IDs and an account-specific Room database; list membership is independent
of caches and watch history. Deselecting a source hides, not deletes, saved items.

Search is universal across all selected sources and starts empty. Debounce remote work, query only selected sources, merge by
message ID and retain server paging cursors. Show pending/error/retry feedback.
Channel pages show 30 loaded files at a time with page numbers and previous/next
buttons. Load more indexes additional files and exposes more page numbers. The Search tab continues to search all selected sources.
The Channels picker searches saved metadata as the user types, then merges
matching Telegram server and local chat results; it must filter to groups/channels
before limiting visible matches. Show search progress and errors inside the picker.
Hide the keyboard on outside taps and navigation without consuming the underlying
button action; preserve focus within text controls. Let the sheet/window own IME
insets and do not apply duplicate `imePadding` to both the app content and sheet.
Use native IME/search actions.

Downloads show actual pending/completed state, never label a sparse stream cache
as a full download. Export via Android Storage Access Framework using the original
filename. Do not claim iOS background-transfer behavior applies to Android;
design Android lifecycle/foreground-service requirements separately.

Continue Watching shows up to five resumable files; older resume positions remain stored.
Cards share Play and Info buttons. Details show Back and saved-list controls,
with an optional user-triggered Wikidata lookup for suggested descriptions.
Profile holds real account identity, palette, storage cleanup, sign-out, the
creator's social links and a visibly unavailable Request feature placeholder.

## Playback

Use the pinned Android libVLC APIs; do not port Swift/Objective-C runtime selectors.
Keep the picture visible under controls: transparent full-screen hit layer with
compact top/bottom black gradients, rather than a dim background over the full
video. Keep player labels and option rows compact while preserving 48dp targets.
Recreate the custom black player,
orange timeline, ±10 seconds, play/pause, tracks, subtitle size/delay, speed, aspect,
lock and explicit orientation toggle. Fit respects cutouts; edge-to-edge is opt-in.

Options are small dark panels: bottom in portrait, bottom-side in landscape, with
independent scrolling and a visible close button. Avoid large native modal sheets
for every setting. Track selected values and acknowledge taps immediately. Match
actual subtitle/codec capability rather than promising every format.

## Android acceptance gates

Test phone/tablet/foldable windows, gesture and three-button navigation, cutouts,
TalkBack, largest font, both orientations and all palettes. Verify tap targets,
scroll separation, keyboard dismissal, process recreation, account isolation,
network interruption, local export and real VLC playback. Compare screenshots
against the **verified** iOS build once available; this guide is not a substitute
for native validation on either platform.
