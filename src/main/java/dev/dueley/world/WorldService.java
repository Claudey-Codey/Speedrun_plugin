package dev.dueley.world;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Creates, prepares and deletes the worlds Dueley uses. */
public final class WorldService {
    public static final String LOBBY_NAME = "dueley_lobby";
    public static final String GAME_PREFIX = "dueley_g";
    /** Game-world prefixes from earlier names of the plugin (Trogger, Hour Duel); only swept, never created. */
    private static final List<String> LEGACY_GAME_PREFIXES = List.of("trogger_g", "hourduel_g");
    /** The key {@code new WorldCreator(LOBBY_NAME)} gave the lobby, so an existing lobby world is reused. */
    public static final NamespacedKey LOBBY_KEY = NamespacedKey.minecraft(LOBBY_NAME);

    private final JavaPlugin plugin;
    private World lobby;

    public WorldService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ───────────────────────── lobby ─────────────────────────

    public World lobby() {
        if (lobby == null) {
            lobby = WorldCreator.ofKey(LOBBY_KEY)
                    .generator(new VoidGenerator())
                    .generateStructures(false)
                    .createWorld();
            setupStaticWorld(lobby);
            buildLobbyPlatform(lobby);
        }
        return lobby;
    }

    public Location lobbySpawn() {
        return new Location(lobby(), 0.5, 100, 0.5, 0, 0);
    }

