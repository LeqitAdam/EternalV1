# Eternal

[![Build](https://github.com/LeqitAdam/EternalV1/actions/workflows/build.yml/badge.svg)](https://github.com/LeqitAdam/EternalV1/actions/workflows/build.yml)
[![Java](https://img.shields.io/badge/Java-17-orange?logo=openjdk)](https://adoptium.net/)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.19-green?logo=minecraft)](https://www.minecraft.net/)
[![Angular](https://img.shields.io/badge/Angular-17-red?logo=angular)](https://angular.io/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A network-wide moderation suite for Minecraft — bans, mutes, reports, unban
appeals, and a video-style **replay system** so staff review the actual
scene instead of teleporting after the fact. Built for a BungeeCord +
Spigot 1.19 network running on CloudNet V3, with an Angular dashboard for
remote moderation.

> ⚠️ Status: feature-complete prototype on a single test network. Not a
> public download yet — the repo is here as a portfolio piece, showing
> how the pieces fit together end-to-end.

---

## What's in the box

- **Bans & mutes** with permission-gated reasons, an admin tier, an
  escalation ladder (1st/2nd/3rd offense durations), and the usual
  `/lookup`, `/history`, `/modify`, `/resethistory` commands.
- **Reports** via in-game GUI (one icon per reason, fully config-driven)
  or directly from the web dashboard. Auto-broadcast to staff,
  auto-claim, auto-link to the ban that resolved them.
- **Replay system** — the recorder always runs, keeps a configurable
  sliding window of every online player's movement / hits / chat / block
  edits / inventory / item drops, and freezes that buffer when a report
  is filed. Staff who "accept" a report drop into spectator mode with
  fake-player ghosts (ProtocolLib NPCs with real skins) re-acting the
  scene — emerald = play/pause, arrows = seek, barrier = exit. Replays
  retain the recorded skin so they keep looking right even after the
  player has been unbanned or has changed cosmetics.
- **Unban appeals** — public form for banned players, dashboard queue
  for staff. Approve restores the account, deny attaches a kick-screen
  note, shorten cuts the remaining duration from a configurable list of
  templates (1h / 6h / 1d / 7d / …).
- **Angular dashboard** — players see their own punishments + appeal
  status; staff get the full mod toolkit (reports, bans, mutes, player
  lookup, live sessions, statistics). Auto-login the moderator into the
  game server on accept, kick the banned player immediately on web-ban.
- **CloudNet integration** — group memberships translate into
  `eternal.*` permissions on both Spigot and Bungee via reflection;
  works just as well without CloudNet (manual `eternal.tier.<n>` perms).

## Tech stack

| Layer    | Choice                                                   | Why                                                |
| -------- | -------------------------------------------------------- | -------------------------------------------------- |
| Server   | Spigot/Paper 1.19, BungeeCord                            | Target runtime is a CloudNet V3 network on Java 17 |
| Storage  | MySQL or SQLite via HikariCP, idempotent migrations      | One schema, runs locally or in prod                |
| REST     | Javalin 6                                                | Minimal HTTP server, no framework overhead         |
| Replay   | Gzipped binary file + flat-text index, ProtocolLib NPCs  | Avoids a second DB; ghosts render with real skins  |
| Frontend | Angular 17 standalone components + Material + Tailwind   | Material for forms, Tailwind for layout polish     |
| Build    | Maven multi-module, GitHub Actions, Java-17 bytecode gate| One `mvn install` produces every JAR + dashboard   |

## Architecture

```
        ┌────────────────────────┐
        │   Angular Dashboard    │
        │     (eternal-web)      │
        └───────────┬────────────┘
                    │  HTTPS / REST
                    ▼
        ┌────────────────────────┐         ┌──────────────────────┐
        │    Javalin REST API    │ ◄──────►│  MySQL / SQLite      │
        │     (eternal-api)      │         │   (HikariCP pool)    │
        └───────────┬────────────┘         └──────────┬───────────┘
                    │  action queue                    │
                    ▼                                  ▼
        ┌────────────────────┐               ┌──────────────────────┐
        │  BungeeCord Proxy  │ ─plugin-msg─► │  Spigot Backends     │
        │  (eternal-bungee)  │ ◄─plugin-msg─ │  (eternal-spigot)    │
        │                    │               │   + eternal-replay   │
        │  /ban  /unban      │               │   /report (GUI)      │
        │  /mute /unmute     │               │   /replay play <id>  │
        │  /lookup /history  │               │   ProtocolLib NPCs   │
        │  /modify  /eternal │               │                      │
        └────────────────────┘               └──────────────────────┘
```

A few things worth pointing out:

- **Bungee owns DB-touching commands** (`/ban`, `/lookup`, `/history`, …).
  Spigot has the same commands as a single-server fallback, but on a
  network Bungee intercepts them before they reach the backend — so
  there's no double-bookkeeping.
- **Spigot owns the GUI and replay**. When `/report <user>` arrives at
  Bungee, it resolves the target and sends a plugin-message to the
  reporter's current backend, which opens the inventory there.
- **Cross-server actions** (web → in-game) use a small action queue:
  the API persists rows like `(type, target_uuid, payload)`, every
  Spigot polls for actions targeting its online players. Replays are
  paused / playback stopped / players kicked through the same mechanism.

## Module layout

| Module           | What it does                                                            |
| ---------------- | ----------------------------------------------------------------------- |
| `eternal-core`   | Shared models, storage interface + SQL implementation, config layer, services. Zero Bukkit/Bungee deps so both sides can import it. |
| `eternal-spigot` | Spigot/Paper plugin — listeners, in-game commands, report GUI, plugin-message bridges to Bungee, replay-bridge to the standalone replay plugin. |
| `eternal-bungee` | BungeeCord plugin — proxy-level commands, kick handling, cross-server broadcasts, web-action polling, plugin-message hub. |
| `eternal-api`    | Standalone Javalin REST service — auth via Minecraft account link or API key, sessions, reports, appeals, action queue. |
| `eternal-replay` | Standalone replay plugin — always-on recorder, persisted gzipped replay files, spectator-mode playback, ProtocolLib NPCs with skin replay. |
| `eternal-web`    | Angular 17 dashboard — Material + Tailwind, dark theme, Eternal-pink accents. Player view + moderator view + admin view. |

## Quick start

```bash
mvn -DskipTests clean install
```

Output JARs:

- `eternal-spigot/target/EternalSpigot-*.jar` → every Spigot's `plugins/`
- `eternal-bungee/target/EternalBungee-*.jar` → Bungee proxy's `plugins/`
- `eternal-replay/target/EternalReplay-*.jar` → every Spigot's `plugins/`
- `eternal-api/target/EternalApi-*.jar` → standalone REST host (`java -jar EternalApi-*.jar`)

Dashboard:

```bash
cd eternal-web
npm install
npx ng build --configuration production
# Serve dist/eternal-web/browser/ via nginx or any static host
```

First boot of each plugin copies `config.yml`, `reasons.yml`, and
`translations/de.yml` + `en.yml` into `plugins/Eternal/`. Edit them
there — they're never overwritten on update.

## Configuration

- **`config.yml`** — language, database (MySQL/SQLite), report settings,
  REST-API bridge (`api.enabled`, `api.shared-secret`), CloudNet group
  mapping under `cloudnet.groups`.
- **`reasons.yml`** — punishment reasons (id, label, type, duration,
  permission gate, admin flag, escalation ladder) + report reasons
  (id, label, GUI item material, GUI slot) + appeal shorten templates.
- **`api.yml`** (next to the REST jar) — bind host/port, CORS origins,
  shared secret, API keys, role tier thresholds.

Switch language with `language: en` in `config.yml`. Every translation
key lives in both `de.yml` and `en.yml` — fallback is `de`.

## Permissions

Verbs split into base and `.admin` escalation. Tier gating
(`max(eternal.tier.X perm, CloudNet sortId)`) affects ban/unban only —
lookup/history are flat.

| Permission                  | Default | Purpose                                            |
| --------------------------- | :-----: | -------------------------------------------------- |
| `eternal.ban`               |  op     | `/ban`, `/mute`                                    |
| `eternal.ban.admin`         |  false  | Reasons flagged `admin: true`                      |
| `eternal.unban`             |  op     | `/unban`                                           |
| `eternal.unban.admin`       |  false  | Lift admin bans                                    |
| `eternal.lookup`            |  op     | `/lookup`, `/history`                              |
| `eternal.modify.duration`   |  false  | `/modify setduration`                              |
| `eternal.modify.reason`     |  false  | `/modify setreason`                                |
| `eternal.history.reset`     |  false  | `/resethistory`                                    |
| `eternal.reportsystem`      |  op     | `/reportsystem`, GUI staff view                    |
| `eternal.report`            |  true   | Submit reports                                     |
| `eternal.notify`            |  op     | Receive ban / mute / report broadcasts             |
| `eternal.replay.rewatch`    |  op     | `/replay list`, `/replay play <id>`, `/replay reports` |
| `eternal.replay.debug`      |  op     | `/replay status` (recorder internals)              |
| `eternal.tier.0` … `tier.N` |  -      | Rank floor (max of perm and CloudNet sortId wins)  |
| `eternal.bypass`            |  false  | Skip tier protection entirely                      |

## Design notes worth a read

- [`eternal-replay/src/main/java/de/eternal/replay/model/Recordable.java`](eternal-replay/src/main/java/de/eternal/replay/model/Recordable.java)
  — sealed interface + binary codec for replay records. v2 of the codec
  added per-player Mojang-signed skin properties so replays render
  identically to the original session even after the player's appearance
  changes.
- [`eternal-api/src/main/java/de/eternal/api/Routes.java`](eternal-api/src/main/java/de/eternal/api/Routes.java)
  — every REST endpoint in one file, with the cross-server action queue
  pattern that drives "click in the browser → effect in-game".

## License

MIT — see [LICENSE](LICENSE).
