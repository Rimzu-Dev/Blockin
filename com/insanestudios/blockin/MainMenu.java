package com.insanestudios.blockin;

import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Blockin main menu overlay: Play / Settings / Quit, a world-select screen and
 * a Settings screen that also hosts the {@link SoundSettingsMenu}.
 */
public final class MainMenu {

    public static final int NONE = 0;
    public static final int PLAY = 1;
    public static final int QUIT = 2;
    public static final int NEW_WORLD = 3;

    private enum MenuState {
        MAIN_MENU,
        SAVE_SELECT,
        SETTINGS
    }

    private static MenuState currentState = MenuState.MAIN_MENU;
    private static boolean active = true;
    private static int choice = NONE;
    private static boolean wasDown = false;
    private static String[] saveSlots = new String[0];
    private static String selectedSlot = null;

    private static final String[] MAIN_OPTIONS = {"PLAY", "SETTINGS", "QUIT"};

    // Settings state
    private static boolean farRenderDistance = true;
    private static int guiScale = 2;

    private static int dirtTex = 0;
    private static int titleTex = 0;

    private static final int[] mainOptTex = new int[MAIN_OPTIONS.length];
    private static final int[] mainOptW = new int[MAIN_OPTIONS.length];

    private static final int MAX_SLOT_ROWS = 4;

    // Texture cache for dynamic text to prevent memory leaks and lag spikes from glGenTextures per-frame
    private static final Map<String, CachedText> textCache = new HashMap<>();

    private static final String TITLE = "BLOCKIN";

    private record CachedText(int textureId, int width, int height) {}

    private MainMenu() {
    }

    public static boolean isActive() {
        return active;
    }

    public static int choice() {
        return choice;
    }

    /** The world the player picked on the SELECT WORLD screen. */
    public static String selectedSave() {
        return selectedSlot;
    }

    public static void startGame() {
        active = false;
        choice = NONE;
        wasDown = false;
        Mouse.setGrabbed(true);
    }

    private static int width() {
        return Display.getWidth();
    }

    private static int height() {
        return Display.getHeight();
    }

    public static void init() {
        dirtTex = generateDirtTexture();
        titleTex = makeTexture(TITLE, 52, true);

        for (int i = 0; i < MAIN_OPTIONS.length; i++) {
            mainOptTex[i] = makeTexture(MAIN_OPTIONS[i], 20, false);
            mainOptW[i] = textWidth(MAIN_OPTIONS[i], 20);
        }

        active = true;
        currentState = MenuState.MAIN_MENU;
        choice = NONE;
        wasDown = false;
        selectedSlot = null;
        saveSlots = SaveManager.listSaves();
        SaveManager.migrateLegacySave();
        Mouse.setGrabbed(false);
    }

    public static void pollInput() {
        if (!active) return;

        if (SoundSettingsMenu.isOpen()) {
            SoundSettingsMenu.pollInput();
            return;
        }

        Mouse.poll();
        Keyboard.poll();

        int w = width();
        int h = height();
        int cx = w / 2;
        int mx = Mouse.getX();
        int my = h - Mouse.getY();
        boolean down = Mouse.isButtonDown(0);

        if (Keyboard.isKeyDown(Keyboard.KEY_ESCAPE) && !wasDown) {
            if (currentState != MenuState.MAIN_MENU) {
                currentState = MenuState.MAIN_MENU;
            } else {
                choose(QUIT);
                return;
            }
        }

        switch (currentState) {
            case MAIN_MENU -> pollMainMenuInput(cx, h, mx, my, down);
            case SAVE_SELECT -> pollSaveSelectInput(cx, h, mx, my, down);
            case SETTINGS -> pollSettingsInput(cx, h, mx, my, down);
        }

        wasDown = down;
    }

    private static void pollMainMenuInput(int cx, int h, int mx, int my, boolean down) {
        int bw = 280;
        int bh = 46;
        int gap = 14;
        int totalHeightGroup = 60 + 12 + 18 + 24 + (3 * bh) + (2 * gap);
        int topAnchor = (h - totalHeightGroup) / 2;
        int startY = topAnchor + 105;

        if (Keyboard.isKeyDown(Keyboard.KEY_RETURN)) {
            currentState = MenuState.SAVE_SELECT;
            return;
        }

        if (down && !wasDown) {
            for (int i = 0; i < MAIN_OPTIONS.length; i++) {
                int by = startY + i * (bh + gap);
                if (mx >= cx - bw / 2 && mx <= cx + bw / 2 && my >= by && my <= by + bh) {
                    switch (i) {
                        case 0 -> currentState = MenuState.SAVE_SELECT;
                        case 1 -> currentState = MenuState.SETTINGS;
                        case 2 -> choose(QUIT);
                        default -> {}
                    }
                }
            }
        }
    }

