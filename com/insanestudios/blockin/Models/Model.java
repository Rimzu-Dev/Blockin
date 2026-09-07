package com.insanestudios.blockin.Models;

import org.lwjgl.opengl.GL11;

import java.util.Arrays;

/**
 * A renderable triangle mesh extracted from an FBX file. Corner data is
 * stored flat, ready for immediate-mode GL11 rendering: every triangle is
 * 3 corners = 9 floats for vertices/normals and 6 floats for UVs.
 *
 * <p>When the mesh is skinned ({@link #bones} != null) the bind-pose control
 * points are stored in {@link #skinVerts} and re-posed every frame by the
 * per-vertex bone weights before drawing.
 */
public final class Model {

    public final float[] verts;
    public final float[] normals;
    public final float[] uvs;
    public int tex = -1;
    public float r = 1f, g = 1f, b = 1f;

    // --- skinning ------------------------------------------------------
    public Bone[] bones;
    /** Inverse of the mesh model's global matrix (folds the model transform). */
    public float[] globalInverse = MatOps.IDENTITY.clone();
    public int skinVertCount;
    public float[] skinVerts;
    public float[] skinNormals;
    public int[] cornerCtrl;     // per corner: index of control point
    public int[] skinBones;      // 4 per control point: bone index (-1 = none)
    public float[] skinWeights;  // 4 per control point

    private float[] poseCache;
    private boolean[] poseDone;
    private float[] wCache;
    private float[] scrV;
    private float[] scrN;

    /** A single bone of the rig. Local rotation is in degrees. */
    public static final class Bone {
        public String name;
        public int parent = -1;
        public float px, py, pz;
        public float rx, ry, rz;
        public float sx = 1f, sy = 1f, sz = 1f;
        /** inverse bind-pose matrix (cluster TransformLink inverted), 16 floats. */
        public float[] bind;
    }

    Model(int triangleCount) {
        verts = new float[triangleCount * 9];
        normals = new float[triangleCount * 9];
        uvs = new float[triangleCount * 6];
    }

    public int triangleCount() {
        return verts.length / 9;
    }

    public int boneCount() {
        return bones == null ? 0 : bones.length;
    }

    /** Adds to a bone's local rotation (degrees). Usually called per-frame to animate. */
    public void setBoneRotation(String name, float drx, float dry, float drz) {
        if (bones == null) return;
        for (Bone b : bones) {
            if (b.name != null && b.name.equals(name)) {
                b.rx += drx;
                b.ry += dry;
                b.rz += drz;
                return;
            }
        }
    }

    /** Draw the mesh with the caller's transform already applied. */
    public void render() {
        if (tex >= 0) {
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        } else {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
        }
        GL11.glColor3f(r, g, b);
        GL11.glBegin(GL11.GL_TRIANGLES);
        if (bones != null && skinVertCount > 0) {
            computeSkin();
            int tris = triangleCount();
            for (int t = 0, cor = 0; t < tris; t++) {
                int uBase = t * 6;
                for (int c = 0; c < 3; c++, cor++) {
                    int ci = cornerCtrl != null ? cornerCtrl[cor] : cor;
                    ci *= 3;
                    GL11.glNormal3f(scrN[ci], scrN[ci + 1], scrN[ci + 2]);
                    GL11.glTexCoord2f(uvs[uBase + c * 2], uvs[uBase + c * 2 + 1]);
                    GL11.glVertex3f(scrV[ci], scrV[ci + 1], scrV[ci + 2]);
                }
            }
        } else {
            int tris = triangleCount();
            for (int t = 0; t < tris; t++) {
                int vBase = t * 9;
                int uBase = t * 6;
                for (int c = 0; c < 3; c++) {
                    int v = vBase + c * 3;
                    int u = uBase + c * 2;
                    GL11.glNormal3f(normals[v], normals[v + 1], normals[v + 2]);
                    GL11.glTexCoord2f(uvs[u], uvs[u + 1]);
                    GL11.glVertex3f(verts[v], verts[v + 1], verts[v + 2]);
                }
            }
        }
        GL11.glEnd();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    /** Re-poses bind control points into {@link #scrV}/{@link #scrN} using current bone rotations. */
    private void computeSkin() {
        int nb = bones.length;
        if (poseCache == null || poseCache.length != nb * 16) poseCache = new float[nb * 16];
        if (poseDone == null || poseDone.length != nb) poseDone = new boolean[nb];
        if (wCache == null || wCache.length != nb * 16) wCache = new float[nb * 16];
        Arrays.fill(poseDone, false);

        for (int b = 0; b < nb; b++) {
            float[] w = MatOps.mul(globalInverse, MatOps.mul(globalMatrix(b), bones[b].bind));
            System.arraycopy(w, 0, wCache, b * 16, 16);
        }

        if (scrV == null || scrV.length != skinVertCount * 3) {
            scrV = new float[skinVertCount * 3];
            scrN = new float[skinVertCount * 3];
        }

        float[] m = new float[16];
        for (int i = 0; i < skinVertCount; i++) {
            Arrays.fill(m, 0F);
            float total = 0;
            int base = i * 4;
            for (int k = 0; k < 4; k++) {
                int bi = skinBones[base + k];
                float w = skinWeights[base + k];
                if (bi < 0 || w == 0) continue;
                total += w;
                MatOps.addM(m, w, wCache, bi * 16);
            }
            float vx = skinVerts[i * 3], vy = skinVerts[i * 3 + 1], vz = skinVerts[i * 3 + 2];
            float nx = skinNormals[i * 3], ny = skinNormals[i * 3 + 1], nz = skinNormals[i * 3 + 2];
            if (total < 1e-8f) {
                scrV[i * 3] = vx;
                scrV[i * 3 + 1] = vy;
                scrV[i * 3 + 2] = vz;
                scrN[i * 3] = nx;
                scrN[i * 3 + 1] = ny;
                scrN[i * 3 + 2] = nz;
            } else {
                float[] p = MatOps.mulPt(m, vx, vy, vz);
                scrV[i * 3] = p[0];
                scrV[i * 3 + 1] = p[1];
                scrV[i * 3 + 2] = p[2];
                float[] d = MatOps.mulDir(m, nx, ny, nz);
                float len = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
                if (len > 1e-6f) {
                    d[0] /= len;
                    d[1] /= len;
                    d[2] /= len;
                }
                scrN[i * 3] = d[0];
                scrN[i * 3 + 1] = d[1];
                scrN[i * 3 + 2] = d[2];
            }
        }
    }

    /** Global (world) matrix of bone b for the current pose, computed bottom-up. */
    private float[] globalMatrix(int b) {
        if (poseDone[b]) return poseCache;
        Bone bone = bones[b];
        float[] local = MatOps.rotRST(bone.px, bone.py, bone.pz,
                bone.rx, bone.ry, bone.rz,
                bone.sx, bone.sy, bone.sz);
        if (bone.parent >= 0) {
            local = MatOps.mul(globalMatrix(bone.parent), local);
        }
        System.arraycopy(local, 0, poseCache, b * 16, 16);
        poseDone[b] = true;
        return poseCache;
    }
}