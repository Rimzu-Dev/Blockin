package com.insanestudios.blockin;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Texture cache and image-to-GL helpers.
 *
 * <p>Loads a resource through the classpath exactly once and hands out the GL
 * texture id for it, with best-effort binding dedup so the same texture is not
 * rebound every quad.
 */
public final class TextureLoader {

    private static final Map<String, Integer> CACHE = new HashMap<>();
    private static int lastBound = -1;

    private TextureLoader() {
    }

    /** Loads (and caches) a classpath image as a GL texture with the given filter. */
    public static int load(String resource, int filter) {
        Integer cached = CACHE.get(resource);
        if (cached != null) {
            return cached;
        }

        try (InputStream in = TextureLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                System.err.println("[TextureLoader] resource not found on classpath: " + resource);
                return -1;
            }
            BufferedImage img = ImageIO.read(in);
            if (img == null) {
                System.err.println("[TextureLoader] could not decode image: " + resource);
                return -1;
            }
            int id = upload(img, filter);
            CACHE.put(resource, id);
            return id;
        } catch (IOException e) {
            System.err.println("[TextureLoader] failed to load " + resource + ": " + e);
            return -1;
        }
    }

    /** Uploads a BufferedImage to OpenGL as a new 2D texture. */
    public static int upload(BufferedImage img, int filter) {
        int w = img.getWidth();
        int h = img.getHeight();

        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, toRGBA(img));
        lastBound = id;
        return id;
    }

    /**
     * Converts a BufferedImage into a tightly packed RGBA byte buffer.
     * Pixels are bytes in the order expected by glTexImage2D (R, G, B, A),
     * rows from top to bottom.
     */
    public static ByteBuffer toRGBA(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] argb = new int[w * h];
        img.getRGB(0, 0, w, h, argb, 0, w);

        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        for (int p : argb) {
            buf.put((byte) (p >> 16 & 0xFF)); // R
            buf.put((byte) (p >> 8 & 0xFF));  // G
            buf.put((byte) (p & 0xFF));       // B
            buf.put((byte) (p >> 24 & 0xFF)); // A
        }
        buf.flip();
        return buf;
    }

    /** Binds a texture, skipping the call when it is already bound. */
    public static void bind(int id) {
        if (id != lastBound) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
            lastBound = id;
        }
    }
}