    private static void pollSaveSelectInput(int cx, int h, int mx, int my, boolean down) {
        saveSlots = SaveManager.listSaves();
        int bw = 320;
        int bh = 46;
        int gap = 14;
        int rows = Math.min(saveSlots.length, MAX_SLOT_ROWS);
        int startY = h / 2 - 20;

        if (down && !wasDown) {
            for (int i = 0; i < rows; i++) {
                int by = startY + i * (bh + gap);
                if (mx >= cx - bw / 2 && mx <= cx + bw / 2 && my >= by && my <= by + bh) {
                    selectedSlot = saveSlots[i];
                    choose(PLAY);
                    return;
                }
            }
            int newY = startY + rows * (bh + gap) + 10;
            if (mx >= cx - bw / 2 && mx <= cx + bw / 2 && my >= newY && my <= newY + bh) {
                selectedSlot = SaveManager.nextWorldName();
                choose(NEW_WORLD);
                return;
            }
            int backY = newY + bh + gap;
            if (mx >= cx - bw / 2 && mx <= cx + bw / 2 && my >= backY && my <= backY + bh) {
                currentState = MenuState.MAIN_MENU;
            }
        }
    }

    private static void pollSettingsInput(int cx, int h, int mx, int my, boolean down) {
        int bw = 300;
        int bh = 42;
        int gap = 12;
        int startY = h / 2 - 60;

        if (down && !wasDown) {
            if (mx >= cx - bw / 2 && mx <= cx + bw / 2 && my >= startY && my <= startY + bh) {
                farRenderDistance = !farRenderDistance;
            }
            int opt2Y = startY + bh + gap;
            if (mx >= cx - bw / 2 && mx <= cx + bw / 2 && my >= opt2Y && my <= opt2Y + bh) {
                guiScale = (guiScale % 3) + 1;
            }
            int opt3Y = opt2Y + bh + gap;
            if (mx >= cx - bw / 2 && mx <= cx + bw / 2 && my >= opt3Y && my <= opt3Y + bh) {
                SoundSettingsMenu.open();
            }
            int backY = opt3Y + bh + 20;
            if (mx >= cx - bw / 2 && mx <= cx + bw / 2 && my >= backY && my <= backY + bh) {
                currentState = MenuState.MAIN_MENU;
            }
        }
    }

    private static void choose(int c) {
        choice = c;
        wasDown = false;
    }

    public static void render() {
        if (!active) return;
        int w = width();
        int h = height();

        begin2D(w, h);
        renderBackground(w, h);

        if (SoundSettingsMenu.isOpen()) {
            SoundSettingsMenu.render();
        } else {
            int cx = w / 2;
            int mx = Mouse.getX();
            int my = h - Mouse.getY();

            switch (currentState) {
                case MAIN_MENU -> renderMainMenu(cx, h, mx, my);
                case SAVE_SELECT -> renderSaveSelect(cx, h, mx, my);
                case SETTINGS -> renderSettings(cx, h, mx, my);
            }
        }

        end2D();
    }

    private static void begin2D(int w, int h) {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, w, h, 0, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
    }

