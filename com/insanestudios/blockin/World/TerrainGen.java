package com.insanestudios.blockin.World;

import com.insanestudios.blockin.Blocks.BlockLoader;

/**
 * Seeded, deterministic chunk-based terrain generator.
 *
 * <p>Every block of the world is produced from a couple of hash-based value
 * noise fields (a 2D height map plus 3D cave carving), so a given seed always
 * yields the same world. Generation runs one 16x16x16 chunk at a time through
 * {@link #fillChunk}, mirroring the renderer's chunk model.
 */
public final class TerrainGen {

    /** Water fills every column whose terrain top is below this height. */
    public static final int SEA_LEVEL = 30;

    private static final int CHUNK = 16;

    private final long seed;
    private final int depth;

    public TerrainGen(long seed, int depth) {
        this.seed = seed;
        this.depth = depth;
    }

    // ------------------------------------------------------------- noise

    private int hash(int x, int y, int z) {
        long h = seed;
        h ^= (long) x * 0x9E3779B97F4A7C15L;
        h ^= (long) y * 0xBF58476D1CE4E5B9L;
        h ^= (long) z * 0x94D049BB133111EBL;
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 32;
        return (int) h;
    }

    private float lattice(int x, int y, int z) {
        return hash(x, y, z) * (1.0F / 2147483647.0F);
    }

    private static float smooth(float t) {
        return t * t * (3.0F - 2.0F * t);
    }

    private float noise2(float x, float z) {
        int xi = (int) Math.floor(x);
        int zi = (int) Math.floor(z);
        float xf = x - xi;
        float zf = z - zi;
        float u = smooth(xf);
        float v = smooth(zf);
        float a = lattice(xi, 0, zi);
        float b = lattice(xi + 1, 0, zi);
        float c = lattice(xi, 0, zi + 1);
        float d = lattice(xi + 1, 0, zi + 1);
        return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
    }

