package com.insanestudios.blockin.Models;

import java.awt.image.BufferedImage;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/**
 * Loads FBX models from {@code Mods/Models/**\/*.fbx} into a catalog keyed by
 * relative name (e.g. {@code Mods/Models/Pig.fbx} becomes {@code "Pig"},
 * {@code Mods/Models/myPack/Chair.fbx} becomes {@code "myPack/Chair"}).
 *
 * <p>Textures referenced by the FBX are resolved relative to the FBX file's
 * own folder, so place {@code texture.png} next to {@code model.fbx}.
 */
public final class ModelLoader {

    /** Catalog: model name -> GL-ready mesh. */
    public static final Map<String, Model> models = new LinkedHashMap<>();

    /** Count of successfully loaded models (also shown in the debug HUD). */
    public static int loadCount;

    private ModelLoader() {
    }

    /** Scan every model root (Mods/Models plus a sibling "Models" in-game root),
     *  load every *.fbx found. Never throws. */
    public static void warmUp() {
        loadCount = 0;
        models.clear();
        File base = findModsDir();
        try {
            addModels(new File(base, "Models"));
            File parentRoot = base.getParentFile();
            if (parentRoot != null) addModels(new File(parentRoot, "Models"));
        } catch (Exception ex) {
            System.out.println("[ModelLoader] warmUp error: " + ex);
        }
        System.out.println("[ModelLoader] loaded " + loadCount + " model(s)");
    }

    /** Loads every FBX under {@code dir} (recursively) into the catalog. */
    private static void addModels(File dir) {
        if (!dir.isDirectory()) return;
        for (File fbx : findFbx(dir)) {
            try {
                load(fbx, dir);
            } catch (Exception ex) {
                System.out.println("[ModelLoader] failed " + fbx.getName() + ": " + ex);
            }
        }
    }

    /** Loads one FBX and registers it under its relative name. */
    public static void load(File fbxFile) throws Exception {
        load(fbxFile, new File(findModsDir(), "Models"));
    }

    /** Loads one FBX relative to {@code relBase} and registers it under the
     *  resulting name. */
    public static void load(File fbxFile, File relBase) throws Exception {
        Model m = FbxParser.toModel(FbxParser.parse(fbxFile), fbxFile.getParentFile());
        String name = relativeName(fbxFile, relBase);
        if (m.triangleCount() <= 0) {
            System.out.println("[ModelLoader] " + name + ": no triangles (empty mesh)");
            return;
        }
        models.put(name, m);
        m.feetY = lowestY(m);
        m.feetY = anchorFeetY(fbxFile, m.feetY);
        m.heightY = highestY(m) - m.feetY;
        loadCount++;
        System.out.println("[ModelLoader] + " + name + " (" + m.triangleCount() + " tris, tex=" + m.tex
                + ", bones=" + m.boneCount() + " feetY=" + m.feetY + " heightY=" + m.heightY + ")");
    }

    /** Lowest vertex Y over both the corner mesh and the bind-control points. */
    private static float lowestY(Model m) {
        float minY = Float.MAX_VALUE;
        for (int i = 0; i < m.verts.length; i += 3) {
            minY = Math.min(minY, m.verts[i + 1]);
        }
        if (m.skinVerts != null) {
            for (int i = 0; i < m.skinVerts.length; i += 3) {
                minY = Math.min(minY, m.skinVerts[i + 1]);
            }
        }
        return minY == Float.MAX_VALUE ? 0f : minY;
    }

    /**
     * Optional per-model feet override: a text file named {@code <model>.fbx.anchor}
     * next to the FBX may contain a single Y value (in FBX units) used as the
     * standing floor. Needed when stray geometry (e.g. robes/capes) hangs below
     * the character's actual feet and would otherwise float the avatar.
     */
    private static float anchorFeetY(File fbxFile, float fallback) {
        File anchor = new File(fbxFile.getParentFile(), fbxFile.getName() + ".anchor");
        if (!anchor.isFile()) return fallback;
        try {
            java.util.Scanner sc = new java.util.Scanner(anchor);
            if (sc.hasNextDouble()) {
                float v = (float) sc.nextDouble();
                sc.close();
                System.out.println("[ModelLoader] " + fbxFile.getName() + ": feet anchored to " + v);
                return v;
            }
            sc.close();
        } catch (Exception ignore) {
        }
        return fallback;
    }

