# New home experience: clickable prototypes (#186)

Step 1 of the experiment in #186: the app feels too branched, so two
alternative home screens are prototyped as clickable HTML before anything is
built. **No app code changes**; main and the installed app stay as they are
until the owner chooses.

| File | What it is |
|---|---|
| [`index.html`](index.html) | Links to both prototypes |
| [`a-board.html`](a-board.html) | **Prototype A (recommended):** a customisable 2D home board |
| [`b-globe.html`](b-globe.html) | **Prototype B:** a 3D globe with one region per feature |

## How to open

Each prototype is **one self-contained file**: inline CSS and JavaScript, no
libraries, no web fonts, no network requests (system font stack only). Copy a
file to the phone and open it in Chrome, or open it on the computer in Chrome
with device emulation at 412 × 915. All content is invented placeholder data
("Course A", "Project X"); nothing is sent anywhere.

Both follow the approved **P2** look of [`../mockups/`](../mockups/): the D3
base tokens, the seven soft tones (each course and project keeps one tone and
one line icon), grey section cards with a faint watermark icon and a heading
icon in the section's tone, and the gradient ring on featured elements (the
coach, the command bar). Light and dark follow `prefers-color-scheme`; the
half-circle button in the top bar switches for the session. The language
follows the browser (Turkish if it starts with `tr`, otherwise English); the
`TR` / `EN` button switches. Neither choice is stored.

## Shared: the command bar

The bar at the bottom (text field and microphone, inside the gradient ring) is
the primary way to do things in both prototypes, and it behaves identically in
both so the comparison is fair. It mocks #185 with simple keyword rules, both
languages:

| Try | Result |
|---|---|
| "Remind me to call Project X tomorrow at 9:00" | Task added; confirmation with **Open** and **Undo** |
| "Note: buy printer paper" | Note saved; **Undo** |
| "Move the meeting to 15:00" | One short question in the bar: *Which meeting?* with two choices, then a confirmation with **Undo** |
| "Open courses" | Opens that page (same transition as tapping it) |
| anything else | "Sent to the assistant", with **Open** (Chats) and **Undo** |
| Microphone | A mocked listening state (level bars), then a sample phrase is "heard" and run |

Focusing the empty field shows suggestion chips with these examples. A
confirmation stays 10 s, longer while it has focus or the pointer.

A page opened from a tile or a region closes with the back button in its
header, **the system back gesture** (a history entry is pushed), or Escape.

## Prototype A: home board

- **Board:** a 4-column grid of tiles (coach, today's tasks, agenda, courses,
  sleep and activity, notes, chats; sessions and projects start in the
  hidden tray). Sizes **1×1, 2×1, 2×2, 4×2**; each tile shows more content as
  it grows (a 1×1 shows one number, a 2×2 a short list or a small chart).
- **Open:** tapping a tile **grows the tile into its page** (container
  transform: the tile's bounds expand to the full screen, the content fades
  in) and back again into the tile.
- **Edit:** a long-press (450 ms) on a tile, or the grid button in the top
  bar, enters edit mode. Tiles get a dashed outline and a drag handle (no
  jiggle). Drag a tile to reorder it; the others slide out of the way. An edit
  sheet at the bottom shows the selected tile with **Move earlier / Move
  later**, the **size** choice, **Hide**, the **hidden tiles** tray (tap to
  add back), **Reset** and **Done**. Keyboard: arrow keys move the focused
  tile, Delete hides it, Escape leaves edit mode.
- **Stored:** the layout is saved in `localStorage` under
  `lifeos.proto.board.v1` (validated on load; unknown or missing tiles fall
  back to the default).

## Prototype B: feature globe

- **Globe:** nine regions shaped like countries (a warped spherical Voronoi
  map), one per feature, in the P2 tones; the coach region carries the
  gradient. Region names sit on page-coloured labels that face the viewer;
  labels near the edge fade out and stop taking taps.
- **Turn:** drag in any direction; a flick keeps turning and slows down
  (inertia). The tilt is limited so labels stay upright.
- **Open:** tap a region (its label or its area). The globe turns the region
  to the front, then the page opens as a circle growing from it; closing
  shrinks it back into the region.
- **List view:** the list button in the top bar replaces the globe with a
  plain list of all regions (name and a one-line summary).
- **Drawing:** canvas 2D, orthographic projection, no libraries. Normals and
  lighting are precomputed per size; regions are found on a coarse grid
  (every 6 pixels) and only blocks on a border are computed per pixel. The
  device pixel ratio is capped at 1.5 and frames are drawn only while
  something moves.

## Material Design 3 and accessibility

✅ meets · ◐ partly · ✗ does not

### Prototype A: home board

