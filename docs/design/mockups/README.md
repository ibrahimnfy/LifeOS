# App mockups — visual directions

Static HTML mockups of the visual directions for the Android app
(see [`docs/app-design.md`](../../app-design.md), section 5).

- **D — the chosen direction, in three variants. The owner approved D3,
  with the light theme as the default; the page opens on D3 in light.**
  A compact top bar with a bold title on the left and one or two line icons
  on the right; a slim icon-only bottom bar with the capture button in the
  centre; thin line icons (1.5 px stroke); list rows with a round mark on
  the left; full-width rounded buttons; the system sans-serif font. Every
  icon and button is drawn by hand for this project as inline SVG and CSS.
  - **D1 — Blue night (not chosen):** a soft blue-white in light and a deep blue-black in
    dark. The accent is led by blue and runs through violet to pink. Thin
    ring (2 px).
  - **D2 — Warm glow (not chosen):** a warm off-white in light and charcoal in dark. The
    accent is led by the gradient, from orange through pink to violet.
    Widest ring (4 px).
  - **D3 — Balanced (chosen):** neutral soft greys in light and a blue-tinted
    charcoal in dark. The accent runs from violet through pink to orange.
    Medium ring (3 px).
- **A — Calm paper (not chosen):** warm paper tones, serif titles, hairline
  cards, one deep-teal accent.
- **B — Material You (not chosen):** tonal surfaces and standard Material 3
  shapes; the purple palette is only one example.
- **C — Dark console (not chosen):** near-black, high contrast, monospace
  headings and numbers, one amber accent.

D1, D2, A, B and C are kept for reference only and are labelled "(not
chosen)" in the switcher. All content is invented placeholder data and is
the same in every direction that draws a screen, so only the design differs.

### What changed after the owner's review of D

The owner approved D3 and asked for changes to the content of the screens.
The colours, the tokens and the coach card's ring are as they were.

- **D3 is the default** direction, in the light theme. Bare `dir=D` now
  means D3.
- **Today is three clearly separate cards**, each on the soft card surface
  with a hairline border and a faint shadow:
  1. the coach card, which keeps its gradient ring and now says "Open
     conversation", because tapping it opens the coach conversation;
  2. "Assistant's picks" (this name replaces the earlier heading), the
     three tasks with a one-line reason each;
  3. "Today", which shows three tasks and scrolls inside the card. A fourth
     row fades out at the lower edge and a line below it counts the rest
     ("2 more"). The list really scrolls in the mockup (mouse wheel, touch
     or keyboard); at the end of the list the hint disappears.
- **Chat has a list state.** The coach conversation is pinned at the top
  (ring mark, last message, a usage line), recent conversations follow with
  their folder name, and "New chat" opens a sheet that asks in which folder
  the chat should run. The coach conversation is drawn too: usage in the
  header, a line saying that earlier messages were summarised, and a
  question card with options A/B/C and a free-text answer.
- **The ordinary conversation no longer contains a coach message.** In D it
  shows an approval card instead: what the assistant wants to do and where,
  "Allow" and "Don't allow", and the fixed note that changes already made
  are kept.
- **Courses are new:** a summary card with the upcoming deadlines and the
  "All grades" button, the course list with a red count where a course has
  new content, the course page with four tabs (Materials, Announcements,
  Assignments, Grades) and the "All grades" screen. The section is called
  "Courses" and is not named after any platform.
- **New icons**, drawn by hand like the others: refresh, download, chevrons,
  file, link, play, open-outside and the coach mark.

The new screens (Chat list, New chat, Coach conversation, Courses, Course,
All grades) and the approval card are drawn in D only.

### Why D changed

The first D was a flat monochrome: pure white or pure black, neutral greys
and almost no colour. The owner's feedback was that the structure is good
but the look is flat and too stark. That version was replaced by D1, D2 and
D3. That step left the layout, the screens and their content unchanged;
what changed was the colour:

- **A softer base.** A slightly soft white in light and a blue-black or
  charcoal in dark, soft grey card surfaces, subtle dividers.
