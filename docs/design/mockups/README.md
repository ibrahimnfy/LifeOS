# App mockups — visual directions

Static HTML mockups of the three visual directions proposed in
[`docs/app-design.md`](../../app-design.md), section 5:

- **A — Calm paper:** warm paper tones, serif titles, hairline cards, one deep-teal accent.
- **B — Material You:** tonal surfaces and standard Material 3 shapes. On a phone
  the colours would follow the wallpaper; the purple palette here is only one example.
- **C — Dark console:** near-black, high contrast, monospace headings and
  numbers, one amber accent. Dark is its primary look.

Each direction shows the same three screens (Today, Chat, Activity) with the
same invented placeholder content, so only the design differs.

## How to open

Double-click `index.html` (or open it in any current browser). There is no
server, no build step and no network access: just `index.html`, `styles.css`
and `app.js`. Fonts come from system font stacks, so the exact typefaces
depend on what is installed on the machine.

## Controls

| Control | What it does |
|---|---|
| View | *One direction* shows Today, Chat and Activity for one direction. *Compare all* shows one screen in A, B and C side by side. |
| Direction | A, B or C (used in the *One direction* view). |
| Screen | Today, Chat or Activity (used in the *Compare all* view). |
| Theme | Light or dark, for the phones and the page around them. |
| Language | English or Turkish, for every visible string, date and number. |

## URL parameters

The controls keep the address bar in sync, so any combination can be linked
or screenshotted. Names and values are case-insensitive; anything missing or unknown
falls back to the default.

| Parameter | Values | Default |
|---|---|---|
| `view` | `single`, `compare` | `single` |
| `dir` | `A`, `B`, `C` | `A` |
| `screen` | `today`, `chat`, `activity` | `today` |
| `theme` | `light`, `dark` | `light` |
| `lang` | `en`, `tr` | `en` |

Examples:

```
index.html?dir=B&theme=dark&lang=tr
index.html?view=compare&screen=activity&theme=light&lang=en
```

## Editing

- `app.js` — the EN/TR string table, the placeholder data, the screen
  templates and the inline SVG charts. Screens are built once and shared by
  all directions; the few structural differences are keyed on the direction.
- `styles.css` — page chrome, the shared phone skeleton, then one block per
  direction: CSS custom properties per direction and theme, followed by that
  direction's component styles.

All placeholder content is invented. Keep it generic: no real names,
institutions, addresses or paths.
