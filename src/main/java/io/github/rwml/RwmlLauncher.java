package io.github.rwml;

import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.core.ExtensionHandle;
import io.github.rwml.core.ExtensionManager;
import io.github.rwml.core.LoadReport;
import io.github.rwml.core.LoadingProgressWindow;
import io.github.rwml.api.ProgressListener;
import io.github.rwml.asm.MixinSupport;
import io.github.rwml.bootstrap.Relauncher;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Stage 1 of RWML: discovers and loads the extensions, then gets out of the way.
 *
 * <p>This class is the extension-loading half of the entry point. It is deliberately separate from
 * {@code com.corrodinggames.rts.java.Main}, which is compiled for Java 8 so it can start on any JVM:
 * everything here is modern bytecode and is only reached once {@code Bootstrap} has made sure the
 * JVM is suitable.</p>
 *
 * <p>{@link #prepare(String[])} never throws. A failure here disables extensions - it must never stop
 * the game from starting - so every problem is reported and turned into a {@code false} return.</p>
 */
public final class RwmlLauncher {

    /** The mixin registry is global; preparing twice would double-register every mixin. */
    private static boolean prepared;

    private RwmlLauncher() {
    }

    public static ExtensionManager manager;
    public static LoadingProgressWindow window;
    public static LoadReport report;

    /**
     * Loads every extension found next to the game.
     *
     * @param args the game's arguments, forwarded by the bootstrap
     * @return whether the extension system is up; {@code false} means the game should still be
     *         started, just without extensions
     */
    public static boolean prepare(String[] args) {
        synchronized (RwmlLauncher.class) {
            if (prepared) {
                return true;
            }
            try {
                if (start()) {
                    prepared = true;
                }
                return prepared;
            } catch (Throwable t) {
                report("The extension system failed to start: " + describe(t), t);
                return false;
            }
        }
    }

    private static boolean start() throws Exception {
        Path root = resolveRootDirectory();
        System.out.println("[rwml] Game directory: " + root);
        System.out.println("[rwml] Extensions directory: " + root.resolve("extensions"));

        installTransformer();

        manager = new ExtensionManager(root);
        List<Path> archives = manager.discover();
        if (archives.isEmpty()) {
            System.out.println("[rwml] No extensions installed. Drop extension jars into "
                + manager.extensionDirectory() + " and restart.");
            return true;
        }

        System.out.println("[rwml] Loading " + archives.size() + " extension(s).");
        window = LoadingProgressWindow.showIfPossible("RWML");
        try {
            report = manager.loadAll(archives, window == null ? consoleListener() : window.listener());
        } finally {
            if (window != null) {
                window.close();
            }
        }

        printReport(report);
        Runtime.getRuntime().addShutdownHook(new Thread(manager::shutdown, "rwml-shutdown"));
        return true;
    }

    /**
     * Makes sure the ASM class-file transformer is live.
     *
     * <p>In a packaged jar this has already happened: the bootstrap restarts the game with
     * {@code -javaagent}, so {@code RwmlAgent.premain} installed the transformer before this code
     * ran. The call covers the remaining cases (running the extension system from an IDE, or a host
     * that already loaded classes) by attempting a self-attach, which a JVM started without the
     * {@code jdk.attach.allowAttachSelf} flag will refuse.</p>
     */
    private static void installTransformer() {
        MixinSupport mixin = MixinSupport.instance();
        mixin.installTransformer();
        if (mixin.isInstalled()) {
            System.out.println("[rwml] Bytecode transformer is active; mixins apply as classes load.");
        } else {
            System.out.println("[rwml] Live bytecode rewriting is unavailable on this JVM. Extensions "
                + "still load and register their mixins, but classes that are already loaded will not "
                + "be patched. Start the game through the packaged jar, or add -javaagent:<rwml>.jar.");
        }
    }

    /**
     * Resolves the directory RWML keeps {@code extensions/} and {@code data/} in.
     *
     * <p>The directory holding the game jar, so extensions live next to the game and are found
     * without configuration.</p>
     */
    private static Path resolveRootDirectory() {
        File jar = Relauncher.ownJar();
        if (jar != null && jar.getParentFile() != null) {
            return jar.getParentFile().toPath().toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    /** Prints the outcome of the load pass, one line per extension so failures are visible. */
    private static void printReport(LoadReport report) {
        for (ExtensionHandle handle : report.loaded()) {
            ExtensionDescriptor d = handle.descriptor();
            System.out.println("[rwml]   + " + d.name() + " " + d.version() + "  [" + d.id() + "]");
        }
        for (Map.Entry<String, String> failure : report.failed().entrySet()) {
            System.out.println("[rwml]   ! " + failure.getKey() + ": " + failure.getValue());
        }
        for (String id : report.disabled()) {
            System.out.println("[rwml]   - " + id + " (disabled)");
        }
        System.out.println("[rwml] " + report.loaded().size() + " loaded, " + report.disabled().size()
            + " disabled, " + report.failed().size() + " skipped");
    }

    /**
     * The headless fallback, used when no window can be shown: a load pass is short, so every step
     * is reported on the console. The per-extension summary is printed either way.
     */
    private static ProgressListener consoleListener() {
        return (percent, message) ->
            System.out.println("[rwml]   " + percent + "%  " + message);
    }

    private static void report(String message, Throwable cause) {
        System.err.println("[rwml] " + message);
        cause.printStackTrace(System.err);
    }

    private static String describe(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
