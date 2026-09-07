package com.insanestudios.blockin;

/**
 * Fixed-timestep game clock.
 *
 * <p>{@link #advanceTime()} accumulates wall-clock time and reports how many
 * fixed simulation ticks should run this frame ({@link #ticks}) plus the
 * interpolation fraction {@link #a} between the previous and current tick for
 * smooth rendering.
 */
public final class GameClock {

    private static final long NS_PER_SECOND = 1_000_000_000L;
    private static final int MAX_TICKS_PER_FRAME = 100;

    private final float ticksPerSecond;
    private long lastTime;

    /** Whole simulation ticks pending this frame. */
    public int ticks;
    /** Interpolation fraction (0 = previous tick, 1 = next tick). */
    public float a;

    public float timeScale = 1.0F;
    public float fps = 0.0F;
    public float passedTime = 0.0F;

    public GameClock(float ticksPerSecond) {
        this.ticksPerSecond = ticksPerSecond;
        this.lastTime = System.nanoTime();
    }

    public void advanceTime() {
        long now = System.nanoTime();
        long passedNs = Math.max(0L, now - lastTime);
        lastTime = now;
        if (passedNs > NS_PER_SECOND) {
            passedNs = NS_PER_SECOND;
        }

        fps = 1_000_000_000.0F / passedNs;
        passedTime += (float) passedNs * timeScale * ticksPerSecond / 1.0E9F;
        ticks = (int) passedTime;
        if (ticks > MAX_TICKS_PER_FRAME) {
            ticks = MAX_TICKS_PER_FRAME;
        }

        passedTime -= ticks;
        a = passedTime;
    }
}