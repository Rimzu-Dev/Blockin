package com.insanestudios.blockin.Models;

/**
 * Minimal 4x4 matrix math for FBX skinning. All matrices are stored
 * row-major (row-major storage) and treat points as column vectors, so a
 * matrix product {@code M = mul(A, B)} applies B first, then A.
 */
public final class MatOps {

    public static final float[] IDENTITY = {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1
    };

    private MatOps() {
    }

    /** Returns {@code a * b} (row-major; applies b then a). */
    public static float[] mul(float[] a, float[] b) {
        float[] o = new float[16];
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                float s = 0;
                for (int k = 0; k < 4; k++) s += a[r * 4 + k] * b[k * 4 + c];
                o[r * 4 + c] = s;
            }
        }
        return o;
    }

    /** General 4x4 inverse via Gauss-Jordan. */
    public static float[] inv(float[] m) {
        float[][] a = new float[4][8];
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 8; c++) {
                a[r][c] = c < 4 ? m[r * 4 + c] : (c - 4 == r ? 1F : 0F);
            }
        }
        for (int col = 0; col < 4; col++) {
            int piv = col;
            for (int r = col + 1; r < 4; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[piv][col])) piv = r;
            }
            if (Math.abs(a[piv][col]) < 1e-9f) return IDENTITY.clone();
            float[] tmp = a[col];
            a[col] = a[piv];
            a[piv] = tmp;
            float d = a[col][col];
            for (int c = 0; c < 8; c++) a[col][c] /= d;
            for (int r = 0; r < 4; r++) {
                if (r == col) continue;
                float f = a[r][col];
                if (f == 0F) continue;
                for (int c = 0; c < 8; c++) a[r][c] -= f * a[col][c];
            }
        }
        float[] o = new float[16];
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) o[r * 4 + c] = a[r][4 + c];
        }
        return o;
    }

    /**
     * Builds {@code T * R * S} where R = Rx * Ry * Rz (Euler, applied around
     * Z then Y then X, matching FBX default rotation order). Rotation angles
     * are in degrees.
     */
    public static float[] rotRST(float tx, float ty, float tz,
                                 float rx, float ry, float rz,
                                 float sx, float sy, float sz) {
        float cx = (float) Math.cos(Math.toRadians(rx));
        float sxr = (float) Math.sin(Math.toRadians(rx));
        float cy = (float) Math.cos(Math.toRadians(ry));
        float syr = (float) Math.sin(Math.toRadians(ry));
        float cz = (float) Math.cos(Math.toRadians(rz));
        float szr = (float) Math.sin(Math.toRadians(rz));
        float[] rxM = {1, 0, 0, 0, 0, cx, -sxr, 0, 0, sxr, cx, 0, 0, 0, 0, 1};
        float[] ryM = {cy, 0, syr, 0, 0, 1, 0, 0, -syr, 0, cy, 0, 0, 0, 0, 1};
        float[] rzM = {cz, -szr, 0, 0, szr, cz, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        float[] r = mul(rxM, mul(ryM, rzM));
        for (int c = 0; c < 3; c++) {
            float s = c == 0 ? sx : (c == 1 ? sy : sz);
            r[0 * 4 + c] *= s;
            r[1 * 4 + c] *= s;
            r[2 * 4 + c] *= s;
        }
        r[3] = tx;
        r[7] = ty;
        r[11] = tz;
        return r;
    }

    /** Applies the affine matrix to a position (w = 1). */
    public static float[] mulPt(float[] m, float x, float y, float z) {
        return new float[]{
                x * m[0] + y * m[1] + z * m[2] + m[3],
                x * m[4] + y * m[5] + z * m[6] + m[7],
                x * m[8] + y * m[9] + z * m[10] + m[11]
        };
    }

    /** Applies only the rotation/scale part of the matrix to a direction. */
    public static float[] mulDir(float[] m, float x, float y, float z) {
        return new float[]{
                x * m[0] + y * m[1] + z * m[2],
                x * m[4] + y * m[5] + z * m[6],
                x * m[8] + y * m[9] + z * m[10]
        };
    }

    /** Accumulates {@code acc += w * m} reading m starting at {@code off}. */
    public static void addM(float[] acc, float w, float[] m, int off) {
        for (int i = 0; i < 16; i++) acc[i] += w * m[off + i];
    }
}