package com.insanestudios.blockin;

import com.insanestudios.blockin.Physics.Box;
import com.insanestudios.blockin.Physics.PlayerPhysics;
import com.insanestudios.blockin.World.Level;
import com.insanestudios.blockin.Blocks.Block;
import com.insanestudios.blockin.Blocks.BlockLoader;
import com.insanestudios.blockin.Sound.SoundEngine;

/**
 * Headless player simulation. No LWJGL imports: input arrives as a
 * {@link PlayerInput} value each tick and sound effects route through
 * {@link SoundEngine} (pure JavaSound), so one simulation instance can run per
 * player on a dedicated server later. All mutable state is per-instance —
 * nothing is {@code static}.
 */
public final class PlayerSim {

    private static final long STEP_MIN_INTERVAL_MS = 320L;
    private static final float STEP_DISTANCE = 0.6F;

    private final Level level;

    // Position of previous simulate() call (for smooth render interpolation).
    public float xo;
    public float yo;
    public float zo;

    /** Feet position. */
    public float x;
    public float y;
    public float z;

    /** Velocity. */
    public float xd;
    public float yd;
    public float zd;

    /** Look angles (yaw around Y, pitch around X). */
    public float yRot;
    public float xRot;

    /** Physical collision body (0.6 wide, 1.8 tall). */
    public Box bb;

    public boolean onGround = false;

    /** True when the body is submerged in water (drives swim physics). */
    public boolean inWater = false;

    /** Number of simulate() calls so far (future input sequence number). */
    public long tickCount = 0;

    private float walkDistance = 0.0F;
    private float nextStepDistance = 0.0F;
    private long lastStepTime = 0L;
    private boolean jumpKeyHeld = false;
    private int driftReport = 0;

    /** Per-instance debug diagnostics (safe with multiple players). */
    public String driftDiag = null;
    public String stepDiag = null;
    public String liveState = null;

    public PlayerSim(Level level) {
        this.level = level;
        resetPos();
    }

    public void resetPos() {
        for (int i = 0; i < 32; i++) {
            int x = 3 + (int) (Math.random() * (level.width - 6));
            int z = 3 + (int) (Math.random() * (level.height - 6));
            if (level.isOcean(x, z)) continue;
            setPos(x + 0.5F, level.getSurfaceY(x, z) + 1.9F, z + 0.5F);
            return;
        }
        setPos(level.width / 2.0F, level.depth * 0.75F, level.height / 2.0F);
    }

    /** True when the body is at least partly under water. */
    private boolean isSubmerged(Box body) {
        float cx = body.centerX();
        float cz = body.centerZ();
        return level.isLiquid((int) Math.floor(cx), (int) Math.floor(body.y0 + 0.3F), (int) Math.floor(cz))
                || level.isLiquid((int) Math.floor(cx), (int) Math.floor(body.y1 - 0.2F), (int) Math.floor(cz));
    }

    private void setPos(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.bb = new Box(x - 0.3F, y - 0.9F, z - 0.3F, x + 0.3F, y + 0.9F, z + 0.3F);
    }

    /** Camera eye height above the feet. */
    public float eyeY() {
        return bb.y0 + 1.62F;
    }

    /** Applies a mouse-look delta (same scaling as the old client-side turn). */
    public void turn(float dx, float dy) {
        yRot += dx * 0.15F;
        xRot -= dy * 0.15F;
        if (xRot < -90.0F) xRot = -90.0F;
        if (xRot > 90.0F) xRot = 90.0F;
    }

    /** Restores a position saved by {@code Level.setSavedPlayer} (x/z center,
     *  y = feet). Falls back to {@link #resetPos} if the spot is now solid. */
    public void restoreState(float x, float z, float feetY, float yaw, float pitch) {
        if (feetY < 0.0F || feetY >= level.depth
                || level.isSolidTile((int) Math.floor(x), (int) Math.floor(feetY + 0.1F), (int) Math.floor(z))) {
            resetPos();
            return;
        }
        setPos(x, feetY + 0.9F, z);
        yRot = yaw;
        xRot = pitch;
        xd = 0.0F;
        yd = 0.0F;
        zd = 0.0F;
        xo = x;
        yo = bb.y0 + 1.62F;
        zo = z;
        onGround = false;
    }