- **Blue for actions.** Primary buttons ("Show all (23)" on Tasks, "All
  grades" on Courses, "Start", "Allow", the send button) are filled with a
  vivid blue; links ("Open conversation" on the coach card, "All sessions"
  in the Chat list) are blue text. "Don't allow" stays neutral.
- **Red for counts.** The bell on Today carries a count, the chat icon in
  the bottom bar carries an unread count while another screen is open, one
  session row shows an unread count, and a course with new content shows
  how many items are new. The overdue dot and the "New" marker use the same
  red.
- **A warm gradient for what is featured or active.** A three-stop gradient
  is drawn as a ring around the coach card (the top card on Today), around
  the pinned coach conversation in the Chat list, around the "Coach" label,
  around the round mark of a pinned session and around the capture button,
  and as a short bar under the active bottom-bar item and under the active
  tab on Activity and on a course page.

The three variants differ only in token values (next section); every layout
and component rule is shared.

## Tokens

Each variant and theme is one block of CSS custom properties in
`styles.css` (`.phone[data-variant="D1"][data-theme="light"]` and so on).
All hex values were chosen by hand for this project. Ring widths are whole
pixels, because fractional widths are rounded on ordinary screens. The
gradient is always
`linear-gradient(135deg, stop 1, stop 2, stop 3)`.

| Token | Role | D1 light | D1 dark | D2 light | D2 dark | D3 light | D3 dark |
|---|---|---|---|---|---|---|---|
| `--bg` | Background | `#f6f8fc` | `#0b1220` | `#faf7f2` | `#171615` | `#f8f8fa` | `#101218` |
| `--surface` | Card surface | `#ebeff6` | `#141d2e` | `#f1ece4` | `#222120` | `#eeeef2` | `#1a1d25` |
| `--surface-2` | Second surface (round marks, neutral buttons, chart tracks) | `#dfe5ef` | `#1e293d` | `#e6dfd4` | `#2e2c2a` | `#e3e3e9` | `#252933` |
| `--divider` | Divider | `#d5dce8` | `#28344b` | `#ddd5c8` | `#393633` | `#d9d9e1` | `#303541` |
| `--text` | Text | `#121826` | `#e8edf6` | `#1f1b17` | `#f1ede7` | `#17171c` | `#ecedf2` |
| `--muted` | Muted text | `#535e72` | `#9ba7bb` | `#61594f` | `#aaa399` | `#595966` | `#a1a5b1` |
| `--primary` | Primary (blue fill) | `#1b62e8` | `#1f66e8` | `#2260e0` | `#2466e6` | `#1e60e6` | `#2165e8` |
| `--on-primary` | Label on primary | `#ffffff` | `#ffffff` | `#ffffff` | `#ffffff` | `#ffffff` | `#ffffff` |
| `--link` | Link (blue text) | `#1553cc` | `#78acff` | `#1a51c4` | `#82b1ff` | `#1852c8` | `#7dafff` |
| `--badge` | Badge (red) | `#d91f3a` | `#dc2742` | `#d42a2a` | `#dc3030` | `#d62438` | `#dc2a3e` |
| `--on-badge` | Count on badge | `#ffffff` | `#ffffff` | `#ffffff` | `#ffffff` | `#ffffff` | `#ffffff` |
| `--ok` | Status green | `#157a43` | `#3fd684` | `#1b7a3f` | `#4ed58a` | `#177a41` | `#45d688` |
| `--grad-1` | Gradient stop 1 | `#2f6bff` | `#4f86ff` | `#e5631a` | `#ff9550` | `#6a5cf2` | `#8577ff` |
| `--grad-2` | Gradient stop 2 | `#7a55f2` | `#9273ff` | `#e63f7c` | `#f55c92` | `#d845a4` | `#ec5fb2` |
| `--grad-3` | Gradient stop 3 | `#e0489a` | `#f065ad` | `#8f4fe8` | `#a574ff` | `#e8651e` | `#ff9458` |
| `--ring-w` | Ring width | `2px` | `2px` | `4px` | `4px` | `3px` | `3px` |

A few more tokens are derived from these once, for all variants: the done
check and the capture button are filled with `--text`, the charts are drawn
in `--text` on `--bg` with `--divider` grid lines and `--surface-2` tracks,
and the overdue dot uses `--badge`.

A visible card (the three cards on Today, the summary card on Courses, the
cards on the course page and on All grades, question and approval cards) is
`--surface` with a 1 px `--divider` border, a 20 px radius and one shared
soft shadow (`--card-shadow`, two layers of black at 5–6 % opacity). In the
dark theme the shadow is practically invisible and the border and the
surface carry the card. Round marks inside a card use `--surface-2`.

## Contrast

WCAG 2 contrast ratios, computed from the hex values above (relative
luminance formula, rounded down to two decimals). Body and muted text need
4.5:1; bold or large text, icons and other meaningful graphics need 3:1.
Every pair with a requirement passes in every variant and theme.

| Pair | Needs | D1 light | D1 dark | D2 light | D2 dark | D3 light | D3 dark |
|---|---|---|---|---|---|---|---|
| Text on background | 4.5:1 | 16.67 | 15.93 | 16.01 | 15.49 | 16.83 | 16.01 |
| Text on surface | 4.5:1 | 15.37 | 14.35 | 14.54 | 13.78 | 15.43 | 14.41 |
| Muted on background | 4.5:1 | 6.14 | 7.70 | 6.44 | 7.23 | 6.50 | 7.60 |
| Muted on surface | 4.5:1 | 5.66 | 6.93 | 5.85 | 6.43 | 5.95 | 6.84 |
| Muted on surface-2 | 4.5:1 | 5.16 | 6.00 | 5.20 | 5.56 | 5.39 | 5.91 |
| Button label on primary | 4.5:1 | 5.30 | 5.09 | 5.50 | 5.09 | 5.42 | 5.13 |
| Link on background | 4.5:1 | 6.28 | 8.15 | 6.51 | 8.33 | 6.42 | 8.42 |
| Link on surface | 4.5:1 | 5.79 | 7.34 | 5.91 | 7.41 | 5.89 | 7.58 |
| Count on badge | 4.5:1 | 4.99 | 4.75 | 5.04 | 4.65 | 5.03 | 4.72 |
| Badge on background | 3:1 | 4.69 | 3.93 | 4.71 | 3.87 | 4.74 | 3.96 |
| Primary on background | 3:1 | 4.98 | 3.67 | 5.14 | 3.54 | 5.11 | 3.64 |
| Status green on background | 3:1 | 5.06 | 9.95 | 5.03 | 9.64 | 5.08 | 10.00 |
| Badge on surface | 3:1 | 4.32 | 3.54 | 4.28 | 3.45 | 4.35 | 3.56 |
| Primary on surface | 3:1 | 4.59 | 3.31 | 4.67 | 3.15 | 4.68 | 3.28 |
| Status green on surface | 3:1 | 4.67 | 8.96 | 4.57 | 8.57 | 4.65 | 9.00 |
| Icon (text colour) on surface-2 | 3:1 | 14.00 | 12.41 | 12.92 | 11.92 | 13.97 | 12.44 |
| Gradient stop 1 on background | – | 4.23 | 5.51 | 3.20 | 8.31 | 4.47 | 5.42 |
| Gradient stop 2 on background | – | 4.47 | 5.45 | 3.66 | 5.87 | 3.73 | 6.10 |
| Gradient stop 3 on background | – | 3.55 | 6.39 | 4.41 | 5.62 | 3.13 | 8.58 |
| Gradient stop 1 on surface | – | 3.90 | 4.96 | 2.91 | 7.39 | 4.10 | 4.87 |
| Gradient stop 2 on surface | – | 4.13 | 4.91 | 3.32 | 5.22 | 3.41 | 5.49 |
| Gradient stop 3 on surface | – | 3.27 | 5.75 | 4.01 | 5.00 | 2.87 | 7.72 |

Notes:

- The button label (15 px, semibold) and the badge count (about 11 px,
  bold) are small text, so both are held to 4.5:1. This is why the blue fill
  is the same strength in dark as in light, and why links use a separate,
  lighter blue (`--link`) in dark.
- The cards added after the owner's review put a few things on the card
  surface instead of the background: the red overdue dot and "New" marker,
  the blue buttons and the green check. Their rows ("… on surface") were
  added to the table.
- The gradient ring is decorative: the coach card is also set apart by its
  surface and its "Coach" label, a pinned session by its section title and
  pin icon, and the active bottom-bar item by its heavier icon and
  `aria-current`. No minimum applies, and the ratios are listed for
  information. The weakest is the orange stop on the card surface in light
  (2.91 in D2, 2.87 in D3); every other stop is above 3:1.
- Dividers are deliberately faint (about 1.3:1 in light and 1.5:1 in dark)
  and carry no information on their own.

## Screens

| Screen | `screen=` | What it shows | Directions |
|---|---|---|---|
| Today | `today` | Three cards. The coach card (what to focus on today, with a short "why" line; it opens the coach conversation), "Assistant's picks" (the three tasks the assistant ranks highest, each with a one-line reason) and "Today" (the tasks that belong to today as checkbox rows; three are visible and the rest scroll inside the card). No events. In D the bell carries a red count. In A, B and C the same content is drawn in the older layout, without the inner scrolling. | A, B, C, D1–D3 |
| Chat · list | `chatlist` | The Chat tab before a conversation is opened: the coach conversation pinned at the top (ring mark, last message, "Today: 2 runs"), three recent conversations with folder name and last activity, a link to all sessions and the "New chat" button. | D1–D3 |
| New chat · folder | `newchat` | The sheet that opens from "New chat": "Where should this chat run?", with the memory folder (default, selected) and two allowed folders as radio rows, and the "Start" button. | D1–D3 |
| Coach conversation | `coach` | The coach conversation: "Coach" and the usage line in the header, the line "Earlier messages were summarised", a few messages, and a question card with options A, B and C, a free-text field and "Send". | D1–D3 |
| Chat · conversation | `chat` | An ordinary conversation and the composer. In D it ends with an approval card ("The assistant wants to delete 3 files in notes/drafts", "Allow", "Don't allow", "Changes already made are kept."); in A, B and C it ends with the "Working…" line. | A, B, C, D1–D3 |
| Tasks | `tasks` | "Most important": five tasks ordered by the assistant, then a full-width "Show all (23)" button. | D1–D3 |
| Sessions | `sessions` | A search field, a "Pinned" section, groups made by the owner, then automatic "By folder" groups for the remaining sessions. A row shows title, folder name and last activity; one row has an "In use" tag, one a bell for a reminder and one a red unread count. | D1–D3 |
| Activity | `activity` | Sleep and screen-time charts. | A, B, C, D1–D3 |
| Courses | `courses` | A summary card with the three nearest deadlines (item, course, days left) and the "All grades" button; then five courses, each with its next deadline, two of them with a red count of new items. The top bar shows the last sync time and a refresh icon. | D1–D3 |
| Course | `course` | A course page with four tabs. Materials (the tab shown in the comparison views): sections with file rows (name; type, size and date; a "New" marker on one; a download button on each; one row in the "Saved to Downloads" state) and a "Links" group with a video and an external link. In the *One direction* view the other three tabs are drawn as three more phones: Announcements (newest first), Assignments (due date and a status chip) and Grades (item, grade, range). | D1–D3 |
| All grades | `grades` | The grades of all courses, one card per course, with the course total in the card's heading. | D1–D3 |

In D the bottom bar has no text labels: Today, Chat, the capture (+) button,
Tasks and More. Each item has an `aria-label` (and a tooltip) with its name;
counts are included in that label. In A, B and C the capture button floats
above the bar.

## How to open

Double-click `index.html` (or open it in any current browser). There is no
server, no build step and no network access: just `index.html`, `styles.css`
and `app.js`. Fonts come from system font stacks, so the exact typefaces
depend on the fonts present on the machine.

## Controls

| Control | What it does |
|---|---|
| View | *One direction* shows every screen of one direction or variant, in rows: Today and the four Chat screens; Tasks, Sessions, Activity, Courses and All grades; the four tabs of a course. *Compare D1 · D2 · D3* shows one screen in the three variants side by side. *Earlier directions* shows one screen in D3 next to A, B and C (those that have the screen). |
| Direction | D3 (chosen), or D1, D2, A, B, C (each labelled "not chosen"); used in the *One direction* view. |
| Screen | One of the eleven screens in the table above (used in the two comparison views). |
| Theme | Light, dark, or both. *Both* draws a light band and, below it, a dark band with the same phones. |
| Language | English or Turkish, for every visible string, date and number. |

## URL parameters

The controls keep the address bar in sync, so any combination can be linked
or screenshotted. Names and values are case-insensitive; anything missing or
unknown falls back to the default.

| Parameter | Values | Default |
|---|---|---|
| `view` | `single`, `compare`, `directions` | `single` |
| `dir` | `D1`, `D2`, `D3`, `A`, `B`, `C` (`D` is read as `D3`) | `D3` |
| `screen` | `today`, `chatlist`, `newchat`, `coach`, `chat`, `tasks`, `sessions`, `activity`, `courses`, `course`, `grades` (`chat-list` and `new-chat` are also accepted) | `today` |
| `theme` | `light`, `dark`, `both` | `light` |
| `lang` | `en`, `tr` | `en` |

### Examples

```
index.html                                      (D3, light, English, all screens)
index.html?theme=dark
index.html?lang=tr&theme=both
index.html?view=compare&screen=today&theme=both
index.html?view=compare&screen=courses&theme=light
index.html?view=compare&screen=coach&theme=dark&lang=tr
index.html?dir=D1&theme=dark
index.html?view=directions&screen=today&theme=light
```

For screenshots, three phones side by side need a window about 1400 px
wide; allow about 1320 px of height for one theme and about 2380 px for
`theme=both`. All screens of one D variant (three rows, five phones in the
widest) fit in a window about 2250 px wide and 3200 px high; narrower
windows wrap the rows.

## Editing

- `app.js` — the EN/TR string table, the placeholder data, the screen
  templates and the inline SVG icons and charts. Screens are built once and
  shared by all directions; the few structural differences are keyed on the
  skin (A, B, C or D). `VARIANTS` lists the variants of D, `SKIN_SCREENS`
  the screens of each skin (one array per row of the *One direction* view),
  and `CHOSEN` names the approved variant, which is the default (`D3`).
- `styles.css` — page chrome, the shared phone skeleton, then one block per
  direction: CSS custom properties per direction and theme, followed by that
  direction's component styles. For D there is one token block per variant
  and theme, and one set of component rules that only refers to tokens. To
  try a different tone, change a hex value in a token block and re-check
  the contrast table above.

All placeholder content is invented. Keep it generic: no real names,
institutions, course titles, addresses or paths, and no names of other
apps or platforms. Do not add third-party logos, icon sets, fonts or other
assets.
