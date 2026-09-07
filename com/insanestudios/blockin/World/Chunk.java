package com.insanestudios.blockin.World;

import com.insanestudios.blockin.Blocks.Block;
import com.insanestudios.blockin.Blocks.BlockAtlas;
import com.insanestudios.blockin.Blocks.BlockLoader;

import org.lwjgl.opengl.GL11;

/**
 * A 16x16x16 slice of the world baked into two OpenGL display lists
 * (lit + shadowed layers).
 *
 * <p>Geometry is re-recorded whenever a {@link Level} change marks the chunk
 * dirty. The block atlas texture id is resolved <em>before</em> {@code glNewList}
 * so first-time texture creation never gets captured into a display list.
 */
public final class Chunk {

    /** Upper bound on display lists rebuilt in one frame. */
    static int rebuiltThisFrame = 0;

    /** Number of chunk rebuilds performed, for the fps/debug readout. */
    public static int updates = 0;

    public final com.insanestudios.blockin.Physics.Box aabb;
    public final Level level;
    public final int x0;
    public final int y0;
    public final int z0;
    public final int x1;
    public final int y1;
    public final int z1;

    private boolean dirty = true;
    private int lists = -1;

    private static final Tesselator t = new Tesselator();

    public Chunk(Level level, int x0, int y0, int z0, int x1, int y1, int z1) {
        this.level = level;
        this.x0 = x0;
        this.y0 = y0;
        this.z0 = z0;
        this.x1 = x1;
        this.y1 = y1;
        this.z1 = z1;
        this.aabb = new com.insanestudios.blockin.Physics.Box(x0, y0, z0, x1, y1, z1);
        this.lists = GL11.glGenLists(2);
    }

    private void rebuild(int layer) {
        // At most two display-list rebuilds per frame (self-throttle). Chunks
        // that stay dirty are simply retried on a later frame.
        if (rebuiltThisFrame >= 2) return;
        dirty = false;
        updates++;
        rebuiltThisFrame++;

        // Resolve the atlas texture id BEFORE glNewList so lazy (first-time)
        // texture creation never runs while a display list is being recorded.
        BlockAtlas.getTextureId();
        GL11.glNewList(lists + layer, GL11.GL_COMPILE);
        t.init();
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                for (int z = z0; z < z1; z++) {
                    if (level.isTile(x, y, z)) {
                        int type = level.getTile(x, y, z);
                        Block b = BlockLoader.get(type);
                        if (b != null) {
                            b.render(t, level, layer, x, y, z);
                        }
                    }
                }
            }
        }
        t.flush();
        GL11.glEndList();
    }

    public void render(int layer) {
        if (dirty) {
            rebuild(0);
            rebuild(1);
        }

        // Bind the atlas in immediate mode before playing the list so the bind
        // itself is never captured into the display list.
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, BlockAtlas.getTextureId());
        GL11.glCallList(lists + layer);
    }

    public void setDirty() {
        dirty = true;
    }

    /** Frees the GL display lists when the streaming renderer drops the chunk. */
    public void dispose() {
        if (lists != -1) {
            GL11.glDeleteLists(lists, 2);
            lists = -1;
        }
    }
}