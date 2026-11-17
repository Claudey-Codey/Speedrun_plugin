# Trogger

Trogger is Tuni's 1v1 Minecraft gear-race plugin: two players get their own copy of the same seed, have an hour to gather gear, then fight in an arena with a shrinking border. "Tuni" is the owner's name.

Stack decision: Paper plugin for Minecraft **26.2** (Java 25, Maven, `paper-api` 26.2). Gameplay and commands: `README.md`. Code lives in `src/main/java/dev/trogger/` (`GameManager` runs the match; `world/` makes the lobby, game worlds and arena; `menu/` is the seed selection screen).

Idea board: `docs/IDEAS.md`. Whenever Tuni mentions an idea or asks to save one, add it there (one line), keep the Next up / Ideas / Done sections current, and commit it.

## Working model: architect + subagents

The main Claude session acts as **architect and orchestrator**: it plans, breaks work down, writes briefs, reviews results for consistency, and handles git. All real work (coding or design) is delegated to subagents.

Subagent model selection:

| Task type | Model | Effort |
|---|---|---|
| Default (most coding/design work) | Opus 5.5 | medium |
| Complex (architecture, tricky systems, cross-cutting changes) | Opus 5.5 | high |
| Small, well-defined (single-file fixes, boilerplate, tests, docs) | Sonnet 5.5 | high |

## Testing

Build with `mvn package` (needs Java 25). Test on a real Paper 26.2 server: drop `target/Trogger-*.jar` into `plugins/`, use offline mode + RCON, and drive two players with bots. Admin shortcuts: `/trogger time <seconds>` skips the gather phase, `/trogger stop` aborts a game. When teleporting players from the console, use `execute in <world>` or `execute as <player> at @s`; a bare console `/tp` sends them to the default world.
