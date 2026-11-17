package dev.dueley.fabric.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Flat fighting arena centred on 0,0. The floor's top layer is at {@link #FLOOR_Y};
 * a few stone pillars give some cover, the centre stays open for the final 5x5.
 * <p>
 * Same layout as the plugin's ArenaGenerator. Only blocks are generated: no decorations, caves, structures or mobs
 * (Bukkit's defaults for a custom generator). The codec is registered on the server only and is never sent to clients.
 */
public final class ArenaGenerator extends ChunkGenerator {
    public static final MapCodec<ArenaGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Biome.CODEC.fieldOf("biome").forGetter(g -> g.biome)
    ).apply(i, i.stable(ArenaGenerator::new)));

    public static final int FLOOR_Y = 64;
    private static final int[][] PILLARS = {{-15, -15}, {15, -15}, {-15, 15}, {15, 15}, {0, -36}, {0, 36}};
    /** x1, z1, x2, z2 (inclusive) */
    private static final int[][] WALLS = {{-30, -8, -30, -3}, {31, 4, 31, 9}, {-6, 20, -2, 20}, {3, -20, 7, -20}};
    private static final int[][] BUSHES = {{-38, 18}, {37, -19}, {18, 38}, {-19, -38}, {-8, -8}, {9, 9}, {28, 30}, {-31, -29}};

    private final Holder<Biome> biome;

    public ArenaGenerator(Holder<Biome> biome) {
        super(new FixedBiomeSource(biome));
        this.biome = biome;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState, StructureManager structureManager, ChunkAccess chunk) {
        int minY = chunk.getMinY();
        int chunkX = chunk.getPos().x();
        int chunkZ = chunk.getPos().z();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                BlockState[] column = column(chunkX * 16 + x, chunkZ * 16 + z, minY);
                for (int i = 0; i < column.length; i++) {
                    BlockState state = column[i];
                    if (state == null) {
                        continue;
                    }
                    int y = minY + i;
                    chunk.setBlockState(pos.set(x, y, z), state, 0);
                    oceanFloor.update(x, y, z, state);
                    worldSurface.update(x, y, z, state);
                }
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    /** The blocks of one column from {@code minY} up (null = air). Same rules as the plugin's generateNoise. */
    private static BlockState[] column(int wx, int wz, int minY) {
        BlockState[] col = new BlockState[FLOOR_Y + 3 - minY];
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        col[0] = Blocks.BEDROCK.defaultBlockState();
        for (int y = minY + 1; y < FLOOR_Y - 3; y++) {
            col[y - minY] = Blocks.STONE.defaultBlockState();
        }
        for (int y = FLOOR_Y - 3; y < FLOOR_Y; y++) {
            col[y - minY] = Blocks.DIRT.defaultBlockState();
        }
        col[FLOOR_Y - minY] = floor(wx, wz).defaultBlockState();

        // cover: stone pillars
        for (int[] p : PILLARS) {
            if (Math.abs(wx - p[0]) <= 1 && Math.abs(wz - p[1]) <= 1) {
                col = grow(col, FLOOR_Y + 4 - minY);
                for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 4; y++) {
                    col[y - minY] = (y == FLOOR_Y + 4 ? Blocks.CHISELED_STONE_BRICKS
                            : (wx + wz + y) % 3 == 0 ? Blocks.MOSSY_STONE_BRICKS : Blocks.STONE_BRICKS).defaultBlockState();
                }
            }
        }
        // cover: low walls
        for (int[] w : WALLS) {
            if (wx >= w[0] && wx <= w[2] && wz >= w[1] && wz <= w[3]) {
                col[FLOOR_Y + 1 - minY] = Blocks.COBBLESTONE.defaultBlockState();
                col[FLOOR_Y + 2 - minY] = Blocks.COBBLESTONE_WALL.defaultBlockState();
            }
        }
        // bushes
        for (int[] b : BUSHES) {
            int dx = wx - b[0];
            int dz = wz - b[1];
            if (dx >= 0 && dx <= 1 && dz >= 0 && dz <= 1) {
                col[FLOOR_Y + 1 - minY] = leaves;
                if (dx == 0 && dz == 0) {
                    col[FLOOR_Y + 2 - minY] = leaves;
                }
            }
        }
        return col;
    }

    private static BlockState[] grow(BlockState[] col, int index) {
        if (index < col.length) {
            return col;
        }
        BlockState[] bigger = new BlockState[index + 1];
        System.arraycopy(col, 0, bigger, 0, col.length);
        return bigger;
    }

    private static Block floor(int wx, int wz) {
        int ax = Math.abs(wx);
        int az = Math.abs(wz);
        // final 5x5 zone (border centre is 0.5, so blocks -2..2)
        if (wx >= -2 && wx <= 2 && wz >= -2 && wz <= 2) {
            return (Math.abs(wx) == 2 && Math.abs(wz) == 2) ? Blocks.CHISELED_STONE_BRICKS : Blocks.MOSSY_STONE_BRICKS;
        }
        // 100x100 edge (blocks -49..50)
        boolean edge = (wx == -50 || wx == 51 || wz == -50 || wz == 51) && wx >= -50 && wx <= 51 && wz >= -50 && wz <= 51;
        if (edge) {
            return Blocks.POLISHED_BLACKSTONE_BRICKS;
        }
        boolean inside = wx > -50 && wx < 51 && wz > -50 && wz < 51;
        if (!inside) {
            return Blocks.COARSE_DIRT;
        }
        // a path ring at 25 blocks
        if ((ax == 25 && az <= 25) || (az == 25 && ax <= 25)) {
            return Blocks.DIRT_PATH;
        }
        return Blocks.GRASS_BLOCK;
    }

    // ───────────────────────── nothing else is generated ─────────────────────────

    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structureSets, RandomState randomState, long legacyLevelSeed) {
        return ChunkGeneratorStructureState.createForFlat(randomState, legacyLevelSeed, this.biomeSource, Stream.empty());
    }

    @Override
    public void createStructures(RegistryAccess registryAccess, ChunkGeneratorStructureState state, StructureManager structureManager,
                                 ChunkAccess centerChunk, StructureTemplateManager structureTemplateManager, ResourceKey<Level> level) {
    }

    @Override
    public void createReferences(WorldGenLevel level, StructureManager structureManager, ChunkAccess centerChunk) {
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
    }

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState, BiomeManager biomeManager,
                             StructureManager structureManager, ChunkAccess chunk) {
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager, RandomState randomState, ChunkAccess protoChunk) {
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion worldGenRegion) {
    }

    @Override
    public int getGenDepth() {
        return 384;
    }

    @Override
    public int getSeaLevel() {
        return -63;
    }

    @Override
    public int getMinY() {
        return -64;
    }

    @Override
    public int getSpawnHeight(LevelHeightAccessor heightAccessor) {
        return FLOOR_Y + 1;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState) {
        BlockState[] col = column(x, z, heightAccessor.getMinY());
        for (int i = col.length - 1; i >= 0; i--) {
            if (col[i] != null && type.isOpaque().test(col[i])) {
                return heightAccessor.getMinY() + i + 1;
            }
        }
        return heightAccessor.getMinY();
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
        BlockState[] col = column(x, z, heightAccessor.getMinY());
        BlockState[] out = new BlockState[col.length];
        for (int i = 0; i < col.length; i++) {
            out[i] = col[i] == null ? Blocks.AIR.defaultBlockState() : col[i];
        }
        return new NoiseColumn(heightAccessor.getMinY(), out);
    }

    @Override
    public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos feetPos) {
    }
}
