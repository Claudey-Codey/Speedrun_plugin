# Dueley for Fabric

The Fabric mod version of Dueley, for **Minecraft 26.2**. It plays exactly like the Paper plugin in the repo root
(see the root [`README.md`](../README.md) for how a match goes): lobby, seed menu, your own copy of the seed, one hour
to gather gear, then a fight in a shrinking arena. Solo practice works too.

The mod is **server-side only**. It runs on whatever runs the world (a dedicated Fabric server, or your own game when
you play singleplayer or host a world). Players who join need **no Dueley and no other mods**: a vanilla client works,
and so does Fabric + Essential.

## Install

1. Install **Fabric Loader** (0.20.1 or newer) for Minecraft **26.2**, with **Java 25**.
2. Put **Fabric API** for 26.2 and `Dueley-fabric-1.0.0+26.2.jar` in the `mods/` folder.
   Nothing else is needed: the two libraries Dueley uses (Fantasy for runtime worlds, sgui for the menu) are inside the jar.
3. Start the game or server.

## Singleplayer (practice)

1. Create a world (cheats are **not** needed; any world type works, but keep "Generate structures" on, see below).
2. You start in the Dueley lobby. Run `/dy solo` and pick a seed.
3. The world's host can always use the admin commands (`/dy time`, `/dy stop`, `/dy debug ...`), even with cheats off.

Pressing Esc in a singleplayer world that isn't open to others pauses the game, and the Dueley timer with it.

## Playing with a friend (Essential or Open to LAN)

1. You (the host) install Fabric + Fabric API + Dueley, open your world, and host it with **Essential** ("Host World" /
   invite your friend) or with **Open to LAN**.
2. Your friend needs only what Essential needs (Fabric + Essential), **not** Dueley.
3. When the second player is in, the seed menu opens for both. Right-click the Nether Star (or `/dy menu`) to reopen it.

A dedicated Fabric server works the same way: drop the two jars into `mods/`.

## Commands

| Command | Who | What |
|---|---|---|
| `/dueley toggle_timer` | everyone | Show or hide the timer |
| `/dueley menu` | everyone | Open the seed selection screen |
| `/dueley solo` | everyone | Turn solo practice on or off (only when you're alone in the lobby) |
| `/dueley info` | everyone | Show the state, seed and time left |
| `/dueley stop` | admin | Abort the game and return to the lobby |
| `/dueley time <seconds>` | admin | Set the time left in the gather phase (for testing) |
| `/dueley debug timer_forward <number> <seconds\|minutes>` | admin | Fast-forward the gather timer or the arena border (for testing) |

`/dy` is an alias for `/dueley`. **Admin** = an op (permission level 2), the host of a singleplayer/LAN/Essential world,
or anyone a permission mod gives `dueley:admin`. The server console is always admin.

## Configuration (`config/dueley.yml`)

Same keys, defaults and comments as the plugin's `config.yml`; it is written on first start and read again at the start
of each game. It is YAML (only the simple subset this file uses: comments, nested keys, `[a, b]` or `- a` lists).

```yaml
gather-minutes: 60            # length of the gather phase
start-countdown-seconds: 10
warning-minutes: [5, 4, 3, 2, 1]
fight-countdown-seconds: 10
difficulty: NORMAL
enable-nether: true
arena:
  start-size: 100
  end-size: 5
  shrink-minutes: 15
  border-damage: 1.0
good-seed:
  max-distance: 400           # how close the structure must be to spawn
  max-attempts: 6             # seeds to try; after that the spawn is moved near the closest structure found
return-to-lobby-seconds: 15
```

## Worlds

Dueley makes the same worlds as the plugin, with the same names: `minecraft:dueley_lobby` (kept) and temporary
`minecraft:dueley_g…` game worlds (each player's overworld and nether, and the arena), stored under
`<world>/dimensions/minecraft/`. Game worlds are deleted when the game returns to the lobby and when the server stops;
leftovers from a crash are removed at the next start. All of them use the vanilla overworld / nether dimension types,
which is what lets unmodded clients join. To teleport someone from the console, use `execute in <world> ...`.

## Building

```sh
cd fabric
./gradlew build      # needs Java 25; the jar ends up in build/libs/Dueley-fabric-1.0.0+26.2.jar
```

Code layout mirrors the plugin: `GameManager` (the match), `GameListener` (events), `DueleyCommand`, `world/`
(`WorldService`, `ArenaGenerator`), `menu/` (`SeedMenu`, `MenuListener`). `mixin/` holds the few hooks Fabric API has no
event for; all of them only act on the server.

## Differences from the Paper plugin

Gameplay, texts, colours, titles, sounds, timings, items, menu, commands and config are the same. These are the places
where Fabric/vanilla works differently and the closest equivalent was used:

- **Config**: `config/dueley.yml` instead of `plugins/Dueley/config.yml` (same content).
- **Admin**: op level 2 / the world's host / `dueley:admin` instead of the `dueley.admin` permission (default op).
  The host of a singleplayer or hosted world is admin even without cheats.
- **Command parsing**: `/dueley` and `/dy` are Brigadier commands that take the rest of the line as one argument, so all
  replies (help for unknown subcommands, "No permission.", usage lines) and the tab suggestions match the plugin; the
  client only highlights it as one argument. Over RCON, multi-line replies such as the help arrive without line breaks
  (vanilla RCON joins them).
- **Weather**: since 26.1 vanilla has one weather for the whole server, so both players' worlds share the main
  overworld's weather (cleared when a game starts) instead of each world having its own. The lobby and arena never
  have weather, like in the plugin.
- **World spawn**: since 26.1 vanilla has only one server-wide spawn. Dueley keeps each game world's spawn itself (used
  for the start position, respawns and spectators, as in the plugin), but a plain compass (not the tracker compass)
  spins in the game worlds, and `/setworldspawn` doesn't affect them.
- **Structures**: whether structures generate is a world-wide vanilla setting. If the host world was created with
  "Generate structures" off, the game worlds have none either and "Random Good Seed" falls back to a random seed.
- **Advancements** that check for "the" nether by name (such as *We Need to Go Deeper*) don't trigger in Dueley's
  nethers. Advancement chat messages are off in game worlds anyway.
- **World creation**: worlds are created through Fantasy and don't pre-generate a spawn area like Paper's
  `createWorld`; only the chunks the spawn search needs are generated, the rest generates as players explore. Each step
  still runs on its own tick, so you may see "Can't keep up!" lines in the log while generating (harmless).
- **Teleports** are synchronous (Paper's `teleportAsync` loads chunks first).
- **Portals**: the exit portal is found/built by vanilla's portal logic from the same target point the plugin computes.
  Lighting portals in Dueley's worlds works like on Paper (vanilla only allows it in the worlds named
  `minecraft:overworld`/`minecraft:the_nether`; a small mixin allows it for any overworld/nether-type world).
  Only End portal blocks are closed (End gateways can't occur in an overworld or nether).
- **Bed / anchor respawns** are recognised by the block at the respawn point (Paper's `isBedSpawn()/isAnchorSpawn()`).
- **Singleplayer pause**: Esc in a world that isn't opened to others pauses the whole game, timers included.
