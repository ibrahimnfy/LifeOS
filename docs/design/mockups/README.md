# App mockups — warmer D3 proposals

Static HTML mockups for the Android app (see
[`docs/app-design.md`](../../app-design.md), section 5).

The owner approved **D3** and then asked for it to be made warmer and
friendlier: the coach card is good, but the other cards are flat black and
white. D1 and D2 are dropped, and so are the earlier directions A, B and C;
this folder now shows only D3 and three refined proposals built on it.

All three keep D3's structure and tokens: the compact top bar, the slim
icon-only bottom bar with the capture button, the blue for actions, the red
for counts, the gradient ring for featured and active elements, and the
**coach card unchanged** (same surface, same ring, same text). What changes
is how the other cards look.

## The proposals

- **P1 — Soft tints per section.** Colour follows the section of the app.
  Every card gets a flat pastel tint of its section (Assistant's picks
  violet, Today peach, Tasks mint, deadlines rose, courses and the course
  page sky, grades sand). Each card heading starts with a small rounded icon
  tile in the card's second tint, and round marks inside the card take the
  same tint. No patterns. The calmest of the three, and the closest to D3.
- **P2 — Course colours and watermarks.** Colour follows the course (or
  project). Each one keeps its colour and its own line icon everywhere it
  appears: its tile on Courses, the small label on a task, the mark next to
  a deadline, the header card of the course page, the file icons, the due
  card of an assignment. Section cards (picks, Today, Tasks, deadlines) stay
  D3 grey and carry one large, faint icon of their section in a corner as a
  watermark. On Today and Tasks, the checkbox ring and the rank number take
  the task's course colour.
- **P3 — Playful shapes and geometric illustrations.** The most varied
  layout. Assistant's picks become tiles (the first one wide, two square
  ones beside each other); the first task on Tasks is a large "Up next"
  card and the rest sit on a ticket-edged card; deadlines become three
  small tiles with a big day count; courses form a two-column grid with a
  wide first tile; the assignment's due date is a ticket with a stub. Cards
  use one tighter corner, and carry small illustrations made of circles,
  stripes, dots, arcs, waves, squares and triangles in the card's own tints
  (each course has its own pattern). The course page uses pill tabs.

**Recommendation: P2.** It is the only proposal whose colour carries
information (which course a task, deadline or file belongs to), so the
warmth also makes the app quicker to read, while the section cards stay
calm.

Course and project colours are taken from one fixed palette in order
(sky, teal, violet, peach, sand, mint, rose), so a new course simply takes
the next colour; nothing is hand-picked per course.

## What changed besides the colours

These follow the owner's seventh round of answers and apply to D3 and all
proposals:

- **Courses:** "All grades" is a text button in the top bar, next to the
  refresh icon; the last sync time moves under the title. The summary card
  shows the upcoming deadlines only, and each deadline row has a chevron
  because it opens the assignment.
- **Assignment detail (new screen):** due date and time, days left, when it
  opened and the status chip; the instructions; the attached files (download
  or saved, like materials); the submission format and grade; a row saying
  the assignment is also in the task list and completes when submitted; and
  "Open the submission page".
- **Sample content** now uses generic course names (Signals and Systems,
  Embedded Systems Lab, Linear Algebra, Technical Writing, Probability and
  Statistics) instead of "Example course A–E", and tasks name their course.

## Screens

| Screen | `screen=` | Redrawn by the proposals |
|---|---|---|
| Today | `today` | yes (coach card unchanged) |
| Tasks | `tasks` | yes |
| Courses | `courses` | yes |
| Course · Materials | `course` | yes |
| Assignment | `assignment` | yes (new) |
| Course · Announcements, Assignments, Grades; All grades | single view only | tones and shapes only |
| Chat list, New chat, Coach conversation, Chat, Sessions, Activity | single view only | no, drawn as in D3 |

## How to open

Double-click `index.html` (or open it in any current browser). There is no
server, no build step and no network access: just `index.html`,
`styles.css` and `app.js`. Fonts come from the system font stack.

## Controls and URL parameters

| Control | Parameter | Values | Default |
|---|---|---|---|
| View | `view` | `compare` (P1 · P2 · P3 side by side, one screen), `single` (every screen of one proposal) | `compare` |
| Proposal | `prop` | `P1`, `P2`, `P3`, `D3` (D3 as approved, for reference); used by `single` | `P2` |
| Screen | `screen` | `today`, `tasks`, `courses`, `course`, `assignment`; used by `compare` | `today` |
| Theme | `theme` | `light`, `dark`, `both` (a light band above a dark band) | `light` |
| Language | `lang` | `en`, `tr` | `en` |

The controls keep the address bar in sync. Examples:

```
index.html                                         (P1 · P2 · P3, Today, light)
index.html?screen=courses&theme=both
index.html?screen=assignment&theme=dark&lang=tr
index.html?view=single&prop=P3&theme=both
index.html?view=single&prop=D3
```

Three phones side by side need a window about 1400 px wide; one proposal's
first row (five phones) needs about 2250 px.

## Tokens

