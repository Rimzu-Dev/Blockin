package com.insanestudios.blockin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * Multi-save support: every world is one folder under
 * {@code <user home>/Documents/Blockin/saves/<World Name>/} holding a
 * {@code level.dat} (compressed block array) plus a tiny {@code meta.properties}
 * (name / seed / last played).
 *
 * <p>On first run a legacy {@code level.dat} sitting in the working directory
 * (the pre-multi-save location) is migrated into a "World 1" slot so no old save
 * is lost when the user starts a new game.
 */
public final class SaveManager {

    private static final String SAVE_DIR_NAME = "Blockin";
    private static final String META_FILE = "meta.properties";

    private static File cachedDocumentsDir;

    private SaveManager() {
    }

    /** The user's real Documents folder (honours OneDrive redirection). */
    private static File documentsDir() {
        if (cachedDocumentsDir != null) return cachedDocumentsDir;
        File docs = null;
        String os = System.getProperty("os.name", "");
        if (os.toLowerCase().contains("win")) {
            docs = windowsDocumentsDir();
        }
        if (docs == null || !docs.exists()) {
            docs = new File(System.getProperty("user.home"), "Documents");
        }
        if (!docs.isDirectory()) docs.mkdirs();
        cachedDocumentsDir = docs;
        return docs;
    }

    /** Resolves FOLDERID via the registry "Personal" shell folder. */
    private static File windowsDocumentsDir() {
        try {
            Process p = new ProcessBuilder("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Shell Folders",
                    "/v", "Personal").start();
            String out = new String(p.getInputStream().readAllBytes(), "UTF-8");
            p.waitFor();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?i)Personal\\s+REG_SZ\\s+(\\S.*)").matcher(out);
            if (m.find()) {
                String path = m.group(1).trim();
                if (!path.isEmpty()) return new File(path);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Root saves folder ({@code <Documents>/Blockin/saves}). Created on demand. */
    public static File saveRoot() {
        File root = new File(documentsDir(), SAVE_DIR_NAME + File.separator + "saves");
        if (!root.isDirectory()) root.mkdirs();
        return root;
    }

    /** Folder for a named world, sanitising every name against path tricks. */
    public static File slotDir(String name) {
        File dir = slotDirRaw(name);
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /** Path-only variant that never creates the folder. */
    private static File slotDirRaw(String name) {
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "")
                .replaceAll("\\s+", " ").trim();
        if (safe.isEmpty()) safe = "World";
        if (safe.length() > 24) safe = safe.substring(0, 24);
        return new File(saveRoot(), safe);
    }

    /** The {@code level.dat} file for a world. */
    public static File levelFile(String name) {
        return new File(slotDir(name), "level.dat");
    }

    /** True when the named world has a save file. */
    public static boolean hasSlot(String name) {
        return new File(slotDirRaw(name), "level.dat").isFile();
    }

    /** Save names newest-first (folders that contain a level.dat). */
    public static String[] listSaves() {
        File root = saveRoot();
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return new String[0];
        List<File> valid = new ArrayList<>();
        for (File d : dirs) {
            if (new File(d, "level.dat").isFile()) valid.add(d);
        }
        valid.sort(Comparator.comparingLong(SaveManager::lastPlayed).reversed());
        String[] names = new String[valid.size()];
        for (int i = 0; i < valid.size(); i++) names[i] = valid.get(i).getName();
        return names;
    }

    /** Next free "World N" name. */
    public static String nextWorldName() {
        File root = saveRoot();
        File[] dirs = root.listFiles(File::isDirectory);
        int max = 0;
        if (dirs != null) {
            for (File d : dirs) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("(?i)world[ _]?(\\d+)").matcher(d.getName());
                if (m.matches()) max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        String name = "World " + (max + 1);
        while (hasSlot(name) || slotDirRaw(name).isDirectory()) {
            name = "World " + (++max + 1);
        }
        return name;
    }

    /** Records a play session + generation seed for a world. */
    public static void notePlayed(String name, long seed) {
        File dir = slotDir(name);
        Properties meta = readMeta(dir);
        meta.setProperty("name", name);
        meta.setProperty("seed", Long.toString(seed));
        meta.setProperty("lastPlayed", Long.toString(System.currentTimeMillis()));
        writeMeta(dir, meta);
    }

    /** Seed of a saved world (0 = legacy/unknown). */
    public static long seedOf(String name) {
        Properties meta = readMeta(slotDir(name));
        try {
            return Long.parseLong(meta.getProperty("seed", "0"));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Imports a legacy {@code level.dat} from the working directory into the
     * first slot when no slots exist yet. Idempotent: the source is removed once
     * copied.
     */
    public static void migrateLegacySave() {
        File legacy = new File("level.dat");
        if (!legacy.isFile()) return;
        if (listSaves().length > 0) return;
        String name = nextWorldName();
        File target = levelFile(name);
        try {
            Files.copy(legacy.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
            notePlayed(name, 0L);
            if (target.isFile()) {
                System.out.println("[Saves] migrated legacy level.dat -> "
                        + target.getParent() + " (" + name + ")");
                legacy.delete();
            }
        } catch (IOException e) {
            System.err.println("[Saves] could not migrate legacy save: " + e);
        }
    }

    private static long lastPlayed(File dir) {
        Properties meta = readMeta(dir);
        try {
            long lp = Long.parseLong(meta.getProperty("lastPlayed", "0"));
            if (lp > 0) return lp;
        } catch (NumberFormatException ignored) {
        }
        return dir.lastModified();
    }

    private static Properties readMeta(File dir) {
        Properties p = new Properties();
        File f = new File(dir, META_FILE);
        if (f.isFile()) {
            try (FileInputStream in = new FileInputStream(f)) {
                p.load(in);
            } catch (IOException ignored) {
            }
        }
        return p;
    }

    private static void writeMeta(File dir, Properties meta) {
        try (FileOutputStream out = new FileOutputStream(new File(dir, META_FILE))) {
            meta.store(out, "Blockin world");
        } catch (IOException e) {
            System.err.println("[Saves] failed to write meta: " + e);
        }
    }
}