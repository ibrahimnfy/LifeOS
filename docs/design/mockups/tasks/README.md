# Tasks screen redesign: mockups (#203)

The owner does not like the current Tasks screen. Before any app code, three
clickable mockups show different ways to build it. **No app code changes**;
the owner chooses in a human issue, and the chosen one is then built in the
app in its own issue.

Everything is in one self-contained file, [`index.html`](index.html): inline
CSS and JavaScript, the system font, no libraries and no network requests.
All tasks are invented placeholders ("Call the dentist", "Example course A:
homework 2"); nothing is stored and nothing is sent anywhere.

## The three variants

The strip at the top of the page (not part of the app) switches between
them; `index.html#a`, `#b` and `#c` open one directly.

| | Name | What it is |
|---|---|---|
| **A** | Calm list | One card per group (Overdue, Today, Upcoming, No date) with sticky group headers and 64 dp rows. A prominent add field on top, framed by the gradient ring, with date chips (Today, Tomorrow, No date). Closest to the rest of the app. |
| **B** | Focus | A summary card on top (tasks left, overdue count, a progress ring for today). Overdue and Today are large cards (84 dp, 18 sp title, a 44 dp check button on the right; overdue ones in the rose tone). Upcoming is a day-by-day timeline with the date on the left. No date is folded away until tapped. |
| **C** | Dense list + week | A week strip (today and the next six days, with a dot per task) and filter chips with counts (All, Overdue, Today, Upcoming, No date). Flat 56 dp rows without cards, so more tasks fit on a screen. Tapping a day shows that day; a new task gets the selected day. |

## What all three do

These are the owner's requirements for the redesign, and each variant
really responds to them:

- **Groups:** Overdue / Today / Upcoming / No date (Gecikmiş / Bugün /
  Yaklaşan / Tarihsiz).
- **Quick add on top:** type and press the button or Enter; the date of the
  new task is picked beside the field (chips in A, a pill that cycles in B
  and C).
- **Large touch targets:** every control is at least 48 dp high; rows are
  56 to 84 dp.
- **A tap opens an edit page** (not a sheet): title, date (quick chips and a
  date field), note, Save, Mark as done, Delete.
- **Swipe right completes, swipe left deletes**; the row shows which while it
  moves and snaps back when released early. The round check completes too.
- **Undo** shows for five seconds after completing, deleting, adding and
  saving.
- **Done** tasks are folded at the bottom and can be opened again.

The look is the app's own: the D3 tokens of `ui/theme/Tokens.kt`, the type
scale of `LifeType`, 20 dp card corners, course and project labels in their
soft tones, the gradient ring on the featured element, and the slim bottom
bar (shown for context, not active). Light and dark follow the system; the
half-circle button switches for the session. The language follows the
browser (Turkish if it starts with `tr`, otherwise English); the `TR` / `EN`
button switches.

## How to open

Open `index.html` in Chrome on the phone, or on the computer in Chrome with
device emulation at 412 × 915. On a wide screen the page shows a phone-sized
column.

## Not in the mockups

The coach's order ("Most important"), the "Waiting to sync" state of a task
made offline and the page of a course assignment's task stay as they are in
the app today; the mockups only decide the layout of the list, adding,
editing and the gestures.
