package com.insanestudios.blockin;

import com.insanestudios.blockin.Physics.Box;
import com.insanestudios.blockin.Physics.Mob_Pig;
import com.insanestudios.blockin.World.Level;
import com.insanestudios.blockin.World.LevelRenderer;
import com.insanestudios.blockin.World.TerrainGen;
import com.insanestudios.blockin.World.WaterSystem;
import com.insanestudios.blockin.Blocks.Block;
import com.insanestudios.blockin.Blocks.BlockLoader;
import com.insanestudios.blockin.GameHUD;
import com.insanestudios.blockin.MainMenu;
import com.insanestudios.blockin.Sound.SoundEngine;
import com.insanestudios.blockin.Update;
import com.insanestudios.blockin.WindowPatch;
import org.lwjgl.BufferUtils;
import org.lwjgl.LWJGLException;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Blockin main game. Owns the window, the fixed-timestep loop, world building,
 * mouse look / block interaction, and the 3D + 2D render passes. The game UI
 * layer ({@link Update}) is driven exactly once per tick/frame.
 */
public final class Blockin {

    private static final int WINDOW_W = 1920;
    private static final int WINDOW_H = 1080;

    private static final float[] FOG_COLOR = {0.60F, 0.66F, 0.72F};
    private static final long OPERATION_DELAY_MS = 180L;

    private final GameClock timer = new GameClock(60.0F);
    private final FloatBuffer fogBuffer =
            BufferUtils.createFloatBuffer(4).put(FOG_COLOR[0]).put(FOG_COLOR[1])
                    .put(FOG_COLOR[2]).put(1.0F).flip();

    private final List<Mob_Pig> pigs = new ArrayList<>();
    private final Random rnd = new Random();

    private Level level;
    private LevelRenderer levelRenderer;
    private PlayerController player;

    private HitResult hit;
    private boolean started = false;
    private boolean operateBlock = false;
    private boolean closeRequested = false;
    private long lastOperate = 0L;
    private int dbgJumpTick = 0;

    public static void main(String[] args) {
        Blockin game = null;
        try {
            game = new Blockin();
            game.run();
        } catch (Throwable t) {
            System.err.println("[Blockin] fatal error: " + t);
            t.printStackTrace();
        } finally {
            if (game != null) game.cleanup();
        }
    }

    public Blockin() throws LWJGLException {
        initDisplay();
    }

    private void initDisplay() throws LWJGLException {
        Display.setTitle("Blockin");
        try {
            Display.setDisplayMode(new DisplayMode(WINDOW_W, WINDOW_H));
        } catch (LWJGLException e) {
            Display.setDisplayMode(Display.getDesktopDisplayMode());
        }
        Display.setVSyncEnabled(true);
        Display.create();

        GL11.glClearColor(FOG_COLOR[0], FOG_COLOR[1], FOG_COLOR[2], 1.0F);
        GL11.glClearDepth(1.0F);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        // No GL_CULL_FACE: 2D overlays (menu/HUD) are drawn through a mirrored
        // orthographic projection whose winding would be culled away entirely.
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F);

