# Eternal — Permissions & Dashboard-Zugriff

Es gibt **keine Legacy-Rollen** (`ADMIN` / `MOD` / `PLAYER`) mehr. Wer was im
Dashboard *und* in-game darf, ergibt sich **allein aus Permissions**, die an die
**CloudNet-Ränge** (= Web-Rollen) vergeben werden. Ein User hat genau den Rang,
den es gibt, und dessen Rechte — nichts wird implizit hochgestuft.

---

## Wie Autorisierung aufgelöst wird

Für jede Aktion fragt die API `PermissionService.has(user, key)`. Auflösung,
erste Entscheidung gewinnt:

1. **User-Override** — Zeile in `eternal_user_permissions` für die UUID
   (`granted=true/false`). Sticht alles (auch in-game) — so bleiben gezielte
   Freigaben (Access-Requests) wirksam.
2. **In-game (CloudPerms-Sync)** — die echten in-game-Permission-Nodes der
   CloudNet-Gruppe (gespiegelt in `eternal_cloud_group_perms`). **Vorrang vor der
   Web-Rolle.** Ein negativer in-game-Node (`-perm`) zählt als *deny*.
3. **Web-Rollen-Grant** — Zeile in `eternal_role_permissions` für die Web-Rolle,
   die der CloudNet-Gruppe des Users entspricht (Rollen-Editor).
4. **Hardcoded-Default** — nur noch `EVERYONE` gewährt automatisch. Die alten
   Defaults `ADMIN_ONLY` / `STAFF_ANY` **gewähren nicht mehr von selbst** (kein
   Legacy-Rang mehr da) → sie zählen als *deny*, solange kein Grant/Wildcard
   greift. Sie bleiben nur als Metadaten im Editor sichtbar.

> **In-game-Sync:** Wenn CloudNet verfügbar ist, spiegelt der Bungee-Proxy alle
> ~30 s die **eigenen** Permission-Nodes jeder Gruppe in die DB — so sieht die
> standalone-API (die kein CloudPerms hat) die in-game-Rechte. Einweg
> (in-game → Web). Gruppen-Vererbung wird **nicht** aufgelöst — nur die direkt
> auf der Gruppe gesetzten Nodes. Ohne CloudNet bleibt der Layer leer und es
> zählen nur Web-Rolle + User-Override.

> **Dashboard → in-game:** Damit ein im Dashboard vergebenes Recht **in-game**
> greift (CloudPerms besitzt dort die Auflösung — ein Bukkit-Attachment würde
> ignoriert), schreibt Eternal den Grant zusätzlich nach CloudPerms: **Rollen-
> Grant → CloudNet-Gruppe** (gilt für alle Mitglieder), **User-Override →
> CloudNet-User**. Der Proxy führt den Schreibvorgang aus (nur er hat den
> CloudNet-Treiber). Clear entfernt den Node wieder; abgelaufene Access-Request-
> Grants räumt der Sweep auch in CloudPerms weg. Ohne CloudNet greift nur der
> Attachment-Fallback. *Hinweis: Rollen-Grants ändern damit die CloudNet-Gruppe
> netzwerkweit.*

### Wildcards
Grants dürfen Wildcards sein. Ein granted Eintrag für …

| Grant            | deckt |
| ---------------- | ----- |
| `*`              | **alles** |
| `eternal.*`      | alle `eternal.…`-Keys |
| `eternal.web.*`  | alle `eternal.web.…`-Keys |

Präzedenz: **exakter Key** > spezifischeres Wildcard > breiteres Wildcard. Ein
`deny` auf den genauen Key sticht ein breiteres `grant`. Beispiel: Rolle hat
`eternal.web.*` (grant) **und** `eternal.web.admin` (deny) → alles Web außer
Admin-Panel.

> **Owner mit `*`:** Hat ein Rang `*` granted, darf er alles — genau wie in-game.

---

## Bootstrap / kein Lockout

Die standalone-API liest Permissions nur aus der **DB** (sie hat kein CloudPerms,
sieht das in-game `*` also nicht direkt). Damit das Entfernen der Legacy-Rollen
niemanden aussperrt:

- **Migration-Seed:** Existiert beim Start **kein** Rang mit `*` / `eternal.*` /
  `eternal.web.*` / `eternal.web.admin`, bekommt der **höchste Rang** (kleinster
  `sortOrder`, z.B. *Owner*) automatisch `*`. Läuft genau einmal, idempotent.
- **Statische API-Keys** (`api.yml`) = **Vollzugriff** (vertrauenswürdige
  Secrets). Garantierter Notfall-/Admin-Zugang, auch wenn (noch) kein Rang Perms
  hat.

Danach konfigurierst du pro Rang im Dashboard unter **Administration → Rollen &
Rechte**, welcher Rang welche `eternal.*`-Rechte hat.

---

## Permission-Katalog

### Dashboard (`eternal.web.*`)
| Key | Zweck |
| --- | --- |
| `eternal.web.dashboard` | Dashboard betreten (Übersicht, Reports-Liste, Bans/Mutes-Liste, Reasons, Appeals-Liste, Appeal *verkürzen*) |
| `eternal.web.player.view` | Spieler-Lookup, History, Suche, Chat-Sessions, Report-Chat-Kontext |
| `eternal.web.appeals.decide` | Entbannungsanträge **genehmigen / ablehnen** |
| `eternal.web.chatlogs` | Chat-Logs durchsuchen |
| `eternal.web.chatlogs.sensitive` | Sensible Befehle (Login/Register/Passwort) einsehen |
| `eternal.web.admin` | Admin-Panel: Aktive User, Rollen-/Rechte-Editor, Benutzerverwaltung |

