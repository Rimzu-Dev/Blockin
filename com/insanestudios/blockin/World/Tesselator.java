package com.insanestudios.blockin.World;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

/**
 * Batched immediate-mode-ish renderer.
 *
 * <p>Vertices, texture coordinates and colours are collected into client-side
 * buffers and emitted in a single {@code glDrawArrays(GL_QUADS, ...)} call.
 * Not safe for nested use — the engine keeps one shared instance per render pass.
 */
public final class Tesselator {

    private static final int MAX_VERTICES = 100000;

    private final FloatBuffer vertexBuffer = BufferUtils.createFloatBuffer(MAX_VERTICES * 3);
    private final FloatBuffer texCoordBuffer = BufferUtils.createFloatBuffer(MAX_VERTICES * 2);
    private final FloatBuffer colorBuffer = BufferUtils.createFloatBuffer(MAX_VERTICES * 4);

    private int vertices = 0;
    private float u;
    private float v;
    private float r;
    private float g;
    private float b;
    private float a = 1.0F;
    private boolean hasColor = false;
    private boolean hasTexture = false;

    public void flush() {
        vertexBuffer.flip();
        texCoordBuffer.flip();
        colorBuffer.flip();

        GL11.glVertexPointer(3, 0, vertexBuffer);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);

        if (hasTexture) {
            GL11.glTexCoordPointer(2, 0, texCoordBuffer);
            GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        }
        if (hasColor) {
            GL11.glColorPointer(4, 0, colorBuffer);
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        }

        GL11.glDrawArrays(GL11.GL_QUADS, 0, vertices);

        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        if (hasTexture) {
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        }
        if (hasColor) {
            GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
        }

        clear();
    }

    private void clear() {
        vertices = 0;
        vertexBuffer.clear();
        texCoordBuffer.clear();
        colorBuffer.clear();
    }

    /** Starts a fresh primitive batch. */
    public void init() {
        clear();
        hasColor = false;
        hasTexture = false;
    }

    public void tex(float u, float v) {
        hasTexture = true;
        this.u = u;
        this.v = v;
    }

    public void color(float r, float g, float b) {
        color(r, g, b, 1.0F);
    }

    public void color(float r, float g, float b, float a) {
        hasColor = true;
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
    }

    public void vertex(float x, float y, float z) {
        vertexBuffer.put(vertices * 3 + 0, x);
        vertexBuffer.put(vertices * 3 + 1, y);
        vertexBuffer.put(vertices * 3 + 2, z);

        if (hasTexture) {
            texCoordBuffer.put(vertices * 2 + 0, u);
            texCoordBuffer.put(vertices * 2 + 1, v);
        }
        if (hasColor) {
            colorBuffer.put(vertices * 4 + 0, r);
            colorBuffer.put(vertices * 4 + 1, g);
            colorBuffer.put(vertices * 4 + 2, b);
            colorBuffer.put(vertices * 4 + 3, a);
        }

        vertices++;
        if (vertices == MAX_VERTICES) {
            flush();
        }
    }
}