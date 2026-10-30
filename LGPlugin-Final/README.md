# LGPlugin — LAST GAMBLE: RE:LIFE

Paper 1.21.1+ / Java 21 plugin source. Build with GitHub Actions or Gradle.

## Build

The included workflow builds the JAR and uploads it as an Actions artifact. Download the `LGPlugin` artifact from the successful workflow run, unzip it, then place `LGPlugin-1.0.0.jar` in the server's `plugins/` folder.

## Commands

- `/phase1` or `/phase1 start` — start Phase 1
- `/phase1 reset` — cancel the current session and clear temporary event state
- `/phase1 restart` — reset and start a fresh session
- `/phase1 stop` — stop and reset
- `/phase1 status` — show current state
- Alias: `/lg phase1 [start|reset|restart|stop|status]`

All event commands require `lg.admin` (operators by default). `lg.bypass` excludes an operator/player from Phase 1.

## Implemented in this baseline

- Assigns a card to every eligible online player before teleport begins.
- Staggered, asynchronous player teleports; bounded spawn-area placement rather than an unbounded random-location search.
- Dramatic intro: Wither/Beacon audio, Darkness transition, portal/soul particles, dramatic titles and finale sound.
- Starter inventory, leather armor, health/food setup and event boss bar.
- Idempotent reset/restart with session IDs to prevent stale teleport callbacks from affecting a newer run.
- Suppresses vanilla death messages during the event and broadcasts RP-name-based elimination notices.

## Important scope note

This is a clean, performance-conscious Phase 1 foundation. It does **not** yet implement the full previously discussed game design (round timer, MENACE role/tasks, voting GUI, HEARTS detection ability, full RP-name commands, persistent stats, sidebar, chat restrictions, win conditions, or all reconnect policies). Those systems need to be added and tested as separate modules before this should be treated as the complete production game plugin.

## Performance notes

Teleports are issued one player at a time using Paper's `teleportAsync`; no repeating per-tick world scan or forced mass teleport loop is used. Destination selection is deliberately limited to a small radius around the configured world's spawn. Test on a staging server before using it in a live event.
