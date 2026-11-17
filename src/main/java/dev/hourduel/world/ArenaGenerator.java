package dev.hourduel.world;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.List;
import java.util.Random;

/**
 * Flat fighting arena centred on 0,0. The floor's top layer is at {@link #FLOOR_Y};
 * a few stone pillars give some cover, the centre stays open for the final 5x5.
 */
public final class ArenaGenerator extends ChunkGenerator {
    public static final int FLOOR_Y = 64;
    private static final int[][] PILLARS = {{-15, -15}, {15, -15}, {-15, 15}, {15, 15}, {0, -36}, {0, 36}};
    /** x1, z1, x2, z2 (inclusive) */
    private static final int[][] WALLS = {{-30, -8, -30, -3}, {31, 4, 31, 9}, {-6, 20, -2, 20}, {3, -20, 7, -20}};
    private static final int[][] BUSHES = {{-38, 18}, {37, -19}, {18, 38}, {-19, -38}, {-8, -8}, {9, 9}, {28, 30}, {-31, -29}};

    @Override
    public void generateNoise(WorldInfo info, Random random, int chunkX, int chunkZ, ChunkData data) {
        int minY = info.getMinHeight();
        BlockData leaves = Material.OAK_LEAVES.createBlockData(b -> ((Leaves) b).setPersistent(true));
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int wx = chunkX * 16 + x;
                int wz = chunkZ * 16 + z;
                data.setBlock(x, minY, z, Material.BEDROCK);
                for (int y = minY + 1; y < FLOOR_Y - 3; y++) {
                    data.setBlock(x, y, z, Material.STONE);
                }
                for (int y = FLOOR_Y - 3; y < FLOOR_Y; y++) {
                    data.setBlock(x, y, z, Material.DIRT);
                }
                data.setBlock(x, FLOOR_Y, z, floor(wx, wz));

                // cover: stone pillars
                for (int[] p : PILLARS) {
                    if (Math.abs(wx - p[0]) <= 1 && Math.abs(wz - p[1]) <= 1) {
                        for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 4; y++) {
                            data.setBlock(x, y, z, y == FLOOR_Y + 4 ? Material.CHISELED_STONE_BRICKS
                                    : (wx + wz + y) % 3 == 0 ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS);
                        }
                    }
                }
                // cover: low walls
                for (int[] w : WALLS) {
                    if (wx >= w[0] && wx <= w[2] && wz >= w[1] && wz <= w[3]) {
                        data.setBlock(x, FLOOR_Y + 1, z, Material.COBBLESTONE);
                        data.setBlock(x, FLOOR_Y + 2, z, Material.COBBLESTONE_WALL);
                    }
                }
                // bushes
                for (int[] b : BUSHES) {
                    int dx = wx - b[0];
                    int dz = wz - b[1];
                    if (dx >= 0 && dx <= 1 && dz >= 0 && dz <= 1) {
                        data.setBlock(x, FLOOR_Y + 1, z, leaves);
                        if (dx == 0 && dz == 0) {
                            data.setBlock(x, FLOOR_Y + 2, z, leaves);
                        }
                    }
                }
            }
        }
    }

    private static Material floor(int wx, int wz) {
        int ax = Math.abs(wx);
        int az = Math.abs(wz);
        // final 5x5 zone (border centre is 0.5, so blocks -2..2)
        if (wx >= -2 && wx <= 2 && wz >= -2 && wz <= 2) {
            return (Math.abs(wx) == 2 && Math.abs(wz) == 2) ? Material.CHISELED_STONE_BRICKS : Material.MOSSY_STONE_BRICKS;
        }
        // 100x100 edge (blocks -49..50)
        boolean edge = (wx == -50 || wx == 51 || wz == -50 || wz == 51) && wx >= -50 && wx <= 51 && wz >= -50 && wz <= 51;
        if (edge) {
            return Material.POLISHED_BLACKSTONE_BRICKS;
        }
        boolean inside = wx > -50 && wx < 51 && wz > -50 && wz < 51;
        if (!inside) {
            return Material.COARSE_DIRT;
        }
        // a path ring at 25 blocks
        if ((ax == 25 && az <= 25) || (az == 25 && ax <= 25)) {
            return Material.DIRT_PATH;
        }
        return Material.GRASS_BLOCK;
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, 0.5, FLOOR_Y + 1, 0.5);
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new BiomeProvider() {
            @Override
            public Biome getBiome(WorldInfo info, int x, int y, int z) {
                return Biome.PLAINS;
            }

            @Override
            public List<Biome> getBiomes(WorldInfo info) {
                return List.of(Biome.PLAINS);
            }
        };
    }
}
