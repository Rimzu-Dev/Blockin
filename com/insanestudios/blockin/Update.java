package com.insanestudios.blockin;

import com.insanestudios.blockin.Blocks.BlockLoader;
import com.insanestudios.blockin.Models.ModelLoader;
import com.insanestudios.blockin.Sound.SoundEngine;

/**
 * Central hub for game updates and mods.
 *
 * <p>Standalone feature modules live in this package. Instead of editing the
 * engine core for every change, drop a module in and register it in the
 * {@link #MODULES} list below — the game calls into this one class, which fans
 * the calls out to each registered module.
 *
 * <p>The mod layer is the user's own work; Blockin keeps a single
 * integration point so it stays easy to add and easy to remove.
 */
public final class Update {

    /** All standalone modules to load at startup, in order. */
    private static final UpdateModule[] MODULES = new UpdateModule[]{
            new GameHUD()
    };

    private Update() {
    }

    /** Called once after the game display is ready. */
    public static void init() {
        // Register built-in + Mods/Blocks custom blocks and build the atlas
        // before any module (or world renderer) touches textures.
        BlockLoader.loadAll();
        ModelLoader.warmUp();
        SoundEngine.warmUp();
        MainMenu.init();
        for (UpdateModule m : MODULES) {
            runQuietly("init", m, () -> m.init());
        }
    }

    /** True while the main menu is showing (world not built yet). */
    public static boolean menuActive() {
        return MainMenu.isActive();
    }

    /** The player's menu choice (PLAY or QUIT). */
    public static int menuChoice() {
        return MainMenu.choice();
    }

    /** Poll menu input every frame while the menu is shown. */
    public static void pollMenu() {
        if (MainMenu.isActive()) {
            MainMenu.pollInput();
        }
    }

    /** Called by the game once the world has been built; ends the menu. */
    public static void signalWorldLoaded() {
        MainMenu.startGame();
    }

    /** Called once per game tick. */
    public static void tick() {
        for (UpdateModule m : MODULES) {
            runQuietly("tick", m, m::tick);
        }
    }

    /**
     * Binds the live player/level so HUD modules can read game state. Types
     * are erased at the hub boundary on purpose: modules cast to the engine
     * classes they know about.
     */
    public static void bind(Object player, Object level) {
        for (UpdateModule m : MODULES) {
            if (m instanceof HudModule hud) {
                runQuietly("bind", m, () -> hud.bind(player, level));
            }
        }
    }

    /** Called once per rendered frame (2D overlay pass). */
    public static void render() {
        if (MainMenu.isActive()) {
            MainMenu.render();
            return;
        }
        for (UpdateModule m : MODULES) {
            runQuietly("render", m, m::render);
        }
    }

    private static void runQuietly(String phase, UpdateModule m, Runnable run) {
        try {
            run.run();
        } catch (RuntimeException e) {
            System.err.println("[Update] " + phase + " failed for " + m.name() + ": " + e);
        }
    }

    // ------------------------------------------------------------ contracts

    public interface UpdateModule {
        String name();

        void init();

        void tick();

        void render();
    }

    /** Optional extra for modules that need the live player/level state. */
    public interface HudModule extends UpdateModule {
        void bind(Object player, Object level);
    }
}