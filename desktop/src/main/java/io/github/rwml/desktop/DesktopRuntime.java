/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.desktop;

import io.github.rwml.api.ExtensionException;
import io.github.rwml.api.Platform;
import io.github.rwml.api.ProgressListener;
import io.github.rwml.api.Rwml;
import io.github.rwml.api.RwmlRuntime;
import io.github.rwml.api.game.GameBoot;
import io.github.rwml.api.ui.UiFramework;
import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.core.ExtensionHandle;
import io.github.rwml.core.ExtensionManager;
import io.github.rwml.core.GameContextImpl;
import io.github.rwml.core.LoadReport;
import io.github.rwml.asm.MixinSupport;
import io.github.rwml.bootstrap.Relauncher;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Stage 1 of RWML on the desktop: discovers and loads the extensions, then gets out of the way.
 *
 * <p>It is separate from {@code com.corrodinggames.rts.java.Main}, which is compiled for Java 8 so it
 * can start on any JVM; everything here is modern bytecode, reached once {@code Bootstrap} has made
 * sure the JVM is suitable. The running instance is this process's {@link RwmlRuntime}.</p>
 *
 * <p>{@link #prepare(String[])} never throws: a failure here disables extensions, it must never stop
 * the game from starting.</p>
 */
public final class DesktopRuntime implements RwmlRuntime {

    /** The mixin registry is global; preparing twice would double-register every mixin. */
    private static boolean prepared;

    /** The one runtime of this process, set once the extension system is up. */
    private static DesktopRuntime current;

    private final ExtensionManager manager;
    private volatile LoadReport report;
    private final GameBoot gameBoot;

    private DesktopRuntime(ExtensionManager manager, String[] args) {
        this.manager = manager;
        // The boot belongs to the platform, so the desktop fills it here: the canvas class, the
        // platform flags, the HTTP factory, and the hand-over to the launcher that owns the window.
        // The interface extension only decides that those steps should run.
        this.gameBoot = new GameBoot(new GameContextImpl(
            this, args, manager.rootDirectory(), System.out::println));
        DesktopInterface.contribute(this.gameBoot);
    }

    /** @return the running runtime, or {@code null} before a successful {@link #prepare} */
    public static DesktopRuntime current() {
        return current;
    }

    /**
     * Loads every extension found next to the game.
     *
     * @param args the game's arguments, forwarded by the bootstrap
     * @return whether the extension system is up; {@code false} means the game still starts, without
     *         extensions
     */
    public static boolean prepare(String[] args) {
        synchronized (DesktopRuntime.class) {
            if (prepared) {
                return true;
            }
            try {
                DesktopRuntime runtime = start(args);
                if (runtime != null) {
                    current = runtime;
                    Rwml.install(runtime);
                    prepared = true;
                }
                return prepared;
            } catch (Throwable t) {
                Rwml.install(null);
                report("The extension system failed to start: " + describe(t), t);
                return false;
            }
        }
    }

    @Override
    public ExtensionManager manager() {
        return manager;
    }

    @Override
    public LoadReport report() {
        return report;
    }

    /** @return the platform this runtime runs on, which is a JVM. */
    @Override
    public Platform platform() {
        return Platform.DESKTOP;
    }

    @Override
    public GameBoot gameBoot() {
        return gameBoot;
    }

    /**
     * Starts the interface an extension declared, when one did; a declaration that cannot be used
     * leaves the built-in launcher running.
     *
     * @param args the game's arguments, forwarded to the extension's interface
     * @return whether an extension's interface was started
     */
    public static boolean startExtensionInterface(String[] args) {
        DesktopRuntime runtime = current;
        if (runtime == null) {
            return false;
        }
        UiFramework framework;
        try {
            framework = runtime.manager.uiFramework();
        } catch (ExtensionException e) {
            report("The interface an extension declared could not be built: " + describe(e), e);
            return false;
        }
        if (framework == null) {
            return false;
        }
        try {
            framework.start(new DesktopUiHost(runtime, args));
            System.out.println("[rwml] The interface belongs to "
                + runtime.manager.uiFrameworkExtensionId() + ".");
            return true;
        } catch (Throwable t) {
            report("The interface an extension declared failed to start: " + describe(t), t);
            return false;
        }
    }

    /** @return a runtime whose extension system is up, or {@code null} when nothing can be loaded */
    private static DesktopRuntime start(String[] args) throws Exception {
        Path root = resolveRootDirectory();
        System.out.println("[rwml] Game directory: " + root);
        System.out.println("[rwml] Extensions directory: " + root.resolve("extensions"));

        installTransformer();

        ExtensionManager manager = new ExtensionManager(root, new DesktopHost());
        List<Path> archives = manager.discover();
        if (archives.isEmpty()) {
            System.out.println("[rwml] No extensions installed. Drop extension jars into "
                + manager.extensionDirectory() + " and restart. RWML's own interface is still "
                + "available.");
        }

        System.out.println("[rwml] Loading " + archives.size() + " extension(s).");
        DesktopRuntime runtime = new DesktopRuntime(manager, args);
        Rwml.install(runtime);
        LoadReport report = manager.loadAll(archives, consoleListener());
        runtime.report = report;

        printReport(report);
        Runtime.getRuntime().addShutdownHook(new Thread(manager::shutdown, "rwml-shutdown"));
        return runtime;
    }

    /**
     * Makes sure the ASM transformer is live. In a packaged jar the bootstrap already restarted the
     * game with {@code -javaagent}; this covers the rest by attempting a self-attach.
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
        for (String line : report.staged()) {
            System.out.println("[rwml]   " + line);
        }
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
