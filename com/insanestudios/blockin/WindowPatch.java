package com.insanestudios.blockin;

import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

/**
 * Turns LWJGL's static window into a resizable, maximizable game window with
 * automatic viewport scaling.
 */
public final class WindowPatch {

    private static int lastWidth = -1;
    private static int lastHeight = -1;

    private WindowPatch() {
    }

    /** Call right after {@code Display.create()}. */
    public static void init() {
        Display.setResizable(true);
        lastWidth = Display.getWidth();
        lastHeight = Display.getHeight();
        System.out.println("[WindowPatch] Window resizability enabled.");
    }

    /** Call every frame; re-applies the viewport and projection on resize. */
    public static void checkResize() {
        if (!Display.isResizable()) {
            Display.setResizable(true);
        }

        if (Display.wasResized() || Display.getWidth() != lastWidth || Display.getHeight() != lastHeight) {
            int newWidth = Math.max(1, Display.getWidth());
            int newHeight = Math.max(1, Display.getHeight());
            lastWidth = newWidth;
            lastHeight = newHeight;

            GL11.glViewport(0, 0, newWidth, newHeight);
            updatePerspective(newWidth, newHeight);
        }
    }

    /** Rebuilds the 3D perspective projection so the aspect ratio stays correct. */
    private static void updatePerspective(int width, int height) {
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();

        float aspect = (float) width / (float) height;
        float fov = 70.0F;
        float zNear = 0.05F;
        float zFar = 1000.0F;

        float ymax = zNear * (float) Math.tan(Math.toRadians(fov / 2.0));
        float xmax = ymax * aspect;
        GL11.glFrustum(-xmax, xmax, -ymax, ymax, zNear, zFar);

        GL11.glMatrixMode(GL11.GL_MODELVIEW);
    }
}