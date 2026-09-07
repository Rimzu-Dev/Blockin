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

    /** Scan Mods/Models, load every *.fbx found. Never throws. */
    public static void warmUp() {
        loadCount = 0;
        models.clear();
        try {
            File dir = new File(findModsDir(), "Models");
            if (!dir.isDirectory()) {
                System.out.println("[ModelLoader] no Mods/Models dir: " + dir);
                return;
            }
            for (File fbx : findFbx(dir)) {
                try {
                    load(fbx);
                } catch (Exception ex) {
                    System.out.println("[ModelLoader] failed " + fbx.getName() + ": " + ex);
                }
            }
        } catch (Exception ex) {
            System.out.println("[ModelLoader] warmUp error: " + ex);
        }
        System.out.println("[ModelLoader] loaded " + loadCount + " model(s)");
    }

    /** Loads one FBX and registers it under its relative name. */
    public static void load(File fbxFile) throws Exception {
        Model m = FbxParser.toModel(FbxParser.parse(fbxFile), fbxFile.getParentFile());
        String name = relativeName(fbxFile);
        if (m.triangleCount() <= 0) {
            System.out.println("[ModelLoader] " + name + ": no triangles (empty mesh)");
            return;
        }
        models.put(name, m);
        loadCount++;
        System.out.println("[ModelLoader] + " + name + " (" + m.triangleCount() + " tris, tex=" + m.tex
                + ", bones=" + m.boneCount() + ")");
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
                buf.put((byte) ((p >> 16) & 0xFF));
                buf.put((byte) ((p >> 8) & 0xFF));
                buf.put((byte) (p & 0xFF));
                buf.put((byte) ((p >> 24) & 0xFF));
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

    private static String relativeName(File fbx) {
        File mods = new File(findModsDir(), "Models");
        String abs = fbx.getAbsolutePath();
        String base = mods.getAbsolutePath() + File.separator;
        String rel = abs.startsWith(base) ? abs.substring(base.length()) : fbx.getName();
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