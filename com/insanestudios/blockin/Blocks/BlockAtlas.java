package com.insanestudios.blockin.Blocks;

import com.insanestudios.blockin.TextureLoader;

import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Dynamic block texture atlas.
 *
 * <p>Face textures are queued with {@link #reserve} first, then packed into one
 * power-of-two texture on the first {@link #getTextureId()} build. Custom blocks
 * from {@code Mods/Blocks} therefore share a single GL texture with the built-in
 * grass/stone faces and per-cell UV lookups stay valid during display-list
 * playback.
 *
 * <p>Atlas layout (each cell 256px):
 * <pre>
 *   GRASS_TOP (0)   GRASS_SIDE (1)
 *   DIRT_BOTTOM(2)  STONE (3)
 *   ...custom block faces appended in load order...
 * </pre>
 */
public final class BlockAtlas {

    public static final int GRASS_TOP = 0;
    public static final int GRASS_SIDE = 1;
    public static final int DIRT_BOTTOM = 2;
    public static final int STONE = 3;
    public static final int WATER = 4;
    public static final int SAND = 5;
    public static final int GRAVEL = 6;
    public static final int LOG_SIDE = 7;
    public static final int LOG_TOP = 8;
    public static final int LEAVES = 9;

    static final int CELL = 256;

    private static final List<BufferedImage> cells = new ArrayList<>();
    private static final Map<String, Integer> cellByResource = new HashMap<>();

    private static int cellsPerSide = 0;
    private static int textureId = -1;
    private static BufferedImage atlasImage;
    private static boolean sealed = false;

    private BlockAtlas() {
    }

    /**
     * Queues a face texture as the next atlas cell and returns its stable index.
     * Must run before the atlas is built; a null image becomes a magenta
     * "missing texture" placeholder.
     */
    public static synchronized int reserve(BufferedImage img) {
        if (sealed) {
            throw new IllegalStateException("BlockAtlas already built; reserve() must run before rendering.");
        }
        cells.add(img);
        return cells.size() - 1;
    }

    /** Number of queued atlas cells. */
    public static synchronized int cellCount() {
        return cells.size();
    }

    /**
     * Reserves the texture loaded from {@code resource}. If the resource was
     * already reserved, or its pixels are identical to an existing cell (e.g. a
     * folder-local copy of a core png), the existing cell index is returned.
     */
    public static synchronized int reserveResource(String resource) {
        return reserveResourceOrGenerate(resource, () -> null);
    }

    /**
     * Like {@link #reserveResource} but with a procedural fallback when the
     * resource is missing from the classpath (used by {@link #seedCoreCells}).
     */
    public static synchronized int reserveResourceOrGenerate(String resource,
                                                             Supplier<BufferedImage> fallback) {
        Integer cached = cellByResource.get(resource);
        if (cached != null) return cached;

        BufferedImage img = load(resource);
        if (img == null && fallback != null) img = fallback.get();
        int index;
        if (img != null) {
            int same = findEquivalent(img);
            index = same >= 0 ? same : reserve(img);
        } else {
            index = reserve(null);
        }
        cellByResource.put(resource, index);
        return index;
    }

    /** Index of an existing cell with identical pixels, or -1. */
    private static int findEquivalent(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        for (int i = 0; i < cells.size(); i++) {
            BufferedImage c = cells.get(i);
            if (c == null || c.getWidth() != w || c.getHeight() != h) continue;
            if (Arrays.equals(img.getRGB(0, 0, w, h, null, 0, w),
                    c.getRGB(0, 0, w, h, null, 0, w))) {
                return i;
            }
        }
        return -1;
    }

    /** The source image for an atlas cell (used for HUD icons). */
    public static synchronized BufferedImage cellImage(int tile) {
        if (tile >= 0 && tile < cells.size()) {
            return cells.get(tile);
        }
        return null;
    }

    /** Seeds the built-in core faces; idempotent. Call before any reserve. */
    public static synchronized void seedCoreCells() {
        if (!cells.isEmpty()) return;
        reserveResource("/com/insanestudios/blockin/Blocks/Grass/top.png");
        reserveResource("/com/insanestudios/blockin/Blocks/Grass/side.png");
        reserveResource("/com/insanestudios/blockin/Blocks/dirt.png");
        reserveResource("/com/insanestudios/blockin/Blocks/stone.png");
        reserveResourceOrGenerate("/com/insanestudios/blockin/Blocks/water.png", BlockAtlas::generateWater);
        reserveResourceOrGenerate("/com/insanestudios/blockin/Blocks/Sand/sand.png", BlockAtlas::generateSand);
        reserve(generateGravel());
        reserveResourceOrGenerate("/com/insanestudios/blockin/Blocks/Log/Log Side.png", BlockAtlas::generateLogSide);
        reserveResourceOrGenerate("/com/insanestudios/blockin/Blocks/Log/Log Top.png", BlockAtlas::generateLogTop);
        reserveResourceOrGenerate("/com/insanestudios/blockin/Blocks/Leafs/Leafs.png", BlockAtlas::generateLeaves);
    }

    // --------------------------------------------------- procedural cells

    private static BufferedImage generateWater() {
        BufferedImage img = new BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_ARGB);
        java.util.Random rnd = new java.util.Random(0xF0950ADCL & 0xFFFFFFFFL);
        for (int y = 0; y < CELL; y++) {
            float d = y / (float) CELL;
            int r = (int) lead(24, 10, d);
            int g = (int) lead(100, 44, d);
            int b = (int) lead(205, 130, d);
            for (int x = 0; x < CELL; x++) {
                int rr = clamp(r + rnd.nextInt(9) - 4);
                int gg = clamp(g + rnd.nextInt(9) - 4);
                int bb = clamp(b + rnd.nextInt(9) - 4);
                img.setRGB(x, y, 0xFF000000 | rr << 16 | gg << 8 | bb);
            }
        }
        for (int k = 0; k < 18; k++) {
            int y = rnd.nextInt(CELL);
            for (int x = 0; x < CELL; x++) {
                int rgb = img.getRGB(x, y);
                int rr = clamp(((rgb >> 16) & 0xFF) + 16);
                int gg = clamp(((rgb >> 8) & 0xFF) + 16);
                int bb = clamp((rgb & 0xFF) + 16);
                img.setRGB(x, y, 0xFF000000 | rr << 16 | gg << 8 | bb);
            }
        }
        return img;
    }

    private static BufferedImage generateSand() {
        BufferedImage img = new BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_ARGB);
        java.util.Random rnd = new java.util.Random(0x5A1F0DL & 0x7FFFFFFFL);
        for (int y = 0; y < CELL; y++) {
            for (int x = 0; x < CELL; x++) {
                int r = clamp(220 + rnd.nextInt(21) - 10);
                int g = clamp(196 + rnd.nextInt(21) - 10);
                int b = clamp(130 + rnd.nextInt(17) - 8);
                int v = rnd.nextInt(80);
                if (v == 0) { r -= 16; g -= 12; b -= 8; }
                img.setRGB(x, y, 0xFF000000 | clamp(r) << 16 | clamp(g) << 8 | clamp(b));
            }
        }
        return img;
    }

    private static BufferedImage generateGravel() {
        BufferedImage img = new BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_ARGB);
        java.util.Random rnd = new java.util.Random(0x6A6A6A6AL & 0x7FFFFFFFL);
        for (int y = 0; y < CELL; y++) {
            for (int x = 0; x < CELL; x++) {
                int base = 132 + rnd.nextInt(21) - 10;
                int r = clamp(base + rnd.nextInt(15) - 7);
                int g = clamp(base + rnd.nextInt(15) - 7);
                int b = clamp(base + rnd.nextInt(15) - 7);
                img.setRGB(x, y, 0xFF000000 | r << 16 | g << 8 | b);
            }
        }
        for (int k = 0; k < 26; k++) {
            int px = rnd.nextInt(CELL - 5);
            int py = rnd.nextInt(CELL - 5);
            int shade = rnd.nextBoolean() ? 26 : -22;
            for (int y = py; y < py + 4; y++) {
                for (int x = px; x < px + 4; x++) {
                    int rgb = img.getRGB(x, y);
                    img.setRGB(x, y, 0xFF000000
                            | clamp(((rgb >> 16) & 0xFF) + shade) << 16
                            | clamp(((rgb >> 8) & 0xFF) + shade) << 8
                            | clamp((rgb & 0xFF) + shade));
                }
            }
        }
        return img;
    }

    private static BufferedImage generateLogSide() {
        BufferedImage img = new BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_ARGB);
        java.util.Random rnd = new java.util.Random(0x10651DEL & 0x7FFFFFFFL);
        int[] stripe = new int[CELL];
        int i = 0;
        while (i < CELL) {
            int w = 9 + rnd.nextInt(14);
            int dark = rnd.nextInt(3);
            for (int j = 0; j < w && i < CELL; j++, i++) stripe[i] = dark;
        }
        for (int y = 0; y < CELL; y++) {
            for (int x = 0; x < CELL; x++) {
                int shift = stripe[x] * (14 + rnd.nextInt(12));
                int r = clamp(107 - shift);
                int g = clamp(74 - shift);
                int b = clamp(43 - shift);
                img.setRGB(x, y, 0xFF000000 | r << 16 | g << 8 | b);
            }
        }
        return img;
    }

    private static BufferedImage generateLogTop() {
        BufferedImage img = new BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_ARGB);
        java.util.Random rnd = new java.util.Random(0x71A0A0AL & 0x7FFFFFFFL);
        float c = CELL / 2.0F;
        for (int y = 0; y < CELL; y++) {
            for (int x = 0; x < CELL; x++) {
                float dx = x + 0.5F - c;
                float dy = y + 0.5F - c;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                int ring = (int) Math.floor(dist * 0.16F) & 1;
                int r = ring == 1 ? 172 : 145;
                int g = ring == 1 ? 128 : 106;
                int b = ring == 1 ? 82 : 64;
                r = clamp(r + rnd.nextInt(7) - 3);
                g = clamp(g + rnd.nextInt(7) - 3);
                b = clamp(b + rnd.nextInt(7) - 3);
                img.setRGB(x, y, 0xFF000000 | r << 16 | g << 8 | b);
            }
        }
        return img;
    }

    private static BufferedImage generateLeaves() {
        BufferedImage img = new BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_ARGB);
        java.util.Random rnd = new java.util.Random(0x1EAFAFL & 0x7FFFFFFFL);
        for (int y = 0; y < CELL; y++) {
            for (int x = 0; x < CELL; x++) {
                int v = rnd.nextInt(100);
                if (v < 8) {
                    img.setRGB(x, y, 0x00FFFFFF); // transparent hole
                    continue;
                }
                int r = clamp(58 + rnd.nextInt(25) - 12);
                int g = clamp(112 + rnd.nextInt(40) - 20);
                int b = clamp(42 + rnd.nextInt(20) - 10);
                img.setRGB(x, y, 0xFF000000 | r << 16 | g << 8 | b);
            }
        }
        return img;
    }

    private static float lead(float a, float b, float t) {
        return a + (b - a) * (1.0F - t);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** UV quad {u0, v0, u1, v1} for the given atlas cell index. */
    public static float[] uv(int tile) {
        if (cellsPerSide == 0) getTextureId();
        float cell = 1.0F / cellsPerSide;
        int col = tile % cellsPerSide;
        int row = tile / cellsPerSide;
        float u0 = col * cell;
        float v0 = row * cell;
        return new float[]{u0, v0, u0 + cell, v0 + cell};
    }

    /** GL texture id of the built atlas (built once, lazily on first use). */
    public static int getTextureId() {
        if (textureId == -1) build();
        return textureId;
    }

    /**
     * Uploads an arbitrary image as a small, display-list-independent GL texture
     * (used for HUD icons). NEAREST filtering avoids driver-variable mipmaps.
     */
    public static int fromImage(BufferedImage img) {
        return TextureLoader.upload(img, GL11.GL_NEAREST);
    }

    /**
     * Builds a small dedicated GL texture from one atlas cell — the same kind
     * of list-independent texture the HUD uses for icons.
     */
    public static int iconTexture(int tile) {
        if (atlasImage == null) getTextureId();
        int rawSize = atlasImage.getWidth() / cellsPerSide;
        int icon = 32;
        int col = tile % cellsPerSide;
        int row = tile / cellsPerSide;
        int sx = col * rawSize;
        int sy = row * rawSize;

        BufferedImage img = new BufferedImage(icon, icon, BufferedImage.TYPE_INT_ARGB);
        Graphics g = img.getGraphics();
        g.drawImage(atlasImage, 0, 0, icon, icon, sx, sy, sx + rawSize, sy + rawSize, null);
        g.dispose();
        return fromImage(img);
    }

    /** True once every face texture has been packed into the uploaded atlas. */
    public static boolean isSealed() {
        return sealed;
    }

    private static void build() {
        if (sealed) return;

        seedCoreCells();
        int count = cells.size();
        int sps = (int) Math.ceil(Math.sqrt(count));
        int sidePow = 2;
        while (sidePow < sps) sidePow *= 2;
        cellsPerSide = sidePow;

        int pot = sidePow * CELL;
        BufferedImage atlas = new BufferedImage(pot, pot, BufferedImage.TYPE_INT_ARGB);
        Graphics g = atlas.getGraphics();
        g.setColor(new Color(214, 127, 255)); // magenta placeholder
        g.fillRect(0, 0, pot, pot);

        for (int i = 0; i < count; i++) {
            BufferedImage src = cells.get(i);
            if (src != null) {
                int col = i % cellsPerSide;
                int row = i / cellsPerSide;
                g.drawImage(src, col * CELL, row * CELL, col * CELL + CELL, row * CELL + CELL,
                        0, 0, src.getWidth(), src.getHeight(), null);
            }
        }
        g.dispose();

        atlasImage = atlas;
        textureId = TextureLoader.upload(atlas, GL11.GL_NEAREST);
        sealed = true;
        System.out.println("[BlockAtlas] built " + pot + "x" + pot + " atlas, " + count
                + " cells (" + cellsPerSide + "x" + cellsPerSide + "), tex=" + textureId);
    }

    public static BufferedImage load(String resource) {
        try (InputStream in = BlockAtlas.class.getResourceAsStream(resource)) {
            if (in == null) {
                System.err.println("[BlockAtlas] resource not found on classpath: " + resource);
                return null;
            }
            return ImageIO.read(in);
        } catch (IOException e) {
            System.err.println("[BlockAtlas] failed to load " + resource + ": " + e);
            return null;
        }
    }
}