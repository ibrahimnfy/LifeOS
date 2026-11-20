# App mockups — visual directions

Static HTML mockups of the visual directions for the Android app
(see [`docs/app-design.md`](../../app-design.md), section 5).

- **D — the chosen direction, in three variants (D1 is shown by default).**
  A compact top bar with a bold title on the left and one or two line icons
  on the right; a slim icon-only bottom bar with the capture button in the
  centre; thin line icons (1.5 px stroke); list rows with a round mark on
  the left; full-width rounded buttons; the system sans-serif font. Every
  icon and button is drawn by hand for this project as inline SVG and CSS.
  - **D1 — Blue night:** a soft blue-white in light and a deep blue-black in
    dark. The accent is led by blue and runs through violet to pink. Thin
    ring (2 px).
  - **D2 — Warm glow:** a warm off-white in light and charcoal in dark. The
    accent is led by the gradient, from orange through pink to violet.
    Widest ring (4 px).
  - **D3 — Balanced:** neutral soft greys in light and a blue-tinted
    charcoal in dark. The accent runs from violet through pink to orange.
    Medium ring (3 px).
- **A — Calm paper (not chosen):** warm paper tones, serif titles, hairline
  cards, one deep-teal accent.
- **B — Material You (not chosen):** tonal surfaces and standard Material 3
  shapes; the purple palette is only one example.
- **C — Dark console (not chosen):** near-black, high contrast, monospace
  headings and numbers, one amber accent.

A, B and C are kept for reference only. All content is invented placeholder
data and is the same in every direction, so only the design differs.

### Why D changed

The first D was a flat monochrome: pure white or pure black, neutral greys
and almost no colour. The owner's feedback was that the structure is good
but the look is flat and too stark. That version was replaced by D1, D2 and
D3. The layout, the five screens and their content are unchanged; what
changed is the colour:

- **A softer base.** A slightly soft white in light and a blue-black or
  charcoal in dark, soft grey card surfaces, subtle dividers.
- **Blue for actions.** Primary buttons ("Show all (23)" on Tasks, "Answer"
  on the coach message, the send button) are filled with a vivid blue;
  links ("Cancel" in Chat) are blue text. "Skip" stays neutral.
- **Red for counts.** The bell on Today carries a count, the chat icon in
  the bottom bar carries an unread count while another screen is open, and
  one session row shows an unread count. The overdue dot uses the same red.
- **A warm gradient for what is featured or active.** A three-stop gradient
  is drawn as a ring around the coach card (the top card on Today), around
  the "Coach" label, around the round mark of a pinned session and around
  the capture button, and as a short bar under the active bottom-bar item
  and under the active tab on Activity.

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
- The gradient ring is decorative: the coach card is also set apart by its
  surface and its "Coach" label, a pinned session by its section title and
  pin icon, and the active bottom-bar item by its heavier icon and
  `aria-current`. No minimum applies, and the ratios are listed for
  information. The weakest is the orange stop on the card surface in light
  (2.91 in D2, 2.87 in D3); every other stop is above 3:1.
- Dividers are deliberately faint (about 1.3:1 in light and 1.5:1 in dark)
  and carry no information on their own.

## Screens

| Screen | What it shows | Directions |
|---|---|---|
| Today | The coach card (what to focus on today, with a short "why" line), "Top three" (the three tasks the assistant ranks highest, each with a one-line reason) and "Today" (the tasks that belong to today, as checkbox rows). No events. In D the bell carries a red count. | A, B, C, D1–D3 |
| Chat | A conversation and the composer. One message is a question the assistant asks on its own initiative; it carries a small "Coach" label and "Answer" / "Skip" actions. | A, B, C, D1–D3 |
| Tasks | "Most important": five tasks ordered by the assistant, then a full-width "Show all (23)" button. | D1–D3 |
| Sessions | A search field, a "Pinned" section, groups made by the owner, then automatic "By folder" groups for the remaining sessions. A row shows title, folder name and last activity; one row has an "In use" tag, one a bell for a reminder and one a red unread count. | D1–D3 |
| Activity | Sleep and screen-time charts. | A, B, C, D1–D3 |

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
| View | *One direction* shows every screen of one direction or variant. *Compare D1 · D2 · D3* shows one screen in the three variants side by side. *Earlier directions* shows one screen in D1 next to A, B and C (those that have the screen). |
| Direction | D1, D2, D3, A, B or C (used in the *One direction* view). |
| Screen | Today, Chat, Tasks, Sessions or Activity (used in the two comparison views). |
| Theme | Light, dark, or both. *Both* draws a light band and, below it, a dark band with the same phones. |
| Language | English or Turkish, for every visible string, date and number. |

## URL parameters

The controls keep the address bar in sync, so any combination can be linked
or screenshotted. Names and values are case-insensitive; anything missing or
unknown falls back to the default.

| Parameter | Values | Default |
|---|---|---|
| `view` | `single`, `compare`, `directions` | `single` |
| `dir` | `D1`, `D2`, `D3`, `A`, `B`, `C` (`D` is read as `D1`) | `D1` |
| `screen` | `today`, `chat`, `tasks`, `sessions`, `activity` | `today` |
| `theme` | `light`, `dark`, `both` | `light` |
| `lang` | `en`, `tr` | `en` |

### Comparing D1, D2 and D3

```
index.html?view=compare&screen=today&theme=both
index.html?view=compare&screen=chat&theme=light
index.html?view=compare&screen=sessions&theme=dark&lang=tr
index.html?dir=D2&theme=dark
index.html?dir=D3&theme=both
index.html?view=directions&screen=today&theme=light
```

For screenshots, three phones side by side need a window about 1400 px
wide; allow about 1320 px of height for one theme and about 2380 px for
`theme=both`. The five screens of one variant fit in a window about 2250 px
wide and 1250 px high.

## Editing

- `app.js` — the EN/TR string table, the placeholder data, the screen
  templates and the inline SVG icons and charts. Screens are built once and
  shared by all directions; the few structural differences are keyed on the
  skin (A, B, C or D). `VARIANTS` lists the variants of D, `SKIN_SCREENS`
  the screens of each skin, and `CHOSEN` names the default variant.
- `styles.css` — page chrome, the shared phone skeleton, then one block per
  direction: CSS custom properties per direction and theme, followed by that
  direction's component styles. For D there is one token block per variant
  and theme, and one set of component rules that only refers to tokens. To
  try a different tone, change a hex value in a token block and re-check
  the contrast table above.

All placeholder content is invented. Keep it generic: no real names,
institutions, addresses or paths. Do not add third-party logos, icon sets,
fonts or other assets.
