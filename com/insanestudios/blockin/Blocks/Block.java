package com.insanestudios.blockin.Blocks;

import com.insanestudios.blockin.World.Level;
import com.insanestudios.blockin.World.Tesselator;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * A placeable block. Built-in blocks (stone, grass) are created by
 * {@link BlockLoader}; custom blocks are loaded from folders or ZIPs under
 * {@code Mods/Blocks}.
 *
 * <p>Each block stores the atlas cell indices of its top/side/bottom faces, an
 * optional dedicated HUD icon, and the audio identifiers used for stepping,
 * placing and breaking.
 */
public class Block {

    public final int id;
    public final String name;

    /**
     * Mods may describe the block (from the block XML). Optional; empty for
     * built-in faces.
     */
    public String description = "";

    /**
     * Behavioral category: "solid" (opaque physics), "transparent" (solid but
     * cut-out textures) or "liquid" (non-solid, translucent, swimmable).
     */
    public String blockType = "solid";

    /** True for liquid blocks (non-solid, translucent, swim-through). */
    public boolean isLiquid() {
        return "liquid".equals(blockType);
    }

    /** Face indices understood by {@link #face(int)} / {@link #getFaceImage(int)}. */
    public static final int FACE_BOTTOM = 0;
    public static final int FACE_TOP = 1;
    public static final int FACE_SIDE = 2;

    private final int tileTop;
    private final int tileSide;
    private final int tileBottom;

    private final String iconName;
    private final File iconFile;
    private final BufferedImage iconImage;

    private final String stepSound;
    private final String placeSound;
    private final String breakSound;

    private int iconTex = -1;

    /** Full constructor; sound fields fall back to stone sounds when empty. */
    private Block(int id, String name, String iconName, File iconFile,
                  BufferedImage iconImage, int tileTop, int tileSide, int tileBottom,
                  String stepSound, String placeSound, String breakSound) {
        this.id = id;
        this.name = name;
        this.iconName = iconName;
        this.iconFile = iconFile;
        this.iconImage = iconImage;
        this.tileTop = tileTop;
        this.tileSide = tileSide;
        this.tileBottom = tileBottom;
        this.stepSound = nonEmpty(stepSound, "step.stone");
        this.placeSound = nonEmpty(placeSound, "place.stone");
        this.breakSound = nonEmpty(breakSound, "break.stone");
    }

    // Built-in block (default stone sounds)
    public Block(int id, String name, int tileTop, int tileSide, int tileBottom) {
        this(id, name, null, null, null, tileTop, tileSide, tileBottom, null, null, null);
    }

    // Built-in block with custom sounds
    public Block(int id, String name, int tileTop, int tileSide, int tileBottom,
                 String stepSound, String placeSound, String breakSound) {
        this(id, name, null, null, null, tileTop, tileSide, tileBottom, stepSound, placeSound, breakSound);
    }

    // Folder-mod block (File icon)
    public Block(int id, String name, String iconName, File iconFile,
                 int tileTop, int tileSide, int tileBottom,
                 String stepSound, String placeSound, String breakSound) {
        this(id, name, iconName, iconFile, null, tileTop, tileSide, tileBottom, stepSound, placeSound, breakSound);
    }

    // ZIP-mod block (BufferedImage icon)
    public Block(int id, String name, String iconName, BufferedImage iconImage,
                 int tileTop, int tileSide, int tileBottom,
                 String stepSound, String placeSound, String breakSound) {
        this(id, name, iconName, null, iconImage, tileTop, tileSide, tileBottom, stepSound, placeSound, breakSound);
    }

    private static String nonEmpty(String value, String fallback) {
        return (value != null && !value.trim().isEmpty()) ? value : fallback;
    }

    public String getStepSound() { return stepSound; }
    public String getPlaceSound() { return placeSound; }
    public String getBreakSound() { return breakSound; }

    /** Atlas cell for the given world face (0 bottom, 1 top, else sides). */
    int face(int face) {
        return switch (face) {
            case 0 -> tileBottom;
            case 1 -> tileTop;
            default -> tileSide;
        };
    }

    /** Source image for a face (0 bottom, 1 top, else side), from the atlas. */
    public BufferedImage getFaceImage(int face) {
        return BlockAtlas.cellImage(face(face));
    }

    /** Small dedicated GL texture for HUD hotbar/hotbar icons. */
    public int getIconTexture() {
        if (iconTex != -1) return iconTex;

        BufferedImage ui = loadResource("/com/insanestudios/blockin/UI/Blocks/" + iconName() + ".png");
        if (ui == null && iconFile != null) ui = loadFile(iconFile);
        if (ui == null && iconImage != null) ui = iconImage;
        iconTex = (ui != null) ? BlockAtlas.fromImage(ui) : BlockAtlas.iconTexture(tileSide);
        return iconTex;
    }

    private String iconName() {
        return (iconName != null) ? iconName : name;
    }

