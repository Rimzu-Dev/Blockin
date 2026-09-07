package com.insanestudios.blockin.Physics;

import com.insanestudios.blockin.World.Level;

import java.util.List;

/**
 * Collision and motion helper for entities.
 *
 * <p>Moves an {@link Box} through a {@link Level} one axis at a time, clipping the
 * travel distance against every solid cube it sweeps through. Returns the final
 * applied deltas plus which axes came to an abrupt stop — that is how entities
 * detect landing on the ground or bumping into walls.
 */
public final class PlayerPhysics {

    private PlayerPhysics() {
    }

    public static final class Slide {
        public final float xa;
        public final float ya;
        public final float za;
        public final boolean hitX;
        public final boolean hitY;
        public final boolean hitZ;

        public Slide(float xa, float ya, float za, boolean hitX, boolean hitY, boolean hitZ) {
            this.xa = xa;
            this.ya = ya;
            this.za = za;
            this.hitX = hitX;
            this.hitY = hitY;
            this.hitZ = hitZ;
        }

        /** True when the entity came to a stop against solid ground below. */
        public boolean landed() {
            return hitY && ya < 0.0F;
        }
    }

    /**
     * True when the body is resting on (or within a small step of) solid ground.
     *
     * <p>The slide clip snaps a resting body flush against the surface, so the
     * requested downward delta becomes exactly {@code 0.0f} and {@link
     * Slide#landed()} reports {@code false} for a standing entity (the fall that
     * put it there has finished). Here we instead probe a short downward step: if
     * a cube below clips it, the body is standing on ground.
     */
    public static boolean isOnGround(Box body, Level level) {
        float d = -0.2F;
        for (Box cube : level.getCubes(body.expand(0.0F, d, 0.0F))) {
            d = cube.clipYCollide(body, d);
        }
        return d > -0.2F + 0.001F;
    }

    /**
     * Sweeps {@code body} by the requested deltas against the level's solid cubes.
     * The box is mutated in place to its final position and the applied deltas
     * are returned.
     */
    public static Slide slide(Box body, Level level, float xa, float ya, float za) {
        float xaOrg = xa;
        float yaOrg = ya;
        float zaOrg = za;

        List<Box> solids = level.getCubes(body.expand(xa, ya, za));

        for (Box cube : solids) {
            ya = cube.clipYCollide(body, ya);
        }
        body.move(0.0F, ya, 0.0F);

        for (Box cube : solids) {
            xa = cube.clipXCollide(body, xa);
        }
        body.move(xa, 0.0F, 0.0F);

        for (Box cube : solids) {
            za = cube.clipZCollide(body, za);
        }
        body.move(0.0F, 0.0F, za);

        return new Slide(xa, ya, za, xaOrg != xa, yaOrg != ya, zaOrg != za);
    }
}