    private float noise3(float x, float y, float z) {
        int xi = (int) Math.floor(x);
        int yi = (int) Math.floor(y);
        int zi = (int) Math.floor(z);
        float xf = x - xi;
        float yf = y - yi;
        float zf = z - zi;
        float u = smooth(xf);
        float v = smooth(yf);
        float w = smooth(zf);

        float n000 = lattice(xi, yi, zi);
        float n100 = lattice(xi + 1, yi, zi);
        float n010 = lattice(xi, yi + 1, zi);
        float n110 = lattice(xi + 1, yi + 1, zi);
        float n001 = lattice(xi, yi, zi + 1);
        float n101 = lattice(xi + 1, yi, zi + 1);
        float n011 = lattice(xi, yi + 1, zi + 1);
        float n111 = lattice(xi + 1, yi + 1, zi + 1);

        return lerp(
                lerp(lerp(n000, n100, u), lerp(n010, n110, u), v),
                lerp(lerp(n001, n101, u), lerp(n011, n111, u), v),
                w);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private float fbm2(float x, float z, int octaves) {
        float sum = 0.0F;
        float amp = 1.0F;
        float norm = 0.0F;
        for (int i = 0; i < octaves; i++) {
            sum += noise2(x, z) * amp;
            norm += amp;
            amp *= 0.5F;
            x *= 2.0F;
            z *= 2.0F;
        }
        return sum / norm;
    }

    private float fbm3(float x, float y, float z, int octaves) {
        float sum = 0.0F;
        float amp = 1.0F;
        float norm = 0.0F;
        for (int i = 0; i < octaves; i++) {
            sum += noise3(x, y, z) * amp;
            norm += amp;
            amp *= 0.5F;
            x *= 2.0F;
            y *= 2.0F;
            z *= 2.0F;
        }
        return sum / norm;
    }

    // ---------------------------------------------------------- terrain

    private boolean isOceanColumn(float h) {
        return h < SEA_LEVEL;
    }

    /** Terrain top (lowest solid block index) for a column of the map. */
    public int heightAt(int x, int z) {
        float continental = fbm2(x * 0.0125F + 100.7F, z * 0.0125F + 33.1F, 3);
        float detail = fbm2(x * 0.055F + 1.5F, z * 0.055F + 87.2F, 3) * 0.35F;
        float h = 34.0F + continental * 14.0F + detail * 7.0F;

        // No island border drop: the terrain continues in every direction.
        if (h < 4.0F) h = 4.0F;
        if (h > depth - 6.0F) h = depth - 6.0F;
        return (int) h;
    }

    private boolean isCave(int x, int y, int z) {
        return fbm3(x * 0.05F + 211.9F, y * 0.12F + 7.3F, z * 0.05F + 45.1F, 2) < -0.5F;
    }

    /**
     * The block for one cell of the world. {@code terrain} is the precomputed
     * {@link #heightAt} value for the column so chunk fills only hash it once.
     */
    private int columnBlock(int x, int y, int z, int terrain) {
        if (terrain < SEA_LEVEL && y >= terrain && y < SEA_LEVEL) return BlockLoader.WATER;
        if (y >= terrain) return BlockLoader.AIR;
        if (y <= 0) return BlockLoader.STONE;
        if (y < terrain - 3 && isCave(x, y, z)) return BlockLoader.AIR;
        int above = terrain - 1 - y;
        if (above == 0) return (terrain >= SEA_LEVEL + 2) ? BlockLoader.GRASS : BlockLoader.SAND;
        if (above <= 3) return (terrain >= SEA_LEVEL + 2) ? BlockLoader.DIRT : BlockLoader.SAND;
        if ((hash(x, y, z) & 0xFF) < 2) return BlockLoader.GRAVEL;
        return BlockLoader.STONE;
    }

    /** Generates one 16x16x16 slice of the world into the level. */
    public void fillChunk(Level level, int cx, int cy, int cz) {
        int x0 = cx * CHUNK;
        int z0 = cz * CHUNK;
        int x1 = x0 + CHUNK;
        int z1 = z0 + CHUNK;
        int y0 = cy * CHUNK;
        int y1 = Math.min(depth, y0 + CHUNK);

        for (int x = x0; x < x1; x++) {
            for (int z = z0; z < z1; z++) {
                int terrain = heightAt(x, z);
                for (int y = y0; y < y1; y++) {
                    level.setRaw(x, y, z, columnBlock(x, y, z, terrain));
                }
            }
        }
    }

    // ------------------------------------------------------------- trees

    /**
     * Plants trees whose bases fall inside the given column chunk. The scan
     * extends a two-block halo past the chunk edge so canopies that hang over
     * the border are placed with the chunk that owns their trunk; the halo may
     * lazily generate neighbouring chunks, which is safe during column
     * generation because {@link Level#ensureChunk} inserts the column before
     * filling it.
     */
    public void plantTreesForChunk(Level level, int cx, int cz) {
        int x0 = cx * CHUNK - 2;
        int z0 = cz * CHUNK - 2;
        int x1 = (cx + 1) * CHUNK + 2;
        int z1 = (cz + 1) * CHUNK + 2;
        for (int x = x0; x < x1; x++) {
            for (int z = z0; z < z1; z++) {
                int t = heightAt(x, z);
                if (t < SEA_LEVEL + 2 || t > depth - 8) continue;
                if (Integer.toUnsignedLong(hash(x, 0, z)) % 512L >= 2L) continue;
                plantTree(level, x, z, t);
            }
        }
    }

    private void plantTree(Level level, int x, int z, int ground) {
        int h = 4 + ((hash(x, 1, z) >>> 30) & 1);
        // The trunk starts at "ground" (the first air cell above the surface
        // block at terrain-1) so it sits flush on the grass, not a block up.
        for (int dy = 0; dy < h; dy++) {
            if (level.getTile(x, ground + dy, z) == 0) {
                level.setRaw(x, ground + dy, z, BlockLoader.LOG);
            }
        }
        int top = ground + h - 1;
        for (int dy = -2; dy <= 0; dy++) {
            int yy = top + dy;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx * dx + dz * dz > 5) continue;
                    if (level.getTile(x + dx, yy, z + dz) == 0) {
                        level.setRaw(x + dx, yy, z + dz, BlockLoader.LEAVES);
                    }
                }
            }
        }
    }
}