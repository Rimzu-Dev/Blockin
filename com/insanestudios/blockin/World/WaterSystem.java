package com.insanestudios.blockin.World;

import com.insanestudios.blockin.Blocks.BlockLoader;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Bounded, event-driven water flow.
 *
 * <p>Generated oceans are static (never enqueued). Water only becomes dynamic
 * when the player breaks a block next to it or places a water block; those
 * cells fall down in one leap, then spread sideways carrying a short flow
 * budget, and settle to "still" once the budget is spent so updates stay cheap.
 *
 * <p>To keep water stable, every cell remembers the cell it just flowed out of
 * and cannot flow back into it; that stops the classic side-to-side wobble.
 *
 * <p>Cells inside a true ocean basin (their column's terrain surface is below
 * sea level) behave as <em>infinite sources</em>: when they move they instantly
 * refill their own cell, so digging into a sea pours water back in until the
 * basin is level with the sea surface again. Non-basin water (a placed bucket,
 * a flooded tunnel in dry land) is finite and drains normally.
 */
public final class WaterSystem {

    /** How far a dynamic water cell may spread from its source. */
    private static final int MAX_FLOW = 16;

    /** Maximum cells processed per tick. */
    private static final int MAX_STEPS_PER_TICK = 512;

    private static final ArrayDeque<Long> queue = new ArrayDeque<>();
    private static final Set<Long> marked = new HashSet<>();
    private static final Map<Long, Integer> flowLeft = new HashMap<>();
    private static final Map<Long, Long> prev = new HashMap<>();

    private static final int[][] HORIZONTAL = {
            {-1, 0}, {1, 0}, {0, -1}, {0, 1}
    };

    private WaterSystem() {
    }

    /** Notifies the flow engine that a cell changed (player break/place). */
    public static void onBlockChanged(Level level, int x, int y, int z) {
        enqueue(level, x, y, z);
        enqueue(level, x + 1, y, z);
        enqueue(level, x - 1, y, z);
        enqueue(level, x, y, z + 1);
        enqueue(level, x, y, z - 1);
        enqueue(level, x, y + 1, z);
        enqueue(level, x, y - 1, z);
    }

    /** Processes queued water until the tick budget is used up. */
    public static void update(Level level) {
        int steps = 0;
        while (!queue.isEmpty() && steps < MAX_STEPS_PER_TICK) {
            long key = queue.poll();
            marked.remove(key);

            int x = unpackX(key);
            int z = unpackZ(key);
            int y = unpackY(key);

            if (level.getTile(x, y, z) != BlockLoader.WATER) continue;

            Integer left = flowLeft.get(key);
            if (left == null || left <= 0) continue;

            steps++;

            boolean basin = level.isOcean(x, z);

            // 1) Fall straight down in one leap until we hit a floor/water.
            int fy = y;
            while (fy > 0 && level.getTile(x, fy - 1, z) == 0) fy--;
            if (fy != y) {
                level.setTile(x, y, z, 0);
                level.setTile(x, fy, z, BlockLoader.WATER);
                if (basin) {
                    level.setTile(x, y, z, BlockLoader.WATER);
                    schedule(level, x, y, z, MAX_FLOW);
                } else if (left > 1) {
                    schedule(level, x, fy, z, left - 1);
                }
                continue;
            }

            // 2) Spread sideways, but never straight back into the cell we
            //    just flowed out of. Prefer a floor below; if the neighbour
            //    has no floor it is a waterfall edge and will fall next tick.
            Long prevKey = prev.get(key);
            boolean waterfall = false;
            int[] pick = null;
            for (int[] d : HORIZONTAL) {
                int tx = x + d[0];
                int tz = z + d[1];
                if (level.getTile(tx, y, tz) != 0) continue;
                long nk = pack(tx, tz, y);
                if (prevKey != null && nk == prevKey) continue;
                if (level.getTile(tx, y - 1, tz) != 0) { // has a floor
                    pick = d;
                    break;
                }
            }
            if (pick == null) {
                for (int[] d : HORIZONTAL) {
                    int tx = x + d[0];
                    int tz = z + d[1];
                    if (level.getTile(tx, y, tz) != 0) continue;
                    long nk = pack(tx, tz, y);
                    if (prevKey != null && nk == prevKey) continue;
                    pick = d;
                    waterfall = true;
                    break;
                }
            }
            if (pick != null) {
                int nx = x + pick[0];
                int nz = z + pick[1];
                long nk = pack(nx, nz, y);
                boolean source = left == MAX_FLOW;
                level.setTile(nx, y, nz, BlockLoader.WATER);
                if (!source) {
                    level.setTile(x, y, z, 0);
                    prev.put(nk, key);
                }
                if (basin) {
                    level.setTile(x, y, z, BlockLoader.WATER);
                    prev.remove(key);
                    schedule(level, x, y, z, MAX_FLOW);
                } else if (waterfall) {
                    schedule(level, nx, y, nz, MAX_FLOW);
                } else if (left > 1) {
                    schedule(level, nx, y, nz, left - 1);
                }
            } else {
                flowLeft.put(key, 0); // still: nothing to do
                prev.remove(key);
            }
        }
    }

    private static void enqueue(Level level, int x, int y, int z) {
        if (!level.isWater(x, y, z)) return;
        long key = pack(x, z, y);
        if (marked.contains(key)) return;
        marked.add(key);
        queue.add(key);
        flowLeft.put(key, MAX_FLOW);
        prev.remove(key);
    }

    /** Queues a cell that water just flowed into, carrying its remaining budget. */
    private static void schedule(Level level, int x, int y, int z, int left) {
        long key = pack(x, z, y);
        if (left <= 0) {
            flowLeft.put(key, 0);
            return;
        }
        if (marked.contains(key)) {
            Integer old = flowLeft.get(key);
            if (old == null || old < left) flowLeft.put(key, left);
            return;
        }
        marked.add(key);
        queue.add(key);
        flowLeft.put(key, left);
    }

    private static long pack(int x, int z, int y) {
        // The world is unbounded horizontally, so the key carries a 21-bit
        // signed X and Z (enough for a few million blocks in every direction)
        // plus the finite 10-bit vertical Y.
        return (((long) x & 0x1FFFFFL) << 31) | (((long) z & 0x1FFFFFL) << 10) | (y & 0x3FFL);
    }

    private static int unpackX(long key) {
        return ((int) (key >>> 31) << 11) >> 11;
    }

    private static int unpackZ(long key) {
        return ((int) (key >>> 10) << 11) >> 11;
    }

    private static int unpackY(long key) {
        return (int) (key & 0x3FFL);
    }
}