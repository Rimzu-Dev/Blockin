package com.insanestudios.blockin;

import com.insanestudios.blockin.Physics.Box;
import com.insanestudios.blockin.World.Level;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

import java.util.Arrays;

/**
 * Client-side player wrapper. Polls the keyboard/mouse each tick, builds a
 * {@link PlayerInput} and feeds it to the headless {@link PlayerSim}, which
 * owns all simulation state. The public position/look fields below mirror the
 * sim's state after every tick so renderers and the HUD keep reading them
 * exactly as they always have.
 */
public final class PlayerController {

    /** Event-mirrored keyboard state; LWJGL 2's {@code isKeyDown} can stick
     * "down" forever when a key is released during a focus change or hitch. */
    private static final int KEY_COUNT = Keyboard.KEYBOARD_SIZE;
    private static final boolean[] keyDown = new boolean[KEY_COUNT];
    private static boolean wasActive = true;

    /** The per-player simulation (no LWJGL usage). */
    public final PlayerSim sim;

    // Public mirrors of sim state, refreshed after each tick()/action.
    public float xo;
    public float yo;
    public float zo;
    public float x;
    public float y;
    public float z;
    public float xd;
    public float yd;
    public float zd;
    public float yRot;
    public float xRot;
    public Box bb;
    public boolean onGround = false;
    public boolean inWater = false;

    public PlayerController(Level level) {
        this.sim = new PlayerSim(level);
        mirror();
    }

    private void mirror() {
        xo = sim.xo;
        yo = sim.yo;
        zo = sim.zo;
        x = sim.x;
        y = sim.y;
        z = sim.z;
        xd = sim.xd;
        yd = sim.yd;
        zd = sim.zd;
        yRot = sim.yRot;
        xRot = sim.xRot;
        bb = sim.bb;
        onGround = sim.onGround;
        inWater = sim.inWater;
    }

    /** Captures input for this frame and runs one simulation tick. */
    public void tick() {
        pollKeys();
        boolean active = Display.isActive();
        if (active && !wasActive) {
            Arrays.fill(keyDown, false);
        }
        wasActive = active;

        if (isDown(Keyboard.KEY_R)) {
            sim.resetPos();
        }

        PlayerInput input = new PlayerInput();
        input.forward = isDown(Keyboard.KEY_UP) || isDown(Keyboard.KEY_W);
        input.back = isDown(Keyboard.KEY_DOWN) || isDown(Keyboard.KEY_S);
        input.left = isDown(Keyboard.KEY_LEFT) || isDown(Keyboard.KEY_A);
        input.right = isDown(Keyboard.KEY_RIGHT) || isDown(Keyboard.KEY_D);
        input.jump = isDown(Keyboard.KEY_SPACE);
        input.yawDelta = Mouse.getDX();
        input.pitchDelta = Mouse.getDY();

        sim.simulate(input);
        mirror();
    }

    /** Applies a mouse-look delta directly (client convenience wrapper). */
    public void turn(float dx, float dy) {
        sim.turn(dx, dy);
        mirror();
    }

    /** Restores a position saved by {@code Level.setSavedPlayer}. */
    public void restoreState(float x, float z, float feetY, float yaw, float pitch) {
        sim.restoreState(x, z, feetY, yaw, pitch);
        mirror();
    }

    /** Camera eye height above the feet. */
    public float eyeY() {
        return sim.eyeY();
    }

    private static void pollKeys() {
        long now = System.currentTimeMillis();
        while (Keyboard.next()) {
            int code = Keyboard.getEventKey();
            if (code >= 0 && code < KEY_COUNT) {
                keyDown[code] = Keyboard.getEventKeyState();
            }
        }
    }

    private static boolean isDown(int code) {
        return code >= 0 && code < KEY_COUNT && keyDown[code];
    }
}