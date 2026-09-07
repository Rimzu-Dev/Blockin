package com.insanestudios.blockin.Blocks;

import com.insanestudios.blockin.World.Level;
import com.insanestudios.blockin.World.Tesselator;

/**
 * Water. Renders with real translucency (per-vertex alpha in the shared
 * tesselator) instead of the opaque color path, and never appears in the
 * shaded pass twice — the lit surface and the shaded underwater walls are
 * still split across the two world layers exactly like opaque blocks.
 *
 * <p>Water is intentionally <em>not</em> physically solid, so entities sink
 * into it and swim; {@link Level#isSolidTile} treats it like air.
 */
public final class WaterBlock extends Block {

    private static final float ALPHA = 0.62F;

    public WaterBlock(int id, String name, int tileTop, int tileSide, int tileBottom,
                      String stepSound, String placeSound, String breakSound) {
        super(id, name, tileTop, tileSide, tileBottom, stepSound, placeSound, breakSound);
    }

    public WaterBlock(int id, String name, String iconName, java.awt.image.BufferedImage icon,
                      int tileTop, int tileSide, int tileBottom,
                      String stepSound, String placeSound, String breakSound) {
        super(id, name, iconName, icon, tileTop, tileSide, tileBottom, stepSound, placeSound, breakSound);
    }

    @Override
    public void render(Tesselator t, Level level, int layer, int x, int y, int z) {
        float c1 = 1.0F;
        float c2 = 0.8F;
        float c3 = 0.6F;

        float x0 = x;
        float x1 = x + 1.0F;
        float y0 = y;
        float y1 = y + 1.0F;
        float z0 = z;
        float z1 = z + 1.0F;

        if (!level.isTile(x, y - 1, z)) {
            float br = level.getBrightness(x, y - 1, z) * c1;
            if (br == c1 ^ layer == 1) {
                float[] uv = faceUV(0);
                t.color(br, br, br, ALPHA);
                t.tex(uv[0], uv[3]); t.vertex(x0, y0, z1);
                t.tex(uv[0], uv[1]); t.vertex(x0, y0, z0);
                t.tex(uv[2], uv[1]); t.vertex(x1, y0, z0);
                t.tex(uv[2], uv[3]); t.vertex(x1, y0, z1);
            }
        }

        if (!level.isTile(x, y + 1, z)) {
            float br = level.getBrightness(x, y, z) * c1;
            if (br == c1 ^ layer == 1) {
                float[] uv = faceUV(1);
                t.color(br, br, br, ALPHA);
                t.tex(uv[2], uv[3]); t.vertex(x1, y1, z1);
                t.tex(uv[2], uv[1]); t.vertex(x1, y1, z0);
                t.tex(uv[0], uv[1]); t.vertex(x0, y1, z0);
                t.tex(uv[0], uv[3]); t.vertex(x0, y1, z1);
            }
        }

        float[] sideUV = faceUV(2);
        if (!level.isTile(x, y, z - 1)) {
            float br = level.getBrightness(x, y, z - 1) * c2;
            if (br == c2 ^ layer == 1) {
                t.color(br, br, br, ALPHA);
                t.tex(sideUV[2], sideUV[1]); t.vertex(x0, y1, z0);
                t.tex(sideUV[0], sideUV[1]); t.vertex(x1, y1, z0);
                t.tex(sideUV[0], sideUV[3]); t.vertex(x1, y0, z0);
                t.tex(sideUV[2], sideUV[3]); t.vertex(x0, y0, z0);
            }
        }

        if (!level.isTile(x, y, z + 1)) {
            float br = level.getBrightness(x, y, z + 1) * c2;
            if (br == c2 ^ layer == 1) {
                t.color(br, br, br, ALPHA);
                t.tex(sideUV[0], sideUV[1]); t.vertex(x0, y1, z1);
                t.tex(sideUV[0], sideUV[3]); t.vertex(x0, y0, z1);
                t.tex(sideUV[2], sideUV[3]); t.vertex(x1, y0, z1);
                t.tex(sideUV[2], sideUV[1]); t.vertex(x1, y1, z1);
            }
        }

        if (!level.isTile(x - 1, y, z)) {
            float br = level.getBrightness(x - 1, y, z) * c3;
            if (br == c3 ^ layer == 1) {
                t.color(br, br, br, ALPHA);
                t.tex(sideUV[2], sideUV[1]); t.vertex(x0, y1, z1);
                t.tex(sideUV[0], sideUV[1]); t.vertex(x0, y1, z0);
                t.tex(sideUV[0], sideUV[3]); t.vertex(x0, y0, z0);
                t.tex(sideUV[2], sideUV[3]); t.vertex(x0, y0, z1);
            }
        }

        if (!level.isTile(x + 1, y, z)) {
            float br = level.getBrightness(x + 1, y, z) * c3;
            if (br == c3 ^ layer == 1) {
                t.color(br, br, br, ALPHA);
                t.tex(sideUV[0], sideUV[3]); t.vertex(x1, y0, z1);
                t.tex(sideUV[2], sideUV[3]); t.vertex(x1, y0, z0);
                t.tex(sideUV[2], sideUV[1]); t.vertex(x1, y1, z0);
                t.tex(sideUV[0], sideUV[1]); t.vertex(x1, y1, z1);
            }
        }
    }

    private float[] faceUV(int face) {
        return BlockAtlas.uv(face(face));
    }
}