    private static BufferedImage loadResource(String path) {
        try (InputStream in = Block.class.getResourceAsStream(path)) {
            return (in == null) ? null : ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static BufferedImage loadFile(File f) {
        try {
            return (f != null && f.isFile()) ? ImageIO.read(f) : null;
        } catch (IOException e) {
            return null;
        }
    }

    // -------------------------------------------------------- world render

    /**
     * Draws the visible faces of this block into a tesselator, honoring the
     * two-pass lit/unlit layer system of the world renderer.
     */
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

        if (!level.isSolidTile(x, y - 1, z)) {
            float br = level.getBrightness(x, y - 1, z) * c1;
            if (br == c1 ^ layer == 1) {
                float[] uv = BlockAtlas.uv(tileBottom);
                t.color(br, br, br);
                t.tex(uv[0], uv[3]); t.vertex(x0, y0, z1);
                t.tex(uv[0], uv[1]); t.vertex(x0, y0, z0);
                t.tex(uv[2], uv[1]); t.vertex(x1, y0, z0);
                t.tex(uv[2], uv[3]); t.vertex(x1, y0, z1);
            }
        }

        if (!level.isSolidTile(x, y + 1, z)) {
            float br = level.getBrightness(x, y, z) * c1;
            if (br == c1 ^ layer == 1) {
                float[] uv = BlockAtlas.uv(tileTop);
                t.color(br, br, br);
                t.tex(uv[2], uv[3]); t.vertex(x1, y1, z1);
                t.tex(uv[2], uv[1]); t.vertex(x1, y1, z0);
                t.tex(uv[0], uv[1]); t.vertex(x0, y1, z0);
                t.tex(uv[0], uv[3]); t.vertex(x0, y1, z1);
            }
        }

        float[] sideUV = BlockAtlas.uv(tileSide);
        if (!level.isSolidTile(x, y, z - 1)) {
            float br = level.getBrightness(x, y, z - 1) * c2;
            if (br == c2 ^ layer == 1) {
                t.color(br, br, br);
                t.tex(sideUV[2], sideUV[1]); t.vertex(x0, y1, z0);
                t.tex(sideUV[0], sideUV[1]); t.vertex(x1, y1, z0);
                t.tex(sideUV[0], sideUV[3]); t.vertex(x1, y0, z0);
                t.tex(sideUV[2], sideUV[3]); t.vertex(x0, y0, z0);
            }
        }

        if (!level.isSolidTile(x, y, z + 1)) {
            float br = level.getBrightness(x, y, z + 1) * c2;
            if (br == c2 ^ layer == 1) {
                t.color(br, br, br);
                t.tex(sideUV[0], sideUV[1]); t.vertex(x0, y1, z1);
                t.tex(sideUV[0], sideUV[3]); t.vertex(x0, y0, z1);
                t.tex(sideUV[2], sideUV[3]); t.vertex(x1, y0, z1);
                t.tex(sideUV[2], sideUV[1]); t.vertex(x1, y1, z1);
            }
        }

        if (!level.isSolidTile(x - 1, y, z)) {
            float br = level.getBrightness(x - 1, y, z) * c3;
            if (br == c3 ^ layer == 1) {
                t.color(br, br, br);
                t.tex(sideUV[2], sideUV[1]); t.vertex(x0, y1, z1);
                t.tex(sideUV[0], sideUV[1]); t.vertex(x0, y1, z0);
                t.tex(sideUV[0], sideUV[3]); t.vertex(x0, y0, z0);
                t.tex(sideUV[2], sideUV[3]); t.vertex(x0, y0, z1);
            }
        }

        if (!level.isSolidTile(x + 1, y, z)) {
            float br = level.getBrightness(x + 1, y, z) * c3;
            if (br == c3 ^ layer == 1) {
                t.color(br, br, br);
                t.tex(sideUV[0], sideUV[3]); t.vertex(x1, y0, z1);
                t.tex(sideUV[2], sideUV[3]); t.vertex(x1, y0, z0);
                t.tex(sideUV[2], sideUV[1]); t.vertex(x1, y1, z0);
                t.tex(sideUV[0], sideUV[1]); t.vertex(x1, y1, z1);
            }
        }
    }

    /**
     * Writes the four corners of a single block face (no texture/colour) —
     * used by the picking and block-highlight passes.
     */
    public void renderFace(Tesselator t, int x, int y, int z, int face) {
        float x0 = x;
        float x1 = x + 1.0F;
        float y0 = y;
        float y1 = y + 1.0F;
        float z0 = z;
        float z1 = z + 1.0F;

        switch (face) {
            case 0 -> { // bottom
                t.vertex(x0, y0, z1);
                t.vertex(x0, y0, z0);
                t.vertex(x1, y0, z0);
                t.vertex(x1, y0, z1);
            }
            case 1 -> { // top
                t.vertex(x1, y1, z1);
                t.vertex(x1, y1, z0);
                t.vertex(x0, y1, z0);
                t.vertex(x0, y1, z1);
            }
            case 2 -> { // -z
                t.vertex(x0, y1, z0);
                t.vertex(x1, y1, z0);
                t.vertex(x1, y0, z0);
                t.vertex(x0, y0, z0);
            }
            case 3 -> { // +z
                t.vertex(x0, y1, z1);
                t.vertex(x0, y0, z1);
                t.vertex(x1, y0, z1);
                t.vertex(x1, y1, z1);
            }
            case 4 -> { // -x
                t.vertex(x0, y1, z1);
                t.vertex(x0, y1, z0);
                t.vertex(x0, y0, z0);
                t.vertex(x0, y0, z1);
            }
            default -> { // +x
                t.vertex(x1, y0, z1);
                t.vertex(x1, y0, z0);
                t.vertex(x1, y1, z0);
                t.vertex(x1, y1, z1);
            }
        }
    }
}