D3's tokens are unchanged and match `app/.../ui/theme/Tokens.kt`:

| Token | Role | Light | Dark |
|---|---|---|---|
| `--bg` | Background | `#f8f8fa` | `#101218` |
| `--surface` | Card surface (also the coach card) | `#eeeef2` | `#1a1d25` |
| `--surface-2` | Second surface | `#e3e3e9` | `#252933` |
| `--divider` | Divider | `#d9d9e1` | `#303541` |
| `--text` | Text | `#17171c` | `#ecedf2` |
| `--muted` | Muted text | `#595966` | `#a1a5b1` |
| `--primary` | Primary (blue fill) | `#1e60e6` | `#2165e8` |
| `--link` | Link | `#1852c8` | `#7dafff` |
| `--badge` | Badge (red) | `#d62438` | `#dc2a3e` |
| `--ok` | Status green | `#177a41` | `#45d688` |
| `--grad-1/2/3` | Gradient stops | `#6a5cf2` `#d845a4` `#e8651e` | `#8577ff` `#ec5fb2` `#ff9458` |

The proposals add one palette of seven soft tones. Each tone has a card
tint, a second tint (icon tiles, round marks, card edge, row dividers) and
an ink (icons, numbers, accents, course labels). All values were chosen for
this project; every fill is flat.

| Tone | Light: card / second / ink | Dark: card / second / ink |
|---|---|---|
| violet | `#f0edfc` `#e0dafa` `#5443c4` | `#1e1c31` `#2c2850` `#b5aaff` |
| peach | `#fcefe5` `#f8dcc6` `#9a420c` | `#261e19` `#3b2b20` `#ffb17a` |
| mint | `#e8f5ee` `#cdeadb` `#176b45` | `#16241e` `#1e3a2d` `#7cdba8` |
| sky | `#e8f1fb` `#cfe2f7` `#1b5ca3` | `#152030` `#1d324a` `#8ec5ff` |
| rose | `#fbecef` `#f6d4db` `#a8233c` | `#28191e` `#42232c` `#ff9cae` |
| sand | `#f9f2dc` `#f0e1ae` `#765700` | `#24200f` `#3a3218` `#efcd6a` |
| teal | `#e4f4f2` `#c6e7e3` `#0d6862` | `#132423` `#1a3a38` `#6ed6cd` |

Watermarks (P2) are the tone's ink at 8 % opacity. Illustrations (P3) use
the second tint, the ink at 14 % for filled shapes and the ink at 25–40 %
for thin outlines.

## Contrast

WCAG 2 ratios, computed from the hex values (relative luminance, two
decimals). The table gives the lowest value over the seven tones. Body and muted
text need 4.5:1; the red badge, the blue button fill and icons need 3:1.
Everything passes.

| Pair | Needs | Light (lowest) | Dark (lowest) |
|---|---|---|---|
| Text on a tinted card | 4.5 | 15.50 | 13.76 |
| Muted text on a tinted card | 4.5 | 5.98 | 6.54 |
| Link on a tinted card | 4.5 | 5.92 | 7.24 |
| Ink on a tinted card (icons, numbers, course name) | 4.5 | 5.81 | 8.03 |
| Ink on the second tint (course labels, icon tiles) | 4.5 | 5.02 | 6.66 |
| Muted text on the second tint | 4.5 | 5.04 | 5.00 |
| Muted text over a watermark (8 %), tinted or grey card | 4.5 | 5.25 | 5.47 |
| Muted text over an illustration fill (14 %) | 4.5 | 4.81 | 4.72 |
| Status green on a tinted card | 3 | 4.68 | 8.60 |
| Red badge on a tinted card | 3 | 4.37 | 3.40 |
| Blue button fill on a tinted card | 3 | 4.71 | 3.13 |

The D3 pairs on the background and the grey surface are unchanged from the
approved version (text 16.83 and 16.01 on the background, muted 5.95 and
6.84 on the surface, link 5.89 and 7.58 on the surface, count on badge 5.03
and 4.72). Labels on the blue button and counts on the red badge sit on the
fill, not on the tint, so they are unchanged too.

Text never sits on an illustration's outlines; the outlines and the
gradient ring are decorative and have no minimum.

## Editing

- `app.js` — the EN/TR string table, the placeholder data, the screens, the
  inline SVG icons and the illustration generator (`deco`). `PROPOSALS`,
  `SECTION_TONE` (P1), `WATERMARK` (P2), `P3_SECTION` (P3), `PALETTE` and
  `CTX` (course colour, icon and pattern) hold the design choices.
- `styles.css` — page chrome, the phone skeleton, the D3 and tone tokens,
  the D3 components (which read `--card`, `--card-2`, `--ink` and `--line`,
  so an untinted card looks exactly as approved), then one block per
  proposal.

All placeholder content is invented. Keep it generic: no real names,
institutions, addresses or paths (generic subject names such as "Linear
Algebra" are fine), and no names of other apps or platforms. Every icon,
pattern and illustration is drawn for this project as inline SVG and CSS;
do not add third-party logos, icon sets, fonts or other assets.
