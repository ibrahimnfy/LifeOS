# LifeOS – AI-Assisted Personal Life Management App

LifeOS is an offline-first Android app that helps me track the parts of my life
that matter: notes and ideas, study schedule and exams, calendar, spending,
sleep, and digital wellbeing. A Claude-powered layer turns this data into
insights, summaries, and timely reminders.

## How it works

LifeOS runs in two modes:

**Offline mode (always available)**
- Quick notes and idea capture
- Study schedule, exam and calendar tracking
- Expense logging
- Sleep and screen-time tracking (Android APIs)
- Local reminders

**AI mode (when the bridge server is online)**
- Weekly life summaries and pattern detection
  (e.g. "exam in 3 days, but sleep has dropped below 6 hours")
- Natural-language questions about my own data
- Turning voice/raw notes into organized tasks
- Smart push notifications

## Architecture

    Android app (local database, offline-first)
            │  sync over a private network
            ▼
    Bridge server on Ubuntu
            │  runs Claude Code in headless mode (claude -p)
            ▼
    Insights → back to the app + push notifications

## Tech stack

Not finalized yet. Options under consideration:

- Android app: Kotlin (Jetpack Compose) or Flutter
- Local storage: SQLite-based database (e.g. Room)
- Bridge server on Ubuntu: Python or Node.js
- AI: Claude Code in headless mode
- Notifications: Firebase Cloud Messaging, ntfy, or similar
- Networking: a private network solution such as Tailscale

Decisions will be documented here as the project evolves.

## Roadmap

- [ ] Choose tech stack and set up project skeleton
- [ ] Notes and idea capture
- [ ] Study schedule, exams and calendar
- [ ] Expense tracking
- [ ] Bridge server with Claude integration
- [ ] Weekly AI summary and push notifications
- [ ] Sleep and screen-time tracking

## Status

Early development. Built with Claude Code.
