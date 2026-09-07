package com.insanestudios.blockin.Models;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;

/**
 * Pure-Java FBX reader. Parses both the ASCII (7.x text) and binary (FBX
 * 6.x-7.x, little-endian) file formats into a common {@link FbxNode} tree,
 * then converts the mesh geometry into {@link Model} meshes for GL11.
 *
 * Supported: vertices, polygon (triangulated) indices, per-polygon-vertex and
 * per-control-point normals/UVs (Direct and IndexToDirect), materials, external
 * textures, and a per-mesh translation/rotation(Euler)/scale. Animation,
 * skinning/bones and embedded media are ignored.
 */
public final class FbxParser {

    private static final int BINARY_HEADER_LEN = 23;

    private FbxParser() {
    }

    public static FbxNode parse(File file) throws Exception {
        return parse(readAll(file));
    }

    static byte[] readAll(File file) throws Exception {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    public static FbxNode parse(byte[] data) {
        if (data.length > BINARY_HEADER_LEN && binaryHeader(data)) {
            return parseBinary(data);
        }
        return parseAscii(new String(data, StandardCharsets.UTF_8));
    }

    private static boolean binaryHeader(byte[] data) {
        String head = "Kaydara FBX Binary ";
        for (int i = 0; i < head.length(); i++) {
            if ((data[i] & 0xFF) != head.charAt(i)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Binary format (FBX 6.x / 7.x, little-endian records)
    // ------------------------------------------------------------------

    private static FbxNode parseBinary(byte[] data) {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int[] p = {BINARY_HEADER_LEN + 1};
        FbxNode root = new FbxNode("__root__");
        while (p[0] + 13 <= data.length && bb.getInt(p[0]) > 0) {
            readBinaryNode(bb, p, root);
        }
        return root;
    }

    private static void readBinaryNode(ByteBuffer bb, int[] p, FbxNode parent) {
        int endOffset = bb.getInt(p[0]);
        int numProps = bb.getInt(p[0] + 4);
        int propBytes = bb.getInt(p[0] + 8);
        p[0] += 12;
        int nameLen = bb.get(p[0]) & 0xFF;
        p[0]++;
        String name = ascii(bb, p[0], nameLen);
        p[0] += nameLen;
        if (endOffset <= 0 || endOffset > bb.capacity()) endOffset = bb.capacity();
        FbxNode node = new FbxNode(name);
        parent.children.add(node);
        int propEnd = Math.min(p[0] + propBytes, endOffset);
        for (int i = 0; i < numProps && p[0] < propEnd; i++) {
            node.props.add(readBinaryProp(bb, p, propEnd));
        }
        p[0] = propEnd;
        while (p[0] + 13 < endOffset) {
            readBinaryNode(bb, p, node);
        }
        p[0] = endOffset;
    }

    private static Object readBinaryProp(ByteBuffer bb, int[] p, int limit) {
        if (p[0] >= limit || limit - p[0] < 1) return null;
        char t = (char) bb.get(p[0]);
        p[0]++;
        switch (t) {
            case 'Y': p[0] += 2; return (double) (short) bb.getShort(p[0] - 2);
            case 'C': p[0] += 1; return (double) ((bb.get(p[0] - 1) & 0xFF) != 0 ? 1 : 0);
            case 'I': p[0] += 4; return (double) bb.getInt(p[0] - 4);
            case 'F': p[0] += 4; return (double) bb.getFloat(p[0] - 4);
            case 'D': p[0] += 8; return bb.getDouble(p[0] - 8);
            case 'L': p[0] += 8; return (double) bb.getLong(p[0] - 8);
            case 'R': {
                int len = bb.getInt(p[0]);
                p[0] += 4;
                byte[] raw = new byte[len];
                bb.position(p[0]);
                bb.get(raw);
                p[0] += len;
                return raw;
            }
            case 'S': {
                int len = bb.getInt(p[0]);
                p[0] += 4;
                String s = ascii(bb, p[0], len);
                p[0] += len;
                return s;
            }
            case '[': {
                char et = (char) bb.get(p[0]);
                p[0]++;
                int count = bb.getInt(p[0]);
                int encoding = bb.getInt(p[0] + 4);
                int compLen = bb.getInt(p[0] + 8);
                p[0] += 12;
                byte[] raw = new byte[compLen];
                bb.position(p[0]);
                bb.get(raw);
                p[0] += compLen;
                byte[] arr = encoding == 1 ? inflate(raw, count * elementSize(et)) : raw;
                ByteBuffer ab = ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN);
                switch (et) {
                    case 'f': {
                        double[] r = new double[count];
                        for (int i = 0; i < count; i++) r[i] = ab.getFloat(i * 4);
                        return r;
                    }
                    case 'd': {
                        double[] r = new double[count];
                        for (int i = 0; i < count; i++) r[i] = ab.getDouble(i * 8);
                        return r;
                    }
                    case 'i': {
                        int[] r = new int[count];
                        for (int i = 0; i < count; i++) r[i] = ab.getInt(i * 4);
                        return r;
                    }
                    case 'l': {
                        double[] r = new double[count];
                        for (int i = 0; i < count; i++) r[i] = (double) ab.getLong(i * 8);
                        return r;
                    }
                    case 'c':
                    case 'b': {
                        int[] r = new int[count];
                        for (int i = 0; i < count; i++) r[i] = ab.get(i) & 0xFF;
                        return r;
                    }
                    default:
                        return null;
                }
            }
            default:
                return null;
        }
    }

    private static int elementSize(char t) {
        switch (t) {
            case 'd': case 'l': return 8;
            case 'f': case 'i': return 4;
            default: return 1;
        }
    }

    private static byte[] inflate(byte[] in, int expected) {
        try {
            Inflater inf = new Inflater();
            inf.setInput(in);
            byte[] out = new byte[expected];
            int n = inf.inflate(out);
            inf.end();
            if (n == expected) return out;
            byte[] trim = new byte[n];
            System.arraycopy(out, 0, trim, 0, n);
            return trim;
        } catch (Exception ex) {
            return new byte[expected];
        }
    }

    private static String ascii(ByteBuffer bb, int off, int len) {
        len = Math.max(0, Math.min(len, bb.capacity() - off));
        byte[] b = new byte[len];
        bb.position(off);
        bb.get(b);
        return new String(b, StandardCharsets.US_ASCII);
    }

    // ------------------------------------------------------------------
    // ASCII format (FBX 7.x text)
    // ------------------------------------------------------------------

    private static FbxNode parseAscii(String text) {
        FbxNode root = new FbxNode("__root__");
        Deque<FbxNode> stack = new ArrayDeque<>();
        stack.push(root);
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith(";")) continue;
            if (line.equals("}")) {
                if (stack.size() > 1) stack.pop();
                continue;
            }
            if (line.startsWith("a:")) { // array continuation lines
                parseTokens(line.substring(2), stack.peek().props);
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim();
            String rest = line.substring(colon + 1).trim();
            boolean hasChildren = rest.endsWith("{");
            if (hasChildren) rest = rest.substring(0, rest.length() - 1).trim();
            FbxNode node = new FbxNode(name);
            stack.peek().children.add(node);
            parseTokens(rest, node.props);
            if (hasChildren) stack.push(node);
        }
        return root;
    }

    private static void parseTokens(String s, List<Object> dest) {
        for (String part : s.split(",")) {
            String token = part.trim();
            if (token.isEmpty() || token.matches("\\*\\d+")) continue;
            if (token.matches("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?")) {
                dest.add(Double.parseDouble(token));
            } else {
                dest.add(stripQuotes(token));
            }
        }
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // ------------------------------------------------------------------
    // Scene wiring: objects + connections
    // ------------------------------------------------------------------

    private static final class Scene {
        final List<FbxNode> geometries = new ArrayList<>();
        final List<FbxNode> models = new ArrayList<>();
        final List<FbxNode> materials = new ArrayList<>();
        final Map<Long, FbxNode> materialById = new HashMap<>();
        final Map<Long, FbxNode> textureById = new HashMap<>();
        final Map<Long, Long> ownerModelByGeom = new HashMap<>(); // geometry id -> model id
        final Map<Long, List<Long>> materialsByModel = new HashMap<>(); // model id -> material ids
        final Map<Long, String> textureByMaterial = new HashMap<>(); // material id -> texture file

        // --- bones / skinning ---
        final Map<Long, FbxNode> deformerById = new HashMap<>();   // deformer id -> node
        final Map<Long, String> deformerTypeById = new HashMap<>(); // deformer id -> "Skin"/"Cluster"
        final Map<Long, Long> skinByGeom = new HashMap<>();         // geometry id -> skin deformer id
        final Map<Long, List<Long>> clustersBySkin = new HashMap<>(); // skin id -> cluster ids
        final Map<Long, Long> boneByCluster = new HashMap<>();      // cluster id -> bone model id
        final Map<Long, Long> boneParent = new HashMap<>();         // bone model id -> parent bone id
        final Map<Long, String> modelNameById = new HashMap<>();    // object id -> name
    }

    private static Scene gather(FbxNode root) {
        Scene s = new Scene();
        FbxNode objects = root.child("Objects");
        if (objects != null) {
            for (FbxNode c : objects.children) {
                if ("Geometry".equals(c.name)) s.geometries.add(c);
                else if ("Model".equals(c.name)) {
                    s.models.add(c);
                    String nm = c.string();
                    if (nm != null) s.modelNameById.put(idOf(c), nm);
                } else if ("Material".equals(c.name)) {
                    s.materials.add(c);
                    s.materialById.put(idOf(c), c);
                } else if ("Texture".equals(c.name)) {
                    s.textureById.put(idOf(c), c);
                } else if ("SubDeformer".equals(c.name) || "Deformer".equals(c.name)) {
                    s.deformerById.put(idOf(c), c);
                    String ty = deformerType(c);
                    if (ty != null) s.deformerTypeById.put(idOf(c), ty);
                }
            }
        }
        FbxNode conns = root.child("Connections");
        if (conns != null) {
            for (FbxNode c : conns.children) {
                if (!"C".equals(c.name) || c.props.size() < 3) continue;
                String kind = strOf(c.props.get(0));
                if (kind == null) continue;
                long child = numOf(c.props.get(1));
                long parent = numOf(c.props.get(2));
                if ("OO".equals(kind)) {
                    if (s.textureById.containsKey(child)) {
                        String tex = texName(s.textureById.get(child));
                        if (tex != null) s.textureByMaterial.put(parent, tex);
                        continue;
                    }
                    String childType = s.deformerTypeById.get(child);
                    String parentType = s.deformerTypeById.get(parent);
                    if ("Cluster".equals(parentType)) { // bone model under a cluster
                        s.boneByCluster.put(parent, child);
                        continue;
                    }
                    if ("Skin".equals(childType)) {      // skin -> geometry
                        for (FbxNode g : s.geometries) {
                            if (idOf(g) == parent) {
                                s.skinByGeom.put(parent, child);
                                break;
                            }
                        }
                    }
                    if ("Cluster".equals(childType)) {   // cluster -> skin
                        s.clustersBySkin.computeIfAbsent(parent, k -> new ArrayList<>()).add(child);
                        continue;
                    }
                    if ("Skin".equals(parentType)) {     // cluster (child) -> skin (parent), alt order
                        if (s.clustersBySkin.containsKey(parent)) continue;
                        s.clustersBySkin.computeIfAbsent(parent, k -> new ArrayList<>()).add(child);
                        continue;
                    }
                    boolean childIsModel = false;
                    for (FbxNode mo : s.models) {
                        if (idOf(mo) == child) { childIsModel = true; break; }
                    }
                    boolean parentIsModel = false;
                    for (FbxNode mo : s.models) {
                        if (idOf(mo) == parent) { parentIsModel = true; break; }
                    }
                    if (childIsModel && parentIsModel) {
                        s.boneParent.put(child, parent);
                        continue;
                    }
                    for (FbxNode g : s.geometries) {
                        if (idOf(g) == child) {
                            s.ownerModelByGeom.put(child, parent);
                            break;
                        }
                    }
                    if (parentIsModel && s.materialById.containsKey(child)) {
                        s.materialsByModel.computeIfAbsent(parent, k -> new ArrayList<>()).add(child);
                    }
                } else if ("OP".equals(kind) && s.textureById.containsKey(child)) {
                    FbxNode tex = s.textureById.get(child);
                    if (texName(tex) != null) {
                        s.textureByMaterial.put(parent, texName(tex));
                    }
                }
            }
        }
        return s;
    }

    private static String texName(FbxNode tex) {
        FbxNode fn = tex.child("FileName");
        String v = fn != null ? fn.string() : null;
        if (v != null && !v.isEmpty()) return v;
        FbxNode rf = tex.child("RelativeFilename");
        return rf != null ? rf.string() : null;
    }

    /** Deformer type: "Skin", "Cluster", "Morph"... from props or a "Type" child. */
    private static String deformerType(FbxNode node) {
        if (node.props.size() > 2) {
            Object t = node.props.get(2);
            if (t instanceof String s) return s;
        }
        for (FbxNode c : node.children) {
            if ("Type".equals(c.name)) {
                String s = c.string();
                if (s != null) return s;
            }
        }
        return null;
    }

    private static double[] modelTransform(FbxNode model) {
        double[] t = {0, 0, 0, 0, 0, 0, 1, 1, 1};
        if (model == null) return t;
        FbxNode props70 = model.child("Properties70");
        if (props70 == null) return t;
        for (FbxNode p : props70.children) {
            String pname = p.string();
            if (pname == null) continue;
            double[] v = pNumbers(p);
            if (v.length < 3) continue;
            if (pname.equals("Lcl Translation")) { t[0] = v[0]; t[1] = v[1]; t[2] = v[2]; }
            else if (pname.equals("Lcl Rotation")) { t[3] = v[0]; t[4] = v[1]; t[5] = v[2]; }
            else if (pname.equals("Lcl Scaling")) { t[6] = v[0]; t[7] = v[1]; t[8] = v[2]; }
        }
        return t;
    }

    private static double[] pNumbers(FbxNode p) {
        List<Double> acc = new ArrayList<>();
        for (Object o : p.props) {
            if (o instanceof Number n) acc.add(n.doubleValue());
        }
        double[] out = new double[acc.size()];
        for (int i = 0; i < out.length; i++) out[i] = acc.get(i);
        return out;
    }

    // ------------------------------------------------------------------
    // Mesh extraction
    // ------------------------------------------------------------------

    public static Model toModel(FbxNode root, File dir) {
        Scene s = gather(root);
        if (s.geometries.isEmpty()) {
            throw new IllegalArgumentException("FBX contains no mesh geometry");
        }
        int totalTris = 0;
        for (FbxNode g : s.geometries) totalTris += countTriangles(g);
        Model m = new Model(totalTris);

        int triBase = 0;
        FbxNode first = s.geometries.get(0);
        boolean firstSkin = s.skinByGeom.containsKey(idOf(first));
        for (int gi = 0; gi < s.geometries.size(); gi++) {
            FbxNode g = s.geometries.get(gi);
            FbxNode model = null;
            Long owner = s.ownerModelByGeom.get(idOf(g));
            if (owner != null) {
                for (FbxNode mo : s.models) {
                    if (idOf(mo) == owner) { model = mo; break; }
                }
            }
            // Only the first geometry can carry the skin rig for now.
            triBase = fillGeometry(g, modelTransform(model), m, triBase, gi == 0 && firstSkin);
        }
        if (m.skinNormals != null) normalizeBindNormals(m);
        if (firstSkin) {
            long geomId = idOf(first);
            FbxNode model = null;
            Long owner = s.ownerModelByGeom.get(geomId);
            if (owner != null) {
                for (FbxNode mo : s.models) {
                    if (idOf(mo) == owner) { model = mo; break; }
                }
            }
            buildSkin(s, geomId, modelTransform(model), m);
        }

        double[] tint = null;
        for (List<Long> mats : s.materialsByModel.values()) {
            for (long mid : mats) {
                FbxNode mat = s.materialById.get(mid);
                if (mat == null) continue;
                FbxNode dc = mat.child("DiffuseColor");
                if (dc != null) {
                    double[] d = dc.doubles();
                    if (d.length >= 3) { tint = new double[]{d[0], d[1], d[2]}; break; }
                }
            }
            if (tint != null) break;
        }
        if (tint == null) {
            for (FbxNode mat : s.materials) {
                FbxNode dc = mat.child("DiffuseColor");
                if (dc != null) {
                    double[] d = dc.doubles();
                    if (d.length >= 3) { tint = new double[]{d[0], d[1], d[2]}; break; }
                }
            }
        }
        if (tint != null) {
            m.r = (float) tint[0];
            m.g = (float) tint[1];
            m.b = (float) tint[2];
        }

        String texFile = null;
        for (String v : s.textureByMaterial.values()) {
            if (v != null) { texFile = v; break; }
        }
        if (texFile != null) {
            try {
                m.tex = ModelLoader.loadTexture(resolveTexturePath(texFile), dir);
            } catch (Exception ignore) {
                m.tex = -1;
            }
        }
        return m;
    }

    private static String resolveTexturePath(String file) {
        String p = file;
        if (p.startsWith("//") || p.startsWith("\\\\")) p = p.substring(2);
        int back = p.lastIndexOf('\\');
        if (back > 0) p = p.substring(back + 1); // keep just the filename to search next to fbx
        return p;
    }

    private static int countTriangles(FbxNode geom) {
        int[] pi = childInts(geom, "PolygonVertexIndex");
        int tris = 0;
        int i = 0;
        while (i < pi.length) {
            int start = i;
            while (i < pi.length && pi[i] >= 0) i++;
            if (i >= pi.length) break;
            int size = i - start + 1;
            tris += Math.max(0, size - 2);
            i++;
        }
        return tris;
    }

    private static int fillGeometry(FbxNode geom, double[] modelT, Model m, int triBase, boolean skin) {
        double[] verts = childNums(geom, "Vertices");
        int[] pi = childInts(geom, "PolygonVertexIndex");
        if (verts.length == 0 || pi.length == 0) return triBase;
        int nControl = verts.length / 3;

        FbxNode normalLayer = firstLayer(geom, "LayerElementNormal");
        FbxNode uvLayer = firstLayer(geom, "LayerElementUV");

        double[] normals = normalLayer == null ? new double[0] : childNums(normalLayer, "Normals");
        int[] normalIdx = normalLayer == null ? null : childInts(normalLayer, "NormalsIndex");
        String nMap = normalLayer == null ? null : childString(normalLayer, "MappingInformationType");
        String nRef = normalLayer == null ? null : childString(normalLayer, "ReferenceInformationType");
        boolean hasNormals = normals.length > 0;

        double[] uvs = uvLayer == null ? new double[0] : childNums(uvLayer, "UV");
        int[] uvIdx = uvLayer == null ? null : childInts(uvLayer, "UVIndex");
        String uMap = uvLayer == null ? null : childString(uvLayer, "MappingInformationType");
        String uRef = uvLayer == null ? null : childString(uvLayer, "ReferenceInformationType");
        boolean hasUvs = uvs.length > 0;

        // Polygon runs: {startIndexInPi, vertexCount, streamOffset}
        List<int[]> polys = new ArrayList<>();
        int off = 0;
        int i = 0;
        while (i < pi.length) {
            int start = i;
            while (i < pi.length && pi[i] >= 0) i++;
            if (i >= pi.length) break;
            int size = i - start + 1;
            polys.add(new int[]{start, size, off});
            off += size;
            i++;
        }

        boolean directCtrlNormals = skin && hasNormals && "ByControlPoint".equals(nMap);
        if (skin) {
            m.skinVertCount = nControl;
            m.skinVerts = new float[nControl * 3];
            for (int c = 0; c < nControl; c++) {
                m.skinVerts[c * 3] = (float) verts[c * 3];
                m.skinVerts[c * 3 + 1] = (float) verts[c * 3 + 1];
                m.skinVerts[c * 3 + 2] = (float) verts[c * 3 + 2];
            }
            m.skinNormals = new float[nControl * 3];
            if (directCtrlNormals) {
                for (int c = 0; c < nControl; c++) {
                    float[] nn = unit(valsAt3(normals, c));
                    m.skinNormals[c * 3] = nn[0];
                    m.skinNormals[c * 3 + 1] = nn[1];
                    m.skinNormals[c * 3 + 2] = nn[2];
                }
            }
            int corners = 0;
            for (int[] poly : polys) corners += Math.max(0, poly[1] - 2) * 3;
            m.cornerCtrl = new int[corners];
        }

        float[] tx = new float[nControl * 3];
        for (int c = 0; c < nControl; c++) {
            float[] pp = transformPoint(modelT, verts[c * 3], verts[c * 3 + 1], verts[c * 3 + 2]);
            tx[c * 3] = pp[0];
            tx[c * 3 + 1] = pp[1];
            tx[c * 3 + 2] = pp[2];
        }

        int tri = triBase;
        for (int[] poly : polys) {
            int start = poly[0];
            int size = poly[1];
            int streamOff = poly[2];
            for (int k = 1; k + 1 < size; k++) {
                int[] corners = {0, k, k + 1};
                float[] faceV = new float[9];
                int[] gv = new int[3];
                for (int j = 0; j < 3; j++) {
                    int raw = pi[start + corners[j]];
                    if (corners[j] == size - 1) raw = -raw - 1;
                    int v = Math.abs(raw);
                    if (v >= nControl) v = 0;
                    gv[j] = v;
                    faceV[j * 3] = tx[v * 3];
                    faceV[j * 3 + 1] = tx[v * 3 + 1];
                    faceV[j * 3 + 2] = tx[v * 3 + 2];
                }
                float[] skinFn = (skin && !directCtrlNormals) ? faceNormal(faceV) : null;
                int base = tri * 9;
                for (int j = 0; j < 3; j++) {
                    int v = gv[j];
                    m.verts[base + j * 3] = tx[v * 3];
                    m.verts[base + j * 3 + 1] = tx[v * 3 + 1];
                    m.verts[base + j * 3 + 2] = tx[v * 3 + 2];
                    if (skin) {
                        m.cornerCtrl[(tri - triBase) * 3 + j] = v;
                        if (!directCtrlNormals) {
                            m.skinNormals[v * 3] += skinFn[0];
                            m.skinNormals[v * 3 + 1] += skinFn[1];
                            m.skinNormals[v * 3 + 2] += skinFn[2];
                        }
                    }
                }
                if (hasNormals) {
                    for (int j = 0; j < 3; j++) {
                        float[] nn = normalAt(normals, normalIdx, nMap, nRef, streamOff, corners[j], gv[j]);
                        m.normals[base + j * 3] = nn[0];
                        m.normals[base + j * 3 + 1] = nn[1];
                        m.normals[base + j * 3 + 2] = nn[2];
                    }
                } else {
                    float[] fn = faceNormal(faceV);
                    for (int j = 0; j < 3; j++) {
                        m.normals[base + j * 3] = fn[0];
                        m.normals[base + j * 3 + 1] = fn[1];
                        m.normals[base + j * 3 + 2] = fn[2];
                    }
                }
                if (hasUvs) {
                    int ub = tri * 6;
                    for (int j = 0; j < 3; j++) {
                        float[] uv = uvAt(uvs, uvIdx, uMap, uRef, streamOff, corners[j], gv[j]);
                        m.uvs[ub + j * 2] = uv[0];
                        m.uvs[ub + j * 2 + 1] = uv[1];
                    }
                }
                tri++;
            }
        }
        return tri;
    }

    private static FbxNode firstLayer(FbxNode geom, String prefix) {
        for (FbxNode c : geom.children) {
            if (c.name.startsWith(prefix)) return c;
        }
        return null;
    }

    private static float[] normalAt(double[] vals, int[] idx, String map, String ref,
                                    int streamOff, int local, int vertexIndex) {
        int gi = "ByControlPoint".equals(map) || "ByVertice".equals(map) ? vertexIndex : streamOff + local;
        if ("IndexToDirect".equals(ref) && idx != null && idx.length > 0) {
            int i = Math.min(Math.max(0, gi), idx.length - 1);
            gi = idx[i];
        }
        gi = Math.max(0, Math.min(gi * 3, vals.length - 3));
        double x = vals[gi], y = vals[gi + 1], z = vals[gi + 2];
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-9) return new float[]{0, 1, 0};
        return new float[]{(float) (x / len), (float) (y / len), (float) (z / len)};
    }

    private static float[] uvAt(double[] vals, int[] idx, String map, String ref,
                                int streamOff, int local, int vertexIndex) {
        int gi = "ByControlPoint".equals(map) || "ByVertice".equals(map) ? vertexIndex : streamOff + local;
        if ("IndexToDirect".equals(ref) && idx != null && idx.length > 0) {
            int i = Math.min(Math.max(0, gi), idx.length - 1);
            gi = idx[i];
        }
        gi = Math.max(0, Math.min(gi * 2, vals.length - 2));
        return new float[]{(float) vals[gi], (float) vals[gi + 1]};
    }

    private static float[] faceNormal(float[] v) {
        float ux = v[3] - v[0], uy = v[4] - v[1], uz = v[5] - v[2];
        float wx = v[6] - v[0], wy = v[7] - v[1], wz = v[8] - v[2];
        float nx = uy * wz - uz * wy;
        float ny = uz * wx - ux * wz;
        float nz = ux * wy - uy * wx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-6f) return new float[]{0, 1, 0};
        return new float[]{nx / len, ny / len, nz / len};
    }

    private static float[] unit(float[] v) {
        float len = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len < 1e-6f) return new float[]{0, 1, 0};
        return new float[]{v[0] / len, v[1] / len, v[2] / len};
    }

