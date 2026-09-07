package com.insanestudios.blockin;

/**
 * One tick's worth of player input, decoupled from any keyboard/mouse API.
 * Movement is relative to the player's yaw: {@code forward} is whatever
 * direction the camera faces (the client maps W/UP etc. onto it). Look deltas
 * are degrees-ish mouse deltas for this tick and are applied by the
 * simulation, not the client.
 */
public final class PlayerInput {

    /** Move toward the camera heading. */
    public boolean forward;
    /** Move away from the camera heading. */
    public boolean back;
    /** Strafe left of the camera heading. */
    public boolean left;
    /** Strafe right of the camera heading. */
    public boolean right;
    /** Jump / swim-up request (SPACE). */
    public boolean jump;

    /** Horizontal mouse movement for this tick (YAW). */
    public float yawDelta;
    /** Vertical mouse movement for this tick (PITCH). */
    public float pitchDelta;
}