    /** Highest vertex Y over both the corner mesh and the bind-control points. */
    private static float highestY(Model m) {
        float maxY = -Float.MAX_VALUE;
        for (int i = 0; i < m.verts.length; i += 3) {
            maxY = Math.max(maxY, m.verts[i + 1]);
        }
        if (m.skinVerts != null) {
            for (int i = 0; i < m.skinVerts.length; i += 3) {
                maxY = Math.max(maxY, m.skinVerts[i + 1]);
            }
        }
        return maxY == -Float.MAX_VALUE ? 1f : maxY;
    }

    /**
     * Loads a texture file referenced by an FBX into a GL texture.
     * The file is searched relative to the FBX's directory (plus a single
     * parent level, since Blender often writes "//textures/foo.png").
     */
    static int loadTexture(String fileName, File dir) {
        File tex = null;
        File direct = new File(dir, fileName);
        if (direct.isFile()) tex = direct;
        else {
            File parent = new File(dir, "textures");
            if (parent.isDirectory()) {
                File inTextures = new File(parent, fileName);
                if (inTextures.isFile()) tex = inTextures;
                else {
                    String base = new File(fileName).getName();
                    File inTexturesBase = new File(parent, base);
                    if (inTexturesBase.isFile()) tex = inTexturesBase;
                }
            }
        }
        if (tex == null) return -1;
        try {
            BufferedImage img = ImageIO.read(tex);
            if (img == null) return -1;
            return uploadTexture(img);
        } catch (Exception ex) {
            return -1;
        }
    }

    private static int uploadTexture(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] pixels = new int[w * h];
        img.getRGB(0, 0, w, h, pixels, 0, w);
        java.nio.ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = pixels[y * w + x];
                int a = (p >> 24) & 0xFF;
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                if (a < 26) { r = 150; g = 150; b = 150; } // transparent padding -> neutral gray
                buf.put((byte) r);
                buf.put((byte) g);
                buf.put((byte) b);
                buf.put((byte) 0xFF); // force opaque: the world's alpha test (GL_GREATER 0.1)
                // otherwise discards whole faces whose UVs land on transparent cells.
            }
        }
        buf.flip();
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0, GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE, buf);
        return id;
    }

    /** Render a cataloged model (should be called between push/pop or with transform applied). */
    public static void render(String name) {
        Model m = models.get(name);
        if (m != null) m.render();
    }

    /** Renders a model translated + uniformly scaled. */
    public static void render(String name, float x, float y, float z, float scale) {
        Model m = models.get(name);
        if (m == null) return;
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, z);
        GL11.glScalef(scale, scale, scale);
        m.render();
        GL11.glPopMatrix();
    }

    private static String relativeName(File fbx, File base) {
        String abs = fbx.getAbsolutePath();
        String basePath = base.getAbsolutePath() + File.separator;
        String rel = abs.startsWith(basePath) ? abs.substring(basePath.length()) : fbx.getName();
        if (rel.toLowerCase(java.util.Locale.ROOT).endsWith(".fbx")) {
            rel = rel.substring(0, rel.length() - 4);
        }
        return rel.replace('\\', '/');
    }

    private static java.util.List<File> findFbx(File dir) {
        java.util.List<File> out = new java.util.ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isDirectory()) out.addAll(findFbx(f));
            else if (f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".fbx")) out.add(f);
        }
        return out;
    }

    /** Mirrors BlockLoader.findModsDir(): walk up from cwd to the first "Mods" folder. */
    private static File findModsDir() {
        String override = System.getProperty("mc.modsDir");
        if (override != null) {
            File f = new File(override);
            if (f.isDirectory()) return f;
        }
        File dir = new File("").getAbsoluteFile();
        while (dir != null) {
            File mods = new File(dir, "Mods");
            if (mods.isDirectory()) return mods;
            dir = dir.getParentFile();
        }
        return new File("Mods");
    }
}