    private static void end2D() {
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    private static void renderMainMenu(int cx, int h, int mx, int my) {
        int bw = 280;
        int bh = 46;
        int gap = 14;
        
        int totalHeightGroup = 60 + 12 + 18 + 24 + (3 * bh) + (2 * gap);
        int topAnchor = (h - totalHeightGroup) / 2;

        // Modern Title & Subtitle
        int tw = textWidth(TITLE, 52);
        drawTexture(titleTex, cx - tw / 2, topAnchor, tw, 60);
        drawDynamicText("INSEANE STUDIOS", cx, topAnchor + 66, 12, new Color(140, 140, 150), true);

        // Modern Buttons
        int startY = topAnchor + 105;
        for (int i = 0; i < MAIN_OPTIONS.length; i++) {
            int by = startY + i * (bh + gap);
            boolean hover = mx >= cx - bw / 2 && mx <= cx + bw / 2 && my >= by && my <= by + bh;
            drawModernButton(mainOptTex[i], cx - bw / 2, by, bw, bh, mainOptW[i], 20, hover, true);
        }
    }

    private static void renderSaveSelect(int cx, int h, int mx, int my) {
        saveSlots = SaveManager.listSaves();
        int bw = 320;
        int bh = 46;
        int gap = 14;
        int rows = Math.min(saveSlots.length, MAX_SLOT_ROWS);
        int startY = h / 2 - 20;

        drawDynamicText("SELECT WORLD", cx, h / 4, 26, new Color(0, 210, 255), true);

        for (int i = 0; i < rows; i++) {
            int by = startY + i * (bh + gap);
            String label = saveSlots[i];
            long seed = SaveManager.seedOf(label);
            if (seed != 0) label = label + " \u00B7 " + seed;
            boolean hover = mx >= cx - bw / 2 && mx <= cx + bw / 2 && my >= by && my <= by + bh;
            drawModernButtonText(label, cx - bw / 2, by, bw, bh, 15, hover, true);
        }

        int newY = startY + rows * (bh + gap) + 10;
        boolean newHover = mx >= cx - bw / 2 && mx <= cx + bw / 2 && my >= newY && my <= newY + bh;
        drawModernButtonText("New World", cx - bw / 2, newY, bw, bh, 15, newHover, true);

        int backY = newY + bh + gap;
        boolean backHover = mx >= cx - bw / 2 && mx <= cx + bw / 2 && my >= backY && my <= backY + bh;
        drawModernButtonText("Back", cx - bw / 2, backY, bw, bh, 16, backHover, true);
    }

    private static void renderSettings(int cx, int h, int mx, int my) {
        int bw = 300;
        int bh = 42;
        int gap = 12;
        int startY = h / 2 - 60;

        drawDynamicText("SETTINGS", cx, h / 4, 26, new Color(0, 210, 255), true);

        String rdText = "Render Distance: " + (farRenderDistance ? "FAR" : "SHORT");
        boolean rdHover = mx >= cx - bw / 2 && mx <= cx + bw / 2 && my >= startY && my <= startY + bh;
        drawModernButtonText(rdText, cx - bw / 2, startY, bw, bh, 15, rdHover, true);

        int opt2Y = startY + bh + gap;
        String guiText = "GUI Scale: " + guiScale + "x";
        boolean guiHover = mx >= cx - bw / 2 && mx <= cx + bw / 2 && my >= opt2Y && my <= opt2Y + bh;
        drawModernButtonText(guiText, cx - bw / 2, opt2Y, bw, bh, 15, guiHover, true);

        int opt3Y = opt2Y + bh + gap;
        boolean soundHover = mx >= cx - bw / 2 && mx <= cx + bw / 2 && my >= opt3Y && my <= opt3Y + bh;
        drawModernButtonText("Sound Settings", cx - bw / 2, opt3Y, bw, bh, 15, soundHover, true);

        int backY = opt3Y + bh + 20;
        boolean backHover = mx >= cx - bw / 2 && mx <= cx + bw / 2 && my >= backY && my <= backY + bh;
        drawModernButtonText("Back", cx - bw / 2, backY, bw, bh, 15, backHover, true);
    }

    private static void renderBackground(int w, int h) {
        // Deep modern dark background overlay with subtle tint
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(0.07F, 0.07F, 0.09F, 1.0F);

        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(0, 0);
        GL11.glVertex2f(0, h);
        GL11.glVertex2f(w, h);
        GL11.glVertex2f(w, 0);
        GL11.glEnd();

        // Optional texture pass with low alpha for a subtle pattern effect
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, dirtTex);
        GL11.glColor4f(0.15F, 0.15F, 0.18F, 0.35F);

        float tileScale = 64.0F;
        float uMax = w / tileScale;
        float vMax = h / tileScale;

        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, 0); GL11.glVertex2f(0, 0);
        GL11.glTexCoord2f(0, vMax); GL11.glVertex2f(0, h);
        GL11.glTexCoord2f(uMax, vMax); GL11.glVertex2f(w, h);
        GL11.glTexCoord2f(uMax, 0); GL11.glVertex2f(w, 0);
        GL11.glEnd();

        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    private static void drawModernButton(int tex, int x, int y, int bw, int bh, int tw, int th, boolean hover, boolean enabled) {
        drawModernBox(x, y, bw, bh, hover, enabled);
        drawTexture(tex, x + bw / 2 - tw / 2, y + bh / 2 - th / 2, tw, th);
    }

    static void drawModernButtonText(String text, int x, int y, int bw, int bh, int fontSize, boolean hover, boolean enabled) {
        drawModernBox(x, y, bw, bh, hover, enabled);
        drawDynamicText(text, x + bw / 2, y + bh / 2 - fontSize / 2, fontSize,
                enabled ? (hover ? Color.WHITE : new Color(200, 200, 210)) : new Color(100, 100, 110), true);
    }

