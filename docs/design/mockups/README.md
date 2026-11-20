# App mockups — visual directions

Static HTML mockups of the visual directions for the Android app
(see [`docs/app-design.md`](../../app-design.md), section 5).

- **D — Monochrome (the chosen direction, shown by default):** pure white
  (`#fff`) in light and pure black (`#000`) in dark, neutral greys and almost
  no colour. Colour appears only where it carries meaning: the connection
  dot, the "in use" dot and the overdue mark. A compact top bar with a bold
  title on the left and one or two line icons on the right; a slim icon-only
  bottom bar; thin line icons (1.5 px stroke); hairline dividers; list rows
  with a round mark on the left; full-width rounded buttons; the system
  sans-serif font. Every icon and button is drawn by hand for this project
  as inline SVG and CSS.
- **A — Calm paper (not chosen):** warm paper tones, serif titles, hairline
  cards, one deep-teal accent.
- **B — Material You (not chosen):** tonal surfaces and standard Material 3
  shapes; the purple palette is only one example.
- **C — Dark console (not chosen):** near-black, high contrast, monospace
  headings and numbers, one amber accent.

A, B and C are kept for reference only. All content is invented placeholder
data and is the same in every direction, so only the design differs.

## Screens

| Screen | What it shows | Directions |
|---|---|---|
| Today | An advice card (what to focus on today, with a short "why" line), "Top three" (the three tasks the assistant ranks highest, each with a one-line reason) and "Today" (the tasks that belong to today, as checkbox rows). No events. | A, B, C, D |
| Chat | A conversation and the composer. One message is a question the assistant asks on its own initiative; it carries a small "Coach" label and "Answer" / "Skip" actions. | A, B, C, D |
| Tasks | "Most important": five tasks ordered by the assistant, then a full-width "Show all (23)" button. | D |
| Sessions | A search field, a "Pinned" section, groups made by the owner, then automatic "By folder" groups for the remaining sessions. A row shows title, folder name and last activity; one row has an "In use" tag and one a bell for a reminder. | D |
| Activity | Sleep and screen-time charts. | A, B, C, D |

In D the bottom bar has no text labels: Today, Chat, the capture (+) button,
Tasks and More. Each item has an `aria-label` (and a tooltip) with its name.
In A, B and C the capture button floats above the bar.

## How to open

Double-click `index.html` (or open it in any current browser). There is no
server, no build step and no network access: just `index.html`, `styles.css`
and `app.js`. Fonts come from system font stacks, so the exact typefaces
depend on the fonts present on the machine.

## Controls

| Control | What it does |
|---|---|
| View | *One direction* shows every screen of one direction. *Compare all* shows one screen side by side in every direction that has it (D first). |
| Direction | A, B, C or D (used in the *One direction* view). |
| Screen | Today, Chat, Tasks, Sessions or Activity (used in the *Compare all* view). |
| Theme | Light or dark, for the phones and the page around them. |
| Language | English or Turkish, for every visible string, date and number. |

## URL parameters

The controls keep the address bar in sync, so any combination can be linked
or screenshotted. Names and values are case-insensitive; anything missing or unknown
falls back to the default.

| Parameter | Values | Default |
|---|---|---|
| `view` | `single`, `compare` | `single` |
| `dir` | `A`, `B`, `C`, `D` | `D` |
| `screen` | `today`, `chat`, `tasks`, `sessions`, `activity` | `today` |
| `theme` | `light`, `dark` | `light` |
| `lang` | `en`, `tr` | `en` |

Examples:

```
index.html?dir=D&theme=dark&lang=tr
index.html?view=compare&screen=sessions&theme=light&lang=en
```

## Editing

- `app.js` — the EN/TR string table, the placeholder data, the screen
  templates and the inline SVG icons and charts. Screens are built once and
  shared by all directions; the few structural differences are keyed on the
  direction. `DIR_SCREENS` lists the screens of each direction and `CHOSEN`
  names the default one.
- `styles.css` — page chrome, the shared phone skeleton, then one block per
  direction: CSS custom properties per direction and theme, followed by that
  direction's component styles.

All placeholder content is invented. Keep it generic: no real names,
institutions, addresses or paths. Do not add third-party logos, icon sets,
fonts or other assets.
