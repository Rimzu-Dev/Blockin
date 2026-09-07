package com.insanestudios.blockin.World;

import com.insanestudios.blockin.HitResult;
import com.insanestudios.blockin.Physics.Box;
import com.insanestudios.blockin.PlayerController;
import com.insanestudios.blockin.Blocks.Block;
import com.insanestudios.blockin.Blocks.BlockLoader;
import com.insanestudios.blockin.MainMenu;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;

import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Renders the world out of streamed 16x16x16 chunks and supports block picking
 * through OpenGL's selection mode.
 *
 * <p>The world is infinite, so only the chunks within a radius around the
 * player are materialized as display lists; chunks that fall out of range are
 * disposed. Chunk edit events only repaint chunks that already exist.
 */
public final class LevelRenderer implements LevelListener {

    private static final int CHUNK_SIZE = 16;
    /** Chunk-load radius (in chunks) used when render distance is far. */
    private static final int FAR_RADIUS = 12;
    /** Chunk-load radius (in chunks) used when render distance is short. */
    private static final int SHORT_RADIUS = 6;
    private static final int SELECT_BUFFER_SIZE = 4096;

    private final Level level;
    private final Map<Long, Chunk> chunks = new HashMap<>();

    private final Tesselator t = new Tesselator();
    private final IntBuffer selectBuffer = BufferUtils.createIntBuffer(SELECT_BUFFER_SIZE);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(4);

    public LevelRenderer(Level level) {
        this.level = level;
        level.addListener(this);
    }

    private static long key(int cx, int cy, int cz) {
        return ((long) cx << 32) | ((cy & 0xFFL) << 16) | (cz & 0xFFFFL);
    }

    public void render(PlayerController player, int layer) {
        Chunk.rebuiltThisFrame = 0;
        updateChunks(player);
        Frustum frustum = Frustum.getFrustum();

        for (Chunk chunk : chunks.values()) {
            if (frustum.cubeInFrustum(chunk.aabb)) {
                chunk.render(layer);
            }
        }
    }

