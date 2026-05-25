# Eternal V1

Ban-/Mute-/Report-System für CloudNet V3 Netzwerke (BungeeCord + Spigot 1.19, Java 17).
Inklusive REST-API und Angular-Dashboard.

## Module

| Modul            | Was es macht                                                            |
| ---------------- | ----------------------------------------------------------------------- |
| `eternal-core`   | Shared models, Storage (SQLite/MySQL via HikariCP), Services, CloudNet-Reflection-Bridge |
| `eternal-spigot` | Spigot/Paper 1.19 Plugin — Listener (Join/Quit/Chat), Report-GUI, `/report`, `/reportsystem`, `/eternalreport`. Optionale CloudNet-Bridge wenn CloudPerms erkannt wird. |
| `eternal-bungee` | BungeeCord Plugin — alle DB-Commands (`/ban`, `/unban`, `/mute`, `/unmute`, `/lookup`, `/history`, `/modify`, `/resethistory`, `/eternal`). Optionale CloudNet-Bridge wenn CloudPerms erkannt wird. |
| `eternal-api`    | Javalin-basierter REST-Service für das Web-Dashboard                    |
| `eternal-web`    | Angular 17 Dashboard (Material + Tailwind, Dark-Theme mit Pink-Akzenten) |

## Quick start

```bash
mvn -DskipTests clean install
```

Erzeugte Artefakte:
- `eternal-spigot/target/EternalSpigot-0.1.0-SNAPSHOT.jar` → in jeden Spigot-Server (`plugins/`)
- `eternal-bungee/target/EternalBungee-0.1.0-SNAPSHOT.jar` → in den Proxy (`plugins/`)
- `eternal-api/target/EternalApi-0.1.0-SNAPSHOT.jar` → standalone REST-Service (`java -jar ...`)

Web-Dashboard:
```bash
cd eternal-web && npm install && npm run build
# Output in dist/browser/ - dann via Apache/Nginx servieren
```

## Architektur in einem Bild

```
                 ┌──────────────────────────┐
                 │     Angular Dashboard    │
                 │       (eternal-web)      │
                 └────────────┬─────────────┘
                              │ HTTPS/REST
                              ▼
                 ┌──────────────────────────┐
                 │   Javalin REST-API       │
                 │    (eternal-api)         │
                 └────────────┬─────────────┘
                              │
                              ▼
                ┌────────────────────────────┐
                │  MySQL / SQLite (HikariCP) │
                └──────┬───────────────┬─────┘
                       │               │
              ┌────────▼─────┐   ┌─────▼────────┐
              │  Bungee      │   │  Spigot 1.19 │
              │  Plugin      │   │  Plugins     │
              │              │   │              │
              │ /ban,/mute,  │   │ /report,     │
              │ /lookup,     │   │ /reportsys,  │
              │ /history,    │   │ /eternalrpt  │
              │ /modify,...  │   │              │
              └──────────────┘   └──────────────┘

   CloudNet-CloudPerms wird auf BEIDEN Seiten automatisch erkannt;
   ohne ihn vergibt man eternal.* Permissions manuell.
```

## Konfiguration

Alles wichtige liegt in `plugins/Eternal/` (Spigot) bzw. `plugins/Eternal/`
(Bungee) und wird beim ersten Start aus dem JAR kopiert:

- `config.yml` — Sprache, DB-Zugang, Report-Einstellungen, REST-API-Bridge, **CloudNet-Bridge** (`cloudnet.groups`)
- `reasons.yml` — Bann-/Mute-Gründe inkl. Permissions, Admin-Flag, Eskalations-Ladder
- `translations/de.yml` + `en.yml` — alle Chat-Strings

Sprache umschalten: `language: en` in der `config.yml`.

## Features

- 3-Tier-Rollen: PLAYER / MOD / ADMIN
- Tier aus CloudNet-SortID **oder** `eternal.tier.<n>` Permission (max gewinnt)
- Per-Reason-Permissions, optionale Admin-Bann-Markierung
- Eskalations-Ladder: 1./2./3. Vergehen können unterschiedliche Dauern haben
- Session-Tracking (IPs, Login/Logout) im `/lookup`
- Klickbare Issuer-Namen mit Rang-DisplayName aus CloudNet-Chat
- `/modify <banid> setduration|setreason` mit modified-by-Tracking
- `/resethistory <player>` (hard delete oder soft via Config)
- Auto-Close-Report bei Ban + Report→Ban-Verlinkung
- Web-Entbannungsanträge (öffentlich für gebannte Spieler + intern für eingeloggte)
- Web→Ingame Teleport via Action-Queue + Bungee-Plugin-Messaging-Connect

## Permissions

| Permission                    | Default | Beschreibung                                                |
| ----------------------------- | ------- | ----------------------------------------------------------- |
| `eternal.ban`                 | op      | /ban + /mute regulär                                        |
| `eternal.ban.admin`           | false   | Admin-Banns (reasons mit `admin: true`)                     |
| `eternal.unban`               | op      | /unban regulär                                              |
| `eternal.unban.admin`         | false   | Admin-Banns aufheben                                        |
| `eternal.lookup`              | op      | /lookup + /history                                          |
| `eternal.modify.duration`     | false   | /modify setduration                                         |
| `eternal.modify.reason`       | false   | /modify setreason                                           |
| `eternal.history.reset`       | false   | /resethistory                                               |
| `eternal.reportsystem`        | op      | /reportsystem login/list                                    |
| `eternal.report`              | true    | /report (Spieler reportet Spieler)                          |
| `eternal.tier.0` … `tier.100` | -       | Rang-Override (max(perm, CloudNet-SortID) gewinnt)          |
| `eternal.bypass`              | false   | Tier-Schutz komplett ignorieren                             |
| `eternal.notify`              | op      | Broadcast bei neuen Bans/Mutes/Reports                      |

## Lizenz

Eigenes Projekt, keine offizielle Lizenz vergeben — Code ist nur intern gedacht.
