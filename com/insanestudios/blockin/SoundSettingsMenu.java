package com.insanestudios.blockin;

import com.insanestudios.blockin.Sound.SoundEngine;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

/**
 * Sound settings overlay opened from the main menu's Settings screen.
 *
 * <p>Each effect volume cycles 100% → 50% → 20% → OFF → back, with a live
 * preview every time the level changes. Rendered with the same bevel-button
 * helpers the main menu uses.
 */
public final class SoundSettingsMenu {

    private static final int B_X = 100;
    private static final int B_W = 200;
    private static final int B_H = 30;

    private static boolean active = false;
    private static boolean lastMouseDown = false;

    private SoundSettingsMenu() {
    }

    public static void open() {
        active = true;
    }

    public static void close() {
        active = false;
    }

    public static boolean isOpen() {
        return active;
    }

    public static void pollInput() {
        if (!active) return;

        int h = Display.getHeight();
        int mx = Mouse.getX();
        int my = h - Mouse.getY();
        boolean down = Mouse.isButtonDown(0);
        
        // Register true only on the exact frame the button goes from up to down
        boolean clicked = down && !lastMouseDown;
        lastMouseDown = down;

        if (Keyboard.isKeyDown(Keyboard.KEY_ESCAPE)) {
            close();
            return;
        }

        if (clicked) {
            if (hovered(mx, my, 100)) {
                SoundEngine.stepVolume = cycle(SoundEngine.stepVolume);
                SoundEngine.stepEnabled = SoundEngine.stepVolume > 0.0F;
                SoundEngine.playStep("Grass");
            }
            if (hovered(mx, my, 140)) {
                SoundEngine.breakVolume = cycle(SoundEngine.breakVolume);
                SoundEngine.breakEnabled = SoundEngine.breakVolume > 0.0F;
                SoundEngine.playBreak("Grass");
            }
            if (hovered(mx, my, 180)) {
                SoundEngine.placeVolume = cycle(SoundEngine.placeVolume);
                SoundEngine.placeEnabled = SoundEngine.placeVolume > 0.0F;
                SoundEngine.playPlace("Grass");
            }
            if (hovered(mx, my, 230)) {
                close();
            }
        }
    }

    public static void render() {
        if (!active) return;

        int h = Display.getHeight();
        int mx = Mouse.getX();
        int my = h - Mouse.getY();

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(0.0F, 0.0F, 0.0F, 0.5F);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(0, 0);
        GL11.glVertex2f(Display.getWidth(), 0);
        GL11.glVertex2f(Display.getWidth(), h);
        GL11.glVertex2f(0, h);
        GL11.glEnd();

        MainMenu.drawDynamicText("Sound Settings", Display.getWidth() / 2, 90, 26,
                new java.awt.Color(240, 210, 60), true);

        MainMenu.drawModernButtonText(getStepLabel(), B_X, 100, B_W, B_H, 14,
                hovered(mx, my, 100), true);
        MainMenu.drawModernButtonText(getBreakLabel(), B_X, 140, B_W, B_H, 14,
                hovered(mx, my, 140), true);
        MainMenu.drawModernButtonText(getPlaceLabel(), B_X, 180, B_W, B_H, 14,
                hovered(mx, my, 180), true);
        MainMenu.drawModernButtonText("Back", B_X, 230, B_W, B_H, 14,
                hovered(mx, my, 230), true);
    }

    private static boolean hovered(int mx, int my, int y) {
        return mx >= B_X && mx <= B_X + B_W && my >= y && my <= y + B_H;
    }

    private static float cycle(float current) {
        if (current >= 0.8F) return 0.5F;
        if (current >= 0.4F) return 0.2F;
        if (current >= 0.1F) return 0.0F;
        return 1.0F;
    }

    // ---------------------------------------------------------- labels

    public static String getStepLabel() {
        return "Step Sounds: " + status(SoundEngine.stepVolume, SoundEngine.stepEnabled);
    }

    public static String getBreakLabel() {
        return "Block Break: " + status(SoundEngine.breakVolume, SoundEngine.breakEnabled);
    }

    public static String getPlaceLabel() {
        return "Block Place: " + status(SoundEngine.placeVolume, SoundEngine.placeEnabled);
    }

    private static String status(float volume, boolean enabled) {
        if (!enabled || volume <= 0.0F) return "OFF";
        return (int) (volume * 100) + "%";
    }
}