    private static void drawModernBox(int x, int y, int bw, int bh, boolean hover, boolean enabled) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);

        // Flat dark surface with smooth accent styling on hover
        if (!enabled) {
            GL11.glColor4f(0.12F, 0.12F, 0.14F, 0.5F);
        } else if (hover) {
            GL11.glColor4f(0.18F, 0.20F, 0.25F, 0.95F);
        } else {
            GL11.glColor4f(0.14F, 0.14F, 0.17F, 0.85F);
        }

        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x, y + bh);
        GL11.glVertex2f(x + bw, y + bh);
        GL11.glVertex2f(x + bw, y);
        GL11.glEnd();

        // Modern thin accent border or glow line on hover
        if (enabled) {
            if (hover) {
                // Electric cyan modern indicator line on the left edge
                GL11.glColor4f(0.0F, 0.82F, 1.0F, 1.0F);
                GL11.glLineWidth(3.0F);
                GL11.glBegin(GL11.GL_LINES);
                GL11.glVertex2f(x, y); GL11.glVertex2f(x, y + bh);
                GL11.glEnd();
            }

            // Subtle outer border outline
            GL11.glColor4f(0.25F, 0.27F, 0.32F, hover ? 0.8F : 0.4F);
            GL11.glLineWidth(1.0F);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2f(x, y);
            GL11.glVertex2f(x + bw, y);
            GL11.glVertex2f(x + bw, y + bh);
            GL11.glVertex2f(x, y + bh);
            GL11.glEnd();
        }
    }

    static void drawDynamicText(String text, int cx, int y, int fontSize, Color color, boolean centered) {
        String cacheKey = text + "_" + fontSize + "_" + color.getRGB();
        CachedText cached = textCache.get(cacheKey);
        
        if (cached == null) {
            int tex = makeTextureWithColor(text, fontSize, color);
            int tw = textWidth(text, fontSize);
            cached = new CachedText(tex, tw, fontSize + 16);
            textCache.put(cacheKey, cached);
        }

        int rx = centered ? cx - cached.width / 2 : cx;
        drawTexture(cached.textureId, rx, y, cached.width, cached.height);
    }

    static void drawTexture(int tex, int x, int y, int w, int h) {
        if (tex == 0) return;

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, 0); GL11.glVertex2f(x, y);
        GL11.glTexCoord2f(1, 0); GL11.glVertex2f(x + w, y);
        GL11.glTexCoord2f(1, 1); GL11.glVertex2f(x + w, y + h);
        GL11.glTexCoord2f(0, 1); GL11.glVertex2f(x, y + h);
        GL11.glEnd();

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
    }

    static int textWidth(String s, int fontSize) {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setFont(new Font("SansSerif", Font.BOLD, fontSize));
        int w = g.getFontMetrics().stringWidth(s);
        g.dispose();
        return w;
    }

    static int makeTexture(String s, int fontSize, boolean titleStyle) {
        return makeTextureWithColor(s, fontSize, titleStyle ? Color.WHITE : new Color(220, 220, 230));
    }

    private static int makeTextureWithColor(String s, int fontSize, Color textColor) {
        int width = Math.max(1, textWidth(s, fontSize) + 12);
        int height = fontSize + 16;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setFont(new Font("SansSerif", Font.BOLD, fontSize));

        // Crisp modern soft shadow
        g.setColor(new Color(10, 10, 12, 180));
        g.drawString(s, 3, fontSize + 2);

        g.setColor(textColor);
        g.drawString(s, 2, fontSize);
        g.dispose();

        return uploadTexture(img, width, height);
    }

    private static int generateDirtTexture() {
        int size = 32;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(4321L);

        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                int noise = r.nextInt(20) - 10;
                int red = Math.min(255, Math.max(0, 35 + noise));
                int green = Math.min(255, Math.max(0, 35 + noise));
                int blue = Math.min(255, Math.max(0, 42 + noise));
                img.setRGB(x, y, (red << 16) | (green << 8) | blue);
            }
        }
        return uploadTexture(img, size, size);
    }

    static int uploadTexture(BufferedImage img, int width, int height) {
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

        int[] pixels = new int[width * height];
        img.getRGB(0, 0, width, height, pixels, 0, width);
        ByteBuffer buf = BufferUtils.createByteBuffer(width * height * 4);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int p = pixels[y * width + x];
                int a = (p >> 24) & 0xFF;
                int red = (p >> 16) & 0xFF;
                int green = (p >> 8) & 0xFF;
                int blue = p & 0xFF;
                buf.put((byte) red).put((byte) green).put((byte) blue).put((byte) a);
            }
        }
        buf.flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        return id;
    }

    public static boolean hasSave() {
        return SaveManager.listSaves().length > 0;
    }

    public static boolean isFarRenderDistance() {
        return farRenderDistance;
    }

    public static int getGuiScale() {
        return guiScale;
    }
}