package com.insanestudios.blockin.Models;

import java.util.ArrayList;
import java.util.List;

/**
 * A node of an FBX document (both the ASCII and binary representations parse
 * into the same tree). Properties are scalars or arrays; children are sub-nodes.
 */
public final class FbxNode {

    public final String name;
    public final List<Object> props = new ArrayList<>();
    public final List<FbxNode> children = new ArrayList<>();

    public FbxNode(String name) {
        this.name = name;
    }

    public FbxNode child(String childName) {
        for (FbxNode c : children) {
            if (c.name.equals(childName)) return c;
        }
        return null;
    }

    public FbxNode firstChildByPrefix(String prefix) {
        for (FbxNode c : children) {
            if (c.name.startsWith(prefix)) return c;
        }
        return null;
    }

    /** First String property, or null. */
    public String string() {
        for (Object p : props) {
            if (p instanceof String s) return s;
        }
        return null;
    }

    /** All numeric properties flattened into one double array. */
    public double[] doubles() {
        List<Double> acc = new ArrayList<>();
        collectDoubles(props, acc);
        double[] out = new double[acc.size()];
        for (int i = 0; i < out.length; i++) out[i] = acc.get(i);
        return out;
    }

    /** All numeric properties flattened into one int array. */
    public int[] ints() {
        List<Integer> acc = new ArrayList<>();
        collectInts(props, acc);
        int[] out = new int[acc.size()];
        for (int i = 0; i < out.length; i++) out[i] = acc.get(i);
        return out;
    }

    /** First numeric property as int (node index style, e.g. layer 0 / material 0). */
    public int firstInt() {
        for (Object p : props) {
            if (p instanceof Number n) return n.intValue();
        }
        return 0;
    }

    private static void collectDoubles(List<Object> props, List<Double> acc) {
        for (Object p : props) {
            if (p instanceof double[] a) {
                for (double d : a) acc.add(d);
            } else if (p instanceof int[] a) {
                for (int i : a) acc.add((double) i);
            } else if (p instanceof long[] a) {
                for (long l : a) acc.add((double) l);
            } else if (p instanceof Number n) {
                acc.add(n.doubleValue());
            }
        }
    }

    private static void collectInts(List<Object> props, List<Integer> acc) {
        for (Object p : props) {
            if (p instanceof int[] a) {
                for (int i : a) acc.add(i);
            } else if (p instanceof double[] a) {
                for (double d : a) acc.add((int) d);
            } else if (p instanceof long[] a) {
                for (long l : a) acc.add((int) l);
            } else if (p instanceof Number n) {
                acc.add(n.intValue());
            }
        }
    }
}