    private static float[] valsAt3(double[] a, int i) {
        return new float[]{(float) a[i], (float) a[i + 1], (float) a[i + 2]};
    }

    private static void normalizeBindNormals(Model m) {
        for (int c = 0; c < m.skinVertCount; c++) {
            float[] u = unit(new float[]{m.skinNormals[c * 3], m.skinNormals[c * 3 + 1], m.skinNormals[c * 3 + 2]});
            m.skinNormals[c * 3] = u[0];
            m.skinNormals[c * 3 + 1] = u[1];
            m.skinNormals[c * 3 + 2] = u[2];
        }
    }

    // ------------------------------------------------------------------
    // Skinning rig built from Skin deformers and Cluster/SubDeformer nodes
    // ------------------------------------------------------------------

    private static void buildSkin(Scene s, long geomId, double[] modelT, Model m) {
        Long skinId = s.skinByGeom.get(geomId);
        if (skinId == null) return;
        List<Long> clusters = s.clustersBySkin.get(skinId);
        if (clusters == null || clusters.isEmpty()) return;

        List<Model.Bone> bones = new ArrayList<>();
        List<Long> boneIds = new ArrayList<>();
        Map<Long, Integer> boneIndex = new HashMap<>();

        for (long clusterId : clusters) {
            FbxNode c = s.deformerById.get(clusterId);
            if (c == null) continue;
            double[] tl = childNums(c, "TransformLink");
            if (tl == null || tl.length < 16) {
                System.out.println("[FbxParser] skin cluster missing TransformLink");
                continue;
            }
            Long boneId = s.boneByCluster.get(clusterId);
            if (boneId == null) continue;

            Model.Bone bone = new Model.Bone();
            bone.name = s.modelNameById.getOrDefault(boneId, "Bone" + bones.size());
            FbxNode boneModel = null;
            for (FbxNode mo : s.models) {
                if (idOf(mo) == boneId) { boneModel = mo; break; }
            }
            if (boneModel != null) {
                double[] t = modelTransform(boneModel);
                bone.px = (float) t[0];
                bone.py = (float) t[1];
                bone.pz = (float) t[2];
                bone.rx = (float) t[3];
                bone.ry = (float) t[4];
                bone.rz = (float) t[5];
                bone.sx = (float) t[6];
                bone.sy = (float) t[7];
                bone.sz = (float) t[8];
            }
            float[] bm = new float[16];
            for (int k = 0; k < 16; k++) bm[k] = (float) tl[k];
            bone.bind = MatOps.inv(bm);
            bones.add(bone);
            boneIds.add(boneId);
            boneIndex.put(boneId, bones.size() - 1);
        }
        if (bones.isEmpty()) return;

        for (int i = 0; i < bones.size(); i++) {
            Long pid = s.boneParent.get(boneIds.get(i));
            if (pid != null) {
                Integer pi = boneIndex.get(pid);
                if (pi != null) bones.get(i).parent = pi;
            }
        }

        m.bones = bones.toArray(new Model.Bone[0]);
        float[] gI = MatOps.rotRST((float) modelT[0], (float) modelT[1], (float) modelT[2],
                (float) modelT[3], (float) modelT[4], (float) modelT[5],
                (float) modelT[6], (float) modelT[7], (float) modelT[8]);
        m.globalInverse = MatOps.inv(gI);

        int n = m.skinVertCount;
        if (n <= 0) return;
        m.skinBones = new int[n * 4];
        m.skinWeights = new float[n * 4];
        java.util.Arrays.fill(m.skinBones, -1);

        for (int bi = 0; bi < bones.size(); bi++) {
            FbxNode c = s.deformerById.get(clusters.get(bi));
            if (c == null) continue;
            int[] idx = childInts(c, "Indexes");
            double[] w = childNums(c, "Weights");
            int lim = Math.min(idx.length, w.length);
            for (int j = 0; j < lim; j++) {
                int cv = idx[j];
                if (cv < 0 || cv >= n) continue;
                int slot = -1;
                for (int k = 0; k < 4; k++) {
                    if (m.skinBones[cv * 4 + k] == bi) { slot = k; break; }
                    if (m.skinBones[cv * 4 + k] == -1 && slot == -1) slot = k;
                }
                if (slot == -1) continue;
                m.skinBones[cv * 4 + slot] = bi;
                m.skinWeights[cv * 4 + slot] += (float) w[j];
            }
        }
        for (int cv = 0; cv < n; cv++) {
            float total = 0;
            for (int k = 0; k < 4; k++) total += m.skinWeights[cv * 4 + k];
            if (total <= 1e-9f) {
                for (int k = 0; k < 4; k++) m.skinBones[cv * 4 + k] = -1;
            } else if (Math.abs(total - 1f) > 0.001f) {
                for (int k = 0; k < 4; k++) m.skinWeights[cv * 4 + k] /= total;
            }
        }
        System.out.println("[FbxParser] skin: " + bones.size() + " bone(s) over "
                + m.skinVertCount + " control points");
    }

