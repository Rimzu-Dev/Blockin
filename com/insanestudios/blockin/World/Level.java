package com.insanestudios.blockin.World;

import com.insanestudios.blockin.Physics.Box;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A Blockin world: an unbounded 3D grid of block ids, stored as lazily
 * generated 16x16 column chunks that extend to the full vertical extent
 * ({@link #depth}). The corner footprint {@code width x height x depth} is
 * generated eagerly at construction (spawn area / legacy saves), everything
 * outside it is produced deterministically on demand by {@link TerrainGen}.
 *
 * <p>Persistence uses a versioned format (seed + chunk entries under a magic
 * header). Legacy saves — the old header-less, single flat block array — are
 * detected by their missing header and migrated into the corner chunks.
 */
public final class Level {

    /** Whether legacy (no-slot) constructors should try loading level.dat. */
    public static boolean loadFromDisk = true;

    /** Column chunk footprint: 16 blocks on each horizontal axis. */
    public static final int CHUNK_SIZE = 16;

    /** Width of the eagerly generated spawn footprint (X axis). */
    public final int width;
    /** Depth of the eagerly generated spawn footprint (Z axis). */
    public final int height;
    /** Vertical extent of the world (Y axis; finite). */
    public final int depth;

    /** Number of 16-block vertical sub-chunks. */
    public final int yChunks;

    /** Seed that deterministically produced this world (see {@link TerrainGen}). */
    public long seed;

    private final Map<Long, byte[]> chunkBlocks = new HashMap<>();
    private final Map<Long, int[]> chunkLight = new HashMap<>();
    private final List<LevelListener> listeners = new ArrayList<>();

    /** Save slot file for this world (null = legacy working-directory save). */
    private final File file;

    /** Player position/heading recorded at save time (null = save had none). */
    private PlayerState savedPlayer;

    public Level(int w, int h, int d) {
        this(w, h, d, new java.util.Random().nextLong());
    }

    public Level(int w, int h, int d, long seed) {
        this(w, h, d, seed, null, loadFromDisk);
    }

    /** Creates the world and optionally loads the given save-slot file. */
    public Level(int w, int h, int d, long seed, File saveFile, boolean wantLoad) {
        this.width = w;
        this.height = h;
        this.depth = d;
        this.seed = seed;
        this.file = saveFile;
        this.yChunks = (d + CHUNK_SIZE - 1) / CHUNK_SIZE;

        ChunkBuilder.terrain(this);

        if (wantLoad) {
            load(saveFile);
        }
    }

    // ----------------------------------------------------------- chunking

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private int index(int lx, int y, int lz) {
        return (y * CHUNK_SIZE + lz) * CHUNK_SIZE + lx;
    }

    private static int col(int x) {
        return Math.floorDiv(x, CHUNK_SIZE);
    }

    private static int lok(int x) {
        return Math.floorMod(x, CHUNK_SIZE);
    }

    /**
     * Makes sure a column chunk exists, generating it if needed. The chunk
     * array is inserted into the map before it is filled so {@link #setRaw}
     * always finds it during generation (tree canopies may also stamp into
     * neighbouring chunks, which lazily generate in turn).
     */
    public byte[] ensureChunk(int cx, int cz) {
        byte[] data = chunkBlocks.get(key(cx, cz));
        if (data != null) return data;

        data = new byte[CHUNK_SIZE * CHUNK_SIZE * depth];
        chunkBlocks.put(key(cx, cz), data);

        TerrainGen gen = new TerrainGen(seed, depth);
        for (int cy = 0; cy < yChunks; cy++) {
            gen.fillChunk(this, cx, cy, cz);
        }
        gen.plantTreesForChunk(this, cx, cz);

        int[] light = new int[CHUNK_SIZE * CHUNK_SIZE];
        int baseX = cx * CHUNK_SIZE;
        int baseZ = cz * CHUNK_SIZE;
        for (int lz = 0; lz < CHUNK_SIZE; lz++) {
            for (int lx = 0; lx < CHUNK_SIZE; lx++) {
                int x = baseX + lx;
                int z = baseZ + lz;
                light[lz * CHUNK_SIZE + lx] = calcLightDepth(x, z);
                for (LevelListener listener : listeners) {
                    listener.lightColumnChanged(x, z, 0, depth - 1);
                }
            }
        }
        chunkLight.put(key(cx, cz), light);
        return data;
    }

    private int calcLightDepth(int x, int z) {
        int y;
        for (y = depth - 1; y > 0 && !isLightBlocker(x, y, z); y--) {
        }
        return y;
    }

    private byte[] chunkAt(int cx, int cz) {
        return chunkBlocks.get(key(cx, cz));
    }

    /** True when the chunk column is currently stored. */
    public boolean hasChunk(int cx, int cz) {
        return chunkBlocks.containsKey(key(cx, cz));
    }

    // ------------------------------------------------------------------ io

    /** First byte of the versioned save format; legacy files never start here. */
    private static final int FORMAT_MAGIC_BYTE = 0x5A;

    /** Player feet position + view angles captured on save (optional tail). */
    public static final class PlayerState {
        public final float x, y, z, yaw, pitch;

        public PlayerState(float x, float y, float z, float yaw, float pitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    /** Whether the last loaded save carried a player position. */
    public boolean hasSavedPlayer() {
        return savedPlayer != null;
    }

    /** Player position from the last loaded save, or null. */
    public PlayerState savedPlayer() {
        return savedPlayer;
    }

    /** Records the position to persist on the next {@link #save()}. */
    public void setSavedPlayer(float x, float y, float z, float yaw, float pitch) {
        savedPlayer = new PlayerState(x, y, z, yaw, pitch);
    }

    public void load() {
        load(this.file);
    }

    public void load(File saveFile) {
        File f = saveFile != null ? saveFile : new File("level.dat");
        if (!f.isFile()) return;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(f)))) {
            int first = in.read();
            if (first == FORMAT_MAGIC_BYTE) {
                loadVersioned(in);
            } else {
                loadLegacy(in, first);
            }
            for (LevelListener listener : listeners) {
                listener.allChanged();
            }
            System.out.println("[Level] loaded " + f.getPath());
        } catch (EOFException e) {
            // truncated save — keep the generated world instead
            System.err.println("[Level] " + f.getName() + " truncated, using generated world");
        } catch (Exception e) {
            System.err.println("[Level] failed to load " + f.getName() + ": " + e);
        }
    }

    private void loadVersioned(DataInputStream in) throws java.io.IOException {
        if (in.readInt() != 0x4C564C31) throw new java.io.IOException("new-format marker mismatch");
        long seed = in.readLong();
        int loadDepth = in.readInt();
        int count = in.readInt();

        chunkBlocks.clear();
        chunkLight.clear();
        this.savedPlayer = null;
        this.seed = seed;

        for (int i = 0; i < count; i++) {
            int cx = in.readInt();
            int cz = in.readInt();
            byte[] data = new byte[CHUNK_SIZE * CHUNK_SIZE * loadDepth];
            in.readFully(data);
            if (loadDepth == depth) {
                chunkBlocks.put(key(cx, cz), data);
            }
        }

        for (Map.Entry<Long, byte[]> e : new ArrayList<>(chunkBlocks.entrySet())) {
            int cx = (int) (e.getKey() >> 32);
            int cz = (int) (long) e.getKey();
            int[] light = new int[CHUNK_SIZE * CHUNK_SIZE];
            int baseX = cx * CHUNK_SIZE;
            int baseZ = cz * CHUNK_SIZE;
            for (int lz = 0; lz < CHUNK_SIZE; lz++) {
                for (int lx = 0; lx < CHUNK_SIZE; lx++) {
                    light[lz * CHUNK_SIZE + lx] = calcLightDepth(baseX + lx, baseZ + lz);
                }
            }
            chunkLight.put(key(cx, cz), light);
        }

        // Optional trailing player state: written by current saves, absent in
        // older LVL1 files (in.read() hits -1 at the clean end of the stream).
        // A truncated tail only drops the position, never the whole world.
        try {
            if (in.read() == 1) {
                savedPlayer = new PlayerState(in.readFloat(), in.readFloat(),
                        in.readFloat(), in.readFloat(), in.readFloat());
            }
        } catch (EOFException ignored) {
            savedPlayer = null;
        }
    }

    private void loadLegacy(DataInputStream in, int first) throws java.io.IOException {
        int w = this.width;
        int h = this.height;
        int d = this.depth;
        byte[] raw = new byte[w * h * d];
        raw[0] = (byte) first;
        in.readFully(raw, 1, raw.length - 1);

        Map<Long, byte[]> loaded = new HashMap<>();
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < h; z++) {
                int cx = col(x);
                int cz = col(z);
                long k = key(cx, cz);
                byte[] data = loaded.get(k);
                if (data == null) {
                    data = new byte[CHUNK_SIZE * CHUNK_SIZE * d];
                    loaded.put(k, data);
                }
                for (int y = 0; y < d; y++) {
                    data[index(lok(x), y, lok(z))] = raw[(y * h + z) * w + x];
                }
            }
        }
        chunkBlocks.clear();
        chunkLight.clear();
        chunkBlocks.putAll(loaded);

        for (Map.Entry<Long, byte[]> e : loaded.entrySet()) {
            int cx = (int) (e.getKey() >> 32);
            int cz = (int) (long) e.getKey();
            int[] light = new int[CHUNK_SIZE * CHUNK_SIZE];
            int baseX = cx * CHUNK_SIZE;
            int baseZ = cz * CHUNK_SIZE;
            for (int lz = 0; lz < CHUNK_SIZE; lz++) {
                for (int lx = 0; lx < CHUNK_SIZE; lx++) {
                    light[lz * CHUNK_SIZE + lx] = calcLightDepth(baseX + lx, baseZ + lz);
                }
            }
            chunkLight.put(key(cx, cz), light);
        }
    }

    public void save() {
        save(this.file);
    }

    public void save(File saveFile) {
        File f = saveFile != null ? saveFile : new File("level.dat");
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(f)))) {
            out.writeByte(FORMAT_MAGIC_BYTE);
            out.writeInt(0x4C564C31); // "LVL1"
            out.writeLong(this.seed);
            out.writeInt(this.depth);
            out.writeInt(chunkBlocks.size());
            for (Map.Entry<Long, byte[]> e : chunkBlocks.entrySet()) {
                out.writeInt((int) (e.getKey() >> 32));
                out.writeInt((int) (long) e.getKey());
                out.write(e.getValue());
            }
            out.writeBoolean(savedPlayer != null);
            if (savedPlayer != null) {
                out.writeFloat(savedPlayer.x);
                out.writeFloat(savedPlayer.y);
                out.writeFloat(savedPlayer.z);
                out.writeFloat(savedPlayer.yaw);
                out.writeFloat(savedPlayer.pitch);
            }
            if (parent != null) {
                com.insanestudios.blockin.SaveManager.notePlayed(parent.getName(), this.seed);
            }
            System.out.println("[Level] saved " + f.getPath() + " (" + chunkBlocks.size() + " chunks)");
        } catch (Exception e) {
            System.err.println("[Level] failed to save " + f.getName() + ": " + e);
        }
    }

    // ------------------------------------------------------------- lighting

    public void calcLightDepths(int x0, int y0, int x1, int y1) {
        for (int x = x0; x < x0 + x1; x++) {
            for (int z = y0; z < y0 + y1; z++) {
                int cx = col(x);
                int cz = col(z);
                int[] light = chunkLight.get(key(cx, cz));
                if (light == null) continue;
                int oldDepth = light[lok(z) * CHUNK_SIZE + lok(x)];

                int y;
                for (y = this.depth - 1; y > 0 && !isLightBlocker(x, y, z); y--) {
                }

                light[lok(z) * CHUNK_SIZE + lok(x)] = y;
                if (oldDepth != y) {
                    int yl0 = Math.min(oldDepth, y);
                    int yl1 = Math.max(oldDepth, y);
                    for (LevelListener listener : listeners) {
                        listener.lightColumnChanged(x, z, yl0, yl1);
                    }
                }
            }
        }
    }

    public void addListener(LevelListener listener) {
        listeners.add(listener);
    }

    public void removeListener(LevelListener listener) {
        listeners.remove(listener);
    }

    // --------------------------------------------------------------- tiles

    /** True when a block occupies the cell (any block: solid, water, leaves...). */
    public boolean isTile(int x, int y, int z) {
        if (y < 0 || y >= depth) return false;
        return getBlock(x, y, z) != 0;
    }

    /** Raw block id at a position: 0 = air. */
    public int getTile(int x, int y, int z) {
        if (y < 0 || y >= depth) return 0;
        return getBlock(x, y, z);
    }

    private int getBlock(int x, int y, int z) {
        byte[] data = ensureChunk(col(x), col(z));
        return data[index(lok(x), y, lok(z))] & 0xFF;
    }

    /** True when the tile is physically solid (blocks movement). Liquids are not. */
    public boolean isSolidTile(int x, int y, int z) {
        if (y < 0 || y >= depth) return false;
        byte b = ensureChunk(col(x), col(z))[index(lok(x), y, lok(z))];
        int type = b & 0xFF;
        return type != 0 && !com.insanestudios.blockin.Blocks.BlockLoader.isLiquid(type);
    }

    /** True when the cell holds water. */
    public boolean isWater(int x, int y, int z) {
        if (y < 0 || y >= depth) return false;
        return ensureChunk(col(x), col(z))[index(lok(x), y, lok(z))]
                == (byte) com.insanestudios.blockin.Blocks.BlockLoader.WATER;
    }

    /** True when the cell holds a liquid the player can swim through. */
    public boolean isLiquid(int x, int y, int z) {
        if (y < 0 || y >= depth) return false;
        int type = ensureChunk(col(x), col(z))[index(lok(x), y, lok(z))] & 0xFF;
        return com.insanestudios.blockin.Blocks.BlockLoader.isLiquid(type);
    }

    /** True when the tile blocks skylight (solid blocks and water). */
    public boolean isLightBlocker(int x, int y, int z) {
        return isTile(x, y, z);
    }

    /** Sets a tile and notifies listeners that renderers must repaint it. */
    public void setTile(int x, int y, int z, int type) {
        if (y < 0 || y >= depth) return;
        byte[] data = ensureChunk(col(x), col(z));
        data[index(lok(x), y, lok(z))] = (byte) type;
        calcLightDepths(x, z, 1, 1);
        for (LevelListener listener : listeners) {
            listener.tileChanged(x, y, z);
        }
    }

    /** Package-private raw write used by terrain generation (no listener fan-out). */
    void setRaw(int x, int y, int z, int type) {
        if (y < 0 || y >= depth) return;
        ensureChunk(col(x), col(z))[index(lok(x), y, lok(z))] = (byte) type;
    }

    /**
     * Highest solid block in a column (i.e. the terrain surface; water and air
     * are skipped). Returns 0 when the column has no ground.
     */
    public int getSurfaceY(int x, int z) {
        int y = this.depth - 1;
        while (y > 0 && !isSolidTile(x, y, z)) y--;
        return y;
    }

    /** True when the column's terrain surface sits below sea level. */
    public boolean isOcean(int x, int z) {
        return getSurfaceY(x, z) < TerrainGen.SEA_LEVEL;
    }

    /** All solid unit cubes overlapping the given box. */
    public List<Box> getCubes(Box box) {
        List<Box> cubes = new ArrayList<>();

        int x0 = (int) box.x0;
        int x1 = (int) (box.x1 + 1.0F);
        int y0 = (int) box.y0;
        int y1 = (int) (box.y1 + 1.0F);
        int z0 = (int) box.z0;
        int z1 = (int) (box.z1 + 1.0F);

        // Horizontal bounds are unbounded (infinite world); only the vertical
        // extent is clamped to the fixed column height.
        y0 = Math.max(0, y0);
        y1 = Math.min(this.depth, y1);

        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                for (int z = z0; z < z1; z++) {
                    if (isSolidTile(x, y, z)) {
                        cubes.add(new Box(x, y, z, x + 1.0F, y + 1.0F, z + 1.0F));
                    }
                }
            }
        }
        return cubes;
    }

    /** Face brightness: lit when the sky is visible, shaded otherwise. */
    public float getBrightness(int x, int y, int z) {
        int[] light = chunkLight.get(key(col(x), col(z)));
        if (light != null && y >= 0 && y < depth) {
            return y < light[lok(z) * CHUNK_SIZE + lok(x)] ? 0.8F : 1.0F;
        }
        return 1.0F;
    }
}