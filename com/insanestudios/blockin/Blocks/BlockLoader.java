package com.insanestudios.blockin.Blocks;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Registry and loader for blocks.
 *
 * <p>{@link #loadAll()} seeds the built-in atlas faces, registers the built-in
 * blocks, then scans for mods in three places:
 *
 * <ul>
 *   <li><b>{@code Blocks/<Name>/<Name>.xml}</b> — a top-level folder next to the
 *       game's {@code Mods} folder (e.g. {@code <game>/Blocks/MyBlock/MyBlock.xml});</li>
 *   <li><b>{@code Mods/Blocks/<Name>/<Name>.xml}</b> — folder mods;</li>
 *   <li><b>{@code Mods/*.zip}</b> — zips with {@code info.xml} + per-block XML.</li>
 * </ul>
 *
 * <p>The XML may define a name, description, type (solid/transparent/liquid),
 * textures, sounds and an optional {@code .java} behavior file that is compiled
 * at load time (see {@link BlockCodeCompiler}). Legacy {@code block.properties}
 * folder mods still load. A clash-proof id allocator assigns free ids when a mod
 * omits one or the requested id is already taken.
 */
public final class BlockLoader {

    // Built-in block ids (must match world generation)
    public static final int STONE = 1;
    public static final int GRASS = 2;
    public static final int WATER = 3;
    public static final int DIRT = 4;
    public static final int SAND = 5;
    public static final int GRAVEL = 6;
    public static final int LOG = 7;
    public static final int LEAVES = 8;

    /** Slot id that means "no block" (air). */
    public static final int AIR = 0;

    private static final Map<Integer, Block> blocks = new LinkedHashMap<>();
    private static int nextId = 9; // first free custom id
    private static boolean loaded = false;

    private BlockLoader() {
    }

    /** Returns the block with the given id, or null for air/unknown. */
    public static Block get(int type) {
        return blocks.get(type);
    }

    /** All registered blocks, in registration order. */
    public static List<Block> getAll() {
        return new ArrayList<>(blocks.values());
    }

    /** True when the block id resolves to a liquid type (non-solid, swimmable). */
    public static boolean isLiquid(int type) {
        Block b = blocks.get(type);
        return b != null && b.isLiquid();
    }

    /** Seeds the atlas, registers built-ins, loads mods. Idempotent. */
    public static synchronized void loadAll() {
        if (loaded) return;

        BlockAtlas.seedCoreCells();

        blocks.put(STONE, new Block(STONE, "Stone",
                BlockAtlas.STONE, BlockAtlas.STONE, BlockAtlas.STONE,
                "Stone", "Stone", "Stone"));

        blocks.put(GRASS, new Block(GRASS, "Grass",
                BlockAtlas.GRASS_TOP, BlockAtlas.GRASS_SIDE, BlockAtlas.DIRT_BOTTOM,
                "Grass", "Grass", "Grass"));

        blocks.put(WATER, new WaterBlock(WATER, "Water",
                BlockAtlas.WATER, BlockAtlas.WATER, BlockAtlas.WATER,
                "Water", "Water", "Water"));

        blocks.put(DIRT, new Block(DIRT, "Dirt",
                BlockAtlas.DIRT_BOTTOM, BlockAtlas.DIRT_BOTTOM, BlockAtlas.DIRT_BOTTOM,
                "Dirt", "Dirt", "Dirt"));

        blocks.put(SAND, new Block(SAND, "Sand",
                BlockAtlas.SAND, BlockAtlas.SAND, BlockAtlas.SAND,
                "Sand", "Sand", "Sand"));

        blocks.put(GRAVEL, new Block(GRAVEL, "Gravel",
                BlockAtlas.GRAVEL, BlockAtlas.GRAVEL, BlockAtlas.GRAVEL,
                "Gravel", "Gravel", "Gravel"));

        blocks.put(LOG, new Block(LOG, "Log",
                BlockAtlas.LOG_TOP, BlockAtlas.LOG_SIDE, BlockAtlas.LOG_TOP,
                "Wood", "Wood", "Wood"));

        blocks.put(LEAVES, new Block(LEAVES, "Leaves",
                BlockAtlas.LEAVES, BlockAtlas.LEAVES, BlockAtlas.LEAVES,
                "Grass", "Grass", "Grass"));

        loadCoreXmlBlocks();
        loadModBlocks();
        loadZipMods();

        loaded = true;
        System.out.println("[BlockLoader] registered " + blocks.size() + " block(s)");
    }

    // ------------------------------------------------------------- core XML

    private static final String CORE_DIR = "/com/insanestudios/blockin/Blocks/";

    /**
     * Overlays the built-in blocks that ship as core block XMLs on the classpath
     * ({@code Blocks/<Name>/<Name>.xml} in the game jar). The hardcoded entries
     * above are the fallback when a core XML is missing or malformed, so ids
     * 1-4 stay stable.
     */
    private static void loadCoreXmlBlocks() {
        loadCoreXmlBlock("Stone/Stone.xml", STONE);
        loadCoreXmlBlock("Grass/Grass.xml", GRASS);
        loadCoreXmlBlock("Water/Water.xml", WATER);
        loadCoreXmlBlock("Dirt/Dirt.xml", DIRT);
        loadCoreXmlBlock("Sand/Sand.xml", SAND);
        loadCoreXmlBlock("Log/Log.xml", LOG);
        loadCoreXmlBlock("Leafs/Leafs.xml", LEAVES);
    }

    private static void loadCoreXmlBlock(String relPath, int expectedId) {
        String resource = CORE_DIR + relPath;
        int slash = relPath.indexOf('/');
        String folderName = slash >= 0 ? relPath.substring(0, slash) : relPath;
        String folderResource = CORE_DIR + folderName + "/";
        try (InputStream in = BlockLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                System.err.println("[BlockLoader] core xml missing: " + resource
                        + " (using hardcoded fallback)");
                return;
            }
            Document doc = parseXml(in);
            BlockXml x = parseBlockXml(doc);

            int id;
            try {
                id = Integer.parseInt(x.idRaw.trim());
            } catch (Exception ignored) {
                id = 0;
            }
            if (id != expectedId) {
                System.err.println("[BlockLoader] core xml " + relPath + " declares id=" + id
                        + " but expected " + expectedId + " — forcing core id");
                id = expectedId;
            }

            BlockSpec spec = new BlockSpec();
            spec.id = id;
            spec.name = x.name.isEmpty() ? folderName : x.name;
            spec.description = x.description;
            spec.blockType = x.blockType;
            spec.soundStep = x.soundStep;
            spec.soundPlace = x.soundPlace;
            spec.soundBreak = x.soundBreak;
            spec.tileTop = BlockAtlas.reserveResource(folderResource + x.top);
            spec.tileSide = BlockAtlas.reserveResource(folderResource + x.side);
            spec.tileBottom = BlockAtlas.reserveResource(folderResource + x.bottom);
            if (!x.icon.isEmpty()
                    && BlockLoader.class.getResource(folderResource + x.icon) != null) {
                spec.icon = BlockAtlas.load(folderResource + x.icon);
            }
            if (!x.java.isEmpty()) {
                System.err.println("[BlockLoader] core xml java files are not supported: " + x.java);
            }

            Block block = register(spec, null);
            blocks.put(id, block);
            System.out.println("[BlockLoader] core xml block id=" + id + " name=" + spec.name
                    + " type=" + block.blockType + " tiles(top=" + spec.tileTop
                    + ",side=" + spec.tileSide + ",bottom=" + spec.tileBottom + ")");
        } catch (Exception e) {
            System.err.println("[BlockLoader] failed loading core xml " + relPath
                    + " (using hardcoded fallback): " + e);
        }
    }

    // ------------------------------------------------------------- mod roots

    /**
     * Directory containing mods: prefers {@code -Dmc.modsDir=}, then walks up
     * from the working directory to the first folder named "Mods".
     */
    private static File findModsDir() {
        String override = System.getProperty("mc.modsDir");
        if (override != null) {
            File f = new File(override);
            if (f.isDirectory()) return f;
        }

        File dir = new File("").getAbsoluteFile();
        while (dir != null) {
            File mods = new File(dir, "Mods");
            if (mods.isDirectory()) return mods;
            dir = dir.getParentFile();
        }
        return new File("Mods");
    }

    /**
     * Block-mod roots: {@code Mods/Blocks} plus an optional top-level
     * {@code Blocks} folder next to {@code Mods} (deduplicated).
     */
    private static List<File> blockRoots() {
        List<File> roots = new ArrayList<>();
        File mods = findModsDir();
        File modsBlocks = new File(mods, "Blocks");
        if (modsBlocks.isDirectory()) roots.add(modsBlocks);
        File parent = mods.getParentFile();
        if (parent != null) {
            File top = new File(parent, "Blocks");
            if (top.isDirectory() && !samePath(roots, top)) roots.add(top);
        }
        return roots;
    }

    private static boolean samePath(List<File> roots, File candidate) {
        String c = candidate.getAbsolutePath();
        for (File r : roots) {
            if (r.getAbsolutePath().equalsIgnoreCase(c)) return true;
        }
        return false;
    }

    // ------------------------------------------------------- folder mods

    private static void loadModBlocks() {
        for (File root : blockRoots()) {
            File[] folders = root.listFiles(File::isDirectory);
            if (folders == null) continue;
            for (File folder : folders) {
                File xml = findBlockXml(folder);
                if (xml != null) {
                    loadXmlFolderBlock(folder, xml);
                    continue;
                }
                File propsFile = new File(folder, "block.properties");
                if (propsFile.isFile()) {
                    loadPropsFolderBlock(folder, propsFile);
                }
            }
        }
    }

    /** Prefers the folder-named xml, then block.xml, then any xml. */
    private static File findBlockXml(File folder) {
        File[] xmls = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".xml"));
        if (xmls == null || xmls.length == 0) return null;
        String base = folder.getName().toLowerCase();
        for (File f : xmls) {
            if (stripExt(f.getName()).toLowerCase().equals(base)) return f;
        }
        for (File f : xmls) {
            if (f.getName().equalsIgnoreCase("block.xml")) return f;
        }
        return xmls[0];
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    /** New-style block definition: {@code Blocks/<Name>/<Name>.xml}. */
    private static void loadXmlFolderBlock(File folder, File xmlFile) {
        try (InputStream in = new FileInputStream(xmlFile)) {
            Document doc = parseXml(in);
            BlockXml x = parseBlockXml(doc);

            BlockSpec spec = specFromXml(x, folder.getName());
            BufferedImage top = loadImage(new File(folder, x.top));
            BufferedImage side = loadImage(new File(folder, x.side));
            BufferedImage bottom = loadImage(new File(folder, x.bottom));
            spec.tileTop = BlockAtlas.reserve(top != null ? top : side);
            spec.tileSide = BlockAtlas.reserve(side != null ? side : top);
            spec.tileBottom = BlockAtlas.reserve(bottom != null ? bottom : side);

            File iconFile = new File(folder, x.icon);
            if (iconFile.isFile()) spec.icon = loadImage(iconFile);

            File javaFile = x.java.isEmpty() ? null : new File(folder, x.java);
            Block block = register(spec, javaFile);
            blocks.put(spec.id, block);
            System.out.println("[BlockLoader] xml block id=" + spec.id + " name=" + spec.name
                    + " type=" + block.blockType + " tiles(top=" + spec.tileTop
                    + ",side=" + spec.tileSide + ",bottom=" + spec.tileBottom + ")"
                    + (javaFile != null ? " java=" + javaFile.getName() : ""));
        } catch (Exception e) {
            System.err.println("[BlockLoader] failed loading " + folder.getName() + ": " + e);
        }
    }

    /** Legacy {@code block.properties} folder mod (kept for compatibility). */
    private static void loadPropsFolderBlock(File folder, File propsFile) {
        try {
            Properties p = new Properties();
            try (InputStream in = new FileInputStream(propsFile)) {
                p.load(in);
            }

            String name = p.getProperty("name", folder.getName());
            int id = allocateId(p.getProperty("id", ""));

            BufferedImage top = loadImage(new File(folder, p.getProperty("top", "top.png")));
            BufferedImage side = loadImage(new File(folder, p.getProperty("side", "side.png")));
            BufferedImage bottom = loadImage(new File(folder, p.getProperty("bottom", "bottom.png")));

            int tileTop = BlockAtlas.reserve(top != null ? top : side);
            int tileSide = BlockAtlas.reserve(side != null ? side : top);
            int tileBottom = BlockAtlas.reserve(bottom != null ? bottom : side);

            File iconFile = null;
            File icon = new File(folder, p.getProperty("icon", "icon.png"));
            if (icon.isFile()) iconFile = icon;

            String stepSound = p.getProperty("sound.step", "Stone");
            String placeSound = p.getProperty("sound.place", "Stone");
            String breakSound = p.getProperty("sound.break", "Stone");

            Block block = new Block(id, name, name, iconFile,
                    tileTop, tileSide, tileBottom,
                    stepSound, placeSound, breakSound);
            blocks.put(id, block);
            System.out.println("[BlockLoader] folder mod block id=" + id
                    + " name=" + name + " tiles(top=" + tileTop
                    + ",side=" + tileSide + ",bottom=" + tileBottom + ")");
        } catch (IOException e) {
            System.err.println("[BlockLoader] failed loading " + folder.getName()
                    + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------- zip mods

    private static void loadZipMods() {
        File modsDir = findModsDir();
        File[] zips = modsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".zip"));
        if (zips == null) return;

        for (File zipFile : zips) {
            try (ZipFile zip = new ZipFile(zipFile)) {
                String modName = zipFile.getName();
                String infoPath = findInfoXml(zip);
                Document info = null;

                if (infoPath != null) {
                    ZipEntry infoEntry = findEntry(zip, infoPath);
                    if (infoEntry != null) {
                        try (InputStream in = zip.getInputStream(infoEntry)) {
                            info = parseXml(in);
                        }
                    }
                    if (info != null) {
                        modName = childText(info, "name", zipFile.getName());
                        System.out.println("[BlockLoader] zip mod: " + modName
                                + " v" + childText(info, "version", "?")
                                + " (max game " + childText(info, "maxVersion", "?") + ")");
                    }
                } else {
                    System.out.println("[BlockLoader] zip mod (no info.xml): " + zipFile.getName());
                }

                loadZipBlocks(zip, info, zipFile);
            } catch (Exception e) {
                System.err.println("[BlockLoader] failed to read zip " + zipFile.getName()
                        + ": " + e.getMessage());
            }
        }
    }

    private static String findInfoXml(ZipFile zip) {
        Enumeration<? extends ZipEntry> en = zip.entries();
        String fallback = null;
        while (en.hasMoreElements()) {
            String name = en.nextElement().getName();
            if (normalizeZipPath(name).equalsIgnoreCase("info.xml")) return name;
            if (name.endsWith(".xml")
                    && normalizeZipPath(name).toLowerCase().endsWith("/info.xml")) fallback = name;
        }
        return fallback;
    }

    private static void loadZipBlocks(ZipFile zip, Document info, File zipFile) {
        List<String> paths = blockXmlPaths(info);
        if (paths.isEmpty()) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                String name = en.nextElement().getName();
                if (normalizeZipPath(name).toLowerCase().endsWith("block.xml")) {
                    paths.add(name);
                }
            }
        }
        if (paths.isEmpty()) {
            System.err.println("[BlockLoader] zip mod contains no block.xml entries");
            return;
        }
        for (String path : paths) loadZipBlock(zip, path, zipFile);
        System.out.println("[BlockLoader] loaded " + paths.size() + " block(s) from zip");
    }

    private static List<String> blockXmlPaths(Document info) {
        List<String> paths = new ArrayList<>();
        if (info == null) return paths;

        NodeList blocks = info.getElementsByTagName("block");
        for (int i = 0; i < blocks.getLength(); i++) {
            Element e = (Element) blocks.item(i);
            String file = e.getAttribute("file");
            if (file == null || file.isEmpty()) file = e.getTextContent();
            String p = normalizeZipPath(file.trim());
            if (p.isEmpty()) continue;
            paths.add(p.toLowerCase().endsWith(".xml") ? p : p + "/block.xml");
        }
        return paths;
    }

    private static String normalizeZipPath(String p) {
        if (p == null) return "";
        return p.replace('\\', '/').replaceAll("^/+", "");
    }

    private static Document parseXml(InputStream in) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(in);
    }

    private static String childText(Document doc, String tag, String fallback) {
        if (doc == null) return fallback;
        NodeList list = doc.getElementsByTagName(tag);
        if (list.getLength() == 0) return fallback;
        String text = list.item(0).getTextContent();
        return (text == null || text.trim().isEmpty()) ? fallback : text.trim();
    }

    /** New-style block definition inside a zip (may carry a {@code <java>} entry). */
    private static void loadZipBlock(ZipFile zip, String xmlPath, File zipFile) {
        xmlPath = normalizeZipPath(xmlPath);
        try {
            ZipEntry xmlEntry = findEntry(zip, xmlPath);
            if (xmlEntry == null) {
                System.err.println("[BlockLoader] Could not find zip entry for " + xmlPath);
                return;
            }

            Document doc;
            try (InputStream in = zip.getInputStream(xmlEntry)) {
                doc = parseXml(in);
            }

            int slash = xmlPath.lastIndexOf('/');
            String folder = slash >= 0 ? xmlPath.substring(0, slash) : "";
            String fallbackName = folder.isEmpty() ? "Unknown" : folder.substring(folder.lastIndexOf('/') + 1);

            BlockXml x = parseBlockXml(doc);
            BlockSpec spec = specFromXml(x, fallbackName);
            spec.tileTop = BlockAtlas.reserve(zipImage(spec, zip, folder, x.top));
            spec.tileSide = BlockAtlas.reserve(zipImage(spec, zip, folder, x.side));
            spec.tileBottom = BlockAtlas.reserve(zipImage(spec, zip, folder, x.bottom));
            spec.icon = loadZipImage(zip, folder, x.icon);

            File javaFile = null;
            if (!x.java.isEmpty()) {
                javaFile = extractZipJava(zip, folder, x.java, zipFile);
            }

            Block block = register(spec, javaFile);
            blocks.put(spec.id, block);
            System.out.println("[BlockLoader] zip block id=" + spec.id + " name=" + spec.name
                    + " type=" + block.blockType + " tiles(top=" + spec.tileTop
                    + ",side=" + spec.tileSide + ",bottom=" + spec.tileBottom + ")"
                    + (javaFile != null ? " java=" + x.java : ""));
        } catch (Exception e) {
            System.err.println("[BlockLoader] failed loading zip block " + xmlPath
                    + ": " + e.getMessage());
        }
    }

    /** Extracts the mod's java file from a zip to a scratch folder for compilation. */
    private static File extractZipJava(ZipFile zip, String folder, String javaName, File zipFile) {
        String full = javaName.replace('\\', '/');
        if (!full.startsWith("/") && !folder.isEmpty()) {
            String javaPath = normalizeZipPath(full);
            if (!javaPath.toLowerCase().startsWith(normalizeZipPath(folder).toLowerCase() + "/")) {
                full = folder + "/" + javaName;
            }
        }
        full = normalizeZipPath(full.replaceFirst("^/+", ""));
        ZipEntry entry = findEntry(zip, full);
        if (entry == null) {
            System.err.println("[BlockLoader] <java> entry not found in zip: " + full);
            return null;
        }
        String base = zipFile.getName();
        int idx = base.lastIndexOf('.');
        if (idx >= 0) base = base.substring(0, idx);
        String uid = base.toLowerCase().replaceAll("[^a-z0-9]", "_")
                + "_" + Integer.toHexString(zipFile.getName().hashCode() & 0xFFFFFF);
        String javaBase = javaName.replace('\\', '/');
        int slash = javaBase.lastIndexOf('/');
        if (slash >= 0) javaBase = javaBase.substring(slash + 1);
        try {
            File tmp = new File(System.getProperty("java.io.tmpdir"), "blockin-mods/" + uid);
            if (!tmp.isDirectory() && !tmp.mkdirs()) return null;
            File target = new File(tmp, javaBase);
            try (InputStream in = zip.getInputStream(entry);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
                in.transferTo(out);
            }
            return target;
        } catch (IOException e) {
            System.err.println("[BlockLoader] failed extracting java from zip: " + e);
            return null;
        }
    }

    private static BufferedImage zipImage(BlockSpec spec, ZipFile zip, String folder, String name) {
        BufferedImage img = loadZipImage(zip, folder, name);
        if (img == null && spec.tileSide >= 0) return null;
        return img;
    }

    private static BufferedImage loadZipImage(ZipFile zip, String folder, String file) {
        if (file == null || file.trim().isEmpty()) return null;

        file = file.trim().replace('\\', '/');
        String fullPath;
        if (file.startsWith("/")) {
            fullPath = file.substring(1);
        } else if (!folder.isEmpty()) {
            fullPath = folder + "/" + file;
        } else {
            fullPath = file;
        }

        fullPath = normalizeZipPath(fullPath);
        ZipEntry entry = findEntry(zip, fullPath);
        if (entry == null) return null;

        try (InputStream in = zip.getInputStream(entry)) {
            return ImageIO.read(in);
        } catch (IOException e) {
            System.err.println("[BlockLoader] failed reading image entry " + fullPath + ": " + e.getMessage());
            return null;
        }
    }

    private static ZipEntry findEntry(ZipFile zip, String normalizedPath) {
        ZipEntry entry = zip.getEntry(normalizedPath);
        if (entry != null) return entry;

        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (normalizeZipPath(e.getName()).equalsIgnoreCase(normalizedPath)) return e;
        }
        return null;
    }

    // ------------------------------------------------------- shared helpers

    /** Raw field values parsed from a block XML (new schema, zip or folder). */
    private static BlockXml parseBlockXml(Document doc) {
        BlockXml x = new BlockXml();
        x.name = childText(doc, "name", "");
        x.description = childText(doc, "description", "");
        x.blockType = sanitizeType(childText(doc, "type", "solid"));
        x.texture = childText(doc, "texture", "").trim();
        x.top = childText(doc, "top", x.texture);
        x.side = childText(doc, "side", x.texture);
        x.bottom = childText(doc, "bottom", x.texture);
        if (x.top.isEmpty()) x.top = "top.png";
        if (x.side.isEmpty()) x.side = "side.png";
        if (x.bottom.isEmpty()) x.bottom = "bottom.png";
        x.icon = childText(doc, "icon", "icon.png");
        x.soundStep = childText(doc, "soundStep", "Stone");
        x.soundPlace = childText(doc, "soundPlace", "Stone");
        x.soundBreak = childText(doc, "soundBreak", "Stone");
        x.java = childText(doc, "java", "").trim();
        x.idRaw = childText(doc, "id", "");
        return x;
    }

    private static String sanitizeType(String raw) {
        String t = raw == null ? "" : raw.trim().toLowerCase();
        if (t.equals("transparent") || t.equals("liquid")) return t;
        return "solid";
    }

    /** Fills a spec from parsed XML text; texture files resolved by the caller. */
    private static BlockSpec specFromXml(BlockXml x, String fallbackName) {
        BlockSpec spec = new BlockSpec();
        spec.id = allocateId(x.idRaw);
        spec.name = x.name.isEmpty() ? fallbackName : x.name;
        spec.description = x.description;
        spec.blockType = x.blockType;
        spec.soundStep = x.soundStep;
        spec.soundPlace = x.soundPlace;
        spec.soundBreak = x.soundBreak;
        return spec;
    }

    /**
     * Registers a block: stock block for its type, or the compiled {@code java}
     * behavior when provided ({@code bin/} sits next to the source). Only fills
     * description/type fields that a custom factory left unset.
     */
    private static Block register(BlockSpec spec, File javaFile) {
        Block block = spec.build();
        if (javaFile != null && javaFile.isFile()) {
            File bin = new File(javaFile.getParentFile(), "bin");
            Block custom = BlockCodeCompiler.compile(javaFile, bin, spec);
            if (custom != null) block = custom;
        }
        if (block.description == null || block.description.isEmpty()) {
            block.description = spec.description;
        }
        if (block.blockType == null || block.blockType.isEmpty()) {
            block.blockType = spec.blockType;
        }
        return block;
    }

    private static int allocateId(String raw) {
        int id = 0;
        if (raw != null) {
            try {
                id = Integer.parseInt(raw.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (id <= 0 || blocks.containsKey(id)) {
            id = nextId;
        }
        while (blocks.containsKey(id)) {
            id++;
        }
        if (id >= nextId) {
            nextId = id + 1;
        }
        return id;
    }

    private static BufferedImage loadImage(File f) {
        if (f == null || !f.isFile()) return null;
        try {
            return ImageIO.read(f);
        } catch (IOException e) {
            return null;
        }
    }

    /** Raw parsed values of one block XML. */
    private static final class BlockXml {
        String name;
        String description;
        String blockType;
        String texture;
        String top;
        String side;
        String bottom;
        String icon;
        String soundStep;
        String soundPlace;
        String soundBreak;
        String java;
        String idRaw;
    }
}