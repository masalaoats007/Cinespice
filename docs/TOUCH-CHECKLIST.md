# Touch and button audit

## Implemented fixes

- Custom buttons have at least a 44 × 44 point target, a rectangular hit shape and
  an immediate pressed highlight. They do not shrink or move when pressed.
  Disabled controls are visibly dimmed. Native list rows, menus, alerts, pickers
  and navigation bars retain system handling.
- Header/back/search/clear/overflow controls use explicit targets. The Cancel and
  timing ±/Reset targets include the padding inside their button labels.
- Clipped artwork cannot intercept touches beyond its displayed bounds. Decorative
  artwork, gradients, borders and pagination/background geometry do not receive taps.
- Video single/double-tap gestures occupy the video area between visible controls,
  or the full surface when controls are hidden. They are disabled under menus/lock.
  Single and double taps are mutually exclusive, so seeking does not also hide controls.
- Player auto-hide restarts after interactions and waits while a control is pressed,
  the seek slider is being dragged, a menu is open or a file is being imported.
- Play/pause follows the user's command immediately rather than waiting for VLC's
  next state poll; track selection immediately highlights the requested track.
  Playback/seek/rotation commands give brief visible feedback. They are command
  acknowledgements, not a claim that network playback has already recovered.
- Downloads, cancellation, sign-in, export and history changes expose busy state.
  Duplicate download/cancel/auth/history/collection-selection submissions are guarded.
  Errors clear pending state and remain visible. Reset sign-in ignores an old reply.
- Source scanning keeps a consistent busy indicator between batches; retry scanning
  is attached to the picker lifecycle. Search bars keep their height when Clear appears.

## What has been checked

Source inspection covers every Button/Menu/Link and the gesture layers in the
library, collection picker, settings, details, opening overlay and VLC player.
Portable integration fixtures exercise command state and request duplication;
Swift grammar checks cover edited views. This is not a device UI test. Apple
SDKs, native controls, physical hit testing and VLC rendering cannot be exercised
in the Linux workspace. No statement that every button works on iPhone is made.

## Signed iPhone/iPad checklist

For each visible control, tap the centre and near each edge inside its displayed
target. Then tap just outside it. Only the intended target should activate. Test
portrait, manually locked landscape, camera safe areas, an iPad and larger text.

| Area | Controls / expected result |
| --- | --- |
| Sign-in | Connect; Continue for phone/code/password/email; Back to sign in. Immediate busy state, one request, useful error/retry |
| Library header | Back returns to library; Cinespice refreshes with feedback; the single Channels icon opens picker; Profile opens settings |
| Home | Seven hero slots swipe; unavailable trends say so; hero Play/Resume starts one opening attempt; More info opens details |
| Tabs | Floating bottom bar shows Home/Search/Favorites/Downloads in that order; each entire cell activates its page; selected tab updates; no Favorites header button; hidden inside a channel |
| Search | Clear button works from its whole target; typing does not shift the row height; Retry/More results show progress; tapping a result opens the intended file |
| Posters | Play target matches the clipped image; adjacent posters and their menus do not steal taps; scrolling does not play a file |
| Video menu | Play/resume, start over, download/cancel, Save to Files, mark watched and remove history act on that video; async work is visible |
| Opening/export | Cancel works across the entire label; cancelling then selecting another file cannot cause old playback; export shows preparation before Files picker |
| Source picker | Clear search, owner-first selection row, separate browse arrow, Show more/Continue finding sources and Done; selection busy/checked state is visible; dismissal ends scanning |
| Settings/details | Done, four palettes, profile identity, each clear action and confirmation/cancel, sign-out, creator links; Request feature clearly unavailable |
| Player navigation | Down arrow closes during opening, buffering, playback, pause and landscape; controls remain notch-safe |
| Transport | Play/pause changes symbol immediately; ±10 buttons and seek slider work when enabled; video double tap seeks without also hiding controls |
| Player tools | Speed, Tracks, Picture, Rotate and More open/change the intended setting; full padded button targets work; selected values update |
| Tracks | Audio/Subtitles tabs, each track, Off, external file import/cancel and timing ±/Reset; track changes highlight immediately |
| Subtitle size | All five sizes, Back to subtitles, close options; selection remains after reopening; bitmap/styled subtitles may keep authored size |
| Lock/menus | Lock → Unlock; menu close and outside dismissal; taps on menu rows cannot seek/hide video; hidden controls return on a video tap |
| Auto-hide | Hold a button, drag the slider, scroll a menu, import a file; controls remain reachable and hide only after interaction ends |
| System picker | Files export/import confirm and cancel return cleanly; errors are shown |

Repeat with VoiceOver/Switch Control, slow network, interrupted downloads and rapid
taps. Record the installed commit and any failing control, orientation and screen.

## Redesign acceptance (not yet exercised on a device)

- Scroll a large collection to the bottom. Switch to History: top visible, at most
  five entries. Repeat with Favorites/Watchlist and every tab; return from player
  without losing the underlying scroll position.
- Search is empty before typing. Clear the query: no cached results remain.
  Tap outside the keyboard, then tap a button: keyboard closes and action fires once.
- Favorite and watchlist a remote search result; restart and confirm both. Deselect
  and reselect its channel; items return. Switch accounts: lists remain isolated.
- Upgrade a schema2 library with history/cursors; verify all still available.
- Use all four palettes on every page/sheet. Test large text, VoiceOver and Reduce
  Transparency. Confirm light palette labels and artwork overlays remain readable.
- Check sort order for known/unknown/ambiguous years and A–Z/Z–A. Sort only loaded
  results; no claim of complete chronological order across unindexed messages.
- Complete a download, relaunch, open Downloads, export original filename; clear
  local media and confirm list memberships/history remain. Retry interrupted work.
- In player, open each options panel in both orientations: X/outside dismissal,
  track imports, subtitle sizes, scrolling and selected states. No tap-through seek.
- Check seven hero pages, foreground after a UTC day change, last-search result,
  deselected source removal, and truthful catalogue placeholders.
