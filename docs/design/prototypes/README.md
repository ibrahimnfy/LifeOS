# New home experience: clickable prototypes (#186)

Step 1 of the experiment in #186: the app feels too branched, so alternative
home screens are prototyped as clickable HTML before anything is built. **No
app code changes**; main and the installed app stay as they are until the
owner chooses.

After seeing A and B, the owner kept the board (A) as one option, asked for a
different tile model (C) and a different globe (D), and welcomed a sci-fi look
(D, E). B stays for the record.

| File | What it is |
|---|---|
| [`index.html`](index.html) | Links to all prototypes |
| [`a-board.html`](a-board.html) | **A:** a customisable 2D home board (widget-style grid) |
| [`b-globe.html`](b-globe.html) | **B:** a 3D globe with one country-like region per feature (superseded by D) |
| [`c-physics.html`](c-physics.html) | **C:** physics tiles on a free field: fling, bounce, pinch to resize |
| [`d-holo-globe.html`](d-holo-globe.html) | **D:** a see-through holographic globe with one glowing pin per feature |
| [`e-orbit.html`](e-orbit.html) | **E:** an orbital HUD: the coach as the core, features on two spinning orbits |

## How to open

Each prototype is **one self-contained file**: inline CSS and JavaScript, no
libraries, no web fonts, no network requests (system font stack only). Copy a
file to the phone and open it in Chrome, or open it on the computer in Chrome
with device emulation at 412 × 915. All content is invented placeholder data
("Course A", "Project X"); nothing is sent anywhere.

