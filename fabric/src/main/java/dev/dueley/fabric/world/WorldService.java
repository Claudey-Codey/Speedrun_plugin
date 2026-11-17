package dev.dueley.fabric.world;

import dev.dueley.fabric.DueleyMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;
import xyz.nucleoid.fantasy.RuntimeLevelHandle;
import xyz.nucleoid.fantasy.util.VoidChunkGenerator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Creates, prepares and deletes the worlds Dueley uses (runtime worlds through Fantasy).
 * <p>
 * All worlds use the vanilla overworld / nether dimension types, so unmodded clients can join them.
 */
public final class WorldService {
    public static final String LOBBY_NAME = "dueley_lobby";
    public static final String GAME_PREFIX = "dueley_g";
    /** Game-world prefixes from earlier names of the plugin (Trogger, Hour Duel); only swept, never created. */
    private static final List<String> LEGACY_GAME_PREFIXES = List.of("trogger_g", "hourduel_g");
    /** Same key as the plugin's lobby ({@code minecraft:dueley_lobby}). */
    public static final ResourceKey<Level> LOBBY_KEY = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace(LOBBY_NAME));

    private final MinecraftServer server;
    private ServerLevel lobby;
    private final Map<ServerLevel, RuntimeLevelHandle> handles = new HashMap<>();
    /**
     * Each world's spawn point. Since 26.1 vanilla only has one server-wide spawn, so Dueley keeps the
     * per-world spawn itself (Paper does the same internally).
     */
    private final Map<ResourceKey<Level>, BlockPos> spawns = new HashMap<>();
    /** Lobby and arena: no weather. */
    private final Set<ResourceKey<Level>> staticWorlds = new HashSet<>();

    public WorldService(MinecraftServer server) {
        this.server = server;
    }

    // ───────────────────────── lobby ─────────────────────────

    public ServerLevel lobby() {
        if (lobby == null) {
            staticWorlds.add(LOBBY_KEY);
            RuntimeLevelConfig config = new RuntimeLevelConfig()
                    .setDimensionType(dimensionType(BuiltinDimensionTypes.OVERWORLD))
                    .setGenerator(new VoidChunkGenerator(server, Biomes.PLAINS));
            setupStaticWorld(config);
            RuntimeLevelHandle handle = Fantasy.get(server).getOrOpenPersistentLevel(LOBBY_KEY.identifier(), config);
            lobby = handle.asLevel();
            buildLobbyPlatform(lobby);
        }
        return lobby;
    }

    public Loc lobbySpawn() {
        return new Loc(lobby(), 0.5, 100, 0.5, 0, 0);
    }

    private void buildLobbyPlatform(ServerLevel w) {
        spawns.put(w.dimension(), new BlockPos(0, 100, 0));
        if (!w.getBlockState(new BlockPos(0, 99, 0)).isAir()) {
            return; // already built
        }
        int r = 10;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                double d = Math.sqrt(x * x + z * z);
                if (d > r + 0.4) {
                    continue;
                }
                Block m;
                if (d <= 2.5) {
                    m = Blocks.GOLD_BLOCK;
                } else if (d > 6.5 && d <= 7.5) {
                    m = Blocks.SEA_LANTERN;
                } else if (d > r - 0.6) {
                    m = Blocks.POLISHED_BLACKSTONE_BRICKS;
                } else {
                    m = (x + z) % 2 == 0 ? Blocks.POLISHED_DEEPSLATE : Blocks.DEEPSLATE_TILES;
                }
                set(w, x, 99, z, m);
                set(w, x, 98, z, Blocks.BLACKSTONE);
            }
        }
        // a little crimson/warped flair on the edge
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            int x = (int) Math.round(Math.cos(a) * (r - 1));
            int z = (int) Math.round(Math.sin(a) * (r - 1));
            set(w, x, 100, z, i % 2 == 0 ? Blocks.CRIMSON_FENCE : Blocks.WARPED_FENCE);
            set(w, x, 101, z, Blocks.LANTERN);
        }
    }

    /** Like Bukkit's setType(material, false): no physics / neighbour updates. */
    private static void set(ServerLevel w, int x, int y, int z, Block block) {
        w.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    /** Lobby / arena: always day, no weather, no mobs. */
    private static void setupStaticWorld(RuntimeLevelConfig c) {
        c.setShouldTickTime(false); // ADVANCE_TIME false
        c.setClockTime(WorldClocks.OVERWORLD, 6000);
        c.setGameRule(GameRules.ADVANCE_WEATHER, false);
        c.setGameRule(GameRules.SPAWN_MOBS, false);
        c.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0); // no fire spread
        c.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        c.setDifficulty(Difficulty.PEACEFUL);
    }

    public boolean isStaticWorld(ServerLevel level) {
        return staticWorlds.contains(level.dimension());
    }

    // ───────────────────────── game worlds ─────────────────────────

    /** A vanilla "default" world (overworld or nether) with this seed, like WorldCreator with WorldType.NORMAL. */
    public ServerLevel createSurvivalWorld(String name, long seed, boolean nether, Difficulty difficulty) {
        ResourceKey<LevelStem> stemKey = nether ? LevelStem.NETHER : LevelStem.OVERWORLD;
        LevelStem stem = server.registryAccess().lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value()
                .createWorldDimensions().get(stemKey)
                .orElseThrow(() -> new IllegalStateException("Could not create world " + name));
        RuntimeLevelConfig config = new RuntimeLevelConfig()
                .setDimensionType(stem.type())
                .setGenerator(stem.generator())
                .setSeed(seed)
                .setShouldTickTime(true)
                .setDifficulty(difficulty)
                .setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false)
                // weather is server-wide since 26.1 and the main overworld already advances it
                .setGameRule(GameRules.ADVANCE_WEATHER, false);
        if (!nether) {
            config.setClockTime(WorldClocks.OVERWORLD, 0); // since 26.1 the nether has no world clock
        }
        RuntimeLevelHandle handle = Fantasy.get(server).openTemporaryLevel(Identifier.withDefaultNamespace(name), config);
        ServerLevel w = handle.asLevel();
        handles.put(w, handle);
        if (!nether) {
            server.overworld().resetWeatherCycle(); // setStorm(false) / setThundering(false)
            spawns.put(w.dimension(), findInitialSpawn(w));
        }
        return w;
    }

    public ServerLevel createArena(String name) {
        Holder<Biome> plains = server.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        RuntimeLevelConfig config = new RuntimeLevelConfig()
                .setDimensionType(dimensionType(BuiltinDimensionTypes.OVERWORLD))
                .setGenerator(new ArenaGenerator(plains));
        setupStaticWorld(config);
        config.setDifficulty(Difficulty.NORMAL); // so hunger & damage work, but no mobs spawn
        config.setGameRule(GameRules.PVP, true);
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace(name));
        staticWorlds.add(key);
        RuntimeLevelHandle handle = Fantasy.get(server).openTemporaryLevel(key.identifier(), config);
        ServerLevel w = handle.asLevel();
        handles.put(w, handle);
        spawns.put(w.dimension(), new BlockPos(0, ArenaGenerator.FLOOR_Y + 1, 0));
        return w;
    }

    private Holder<DimensionType> dimensionType(ResourceKey<DimensionType> key) {
        return server.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(key);
    }

    public static boolean isNether(ServerLevel w) {
        return w.dimensionTypeRegistration().is(BuiltinDimensionTypes.NETHER);
    }

    /** The world's spawn point (block position). */
    public BlockPos spawnPoint(ServerLevel w) {
        BlockPos s = spawns.get(w.dimension());
        return s != null ? s : w.getRespawnData().pos();
    }

    /** Move a world's spawn point (setSpawnLocation). */
    public void setSpawnPoint(ServerLevel w, BlockPos pos) {
        spawns.put(w.dimension(), pos.immutable());
    }

    /**
     * Vanilla's first-start spawn search (MinecraftServer.setInitialSpawn), run for a game world, as Paper does
     * when it creates a world.
     */
    private static BlockPos findInitialSpawn(ServerLevel level) {
        ServerChunkCache chunkSource = level.getChunkSource();
        ChunkPos spawnChunk = ChunkPos.containing(chunkSource.randomState().sampler().findSpawnPosition());
        int height = chunkSource.getGenerator().getSpawnHeight(level);
        if (height < level.getMinY()) {
            BlockPos worldPosition = spawnChunk.getWorldPosition();
            height = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldPosition.getX() + 8, worldPosition.getZ() + 8);
        }
        BlockPos spawn = spawnChunk.getWorldPosition().offset(8, height, 8);
        int xChunkOffset = 0;
        int zChunkOffset = 0;
        int dXChunk = 0;
        int dZChunk = -1;
        for (int i = 0; i < Mth.square(11); i++) {
            if (xChunkOffset >= -5 && xChunkOffset <= 5 && zChunkOffset >= -5 && zChunkOffset <= 5) {
                BlockPos tested = PlayerSpawnFinder.getSpawnPosInChunk(level, new ChunkPos(spawnChunk.x() + xChunkOffset, spawnChunk.z() + zChunkOffset));
                if (tested != null) {
                    spawn = tested;
                    break;
                }
            }
            if (xChunkOffset == zChunkOffset || xChunkOffset < 0 && xChunkOffset == -zChunkOffset || xChunkOffset > 0 && xChunkOffset == 1 - zChunkOffset) {
                int olddx = dXChunk;
                dXChunk = -dZChunk;
                dZChunk = olddx;
            }
            xChunkOffset += dXChunk;
            zChunkOffset += dZChunk;
        }
        return spawn.immutable();
    }

    /** A spawn location standing on top of the ground at the world's spawn point. */
    public Loc safeSpawn(ServerLevel w) {
        BlockPos s = spawnPoint(w);
        int x = s.getX();
        int z = s.getZ();
        if (isNether(w)) {
            return new Loc(w, x + 0.5, s.getY() + 0.5, z + 0.5);
        }
        int y = highestBlockY(w, x, z);
        return new Loc(w, x + 0.5, y + 1, z + 0.5);
    }

    /**
     * Find a dry spot on the surface roughly {@code distance} blocks from {@code target},
     * on the line back towards {@code from}. Returns null if only water/lava was found.
     */
    public Loc findSpawnNear(ServerLevel w, BlockPos target, BlockPos from, double distance) {
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
            int topY = highestBlockY(w, x, z);
            BlockState top = w.getBlockState(new BlockPos(x, topY, z));
            boolean liquid = top.is(Blocks.WATER) || top.is(Blocks.LAVA);
            if (!liquid && !top.is(Blocks.ICE) && !top.is(Blocks.MAGMA_BLOCK) && !top.is(Blocks.POWDER_SNOW)) {
                return new Loc(w, x + 0.5, topY + 1, z + 0.5);
            }
        }
        return null;
    }

    /**
     * Bukkit's getHighestBlockYAt(x, z, MOTION_BLOCKING_NO_LEAVES): the y of the highest such block. Unlike
     * Level#getHeight this loads (generates) the chunk first.
     */
    private static int highestBlockY(ServerLevel w, int x, int z) {
        return w.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))
                .getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
    }

    /** Unload (without saving) and delete a world folder. Players still inside are sent to the lobby. */
    public void deleteWorld(ServerLevel w) {
        if (w == null) {
            return;
        }
        for (ServerPlayer p : new ArrayList<>(w.players())) {
            lobbySpawn().teleport(p);
        }
        spawns.remove(w.dimension());
        staticWorlds.remove(w.dimension());
        RuntimeLevelHandle handle = handles.remove(w);
        if (handle != null) {
            handle.delete(); // Fantasy unloads it and deletes the folder at the start of the next tick
        } else {
            DueleyMod.LOGGER.warn("Could not unload world {}", w.dimension().identifier());
        }
    }

    /** Remove leftover game worlds from previous runs (crash / restart mid-game). */
    public void sweepLeftovers() {
        // every world lives in <level>/dimensions/<namespace>/<key>, next to the lobby
        Path parent = DimensionType.getStorageFolder(LOBBY_KEY, server.getWorldPath(LevelResource.ROOT)).getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return;
        }
        File[] dirs = parent.toFile().listFiles(f -> f.isDirectory() && isGameWorldName(f.getName()));
        if (dirs == null) {
            return;
        }
        for (File dir : dirs) {
            Identifier id = Identifier.tryBuild("minecraft", dir.getName()); // null if not a valid key
            ServerLevel loaded = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
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

    private static void deleteFolder(Path root) {
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
            DueleyMod.LOGGER.warn("Could not delete {}: {}", root, e.getMessage());
        }
    }
}