        WindowPatch.init();
        Update.init();
    }

    // ------------------------------------------------------------- world

    private void buildWorld(boolean load) {
        if (started) return;

        String name = MainMenu.selectedSave();
        if (name == null) {
            name = SaveManager.nextWorldName();
        }
        long seed = new Random().nextLong();
        java.io.File saveFile = SaveManager.levelFile(name);
        boolean wantLoad = load && saveFile.isFile();
        // Loading far chunks must regenerate from the world's own seed so the
        // infinite terrain matches what was saved (the legacy flat array has no
        // header, so its meta.properties seed is used; new saves carry theirs
        // in the file and Level adopts it on load).
        if (wantLoad) {
            long saved = SaveManager.seedOf(name);
            if (saved != 0) {
                seed = saved;
            }
        }
        level = new Level(128, 64, 128, seed, saveFile, wantLoad);
        levelRenderer = new LevelRenderer(level);
        player = new PlayerController(level);

        Level.PlayerState sp = level.savedPlayer();
        if (sp != null) {
            player.restoreState(sp.x, sp.z, sp.y, sp.yaw, sp.pitch);
        }

        for (int i = 0; i < 8; i++) {
            int sx = 2;
            int sz = 2;
            int sy = 0;
            for (int tries = 0; tries < 24; tries++) {
                sx = 2 + rnd.nextInt(level.width - 4);
                sz = 2 + rnd.nextInt(level.height - 4);
                sy = level.getSurfaceY(sx, sz);
                if (sy >= TerrainGen.SEA_LEVEL) break; // prefer land / beach
            }
            pigs.add(new Mob_Pig(level, sx + 0.5F, sy + 1.55F, sz + 0.5F));
        }

        System.out.println("[Blockin] " + (wantLoad ? "loaded" : "created") + " world \"" + name + "\""
                + " (seed " + seed + ")" + (wantLoad ? "" : " -> " + saveFile.getParent()));

        Update.bind(player, level);
        Update.signalWorldLoaded();

        started = true;
        Mouse.setGrabbed(true);
        lastOperate = 0L;
    }

    // ------------------------------------------------------------ loop

    public void run() {
        while (!Display.isCloseRequested() && !closeRequested) {
            timer.advanceTime();
            for (int i = 0; i < timer.ticks; i++) {
                tick();
            }
            render(timer.a);
            WindowPatch.checkResize();
            Display.update();
        }
    }

    private void tick() {
        // Poll input every frame while the game runs: the menu is the only
        // other place that polls, so without this the keyboard/mouse freeze
        // (no look, movement, break, place or wheel) once PLAY is chosen.
        Mouse.poll();
        Keyboard.poll();

        Update.pollMenu();

        if (Update.menuActive()) {
            switch (Update.menuChoice()) {
                case MainMenu.PLAY -> buildWorld(true);
                case MainMenu.NEW_WORLD -> buildWorld(false);
                case MainMenu.QUIT -> closeRequested = true;
                default -> {
                }
            }
            return;
        }

        if (!started) return;

        boolean inventoryOpen = GameHUD.isInventoryOpen();
        if (inventoryOpen) {
            hit = null;
        } else {
            handleMouse();

            if (Keyboard.isKeyDown(Keyboard.KEY_ESCAPE)) {
                closeRequested = true;
                return;
            }
            if (Keyboard.isKeyDown(Keyboard.KEY_RETURN)) {
                level.setSavedPlayer(player.bb.centerX(), player.bb.y0,
                        player.bb.centerZ(), player.yRot, player.xRot);
                level.save();
            }
        }

        player.tick();
        for (Mob_Pig pig : pigs) {
            pig.tick();
        }

        WaterSystem.update(level);

        if (++dbgJumpTick % 16 == 0) {
            System.out.println("DBGJ y=" + String.format("%.3f", player.y)
                    + " feet=" + String.format("%.3f", player.bb.y0)
                    + " og=" + player.onGround
                    + " yd=" + String.format("%.3f", player.yd));
        }

        Update.tick();

        if (!inventoryOpen) {
            hit = levelRenderer.pick(player);
        }
    }

    private void handleMouse() {
        long now = System.currentTimeMillis();
        if (now - lastOperate < OPERATION_DELAY_MS) {
            if (!Mouse.isButtonDown(0) && !Mouse.isButtonDown(1)) {
                operateBlock = false;
            }
            return;
        }

        if (Mouse.isButtonDown(0) && !operateBlock) {
            doPlace();
            operateBlock = true;
            lastOperate = now;
        } else if (Mouse.isButtonDown(1) && !operateBlock) {
            doBreak();
            operateBlock = true;
            lastOperate = now;
        }

        if (!Mouse.isButtonDown(0) && !Mouse.isButtonDown(1)) {
            operateBlock = false;
        }
    }

    private void doBreak() {
        if (hit == null) return;

        int x = hit.x();
        int y = hit.y();
        int z = hit.z();
        int before = level.getTile(x, y, z);
        if (before == 0) return;

        level.setTile(x, y, z, 0);
        Block b = BlockLoader.get(before);
        SoundEngine.playBreak(b != null ? b.getBreakSound() : "Stone");
        WaterSystem.onBlockChanged(level, x, y, z);
    }

    private void doPlace() {
        if (hit == null) return;

        int type = GameHUD.getHeldBlockId();
        if (type == 0) return;

        int x = hit.x();
        int y = hit.y();
        int z = hit.z();
        switch (hit.f()) {
            case 0 -> y--;
            case 1 -> y++;
            case 2 -> z--;
            case 3 -> z++;
            case 4 -> x--;
            default -> x++;
        }

        if (level.getTile(x, y, z) != 0) return;

        Box box = new Box(x, y, z, x + 1.0F, y + 1.0F, z + 1.0F);
        if (player.bb.intersects(box)) return;

        level.setTile(x, y, z, type);
        Block b = BlockLoader.get(type);
        SoundEngine.playPlace(b != null ? b.getPlaceSound() : "Stone");
        WaterSystem.onBlockChanged(level, x, y, z);
    }

    // ----------------------------------------------------------- render

    private void render(float a) {
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        if (started) {
            renderWorld3D();
        }

        render2D();
    }

    private void renderWorld3D() {
        float far = MainMenu.isFarRenderDistance() ? 1000.0F : 300.0F;
        float aspect = (float) Display.getWidth() / (float) Display.getHeight();

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GLU.gluPerspective(70.0F, aspect, 0.05F, far);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();

        setupFog(far);

        // Water draws with per-vertex alpha through the shared tesselator, so
        // the whole world pass runs with standard alpha blending. Opaque faces
        // carry alpha 1.0 and are unaffected; only water tints the frame.
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        GL11.glRotatef(player.xRot, 1.0F, 0.0F, 0.0F);
        GL11.glRotatef(player.yRot, 0.0F, 1.0F, 0.0F);
        GL11.glTranslatef(-player.x, -player.y, -player.z);

        levelRenderer.render(player, 0);
        levelRenderer.render(player, 1);

        for (Mob_Pig pig : pigs) {
            pig.render();
        }

        if (hit != null) {
            levelRenderer.renderHit(hit);
        }

        GL11.glDisable(GL11.GL_BLEND);
    }

    private void setupFog(float far) {
        GL11.glEnable(GL11.GL_FOG);
        GL11.glFogi(GL11.GL_FOG_MODE, GL11.GL_LINEAR);
        GL11.glFogf(GL11.GL_FOG_START, far * 0.7F);
        GL11.glFogf(GL11.GL_FOG_END, far);
        GL11.glFog(GL11.GL_FOG_COLOR, fogBuffer);
        GL11.glFogf(GL11.GL_FOG_DENSITY, 0.0F);
    }

    private void render2D() {
        Update.render();

        if (started && !Update.menuActive() && !GameHUD.isInventoryOpen()) {
            drawCrosshair();
        }
    }

    private void drawCrosshair() {
        int w = Display.getWidth();
        int h = Display.getHeight();
        int len = 10;
        int thick = 2;
        int cx = w / 2;
        int cy = h / 2;

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, w, h, 0, -1, 1);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 0.9F);

        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(cx - thick / 2, cy - len);
        GL11.glVertex2f(cx + thick / 2, cy - len);
        GL11.glVertex2f(cx + thick / 2, cy + len);
        GL11.glVertex2f(cx - thick / 2, cy + len);
        GL11.glVertex2f(cx - len, cy - thick / 2);
        GL11.glVertex2f(cx + len, cy - thick / 2);
        GL11.glVertex2f(cx + len, cy + thick / 2);
        GL11.glVertex2f(cx - len, cy + thick / 2);
        GL11.glEnd();

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    private void cleanup() {
        try {
            if (level != null) {
                if (player != null) {
                    level.setSavedPlayer(player.bb.centerX(), player.bb.y0,
                            player.bb.centerZ(), player.yRot, player.xRot);
                }
                level.save();
            }
            SoundEngine.shutdown();
        } catch (Throwable ignored) {
        }
        Mouse.setGrabbed(false);
        Display.destroy();
    }
}