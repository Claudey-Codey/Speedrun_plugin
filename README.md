# Dueley

A 1v1 Minecraft gear race for **Paper 26.2** (Java 25).

Two players get **their own copy of the same seed** and **one hour** to collect gear.
When the time runs out, both are pulled into an arena with a **100×100 border that shrinks to 5×5 over 15 minutes**.
Last one standing wins.

## How a match goes

1. **Lobby.** Players spawn on a floating platform. Until **2 players** are online the whole server is
   *tick-frozen*: no mobs move, the sun doesn't move. It's as if nobody is playing.
2. **Seed selection.** When the second player joins, a menu opens for both players (re-open it with the
   **Nether Star** or `/dueley menu`):
   - **Random Seed**: any random world.
   - **Random Good Seed**: then choose **Village**, **Shipwreck** or **Trial Chamber**. Dueley searches for a
     seed with that structure near spawn, and each player gets a **compass that points to it**.
   - **Custom Seed**: type any seed in chat (numbers or text, same rules as vanilla; `cancel` goes back).

   The game stays frozen until a seed is picked.
3. **World generation.** Each player gets their own overworld (and nether) with the chosen seed, plus a shared arena.
4. **10 second countdown.** Players are in their worlds but can't move yet.
5. **Gather phase (60 min).** The timer is shown above the hotbar. Toggle it with `/dueley toggle_timer`.
   - Big title warnings at **5, 4, 3, 2 and 1 minute** left, then a **10 second countdown** to the fight.
   - Nether portals take each player to **their own** nether. The End is disabled.
   - If a player disconnects, the game **pauses** (frozen) until they come back, then resumes after 5 seconds.
6. **Fight.** Both players are teleported into the arena (they keep their inventory). **Health and hunger are reset.**
   The border starts at 100×100 and shrinks to 5×5 over 15 minutes. Dying, or leaving, loses the fight.
7. After 15 seconds everyone goes back to the lobby, the game worlds are deleted, and you can play again.

## Solo practice

Alone on the server? Run `/dueley solo` in the lobby to practice. It only works when you're the only player
online; run it again to turn it off. The seed menu opens and every seed option works. You get your own
world (and nether), the usual countdown, gather phase and arena fight, just without an opponent.

The practice ends when:
- you **die** in the arena (*PRACTICE OVER*, showing how long you lasted),
- you're still alive **60 seconds after the border reaches its final size** (*PRACTICE COMPLETE*),
- you leave during the fight, or an admin runs `/dueley stop`.

Then everyone goes back to the lobby as usual. If a second player joins while you're still in the lobby,
solo practice turns off and the normal 1v1 seed choice starts. Anyone joining during a solo game spectates.
Solo practice always turns off when the game returns to the lobby.

## Commands

| Command | Who | What |
|---|---|---|
| `/dueley toggle_timer` | everyone | Show or hide the timer |
| `/dueley menu` | everyone | Open the seed selection screen |
| `/dueley solo` | everyone | Turn solo practice on or off (only when you're alone in the lobby) |
| `/dueley info` | everyone | Show the state, seed and time left |
| `/dueley stop` | op (`dueley.admin`) | Abort the game and return to the lobby |
| `/dueley time <seconds>` | op (`dueley.admin`) | Set the time left in the gather phase (for testing) |
| `/dueley debug timer_forward <number> <seconds\|minutes>` | op (`dueley.admin`) | Fast-forward the gather timer or the arena border (for testing) |

`/dy` is an alias for `/dueley`.

## Install

1. Build it with `mvn package` using **JDK 25** (Paper 26.x needs Java 25, e.g.
   `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn package`). The jar ends up in `target/Dueley-1.0.0.jar`.
   Or use a built `Dueley-1.0.0.jar`.
2. Put the jar in the `plugins/` folder of a **Paper 26.2** server (running on Java 25) and start the server.
3. Keep `allow-nether=true` in `server.properties` (the default). Paper only fires the portal events Dueley
   uses when the nether is enabled.

Dueley makes its own worlds (`dueley_lobby` plus temporary `dueley_g…` game worlds). Since Paper 26.1 they are stored
under `world/dimensions/minecraft/`. You don't need to set up anything else.

## Fabric mod

Want to play without a server? `fabric/` has a **Fabric mod version** of Dueley for 26.2. It plays the same, runs only on
the host (singleplayer, Open to LAN, Essential world hosting or a Fabric server), and players who join need no mods.
See [`fabric/README.md`](fabric/README.md).

## Configuration (`plugins/Dueley/config.yml`)

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

Settings are read again at the start of each game.

## Notes

- World generation blocks the server for a few seconds per world. "Random Good Seed" can take a bit
  longer because it tries several seeds. On a slow machine you may see Paper's
  "server has not responded" warnings while it generates. These are harmless.
- Ender chests are cleared when a game starts, so you can't bring gear in from a previous game.