    private void buildLobbyPlatform(World w) {
        if (w.getBlockAt(0, 99, 0).getType() != Material.AIR) {
            return; // already built
        }
        int r = 10;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d > r + 0.4) {
                    continue;
                }
                Material m;
                if (d <= 2.5) {
                    m = Material.GOLD_BLOCK;
                } else if (d > 6.5 && d <= 7.5) {
                    m = Material.SEA_LANTERN;
                } else if (d > r - 0.6) {
                    m = Material.POLISHED_BLACKSTONE_BRICKS;
                } else {
                    m = (x + z) % 2 == 0 ? Material.POLISHED_DEEPSLATE : Material.DEEPSLATE_TILES;
                }
                w.getBlockAt(x, 99, z).setType(m, false);
                w.getBlockAt(x, 98, z).setType(Material.BLACKSTONE, false);
            }
        }
        // a little crimson/warped flair on the edge
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            int x = (int) Math.round(Math.cos(a) * (r - 1));
            int z = (int) Math.round(Math.sin(a) * (r - 1));
            w.getBlockAt(x, 100, z).setType(i % 2 == 0 ? Material.CRIMSON_FENCE : Material.WARPED_FENCE, false);
            w.getBlockAt(x, 101, z).setType(Material.LANTERN, false);
        }
        w.setSpawnLocation(0, 100, 0);
    }

    /** Lobby / arena: always day, no weather, no mobs. */
    private void setupStaticWorld(World w) {
        w.setGameRule(GameRules.ADVANCE_TIME, false);
        w.setGameRule(GameRules.ADVANCE_WEATHER, false);
        w.setGameRule(GameRules.SPAWN_MOBS, false);
        w.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0); // no fire spread
        w.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        w.setTime(6000);
        w.setStorm(false);
        w.setThundering(false);
        w.setDifficulty(Difficulty.PEACEFUL);
    }

    // ───────────────────────── game worlds ─────────────────────────

    public World createSurvivalWorld(String name, long seed, World.Environment env, Difficulty difficulty) {
        World w = WorldCreator.ofKey(NamespacedKey.minecraft(name))
                .seed(seed)
                .environment(env)
                .type(WorldType.NORMAL)
                .generateStructures(true)
                .createWorld();
        if (w == null) {
            throw new IllegalStateException("Could not create world " + name);
        }
        w.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        w.setDifficulty(difficulty);
        if (env != World.Environment.NETHER) {
            w.setTime(0); // since 26.1 the nether has no world clock and setTime throws there
        }
        w.setStorm(false);
        w.setThundering(false);
        return w;
    }

    public World createArena(String name) {
        World w = WorldCreator.ofKey(NamespacedKey.minecraft(name))
                .generator(new ArenaGenerator())
                .generateStructures(false)
                .createWorld();
        if (w == null) {
            throw new IllegalStateException("Could not create arena " + name);
        }
        setupStaticWorld(w);
        w.setDifficulty(Difficulty.NORMAL); // so hunger & damage work, but no mobs spawn
        w.setGameRule(GameRules.PVP, true);
        return w;
    }

    /** A spawn location standing on top of the ground at the world's spawn point. */
    public Location safeSpawn(World w) {
        Location s = w.getSpawnLocation();
        int x = s.getBlockX();
        int z = s.getBlockZ();
        if (w.getEnvironment() == World.Environment.NETHER) {
            return s.toCenterLocation();
        }
        int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        return new Location(w, x + 0.5, y + 1, z + 0.5);
    }

    /**
     * Find a dry spot on the surface roughly {@code distance} blocks from {@code target},
     * on the line back towards {@code from}. Returns null if only water/lava was found.
     */
    public Location findSpawnNear(World w, Location target, Location from, double distance) {
        double dx = from.getX() - target.getX();
        double dz = from.getZ() - target.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1) {
            dx = 1;
            dz = 0;
            len = 1;
        }
        dx /= len;
        dz /= len;
        for (int i = 0; i < 10; i++) {
            double d = Math.min(len, distance + i * 24);
            int x = (int) Math.floor(target.getX() + dx * d);
            int z = (int) Math.floor(target.getZ() + dz * d);
            var top = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            Material m = top.getType();
            if (!top.isLiquid() && m != Material.ICE && m != Material.MAGMA_BLOCK && m != Material.POWDER_SNOW) {
                return new Location(w, x + 0.5, top.getY() + 1, z + 0.5);
            }
        }
        return null;
    }

    /** Unload (without saving) and delete a world folder. Players still inside are sent to the lobby. */
    public void deleteWorld(World w) {
        if (w == null) {
            return;
        }
        for (Player p : w.getPlayers()) {
            p.teleport(lobbySpawn());
        }
        Path folder = w.getWorldPath();
        if (!Bukkit.unloadWorld(w, false)) {
            plugin.getLogger().warning("Could not unload world " + w.getKey());
            return;
        }
        // give the region IO threads a moment before removing files
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> deleteFolder(folder), 40L);
    }

    /** Remove leftover game worlds from previous runs (crash / restart mid-game). */
    public void sweepLeftovers() {
        // Since 26.1 every world lives in <level>/dimensions/<namespace>/<key>, next to the lobby.
        sweepDirectory(lobby().getWorldPath().getParent());
        // Leftovers from a pre-26.1 server sit directly in the world container.
        sweepDirectory(Bukkit.getWorldContainer().toPath());
    }

    private void sweepDirectory(Path parent) {
        if (parent == null || !Files.isDirectory(parent)) {
            return;
        }
        File[] dirs = parent.toFile().listFiles(f -> f.isDirectory() && isGameWorldName(f.getName()));
        if (dirs == null) {
            return;
        }
        for (File dir : dirs) {
            NamespacedKey key = NamespacedKey.fromString(dir.getName()); // null if not a valid key
            World loaded = key == null ? null : Bukkit.getWorld(key);
            if (loaded != null) {
                deleteWorld(loaded);
            } else {
                deleteFolder(dir.toPath());
            }
        }
    }

    private static boolean isGameWorldName(String name) {
        return name.startsWith(GAME_PREFIX) || LEGACY_GAME_PREFIXES.stream().anyMatch(name::startsWith);
    }

    private void deleteFolder(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException e) {
            plugin.getLogger().warning("Could not delete " + root + ": " + e.getMessage());
        }
    }
}