### Moderation (auch in-game, von der API geteilt)
| Key | Zweck |
| --- | --- |
| `eternal.ban` | Bannen (auch Web-Ban aus Report) |
| `eternal.ban.admin` | Reasons mit `admin: true` |
| `eternal.mute` | Muten (auch Web-Mute aus Report) |
| `eternal.unban` | Entbannen / Bann aufheben |
| `eternal.unban.admin` | Admin-Banns aufheben |
| `eternal.modify.duration` / `.reason` | Bestehende Banns ändern |
| `eternal.history.reset` | History zurücksetzen |
| `eternal.report` | Reporten *(EVERYONE — default an)* |
| `eternal.report.handle` | Reports bearbeiten (Liste, Claim, Close, TP) |
| `eternal.report.notify` / `eternal.notify` | Broadcast-Empfang |
| `eternal.replay.rewatch` / `eternal.replay.debug` | Replays ansehen / Recorder-Diagnose |
| `eternal.ban.reason.<id>` | Reason-spezifischer Ban-Scope (auto-registriert je Reason) |
| `eternal.bypass` | Ignoriert den Tier-Schutz beim Lookup (sonst: eigener `sortId` muss höher als der des Ziels sein) |

### Team / Self-Service
| Key | Zweck |
| --- | --- |
| `eternal.team` | Markiert ein Teammitglied. Schaltet die Seite **„Rechte bestellen"** frei (Self-Service Access-Requests). Gewährt selbst sonst nichts — Admin vergibt ihn an die Team-Ränge. |

> Die in-game-Base-Befehle (`eternal.base.*`) und `eternal.tier.*` sind reine
> Spigot/Bungee-Perms und nicht Teil des Web-Katalogs.

---

## Welcher Bereich braucht was

| Dashboard-Bereich | Permission |
| --- | --- |
| Übersicht / Stats | `eternal.web.dashboard` |
| Reports | `eternal.report.handle` |
| Aktive Bans / Mutes | `eternal.web.dashboard` |
| Web-Ban / Web-Mute aus Report | `eternal.ban` / `eternal.mute` |
| Bann aufheben (normal / admin) | `eternal.unban` / `eternal.unban.admin` |
| Spieler / Chat-Logs | `eternal.web.player.view` / `eternal.web.chatlogs` |
| Entbannungsanträge genehmigen/ablehnen | `eternal.web.appeals.decide` |
| Entbannungsantrag verkürzen | `eternal.web.dashboard` |
| Aktive User / Rollen-Editor / Benutzer | `eternal.web.admin` |

Das Dashboard blendet UI je `eternal.web.*`-Capability aus (`/me.permissions`);
die API erzwingt zusätzlich serverseitig (403 bei fehlender Perm, **kein**
Logout — 401 nur bei ungültigem Token).

---

## Access-Requests (Self-Service)

Statt dass ein Teamler sein Recht beim Admin „erfragen" muss, kann er es direkt im
Dashboard **bestellen** — ein Admin gibt frei.

**Ablauf**
1. Wer `eternal.team` hat, sieht in der Nav **„Rechte bestellen"** (`/access-requests`).
   Dort den Katalog durchsuchen und einen Key mit optionaler Begründung anfragen.
   Schon gehaltene Keys (`held`) und bereits offene Anfragen (`pending`) sind markiert.
2. Admins (`eternal.web.admin`) sehen die Queue unter **Administration →
   Rechte-Anfragen** (`/admin/permission-requests`).
3. **Freigeben** schreibt das Recht als **persönliches User-Override** nur für den
   Anfrager (`eternal_user_permissions`) — optional **befristet** (Permanent / 1d /
   7d / 30d). **Ablehnen** vermerkt nur die Entscheidung.

**Befristung**: Ein abgelaufener Grant wird bei der Auflösung wie nicht vorhanden
behandelt (fällt automatisch raus). Zusätzlich räumt `sweepExpiredUserGrants()`
(läuft beim Öffnen der Admin-Queue) die abgelaufenen Zeilen auf und setzt die
zugehörige Anfrage auf `EXPIRED`.

**Validierung** (serverseitig): Key muss in der Registry existieren, darf nicht
schon gehalten werden, keine doppelte offene Anfrage.

> Damit Teamler überhaupt bestellen können, muss `eternal.team` ihren Rängen im
> Rollen-Editor zugewiesen sein (Owner-`*` deckt ihn).

## Typische Rang-Konfiguration

| Rang | Empfohlene Grants |
| --- | --- |
| Owner | `*` (per Seed) |
| Admin | `eternal.web.*`, `eternal.ban`, `eternal.ban.admin`, `eternal.unban`, `eternal.unban.admin`, `eternal.mute`, `eternal.report.handle`, `eternal.team` |
| Mod | `eternal.web.dashboard`, `eternal.web.player.view`, `eternal.web.chatlogs`, `eternal.report.handle`, `eternal.ban`, `eternal.mute`, `eternal.unban`, `eternal.team` |
| Trial/Team | `eternal.team` (darf Rechte bestellen, sonst nichts — Admin gibt frei) |
| Spieler | nichts (sieht nur eigene Bans + Antrags-Status) |