    /** Adds chunks around the player and disposes those that fell out of range. */
    private void updateChunks(PlayerController player) {
        int radius = MainMenu.isFarRenderDistance() ? FAR_RADIUS : SHORT_RADIUS;
        int pcx = Math.floorDiv((int) Math.floor(player.x), CHUNK_SIZE);
        int pcz = Math.floorDiv((int) Math.floor(player.z), CHUNK_SIZE);
        int yc = level.yChunks;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                int cx = pcx + dx;
                int cz = pcz + dz;
                for (int cy = 0; cy < yc; cy++) {
                    long k = key(cx, cy, cz);
                    if (chunks.containsKey(k)) continue;
                    chunks.put(k, new Chunk(
                            level,
                            cx * CHUNK_SIZE, cy * CHUNK_SIZE, cz * CHUNK_SIZE,
                            (cx + 1) * CHUNK_SIZE,
                            Math.min((cy + 1) * CHUNK_SIZE, level.depth),
                            (cz + 1) * CHUNK_SIZE));
                }
            }
        }

        Iterator<Map.Entry<Long, Chunk>> it = chunks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Chunk> e = it.next();
            long k = e.getKey();
            int cx = (int) (k >> 32);
            int cz = (int) (short) (k & 0xFFFF);
            if (cx < pcx - radius - 1 || cx > pcx + radius + 1 || cz < pcz - radius - 1 || cz > pcz + radius + 1) {
                e.getValue().dispose();
                it.remove();
            }
        }
    }

    /**
     * Runs the picking pass against the block under the screen centre (the
     * grabbed mouse always sits there). Names pushed per block are: x, y, z,
     * tag (0), face — matching the order {@link HitResult} expects. Returns the
     * closest hit, or null when nothing is in reach.
     */
    public HitResult pick(PlayerController player) {
        int w = Display.getWidth();
        int h = Display.getHeight();
        if (w <= 0 || h <= 0) return null;

        viewport.clear();
        viewport.put(0).put(0).put(w).put(h);
        viewport.flip();

        selectBuffer.clear();
        // glSelectBuffer MUST be called BEFORE entering GL_SELECT mode:
        // otherwise the driver keeps its own default (tiny) selection buffer,
        // every pick overflows it (hits == -1) and nothing is ever written
        // into our buffer -> pick always returns null -> no break/place.
        GL11.glSelectBuffer(selectBuffer);
        GL11.glRenderMode(GL11.GL_SELECT);
        // Only the front-most faces along each ray should record: clear depth
        // so occluded faces behind already-rasterized ones don't flood the
        // selection buffer (a 5x5 wedge looking straight down can otherwise
        // slice many ground layers and overflow -> hits == -1 -> null hit).
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GLU.gluPickMatrix(w / 2.0F, h / 2.0F, 5.0F, 5.0F, viewport);
        GLU.gluPerspective(70.0F, (float) w / (float) h, 0.05F,
                MainMenu.isFarRenderDistance() ? 1000.0F : 300.0F);

        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glRotatef(player.xRot, 1.0F, 0.0F, 0.0F);
        GL11.glRotatef(player.yRot, 0.0F, 1.0F, 0.0F);
        GL11.glTranslatef(-player.x, -player.y, -player.z);

        final float reach = 3.0F;
        Box box = player.bb.grow(reach, reach, reach);
        int x0 = (int) box.x0;
        int x1 = (int) (box.x1 + 1.0F);
        int y0 = (int) box.y0;
        int y1 = (int) (box.y1 + 1.0F);
        int z0 = (int) box.z0;
        int z1 = (int) (box.z1 + 1.0F);

        GL11.glInitNames();
        for (int x = x0; x < x1; x++) {
            GL11.glPushName(x);
            for (int y = y0; y < y1; y++) {
                GL11.glPushName(y);
                for (int z = z0; z < z1; z++) {
                    GL11.glPushName(z);
                    if (level.isSolidTile(x, y, z)) {
                        GL11.glPushName(0);
                        for (int face = 0; face < 6; face++) {
                            GL11.glPushName(face);
                            t.init();
                            Block b = BlockLoader.get(level.getTile(x, y, z));
                            if (b != null) {
                                b.renderFace(t, x, y, z, face);
                            }
                            t.flush();
                            GL11.glPopName();
                        }
                        GL11.glPopName();
                    }
                    GL11.glPopName();
                }
                GL11.glPopName();
            }
            GL11.glPopName();
        }

        int hits = GL11.glRenderMode(GL11.GL_RENDER);

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();

        return processHits(hits);
    }

    /** Reads the selection buffer and returns the hit closest to the eye. */
    private HitResult processHits(int hits) {
        // On overflow glRenderMode returns -1 but still leaves every record it
        // could fit in the buffer, so bound the scan by capacity instead.
        int maxRecords = hits > 0 ? hits : selectBuffer.capacity() / 3;

        int bestZ = Integer.MAX_VALUE;
        int bx = 0;
        int by = 0;
        int bz = 0;
        int bFace = 0;
        int found = 0;

        selectBuffer.rewind();
        for (int i = 0; i < maxRecords && selectBuffer.remaining() >= 3; i++) {
            int names = selectBuffer.get();
            int zMin = selectBuffer.get();
            int zMax = selectBuffer.get();
            if (zMin >= bestZ) {
                selectBuffer.position(selectBuffer.position() + names);
                continue;
            }

            int n0 = 0;
            int n1 = 0;
            int n2 = 0;
            int n3 = 0;
            int n4 = 0;
            for (int j = 0; j < names && selectBuffer.hasRemaining(); j++) {
                int name = selectBuffer.get();
                switch (j) {
                    case 0 -> n0 = name;
                    case 1 -> n1 = name;
                    case 2 -> n2 = name;
                    case 3 -> n3 = name;
                    case 4 -> n4 = name;
                    default -> {
                    }
                }
            }

            bestZ = zMin;
            bx = n0;
            by = n1;
            bz = n2;
            if (n3 == 0) { // tag must match so face names align to stack depth 5
                bFace = n4;
            }
            found = 1;
        }

        return found != 0 ? new HitResult(bx, by, bz, 0, bFace) : null;
    }

    /** Draws the wire highlight on the targeted block face. */
    public void renderHit(HitResult h) {
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        float pulse = (float) Math.sin(System.currentTimeMillis() / 100.0) * 0.2F + 0.4F;
        GL11.glColor4f(1.0F, 1.0F, 1.0F, pulse);

        t.init();
        Block b = BlockLoader.get(level.getTile(h.x(), h.y(), h.z()));
        if (b != null) {
            b.renderFace(t, h.x(), h.y(), h.z(), h.f());
        }
        t.flush();
        GL11.glDisable(GL11.GL_BLEND);
    }

    // ------------------------------------------------------- LevelListener

    public void setDirty(int x0, int y0, int z0, int x1, int y1, int z1) {
        int cx0 = Math.floorDiv(x0, CHUNK_SIZE);
        int cx1 = Math.floorDiv(x1, CHUNK_SIZE);
        int cz0 = Math.floorDiv(z0, CHUNK_SIZE);
        int cz1 = Math.floorDiv(z1, CHUNK_SIZE);
        int cy0 = Math.max(0, Math.floorDiv(y0, CHUNK_SIZE));
        int cy1 = Math.min(level.yChunks - 1, Math.floorDiv(y1, CHUNK_SIZE));

        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                for (int cy = cy0; cy <= cy1; cy++) {
                    Chunk chunk = chunks.get(key(cx, cy, cz));
                    if (chunk != null) {
                        chunk.setDirty();
                    }
                }
            }
        }
    }

    @Override
    public void tileChanged(int x, int y, int z) {
        setDirty(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1);
    }

    @Override
    public void lightColumnChanged(int x, int z, int y0, int y1) {
        setDirty(x - 1, y0 - 1, z - 1, x + 1, y1 + 1, z + 1);
    }

    @Override
    public void allChanged() {
        for (Chunk chunk : chunks.values()) {
            chunk.setDirty();
        }
    }
}