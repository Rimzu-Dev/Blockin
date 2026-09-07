package com.insanestudios.blockin.Physics;

import com.insanestudios.blockin.World.Level;

import org.lwjgl.opengl.GL11;

import java.util.Random;

/**
 * An ambient wandering pig. Purely cosmetic — it walks around, hops over
 * small bumps, and is rendered as a simple pink box so the rewrite has a
 * little life in it. Uses the same physics as the player so it can never
 * leave the terrain.
 */
public final class Mob_Pig {

    private static final float WALK_SPEED = 0.012F;

    private final Level level;
    private final Random rnd = new Random();

    public float x;
    public float y;
    public float z;
    public float xd;
    public float yd;
    public float zd;

    /** Yaw in degrees; the pig walks toward {@code yaw}. */
    public float yaw;

    public Box bb;
    public boolean onGround = false;

    private float turnTimer = 1.0F;
    private float bob = 0.0F;

    public Mob_Pig(Level level, float x, float y, float z) {
        this.level = level;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = rnd.nextFloat() * 360.0F;
        this.bb = new Box(x - 0.45F, y - 0.45F, z - 0.45F, x + 0.45F, y + 0.45F, z + 0.45F);
    }

    public void tick() {
        turnTimer -= 0.02F;
        if (turnTimer <= 0.0F) {
            turnTimer = 1.0F + rnd.nextFloat() * 2.5F;
            yaw += (rnd.nextFloat() - 0.5F) * 160.0F;
        }

        if (onGround && rnd.nextInt(400) == 0) {
            yd = 0.09F; // happy hop
        }

        float rad = yaw * (float) Math.PI / 180.0F;
        xd = (float) Math.sin(rad) * WALK_SPEED;
        zd = (float) Math.cos(rad) * WALK_SPEED;
        yd -= 0.005F;

        PlayerPhysics.Slide slide = PlayerPhysics.slide(bb, level, xd, yd, zd);
        onGround = PlayerPhysics.isOnGround(bb, level);

        if (slide.hitX || slide.hitZ) {
            yaw = rnd.nextFloat() * 360.0F;
        }
        if (slide.hitY) yd = 0.0F;
        if (onGround) {
            yd = Math.min(yd, 0.0F);
        }

        x = bb.centerX();
        y = bb.y0;
        z = bb.centerZ();
        bob += 0.12F;
    }

    /** Draws the pig as a simple shaded pink body + head. */
    public void render() {
        float py = (float) Math.sin(bob) * 0.02F;
        GL11.glPushMatrix();
        GL11.glTranslatef(bb.centerX(), bb.y0 + py, bb.centerZ());
        GL11.glRotatef(yaw, 0.0F, 1.0F, 0.0F);

        drawBox(-0.4F, 0.0F, -0.45F, 0.4F, 0.5F, 0.45F, 0.93F, 0.67F, 0.71F);
        drawBox(-0.25F, 0.2F, 0.45F, 0.25F, 0.5F, 0.72F, 0.85F, 0.60F, 0.64F);

        GL11.glPopMatrix();
    }

    /** Immediate-mode shaded cube (unit brightness multiplied per face). */
    private void drawBox(float x0, float y0, float z0, float x1, float y1, float z1,
                         float r, float g, float b) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBegin(GL11.GL_QUADS);

        face(r, g, b, 1.0F); // top
        v(x0, y1, z0); v(x0, y1, z1); v(x1, y1, z1); v(x1, y1, z0);

        face(r, g, b, 0.6F); // bottom
        v(x0, y0, z0); v(x1, y0, z0); v(x1, y0, z1); v(x0, y0, z1);

        face(r, g, b, 0.8F); // front (+z)
        v(x0, y0, z1); v(x1, y0, z1); v(x1, y1, z1); v(x0, y1, z1);

        face(r, g, b, 0.8F); // back (-z)
        v(x0, y0, z0); v(x0, y1, z0); v(x1, y1, z0); v(x1, y0, z0);

        face(r, g, b, 0.7F); // left (-x)
        v(x0, y0, z0); v(x0, y0, z1); v(x0, y1, z1); v(x0, y1, z0);

        face(r, g, b, 0.7F); // right (+x)
        v(x1, y0, z0); v(x1, y1, z0); v(x1, y1, z1); v(x1, y0, z1);

        GL11.glEnd();
    }

    private void face(float r, float g, float b, float shade) {
        GL11.glColor3f(r * shade, g * shade, b * shade);
    }

    private void v(float x, float y, float z) {
        GL11.glVertex3f(x, y, z);
    }
}