All follow the approved **P2** look of [`../mockups/`](../mockups/): the D3
base tokens, the seven soft tones (each course and project keeps one tone and
one line icon), grey section cards with a faint watermark icon and a heading
icon in the section's tone, and the gradient ring on featured elements (the
coach, the command bar). C, D and E add a sci-fi layer on top (a faint grid
with scan lines, HUD captions in the system monospace font, glows in each
feature's P2 ink colour); text always sits on an opaque or near-opaque glass
surface, and the feature pages they open are plain P2. Light and dark follow `prefers-color-scheme`; the
half-circle button in the top bar switches for the session. The language
follows the browser (Turkish if it starts with `tr`, otherwise English); the
`TR` / `EN` button switches. Neither choice is stored.

## Shared: the command bar

The bar at the bottom (text field and microphone, inside the gradient ring) is
the primary way to do things in every prototype, and it behaves identically in
all of them so the comparison is fair. It mocks #185 with simple keyword rules, both
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

A page opened from a tile, region, pin or node closes with the back button in its
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

## Prototype C: physics tiles

- **Field:** nine tiles float freely on a grid-lined field (no grid layout).
  Each tile shows the feature's icon, name and one live line ("4 tasks left
  today") and glows in its P2 tone.
- **Throw:** drag a tile and let go: it flies on with the release speed,
  slows down with friction and bounces off the edges of the field (with a
  short flash), like the old bouncing logo. Moving tiles knock into each
  other softly; tiles at rest may overlap, and the last one touched is on
  top.
- **Resize:** pinch a tile with two fingers (the second finger may land
  anywhere); the size stays between 76 and 210 px.
- **Open:** a tap grows the tile into its page (container transform) and
  back.
- **Tidy up:** the button above the command bar slides all tiles into a
  neat 3 × 3 grid of equal size.
- **Stored:** positions and sizes are saved in `localStorage` under
  `lifeos.proto.physics.v1`, as fractions of the field so they survive a
  different screen size.
- **Engine:** DOM tiles moved with `transform`; one `requestAnimationFrame`
  loop that stops as soon as nothing moves.

## Prototype D: holographic globe

- **Globe:** a see-through sphere: a point cloud and latitude and longitude
  lines in the HUD blue, the back half faintly visible through the front,
  a glow around the rim and a dashed orbit ring. No regions.
- **Pins:** one glowing pin per feature (a stem and a dot in the feature's
  P2 ink). Pins facing the viewer carry a floating label card with the name
  and one live line; pins on the back are dimmed and have no card. A card
  that would overlap a nearer one is hidden until the globe turns.
- **Turn:** drag in any direction, with inertia. When left alone the globe
  turns slowly by itself for 25 s after the last touch, then stops (and the
  drawing loop sleeps). A HUD caption shows the longitude and latitude.
- **Open:** tap a pin or its card: the globe turns the pin to the front,
  zooms in on it, and the page opens as a circle from the pin; closing
  zooms back out.
- **Engine:** canvas 2D, device pixel ratio capped at 1.5; the cards are
  real buttons over the canvas.

## Prototype E: orbital HUD

- **Core:** the coach is the core in the middle (gradient ring, one live
  line).
- **Orbits:** the other eight features are nodes on two tilted orbits
  (inner: today's tasks, agenda, chats, notes; outer: courses, sleep and
  activity, sessions, projects). Nodes in front are larger and brighter;
  nodes behind the core are smaller and dimmer and hide their live line.
- **Spin:** drag along an orbit to turn it and let go to fling it (angular
  inertia); the tick marks turn with it. Like D, the orbits drift slowly for
  20 s after the last touch, then stop.
- **Open:** tap a node or the core: a node behind is first brought to the
  front, then the view zooms into it and the page opens as a circle from it.
- **Engine:** canvas 2D for the rings and ticks, DOM buttons for the nodes;
  the loop sleeps at rest.

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

### Prototype C: physics tiles

| Guidance | | Notes |
|---|---|---|
| Few primary destinations | ◐ | All nine features are always visible and one tap away, but positions change whenever a tile is thrown, so there is no stable place to remember; **Tidy up** restores one. No M3 pattern. |
| Touch targets ≥ 48 dp | ✅ | The smallest tile is 76 × 59 px; all controls pass the audit (every state, EN and TR, 412 and 360 px, light and dark). |
| One-hand reach | ◐ | Tidy up and the command bar are at the bottom, and tiles can be thrown anywhere, including into thumb reach; tiles near the top are a stretch. |
| Reduced motion | ✅ | The field starts tidy; a released tile stays where it is (no fling, no bounces); Tidy up is instant; pages swap instantly. Dragging still moves the tile under the finger. |
| Screen reader | ✅ ◐ | Tiles are buttons in a list named "*title*. *live line*. *summary*"; the field has instructions; the **list view** is the full alternative. ◐ Tile positions mean nothing to a screen reader, and the reading order (feature order) differs from the visual one. |
| Keyboard and switch access | ✅ | Tab reaches every tile; Enter opens; arrow keys move the focused tile (Shift for bigger steps); + and − resize it. Pinch and fling have these and the list view as alternatives (WCAG 2.5.7). |
| Contrast | ✅ | Text sits on glass at 86 % (light) or 78 % (dark) opacity over the faint grid; names use the text colour and live lines the tone's ink (≥ 4.5:1 per the P2 table). Glows and corner ticks are decorative. |
| Smoothness | ✅ ◐ | Nine `transform` updates per frame: under 0.1 ms of script per frame with 4× CPU throttling (rendering not measured). Not tried on a real phone. |
| Motion pattern | ◐ | Fling and bounce are custom and playful, deliberately against the app's "calm" principle; the container transform follows M3. |

### Prototype D: holographic globe

| Guidance | | Notes |
|---|---|---|
| Few primary destinations | ✗ | Nine pins, but only those facing the viewer have cards: **about half are hidden** at any moment, and cards that would overlap are hidden too. |
| Touch targets ≥ 48 dp | ✅ | Cards are buttons of at least 48 × 48; a tap within 34 px of a front pin's dot also opens it. Cards that fade out stop taking taps. |
| One-hand reach | ◐ | The globe sits mid-screen; the top pins and the list button are a stretch. |
| Reduced motion | ✅ ◐ | No idle turn, no inertia, no zoom; turning to a pin and opening are instant. ◐ Without reduced motion the idle turn moves on its own for 25 s (WCAG 2.2.2 asks for a way to pause motion that lasts more than 5 s; touching the globe stops it, but there is no explicit pause control). |
| Screen reader | ✅ ◐ | The canvas is hidden; every pin's card is a button named "*title*. *live line*. *summary*" in a labelled group with instructions; the **list view** is the full alternative. ◐ The globe itself carries no meaning for a screen reader. |
| Keyboard and switch access | ✅ | Tab moves through all nine cards and turns each pin to the front (back pins included); arrow keys turn the globe; Enter opens. |
| Contrast | ✅ ◐ | Card text on glass (≥ 4.5:1); the wireframe, point cloud and glows are decorative. ◐ Back-side pins are dimmed on purpose and are not meant to be read. |
| Smoothness | ✅ ◐ | About 1.3 ms of script per frame with 4× CPU throttling and a 2.625 pixel ratio emulated (capped to 1.5); canvas rasterisation was not measured. The idle turn draws continuously for 25 s, then the loop sleeps. |
| Motion pattern | ◐ | Zoom-in and circular reveal are custom; easing follows M3's emphasized tokens. |

### Prototype E: orbital HUD

| Guidance | | Notes |
|---|---|---|
| Few primary destinations | ◐ | One core and eight nodes, all visible at once (the back ones smaller and dimmer); positions change when an orbit spins. |
| Touch targets ≥ 48 dp | ✅ | Each node is at least 62 × 62 before the depth scaling (≥ 48 at the smallest scale); the core is 96 px. |
| One-hand reach | ◐ | The orbits sit mid-screen; spinning brings any node to the front (bottom), closer to the thumb. |
| Reduced motion | ✅ ◐ | No drift, no fling, no zoom; bringing a node to the front and opening are instant. ◐ Without reduced motion the orbits drift on their own for 20 s (same note on WCAG 2.2.2 as D). |
| Screen reader | ✅ ◐ | The core and the nodes are buttons named "*title*. *live line*. *summary*" in a labelled group with instructions; the **list view** is the full alternative. ◐ Orbit membership is not announced. |
| Keyboard and switch access | ✅ | Tab moves through the core and the nodes and brings a node behind to the front; left and right arrows spin the focused node's orbit; Enter opens. |
| Contrast | ✅ | Names on glass pills, live lines in the tone's ink, core text on glass (≥ 4.5:1). Rings, ticks and glows are decorative. |
| Smoothness | ✅ | About 0.1 ms of script per frame with 4× CPU throttling; the canvas holds only two ellipses and their ticks. |
| Motion pattern | ◐ | Custom; easing follows M3's emphasized tokens. |

**Recommendation: A, then E.** It keeps every feature one tap away in a place the
owner chose, works fully without gestures, and maps to standard Compose
building blocks (lazy grid, drag and drop, shared-element transition). Of the
sci-fi designs, **E** keeps every feature visible at once, puts the coach in
the centre as the app's concept already does, and is cheap to draw. **C** is
the most fun but has no stable layout to remember without Tidy up. **D** looks
the most like a film display but, like B, hides half of the destinations at
any moment. B, C, D and E all need custom drawing or physics in the app.

## How the prototypes were checked

With Playwright in Chrome at 412 × 915, served from a loopback address (the
browser tool refuses `file://` URLs). Every file was also rendered from a
`file://` URL in headless Chrome to confirm that it builds its tiles, regions,
pins or nodes there; **layout storage and the back gesture were verified on
the http origin only**, not on a local file on the phone.

- main flows of every prototype, clicked through, with no console errors or
  warnings;
- the command bar: task, note, the clarifying question, open (also from the
  suggestion chips, in English and Turkish), chat fallback, Undo, mocked
  voice; after a tapped Undo, focus moves to the mic button so no soft
  keyboard pops up;
- A: long-press, drag to reorder and edit-mode drag with emulated **touch**
  events (not mouse); a vertical swipe scrolls without entering edit mode;
  a tap opens; the layout survives a reload; Reset; keyboard move and hide;
- B: touch drag; inertia with synthetic pointer events; tap on the canvas
  and on a label; Tab focus turning a back region to the front; list view;
  Escape and back, also while the globe is still turning;
- C: fling with synthetic pointer events (the tile moves on and bounces);
  two-finger pinch with emulated multi-touch (the tile grows to the
  maximum); tap to open and back; positions survive a reload; Tidy up;
  keyboard move, resize and open; list view;
- D and E: drag or spin with synthetic pointer events; focus on a back pin
  or node turns it to the front; open with the zoom and back; list view;
- touch-target audit (≥ 48 × 48) in every state, EN and TR, at 412 and
  360 px, light and dark, with no horizontal overflow;
- `prefers-reduced-motion: reduce`: no transitions, no inertia, no idle
  motion, no zoom; C starts tidy and released tiles stay put;
- network: only the HTML file itself is requested.

The test browser drew only a few frames per second (a shared, throttled
window), so inertia, friction and the idle motion were checked by their
state values, not watched at a normal frame rate.

## Known rough edges

- Voice is mocked (no speech recognition), and the interpreter is a few
  keyword rules, not the model routing of #185.
- Pages are static apart from ticking tasks; rows with a chevron open
  nothing.
- The soft keyboard (`interactive-widget=resizes-content`) is untested: the
  browser tool shows no on-screen keyboard.
- Touch was emulated; long-press, drag, pinch, fling and inertia have not
  been tried on a real phone.
- A: a 1×1 tile has little room, so very large system font sizes clip its
  number and label.
- B: the coach gradient is shaded per 6-pixel block, so faint steps are
  visible up close; adding a feature means placing a new region centre by
  hand.
- The connection dot in the top bar is colour only on screen (it has a
  label for screen readers).
- C: collisions use circles, so the corners of large tiles can pass over
  each other; a tile thrown very hard can pass through a small one in one
  frame.
- D: decluttering hides a farther card instead of moving it, so a pin can
  lose its card while it is still in front.
- D and E: the idle motion starts on its own, which the app's "calm"
  principle would normally rule out; it stops after 20–25 s and never runs
  with reduced motion.
- C, D and E look best in the dark theme; the light theme works, but the
  glows are weaker.
- The shared parts (tokens, strings, data, pages, command bar; for C, D and
  E also the HUD helpers) are duplicated in every file on purpose, so each
  file stays self-contained; change all of them when editing.

## Other ideas

Not prototyped; listed so they can be chosen later.

- **Star map:** features as stars in a constellation; related items (a
  course and its tasks) are linked by lines; pinch to zoom from the whole
  sky into one constellation. Strong for showing relations, weak for speed.
- **Thumb dial:** a half-circle dial at the bottom edge with the features on
  its rim; turn it with the thumb and tap the one at the top. The best
  one-hand reach of all, but only five or six items fit at once.
- **Time ring:** the home is today's 24-hour ring (sleep, classes, tasks and
  events placed on it) with the features around it; the "now" hand points at
  what matters next.