    // ------------------------------------------------------------------
    // Model transform: Lcl Translation / Lcl Rotation (Euler XYZ) / Lcl Scaling
    // Applied as v' = T * Rx*Ry*Rz * S * v
    // ------------------------------------------------------------------

    private static float[] transformPoint(double[] t, double x, double y, double z) {
        double px = x * t[6], py = y * t[7], pz = z * t[8];
        double rx = Math.toRadians(t[3]), ry = Math.toRadians(t[4]), rz = Math.toRadians(t[5]);
        double cz = Math.cos(rz), sz = Math.sin(rz);
        double cy = Math.cos(ry), sy = Math.sin(ry);
        double cx = Math.cos(rx), sx = Math.sin(rx);
        double x1 = px * cz - py * sz;
        double y1 = px * sz + py * cz;
        double x2 = x1 * cy + pz * sy;
        double z2 = -x1 * sy + pz * cy;
        double y3 = y1 * cx - z2 * sx;
        double z3 = y1 * sx + z2 * cx;
        return new float[]{(float) (x2 + t[0]), (float) (y3 + t[1]), (float) (z3 + t[2])};
    }

    // ------------------------------------------------------------------
    // Node helpers
    // ------------------------------------------------------------------

    private static FbxNode childNode(FbxNode node, String name) {
        for (FbxNode c : node.children) {
            if (c.name.equals(name)) return c;
        }
        return null;
    }

    private static double[] childNums(FbxNode node, String name) {
        FbxNode c = childNode(node, name);
        return c == null ? new double[0] : c.doubles();
    }

    private static int[] childInts(FbxNode node, String name) {
        FbxNode c = childNode(node, name);
        return c == null ? new int[0] : c.ints();
    }

    private static String childString(FbxNode node, String name) {
        FbxNode c = childNode(node, name);
        return c == null ? null : c.string();
    }

    private static long idOf(FbxNode node) {
        if (node.props.isEmpty()) return -1;
        Object p = node.props.get(0);
        return p instanceof Number n ? n.longValue() : -1;
    }

    private static String strOf(Object o) {
        return o instanceof String s ? s : null;
    }

    private static long numOf(Object o) {
        return o instanceof Number n ? n.longValue() : -1L;
    }
}