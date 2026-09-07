package com.insanestudios.blockin;

import com.insanestudios.blockin.Blocks.Block;
import com.insanestudios.blockin.Blocks.BlockLoader;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-game HUD: crosshair, hotbar and a scroll/clickable block inventory.
 *
 * <p>Works as an {@link Update.HudModule}: the engine calls {@code render()}
 * after the world pass and {@code tick()} every frame. Static accessors
 * ({@link #isInventoryOpen()}, {@link #getHeldBlockId()}) let the engine gate
 * input while the inventory is showing and know which block to place.
 *
 * <p>The block list comes from {@link BlockLoader#getAll()}, so custom XML
 * mod blocks automatically appear in the inventory.
 */
public class GameHUD implements Update.HudModule {

    /** Slot icon size in pixels (unscaled). */
    private static final int SLOT = 40;
    private static final int SLOT_GAP = 6;
    private static final int PANEL_GAP = 12;
    private static final int GRID_COLS = 9;

    private static final List<Integer> blockIds = new ArrayList<>();
    private static final Map<Integer, Integer> iconTextureFor = new HashMap<>();

    private static boolean inventoryOpen = false;
    private static int heldId = 0;

    private boolean wasEPressed = false;
    private boolean wasClickPressed = false;

    @Override
    public String name() {
        return "GameHUD";
    }

    @Override
    public void init() {
        refreshBlocks();
    }

    @Override
    public void bind(Object player, Object level) {
        // Read from Blockin directly via static state; kept for future use.
    }

    @Override
    public void tick() {
        if (blockIds.isEmpty()) return;

        boolean ePressed = Keyboard.isKeyDown(Keyboard.KEY_E) || Keyboard.isKeyDown(Keyboard.KEY_TAB);
        if (ePressed && !wasEPressed) {
            inventoryOpen = !inventoryOpen;
            if (inventoryOpen) Mouse.setGrabbed(false);
            else Mouse.setGrabbed(true);
            wasClickPressed = true; // swallow the click that opened it
        }
        wasEPressed = ePressed;

        if (inventoryOpen) {
            if (Keyboard.isKeyDown(Keyboard.KEY_ESCAPE)) {
                closeInventory();
                return;
            }
            pickSlotFromCursor();
        } else {
            int wheel = Mouse.getDWheel();
            if (wheel != 0) {
                int index = blockIds.indexOf(heldId);
                int n = blockIds.size();
                index = Math.floorMod(index + (wheel > 0 ? -1 : 1), n);
                heldId = blockIds.get(index);
            }
        }
    }

    @Override
    public void render() {
        if (blockIds.isEmpty()) refreshBlocks();

        int w = Display.getWidth();
        int h = Display.getHeight();

        begin2D(w, h);
        drawCrosshair(w, h);

        if (inventoryOpen) {
            drawInventory(w, h);
        } else {
            drawHotbar(w, h);
        }
        end2D();
    }

    // ------------------------------------------------------------ input

    private void closeInventory() {
        inventoryOpen = false;
        Mouse.setGrabbed(true);
    }

    private void pickSlotFromCursor() {
        int mx = Mouse.getX();
        int my = Display.getHeight() - Mouse.getY();
        boolean down = Mouse.isButtonDown(0);
        if (down && !wasClickPressed) {
            Integer slotId = slotAt(mx, my);
            if (slotId != null) heldId = slotId;
        }
        wasClickPressed = down;
    }

    private Integer slotAt(int mx, int my) {
        int w = Display.getWidth();
        int h = Display.getHeight();

        // Hotbar row (always clickable, even when open).
        int cols = blockIds.size();
        int bw = cols * (SLOT + SLOT_GAP) - SLOT_GAP;
        int bx = (w - bw) / 2;
        int by = h - PANEL_GAP - SLOT - 6;
        for (int i = 0; i < cols; i++) {
            int sx = bx + i * (SLOT + SLOT_GAP);
            if (mx >= sx && mx <= sx + SLOT && my >= by && my <= by + SLOT) {
                return blockIds.get(i);
            }
        }

        if (gridGeometry(w, h) == null) return null;
        int gx = gridGeometry(w, h)[0];
        int gy = gridGeometry(w, h)[1];
        int gw = gridGeometry(w, h)[2];
        int rows = (blockIds.size() + GRID_COLS - 1) / GRID_COLS;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < GRID_COLS; c++) {
                int i = r * GRID_COLS + c;
                if (i >= blockIds.size()) continue;
                int sx = gx + c * (SLOT + SLOT_GAP);
                int sy = gy + r * (SLOT + SLOT_GAP);
                if (mx >= sx && mx <= sx + SLOT && my >= sy && my <= sy + SLOT) {
                    return blockIds.get(i);
                }
            }
        }
        return null;
    }

    // --------------------------------------------------------- render

    private void refreshBlocks() {
        blockIds.clear();
        iconTextureFor.clear();
        for (Block b : BlockLoader.getAll()) {
            blockIds.add(b.id);
            iconTextureFor.put(b.id, b.getIconTexture());
        }
        if (blockIds.isEmpty()) {
            heldId = 0;
        } else if (!blockIds.contains(heldId)) {
            heldId = blockIds.get(0);
        }
    }

    private void drawCrosshair(int w, int h) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 0.85F);
        GL11.glLineWidth(2.0F);
        int cx = w / 2;
        int cy = h / 2;
        int gap = 4;
        int len = 8;
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(cx - gap, cy); GL11.glVertex2f(cx - gap - len, cy);
        GL11.glVertex2f(cx + gap, cy); GL11.glVertex2f(cx + gap + len, cy);
        GL11.glVertex2f(cx, cy - gap); GL11.glVertex2f(cx, cy - gap - len);
        GL11.glVertex2f(cx, cy + gap); GL11.glVertex2f(cx, cy + gap + len);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private void drawHotbar(int w, int h) {
        int cols = blockIds.size();
        int bw = cols * (SLOT + SLOT_GAP) - SLOT_GAP;
        int bx = (w - bw) / 2;
        int by = h - PANEL_GAP - SLOT - 6;

        UI.modernPanel(bx - 8, by - 8, bw + 16, SLOT + 16, 0.85F);
        for (int i = 0; i < cols; i++) {
            int sx = bx + i * (SLOT + SLOT_GAP);
            boolean selected = blockIds.get(i) == heldId;
            drawSlot(sx, by, selected, iconTextureFor.get(blockIds.get(i)));
        }
    }

    private void drawInventory(int w, int h) {
        int cols = blockIds.size();
        int bw = cols * (SLOT + SLOT_GAP) - SLOT_GAP;
        int bx = (w - bw) / 2;
        int by = h - PANEL_GAP - SLOT - 6;

        int[] g = gridGeometry(w, h);
        int rows = (blockIds.size() + GRID_COLS - 1) / GRID_COLS;
        int gw = GRID_COLS * (SLOT + SLOT_GAP) - SLOT_GAP;
        int gh = rows * (SLOT + SLOT_GAP) - SLOT_GAP;

        UI.modernPanel(g[0] - 8, g[1] - 8, gw + 16, gh + 16, 0.92F);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < GRID_COLS; c++) {
                int i = r * GRID_COLS + c;
                if (i >= blockIds.size()) continue;
                int sx = g[0] + c * (SLOT + SLOT_GAP);
                int sy = g[1] + r * (SLOT + SLOT_GAP);
                boolean selected = blockIds.get(i) == heldId;
                drawSlot(sx, sy, selected, iconTextureFor.get(blockIds.get(i)));
            }
        }

        UI.modernPanel(bx - 8, by - 8, bw + 16, SLOT + 16, 0.85F);
        for (int i = 0; i < cols; i++) {
            int sx = bx + i * (SLOT + SLOT_GAP);
            drawSlot(sx, by, blockIds.get(i) == heldId, iconTextureFor.get(blockIds.get(i)));
        }

        MainMenu.drawDynamicText("E: close inventory", w / 2, g[1] - 8 - 24, 13,
                new Color(200, 200, 210), true);
    }

    private void drawSlot(int x, int y, boolean selected, int tex) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(0.10F, 0.10F, 0.14F, 0.9F);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y); GL11.glVertex2f(x + SLOT, y);
        GL11.glVertex2f(x + SLOT, y + SLOT); GL11.glVertex2f(x, y + SLOT);
        GL11.glEnd();

        if (selected) {
            GL11.glColor4f(0.0F, 0.82F, 1.0F, 1.0F);
            GL11.glLineWidth(2.0F);
        } else {
            GL11.glColor4f(0.25F, 0.27F, 0.32F, 0.5F);
            GL11.glLineWidth(1.0F);
        }
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x, y); GL11.glVertex2f(x + SLOT, y);
        GL11.glVertex2f(x + SLOT, y + SLOT); GL11.glVertex2f(x, y + SLOT);
        GL11.glEnd();

        if (tex != 0) {
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            int pad = 6;
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(0, 0); GL11.glVertex2f(x + pad, y + pad);
            GL11.glTexCoord2f(1, 0); GL11.glVertex2f(x + SLOT - pad, y + pad);
            GL11.glTexCoord2f(1, 1); GL11.glVertex2f(x + SLOT - pad, y + SLOT - pad);
            GL11.glTexCoord2f(0, 1); GL11.glVertex2f(x + pad, y + SLOT - pad);
            GL11.glEnd();
            GL11.glDisable(GL11.GL_TEXTURE_2D);
        }
    }

    /** [x, y, panelWidth] of the inventory grid's top-left corner. */
    private int[] gridGeometry(int w, int h) {
        int rows = (blockIds.size() + GRID_COLS - 1) / GRID_COLS;
        if (rows == 0) return null;
        int gw = GRID_COLS * (SLOT + SLOT_GAP) - SLOT_GAP;
        int gh = rows * (SLOT + SLOT_GAP) - SLOT_GAP;
        int cols = blockIds.size();
        int hw = cols * (SLOT + SLOT_GAP) - SLOT_GAP;
        int hbH = h - PANEL_GAP - SLOT - 6;
        int hbTop = hbH - 8;
        int gy = Math.max(80, hbTop - gh - 40);
        int gx = Math.max(8, (w - gw) / 2);
        return new int[]{gx, gy, gw};
    }

    private void begin2D(int w, int h) {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, w, h, 0, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
    }

    private void end2D() {
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    // --------------------------------------------------------- accessors

    public static boolean isInventoryOpen() {
        return inventoryOpen;
    }

    /** The block id selected on the hotbar (0 = air, nothing to place). */
    public static int getHeldBlockId() {
        return heldId;
    }

    // ------------------------------------------------------- UI helpers

    /**
     * Minimal 2D chrome used everywhere in the game: a translucent dark panel
     * with a subtle accent border — no textures, GL-quad only.
     */
    public static class UI {

        public static void modernPanel(int x, int y, int width, int height, float alpha) {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(0.06F, 0.06F, 0.09F, alpha);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(x, y); GL11.glVertex2f(x + width, y);
            GL11.glVertex2f(x + width, y + height); GL11.glVertex2f(x, y + height);
            GL11.glEnd();

            GL11.glColor4f(0.40F, 0.40F, 0.52F, 0.9F);
            GL11.glLineWidth(2.0F);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2f(x, y); GL11.glVertex2f(x + width, y);
            GL11.glVertex2f(x + width, y + height); GL11.glVertex2f(x, y + height);
            GL11.glEnd();
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
        }
    }
}