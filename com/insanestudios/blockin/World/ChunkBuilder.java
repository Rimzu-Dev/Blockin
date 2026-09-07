package com.insanestudios.blockin.World;

/**
 * Eagerly generates the spawn-area terrain for a {@link Level}.
 *
 * <p>Generation itself is fully lazy ({@link Level#ensureChunk}); this only
 * forces the chunks that cover the world's corner footprint so the player's
 * starting area and legacy saves are ready up front.
 */
public final class ChunkBuilder {

    public static final int CHUNK_SIZE = 16;

    private ChunkBuilder() {
    }

    public static void terrain(Level level) {
        int cx = (level.width + CHUNK_SIZE - 1) / CHUNK_SIZE;
        int cz = (level.height + CHUNK_SIZE - 1) / CHUNK_SIZE;

        for (int x = 0; x < cx; x++) {
            for (int z = 0; z < cz; z++) {
                level.ensureChunk(x, z);
            }
        }
    }
}