    /** Runs one physics tick from a synthetic input. Headless-safe. */
    public void simulate(PlayerInput in) {
        tickCount++;
        xo = x;
        yo = y;
        zo = z;

        turn(in.yawDelta, in.pitchDelta);

        float xa = 0.0F;
        float ya = 0.0F;
        if (in.forward) ya--;
        if (in.back) ya++;
        if (in.left) xa--;
        if (in.right) xa++;

        boolean spaceDown = in.jump;
        if (spaceDown && onGround && !inWater) {
            yd = 0.16F;
            if (!jumpKeyHeld) {
                playSoundUnderfoot("jump");
            }
        }
        jumpKeyHeld = spaceDown;

        inWater = isSubmerged(bb);

        float moveSpeed = onGround ? 0.02F : (inWater ? 0.012F : 0.005F);
        moveRelative(xa, ya, moveSpeed);

        if (inWater) {
            yd -= 0.004F; // weakened gravity
            if (spaceDown) {
                yd = 0.06F; // paddle up
            }
            if (yd < -0.09F) yd = -0.09F;
            if (yd > 0.09F) yd = 0.09F;
        } else {
            yd -= 0.008F;
        }

        PlayerPhysics.Slide slide = PlayerPhysics.slide(bb, level, xd, yd, zd);
        onGround = PlayerPhysics.isOnGround(bb, level);

        if (slide.hitX) xd = 0.0F;
        if (slide.hitY) yd = 0.0F;
        if (slide.hitZ) zd = 0.0F;

        xd *= 0.91F;
        yd *= 0.98F;
        zd *= 0.91F;
        if (onGround) {
            xd *= 0.8F;
            zd *= 0.8F;
        }

        x = bb.centerX();
        y = bb.y0 + 1.62F;
        z = bb.centerZ();

        liveState = "keys=" + keys(in) + " og=" + onGround
                + " wtr=" + inWater
                + " feet=" + String.format("%.3f", bb.y0)
                + " pos=(" + String.format("%.2f", x) + "," + String.format("%.2f", z) + ")";

        trackSteps(in);
    }

    private static String keys(PlayerInput in) {
        StringBuilder sb = new StringBuilder();
        if (in.forward) sb.append('W');
        if (in.left) sb.append('A');
        if (in.back) sb.append('S');
        if (in.right) sb.append('D');
        return sb.isEmpty() ? "-" : sb.toString();
    }

    private void trackSteps(PlayerInput in) {
        if (!onGround) return;

        float dx = x - xo;
        float dz = z - zo;
        float moved = (float) Math.sqrt(dx * dx + dz * dz);
        if (moved < 1.0E-4F) return;

        boolean moving = in.forward || in.back || in.left || in.right;

        if (!moving) {
            walkDistance = 0.0F;
            nextStepDistance = STEP_DISTANCE;
            if (driftReport++ % 60 == 0) {
                int bx = (int) Math.floor(x);
                int by = (int) Math.floor(bb.y0 - 0.05F);
                int bz = (int) Math.floor(z);
                driftDiag = "idle drift moved=" + String.format("%.5f", moved)
                        + " pos=(" + String.format("%.2f", x) + "," + String.format("%.2f", z) + ")"
                        + " feet=" + String.format("%.3f", bb.y0)
                        + " under=" + bx + "," + by + "," + bz;
                System.out.println("[BLOCKIN DBG] " + driftDiag);
            }
            return;
        }

        driftDiag = null;
        walkDistance += moved;

        long now = System.currentTimeMillis();
        if (walkDistance > nextStepDistance && now - lastStepTime >= STEP_MIN_INTERVAL_MS) {
            stepDiag = "step moved=" + String.format("%.5f", moved)
                    + " walk=" + String.format("%.3f", walkDistance)
                    + " keys=" + keys(in)
                    + " dt=" + (now - lastStepTime) + "ms";
            System.out.println("[BLOCKIN STEP] " + stepDiag);
            playSoundUnderfoot("step");
            lastStepTime = now;
            nextStepDistance = walkDistance + STEP_DISTANCE;
        }
    }

    /** Routes step/jump effects for the block directly below the feet. */
    private void playSoundUnderfoot(String kind) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(bb.y0 - 0.05F);
        int bz = (int) Math.floor(z);
        Block ground = BlockLoader.get(level.getTile(bx, by, bz));
        String category = (ground != null) ? ground.getStepSound() : "Grass";

        switch (kind) {
            case "step" -> SoundEngine.playStep(category);
            case "jump" -> SoundEngine.playJump(category);
            default -> {
            }
        }
    }

    public void moveRelative(float xa, float za, float speed) {
        float dist = xa * xa + za * za;
        if (dist < 0.01F) return;

        dist = speed / (float) Math.sqrt(dist);
        xa *= dist;
        za *= dist;

        float sin = (float) Math.sin(yRot * Math.PI / 180.0);
        float cos = (float) Math.cos(yRot * Math.PI / 180.0);
        xd += xa * cos - za * sin;
        zd += za * cos + xa * sin;
    }
}