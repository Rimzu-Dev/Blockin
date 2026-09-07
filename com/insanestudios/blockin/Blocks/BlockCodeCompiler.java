package com.insanestudios.blockin.Blocks;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles a mod block's {@code .java} source at load time using the JDK's
 * {@code javax.tools} compiler, then loads the resulting class and asks its
 * static {@code public static Block create(BlockSpec spec)} factory for the
 * block instance.
 *
 * <p>Mod source compiles against the running game's classpath (so it may use
 * {@link Block}, {@link BlockSpec}, the level API, etc.) and is written into a
 * {@code bin/} folder next to the source. If no JDK compiler is available
 * (running on a bare JRE) the mod falls back to the stock block for its type.
 *
 * <p>Custom behavior lives in the factory: the mod returns its own
 * {@link Block} subclass (e.g. overriding {@code render}) instead of
 * {@code spec.build()}. A Lua/Rust backend can later replace this same entry
 * point.
 */
public final class BlockCodeCompiler {

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    private BlockCodeCompiler() {
    }

    /**
     * Compiles {@code source} into {@code binDir} and invokes the mod's factory.
     * Returns null when the JDK compiler is absent or the class/factory is
     * invalid — the caller then falls back to {@code spec.build()}.
     */
    public static Block compile(File source, File binDir, BlockSpec spec) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            System.err.println("[ModCode] JDK compiler unavailable (JRE?), using stock block for " + spec.name);
            return null;
        }

        // Wipe stale outputs so a renamed class doesn't leave ghosts behind.
        deleteTree(binDir);
        if (!binDir.mkdirs() && !binDir.isDirectory()) {
            System.err.println("[ModCode] cannot create output dir " + binDir);
            return null;
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(source);
            List<String> options = new ArrayList<>();
            options.add("-encoding");
            options.add("UTF-8");
            options.add("-classpath");
            options.add(compileClasspath());
            options.add("-d");
            options.add(binDir.getAbsolutePath());
            options.add("-nowarn");

            boolean ok = compiler.getTask(null, fm, diagnostics, options, null, units).call();
            if (!ok) {
                System.err.println("[ModCode] compilation failed for " + source.getName() + ":");
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    System.err.println("  " + d.getKind() + " " + d.getLineNumber()
                            + ": " + d.getMessage(Locale.ROOT));
                }
                return null;
            }
        } catch (Exception e) {
            System.err.println("[ModCode] compile error for " + source.getName() + ": " + e);
            return null;
        }

        String className = resolveClassName(source);
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{binDir.toURI().toURL()}, BlockLoader.class.getClassLoader())) {
            Class<?> loaded = Class.forName(className, true, loader);
            Method factory = findCreate(loaded);
            if (factory == null) {
                System.err.println("[ModCode] " + className + " has no static Block create(BlockSpec) —"
                        + " using stock block for " + spec.name);
                return null;
            }
            Object result = factory.invoke(null, spec);
            if (!(result instanceof Block)) {
                System.err.println("[ModCode] " + className + ".create returned non-Block — using stock block");
                return null;
            }
            System.out.println("[ModCode] loaded custom behavior from " + className);
            return (Block) result;
        } catch (Exception e) {
            System.err.println("[ModCode] failed to load " + className + ": " + e);
            return null;
        }
    }

    /** Fully-qualified class name: the file's package (if any) + its base name. */
    private static String resolveClassName(File source) {
        String name = source.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) name = name.substring(0, dot);
        String pkg = null;
        try {
            Matcher m = PACKAGE.matcher(Files.readString(source.toPath(), StandardCharsets.UTF_8));
            if (m.find()) pkg = m.group(1);
        } catch (Exception ignored) {
        }
        return (pkg == null || pkg.isEmpty()) ? name : pkg + "." + name;
    }

    private static Method findCreate(Class<?> clazz) {
        try {
            Method m = clazz.getMethod("create", BlockSpec.class);
            if (Modifier.isStatic(m.getModifiers())
                    && Block.class.isAssignableFrom(m.getReturnType())) {
                return m;
            }
        } catch (NoSuchMethodException ignored) {
        }
        return null;
    }

    private static String compileClasspath() {
        String cp = System.getProperty("java.class.path", "");
        return cp.isEmpty() ? "." : cp;
    }

    private static void deleteTree(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) deleteTree(f);
            // noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        // noinspection ResultOfMethodCallIgnored
        dir.delete();
    }
}