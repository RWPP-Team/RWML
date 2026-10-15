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
import io.github.rwml.api.i18n.Messages;
import io.github.rwml.api.ui.UiFramework;
import io.github.rwml.api.ui.UiHooks;
import io.github.rwml.api.ui.UiServices;
import io.github.rwml.asm.MixinSupport;
import io.github.rwml.bootstrap.Relauncher;
import io.github.rwml.builtin.VanillaInterface;
import io.github.rwml.builtin.LauncherWindow;
import io.github.rwml.core.DefaultExtensionLibrary;
import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.core.ExtensionHandle;
import io.github.rwml.core.ExtensionManager;
import io.github.rwml.core.GameContextImpl;
import io.github.rwml.core.LoadReport;
import io.github.rwml.game.LocaleUiMixin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The desktop host prepares metadata first and loads extensions only after its bootstrap is visible.
 *
 * <p>It is separate from {@code com.corrodinggames.rts.java.Main}, which is compiled for Java 8 so it
 * can start on any JVM; everything here is modern bytecode, reached once {@code Bootstrap} has made
 * sure the JVM is suitable. The running instance is this process's {@link RwmlRuntime}.</p>
 *
 * <p>{@link #prepare(String[])} prepares metadata and UI services only. Extension failures during the
 * later launch return to the bootstrap and require a fresh process before game preparation.</p>
 */
public final class DesktopRuntime implements RwmlRuntime {

    /** The mixin registry is global; preparing twice would double-register every mixin. */
    private static boolean prepared;

    /** The one runtime of this process, set once the extension system is up. */
    private static DesktopRuntime current;

    private final ExtensionManager manager;
    private volatile LoadReport report;
    private final GameBoot gameBoot;
    private final UiServices ui;
    private volatile boolean starting;
    private final java.util.concurrent.atomic.AtomicBoolean recoveryShown = new java.util.concurrent.atomic.AtomicBoolean();

    private DesktopRuntime(ExtensionManager manager, String[] args) throws IOException {
        this.manager = manager;
        UiHooks hooks = new UiHooks(
            failure -> report("UI listener failed", failure));
        ui = new UiServices(
            new Messages(manager.dataDirectory(), hooks),
            new DefaultExtensionLibrary(manager, manager.extensionDirectory(),
                () -> Collections.singletonList(manager.extensionDirectory()),
                () -> report, archive -> { }, hooks), hooks);
        // The boot belongs to the platform, so the desktop fills it here: the canvas class, the
        // platform flags, the HTTP factory, and the hand-over to the launcher that owns the window.
        // The interface extension only decides that those steps should run.
        this.gameBoot = new GameBoot(new GameContextImpl(
            this, gameArguments(args), manager.rootDirectory(), System.out::println));
        this.gameBoot.stopOnFailure();
        this.gameBoot.beforeStages("extensions", stage -> {
            loadExtensions((percent, message) -> stage.subProgress(message));
        });
        // Lambdas defer resolving game-facing code; extensions can still replace these stages in onLoad.
        this.gameBoot.add("desktop platform", context -> DesktopInterface.choosePlatform(context));
        this.gameBoot.add(GameBoot.ENGINE, context -> DesktopInterface.handOverToTheGame(context));
    }

    /** @return the running runtime, or {@code null} before a successful {@link #prepare} */
    public static DesktopRuntime current() {
        return current;
    }

    /**
     * Prepares the extension catalog and bootstrap services without loading extension code.
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

    @Override
    public UiServices ui() {
        return ui;
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
        DesktopUiHost host = new DesktopUiHost(runtime, args);
        LauncherWindow.launch(host, progress -> runtime.startSelectedInterface(host, progress));
        return true;
    }

    private void startSelectedInterface(DesktopUiHost host, ProgressListener progress) throws Exception {
        starting = true;
        try {
            loadExtensions(progress);
            UiFramework framework = manager.uiFramework();
            if (framework == null || framework instanceof VanillaInterface) {
                io.github.rwml.api.game.StageReport boot = gameBoot.start(progress);
                if (!boot.isClean()) { throw new ExtensionException("Game preparation failed: " + boot.failed()); }
            } else {
                try { framework.start(host); }
                catch (Throwable failure) {
                    quarantine(manager.uiFrameworkExtensionId(), failure);
                    throw new ExtensionException("The extension interface failed to start", failure);
                }
                if (gameBoot.isStarted() && !gameBoot.start().isClean()) {
                    throw new ExtensionException("Game preparation failed: " + gameBoot.start().failed());
                }
            }
            if (manager.recoveryRequired()) { throw new ExtensionException("An extension failed; restart RWML to continue"); }
        } finally { starting = false; }
    }

    /** Called only after the host bootstrap is visible, or from an explicitly headless launch. */
    private synchronized void loadExtensions(ProgressListener progress) throws Exception {
        if (report == null) {
            installTransformer();
            MixinSupport.instance().onFailure((archive, failure) -> {
                try {
                    String id = io.github.rwml.core.ExtensionDescriptorReader.read(archive).id();
                    quarantine(id, failure);
                } catch (Exception error) { report("Cannot recover a failed mixin", error); }
            });
            MixinSupport.instance().register(LocaleUiMixin.class);
            report = manager.loadAll(manager.discover(), progress);
            printReport(report);
            if (report.isClean()) { MixinSupport.instance().validateRegistered(); }
        }
        if (!report.isClean() || manager.recoveryRequired()) {
            ui.hooks().publish(UiHooks.EXTENSIONS_CHANGED, null);
            throw manager.recoveryFailure("Extension preparation failed: " + report.failed());
        }
    }

    private void quarantine(String id, Throwable failure) throws ExtensionException {
        manager.quarantine(id, failure);
        ui.hooks().publish(UiHooks.EXTENSIONS_CHANGED, null);
        if (!starting && recoveryShown.compareAndSet(false, true)) {
            LauncherWindow.recover(new DesktopUiHost(this, gameBoot.context().arguments().toArray(new String[0])), failure);
        }
    }

    static String[] gameArguments(String[] args) {
        return Arrays.stream(args == null ? new String[0] : args)
            .filter(arg -> !"--rwml-no-launcher".equals(arg) && !"--rwml-manager".equals(arg))
            .toArray(String[]::new);
    }

    /** @return a runtime whose extension system is up, or {@code null} when nothing can be loaded */
    private static DesktopRuntime start(String[] args) throws Exception {
        Path root = resolveRootDirectory();
        System.out.println("[rwml] Game directory: " + root);
        System.out.println("[rwml] Extensions directory: " + root.resolve("extensions"));

        ExtensionManager manager = new ExtensionManager(root, new DesktopHost());
        DesktopRuntime runtime = new DesktopRuntime(manager, args);
        runtime.ui.extensions().entries();
        Rwml.install(runtime);
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
                + "that require mixins will be disabled. Start through the packaged jar or add -javaagent:<rwml>.jar.");
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