| Guidance | | Notes |
|---|---|---|
| Few primary destinations | ◐ | There is no navigation bar: the board is the single hub and the command bar the main action, so there is one level instead of tab → More → screen. But the owner decides how many tiles there are; with all nine shown the board is a launcher with nine entries, more than the 3–5 M3 recommends for a bar. It also departs on purpose from the approved bottom bar (Today · Chat · Tasks · More) and from "Today: three cards, nothing else". |
| Touch targets ≥ 48 dp | ✅ | Checked in every state (home, chips, question, confirmation, edit sheet, page; EN and TR; 412 and 360 px wide): every control is at least 48 × 48. The smallest tile (1×1) is 74 px at 360 px wide. |
| One-hand reach | ✅ ◐ | The command bar, the edit sheet (move, size, hide, tray, done) and the confirmation sit at the bottom. The top bar (edit, theme, language) and a page's back button are at the top; the back gesture covers the latter. |
| Reduced motion | ✅ | With `prefers-reduced-motion: reduce` the container transform becomes an instant swap, tiles jump instead of sliding, the sheet and the bar's level bars do not animate. |
| Screen reader | ✅ ◐ | The board is a list; each tile is one button named "*title*. *summary*" (no nested controls). In edit mode tiles report selection (`aria-pressed`), size and position, and every move, resize, hide and show is announced in a live region. The page is a labelled region; the board behind it is `inert`. ◐ The grid packs densely, so the visual order can differ from the reading order when sizes are mixed. |
| Keyboard and switch access | ✅ | Everything is reachable with Tab; dragging always has a button alternative (WCAG 2.5.7): Move earlier / later, size radio group (arrow keys), Hide, the tray, and the Edit button instead of the long-press. Focus returns to the tile after a page closes. |
| Contrast | ✅ | P2 tokens and pairs from the mockups' README (text ≥ 4.5:1 on every tint, light and dark). The selected-tile outline is the primary blue (≥ 3:1). Dashed outlines and watermarks are decorative. |
| Smoothness | ✅ | Transitions animate `clip-path`, `opacity` and `transform` only, for one element at a time. Not measured on a real phone. |
| Motion pattern | ✅ | M3 container transform with emphasized easing (enter 420 ms, exit 260 ms). |

### Prototype B: feature globe

| Guidance | | Notes |
|---|---|---|
| Few primary destinations | ✗ | Nine regions, and at any moment only four or five face the viewer: **about half the destinations are hidden** and need a turn to find. There is no M3 pattern for this; discoverability and muscle memory are weaker than with fixed positions. |
| Touch targets ≥ 48 dp | ✅ | Region labels are buttons of at least 56 × 56 that shrink only visually toward the edge; near the edge they fade out and stop taking taps instead of overlapping. All other controls pass the same audit as A. |
| One-hand reach | ◐ | The globe sits mid-screen, so turning and tapping work with a thumb, but the upper regions are a stretch on a tall phone, and the list button is top right. |
| Reduced motion | ✅ | No inertia (the globe stops when the finger lifts), turning to a region is instant, and the circular reveal becomes an instant swap. Dragging still turns the globe, because the user moves it directly. |
| Screen reader | ✅ ◐ | The canvas is hidden from assistive technology. Every region is a real button named "*title*. *summary*", in a labelled group with instructions; the **list view** is the full alternative. ◐ The spatial idea of the globe itself does not come across. |
| Keyboard and switch access | ✅ | Tab moves through all nine regions and turns each one to the front when focused (also the hidden ones); arrow keys turn the globe; Enter opens; the list view needs no turning at all. Dragging always has an alternative (WCAG 2.5.7). |
| Contrast | ✅ ◐ | Label text sits on page-coloured pills (≥ 4.5:1). Region fills are decorative and are separated by page-coloured borders; a region is identified by its icon and name, never by colour alone. ◐ In dark mode the shaded fills are close to each other in lightness. |
| Smoothness | ✅ ◐ | Median 7.2 ms per frame under 4× CPU throttling with a 2.625 device pixel ratio emulated (capped to 1.5, about 223 000 globe pixels), on a desktop. Under one 60 Hz frame, but not measured on a mid-range phone. |
| Motion pattern | ◐ | The circular reveal and the turn-to-front are custom; M3 has no globe pattern. Easing and durations follow M3's emphasized tokens. |

**Recommendation: A.** It keeps every feature one tap away in a place the
owner chose, works fully without gestures, and maps to standard Compose
building blocks (lazy grid, drag and drop, shared-element transition). B
looks distinctive but hides half of the destinations at any moment and needs
a custom renderer in the app.

## How the prototypes were checked

With Playwright in Chrome at 412 × 915, served from a loopback address (the
browser tool refuses `file://` URLs):

- main flows of both, clicked through, with no console errors or warnings;
- the command bar: task, note, the clarifying question, open, chat
  fallback, Undo, mocked voice;
- A: long-press, drag to reorder and edit-mode drag with emulated **touch**
  events (not mouse); a vertical swipe scrolls without entering edit mode;
  a tap opens; the layout survives a reload; Reset;
- B: touch drag; inertia with synthetic pointer events; tap on the canvas
  and on a label; Tab focus turning a back region to the front; list view;
  Escape and back;
- touch-target audit (≥ 48 × 48) in every state, EN and TR, at 412 and
  360 px, light and dark, with no horizontal overflow;
- `prefers-reduced-motion: reduce`: no transitions, no inertia;
- network: only the HTML file itself is requested.

## Known rough edges

- Voice is mocked (no speech recognition), and the interpreter is a few
  keyword rules, not the model routing of #185.
- Pages are static apart from ticking tasks; rows with a chevron open
  nothing.
- The soft keyboard (`interactive-widget=resizes-content`) is untested: the
  browser tool shows no on-screen keyboard.
- Touch was emulated; long-press, drag and inertia have not been tried on a
  real phone.
- A: a 1×1 tile has little room, so very large system font sizes clip its
  number and label.
- B: the coach gradient is shaded per 6-pixel block, so faint steps are
  visible up close; adding a feature means placing a new region centre by
  hand.
- The connection dot in the top bar is colour only on screen (it has a
  label for screen readers).
- The shared parts (tokens, strings, data, pages, command bar) are
  duplicated in both files on purpose, so each file stays self-contained;
  change both when editing them.
