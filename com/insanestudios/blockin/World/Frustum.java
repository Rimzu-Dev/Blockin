package com.insanestudios.blockin.World;

import com.insanestudios.blockin.Physics.Box;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;

/**
 * View-frustum culling.
 *
 * <p>Extracts the six clipping planes from the combined projection*modelview
 * matrix each frame and tests boxes against them, so only visible chunks pay
 * the cost of a display-list call.
 */
public final class Frustum {

    private static final int PLANES = 6;
    private final float[][] planes = new float[PLANES][4];

    private final FloatBuffer projBuffer = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer modlBuffer = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer clipBuffer = BufferUtils.createFloatBuffer(16);

    private final float[] proj = new float[16];
    private final float[] modl = new float[16];
    private final float[] clip = new float[16];

    private static final Frustum INSTANCE = new Frustum();

    private Frustum() {
    }

    /** Updates from the current matrices and returns the singleton frustum. */
    public static Frustum getFrustum() {
        INSTANCE.calculate();
        return INSTANCE;
    }

    private void calculate() {
        projBuffer.clear();
        modlBuffer.clear();
        clipBuffer.clear();

        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projBuffer);
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modlBuffer);

        projBuffer.flip().limit(16);
        modlBuffer.flip().limit(16);
        projBuffer.get(proj);
        modlBuffer.get(modl);

        // clip = proj * modl as 4x4 column-major matrices
        clip[0] = modl[0] * proj[0] + modl[1] * proj[4] + modl[2] * proj[8] + modl[3] * proj[12];
        clip[1] = modl[0] * proj[1] + modl[1] * proj[5] + modl[2] * proj[9] + modl[3] * proj[13];
        clip[2] = modl[0] * proj[2] + modl[1] * proj[6] + modl[2] * proj[10] + modl[3] * proj[14];
        clip[3] = modl[0] * proj[3] + modl[1] * proj[7] + modl[2] * proj[11] + modl[3] * proj[15];

        clip[4] = modl[4] * proj[0] + modl[5] * proj[4] + modl[6] * proj[8] + modl[7] * proj[12];
        clip[5] = modl[4] * proj[1] + modl[5] * proj[5] + modl[6] * proj[9] + modl[7] * proj[13];
        clip[6] = modl[4] * proj[2] + modl[5] * proj[6] + modl[6] * proj[10] + modl[7] * proj[14];
        clip[7] = modl[4] * proj[3] + modl[5] * proj[7] + modl[6] * proj[11] + modl[7] * proj[15];

        clip[8] = modl[8] * proj[0] + modl[9] * proj[4] + modl[10] * proj[8] + modl[11] * proj[12];
        clip[9] = modl[8] * proj[1] + modl[9] * proj[5] + modl[10] * proj[9] + modl[11] * proj[13];
        clip[10] = modl[8] * proj[2] + modl[9] * proj[6] + modl[10] * proj[10] + modl[11] * proj[14];
        clip[11] = modl[8] * proj[3] + modl[9] * proj[7] + modl[10] * proj[11] + modl[11] * proj[15];

        clip[12] = modl[12] * proj[0] + modl[13] * proj[4] + modl[14] * proj[8] + modl[15] * proj[12];
        clip[13] = modl[12] * proj[1] + modl[13] * proj[5] + modl[14] * proj[9] + modl[15] * proj[13];
        clip[14] = modl[12] * proj[2] + modl[13] * proj[6] + modl[14] * proj[10] + modl[15] * proj[14];
        clip[15] = modl[12] * proj[3] + modl[13] * proj[7] + modl[14] * proj[11] + modl[15] * proj[15];

        planes[0][0] = clip[3] - clip[0];
        planes[0][1] = clip[7] - clip[4];
        planes[0][2] = clip[11] - clip[8];
        planes[0][3] = clip[15] - clip[12];
        normalize(0);

        planes[1][0] = clip[3] + clip[0];
        planes[1][1] = clip[7] + clip[4];
        planes[1][2] = clip[11] + clip[8];
        planes[1][3] = clip[15] + clip[12];
        normalize(1);

        planes[2][0] = clip[3] + clip[1];
        planes[2][1] = clip[7] + clip[5];
        planes[2][2] = clip[11] + clip[9];
        planes[2][3] = clip[15] + clip[13];
        normalize(2);

        planes[3][0] = clip[3] - clip[1];
        planes[3][1] = clip[7] - clip[5];
        planes[3][2] = clip[11] - clip[9];
        planes[3][3] = clip[15] - clip[13];
        normalize(3);

        planes[4][0] = clip[3] - clip[2];
        planes[4][1] = clip[7] - clip[6];
        planes[4][2] = clip[11] - clip[10];
        planes[4][3] = clip[15] - clip[14];
        normalize(4);

        planes[5][0] = clip[3] + clip[2];
        planes[5][1] = clip[7] + clip[6];
        planes[5][2] = clip[11] + clip[10];
        planes[5][3] = clip[15] + clip[14];
        normalize(5);
    }

    private void normalize(int side) {
        float m = (float) Math.sqrt(
                planes[side][0] * planes[side][0]
                + planes[side][1] * planes[side][1]
                + planes[side][2] * planes[side][2]);
        planes[side][0] /= m;
        planes[side][1] /= m;
        planes[side][2] /= m;
        planes[side][3] /= m;
    }

    public boolean pointInFrustum(float x, float y, float z) {
        for (int i = 0; i < PLANES; i++) {
            if (planes[i][0] * x + planes[i][1] * y + planes[i][2] * z + planes[i][3] <= 0.0F) {
                return false;
            }
        }
        return true;
    }

    public boolean cubeInFrustum(Box box) {
        for (int i = 0; i < PLANES; i++) {
            float px = planes[i][0];
            float py = planes[i][1];
            float pz = planes[i][2];
            float pw = planes[i][3];

            boolean anyInside =
                    px * box.x0 + py * box.y0 + pz * box.z0 + pw > 0.0F
                    || px * box.x1 + py * box.y0 + pz * box.z0 + pw > 0.0F
                    || px * box.x0 + py * box.y1 + pz * box.z0 + pw > 0.0F
                    || px * box.x1 + py * box.y1 + pz * box.z0 + pw > 0.0F
                    || px * box.x0 + py * box.y0 + pz * box.z1 + pw > 0.0F
                    || px * box.x1 + py * box.y0 + pz * box.z1 + pw > 0.0F
                    || px * box.x0 + py * box.y1 + pz * box.z1 + pw > 0.0F
                    || px * box.x1 + py * box.y1 + pz * box.z1 + pw > 0.0F;

            if (!anyInside) {
                return false;
            }
        }
        return true;
    }
}