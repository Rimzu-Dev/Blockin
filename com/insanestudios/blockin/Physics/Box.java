package com.insanestudios.blockin.Physics;

/**
 * Axis-aligned bounding box used for all physical volumes in Blockin.
 *
 * <p>Lower corner ({@link #x0 x0},{@link #y0 y0},{@link #z0 z0}) and upper corner
 * ({@link #x1 x1},{@link #y1 y1},{@link #z1 z1}) are kept mutable for cheap motion
 * during a single physics pass; {@link #expand}, {@link #grow} and the clip helpers
 * return new boxes or adjusted deltas without side effects.
 */
public final class Box {

    private float epsilon = 0.0F;

    public float x0;
    public float y0;
    public float z0;
    public float x1;
    public float y1;
    public float z1;

    public Box(float x0, float y0, float z0, float x1, float y1, float z1) {
        this.x0 = x0;
        this.y0 = y0;
        this.z0 = z0;
        this.x1 = x1;
        this.y1 = y1;
        this.z1 = z1;
    }

    /** Returns a box enlarged in the direction of travel by the given deltas. */
    public Box expand(float xa, float ya, float za) {
        float nx0 = xa < 0.0F ? this.x0 + xa : this.x0;
        float nx1 = xa > 0.0F ? this.x1 + xa : this.x1;
        float ny0 = ya < 0.0F ? this.y0 + ya : this.y0;
        float ny1 = ya > 0.0F ? this.y1 + ya : this.y1;
        float nz0 = za < 0.0F ? this.z0 + za : this.z0;
        float nz1 = za > 0.0F ? this.z1 + za : this.z1;
        return new Box(nx0, ny0, nz0, nx1, ny1, nz1);
    }

    /** Returns a box that grows symmetrically outward on every side. */
    public Box grow(float xa, float ya, float za) {
        return new Box(this.x0 - xa, this.y0 - ya, this.z0 - za,
                this.x1 + xa, this.y1 + ya, this.z1 + za);
    }

    /** Clips a horizontal X movement against this box; returns the corrected delta. */
    public float clipXCollide(Box c, float xa) {
        if (c.y1 <= this.y0 || c.y0 >= this.y1) return xa;
        if (c.z1 <= this.z0 || c.z0 >= this.z1) return xa;

        if (xa > 0.0F && c.x1 <= this.x0) {
            float max = this.x0 - c.x1 - this.epsilon;
            if (max < xa) xa = max;
        }
        if (xa < 0.0F && c.x0 >= this.x1) {
            float max = this.x1 - c.x0 + this.epsilon;
            if (max > xa) xa = max;
        }
        return xa;
    }

    /** Clips a vertical Y movement against this box; returns the corrected delta. */
    public float clipYCollide(Box c, float ya) {
        if (c.x1 <= this.x0 || c.x0 >= this.x1) return ya;
        if (c.z1 <= this.z0 || c.z0 >= this.z1) return ya;

        if (ya > 0.0F && c.y1 <= this.y0) {
            float max = this.y0 - c.y1 - this.epsilon;
            if (max < ya) ya = max;
        }
        if (ya < 0.0F && c.y0 >= this.y1 - 0.1F) {
            float max = this.y1 - c.y0 + this.epsilon;
            if (max > ya) ya = max;
        }
        return ya;
    }

    /** Clips a horizontal Z movement against this box; returns the corrected delta. */
    public float clipZCollide(Box c, float za) {
        if (c.x1 <= this.x0 || c.x0 >= this.x1) return za;
        if (c.y1 <= this.y0 || c.y0 >= this.y1) return za;

        if (za > 0.0F && c.z1 <= this.z0) {
            float max = this.z0 - c.z1 - this.epsilon;
            if (max < za) za = max;
        }
        if (za < 0.0F && c.z0 >= this.z1) {
            float max = this.z1 - c.z0 + this.epsilon;
            if (max > za) za = max;
        }
        return za;
    }

    public boolean intersects(Box c) {
        return !(c.x1 <= this.x0) && !(c.x0 >= this.x1)
                && !(c.y1 <= this.y0) && !(c.y0 >= this.y1)
                && !(c.z1 <= this.z0) && !(c.z0 >= this.z1);
    }

    /** Translates the box in place by the given delta. */
    public void move(float xa, float ya, float za) {
        this.x0 += xa;
        this.y0 += ya;
        this.z0 += za;
        this.x1 += xa;
        this.y1 += ya;
        this.z1 += za;
    }

    public float centerX() {
        return (this.x0 + this.x1) * 0.5F;
    }

    public float centerZ() {
        return (this.z0 + this.z1) * 0.5F;
    }
}