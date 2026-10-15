/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import android.content.Context;
import android.content.pm.PackageInfo;

import com.corrodinggames.rts.gameFramework.gui.MinimapHandlerQueuedPointsDrawMixin;

import io.github.rwml.android.builtin.AndroidExtension;
import io.github.rwml.android.mixin.AndroidSoundFactoryMixin;
import io.github.rwml.android.mixin.GraphicsEngineAndroidMixin;
import io.github.rwml.android.mixin.MinimapHandlerMixin;
import io.github.rwml.android.mixin.NetworkEngineNotificationMixin;
import io.github.rwml.android.mixin.ServerListNotifyMixin;
import io.github.rwml.api.ExtensionException;
import io.github.rwml.api.MixinBridge;
import io.github.rwml.api.Platform;
import io.github.rwml.api.ProgressListener;
import io.github.rwml.api.Rwml;
import io.github.rwml.api.RwmlRuntime;
import io.github.rwml.api.game.GameBoot;
import io.github.rwml.api.i18n.Messages;
import io.github.rwml.api.ui.UiFramework;
import io.github.rwml.api.ui.UiHooks;
import io.github.rwml.api.ui.UiServices;
import io.github.rwml.core.DefaultExtensionLibrary;
import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.core.ExtensionDescriptorReader;
import io.github.rwml.core.ExtensionManager;
import io.github.rwml.core.ExtensionSelection;
import io.github.rwml.core.ExtensionStateStore;
import io.github.rwml.core.GameContextImpl;
import io.github.rwml.core.LoadReport;
import io.github.rwml.game.LocaleUiMixin;

import kim.der.asm.AsmRegistry;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/**
 * The Android bootstrapper: the counterpart of the desktop {@code Bootstrap}.
 *
 * <p>Android has no agent, so the archives are rewritten up front, compiled to dex and put on the
 * class path ahead of everything else; the dex is cached and keyed on what it was built from. The
 * extensions are mounted first, because a mixin has to be a resolvable class before the registry can
 * apply it.</p>
 *
 * <p>A failure anywhere stops the run: a half-rewritten game fails later, and somewhere else.</p>
 */
public final class AndroidBootstrap implements RwmlRuntime {

    /**
     * Identifies the rewriting and the cache layout; bump it so an app update never reuses dex a
     * different RWML produced.
     *
     * <p>{@code rwml-2}: dex sealed read-only, dx state reset per run.
     * {@code rwml-3}: the bundled {@code android/*} classes dropped, the platform supplies them.
     * {@code rwml-4}: {@code android/support/*} kept, nothing else supplies it.
     * {@code rwml-5}: the app's own mixins of the game, which no staged file can see.
     * {@code rwml-6}: the build's report kept beside the dex it produced.
     * {@code rwml-7}: one forced rebuild, so the new reporting runs on a device holding a cache.
     * {@code rwml-8}: the image mixin's handler became static and takes its context from
     * {@link GameResources} instead of a shadowed field that was always null.
     * {@code rwml-9}: the app's own code is part of the key, so a new build of the app no longer
     * needs a hand-written bump to replace the dex its mixins are compiled into.</p>
     */
    private static final String CACHE_FORMAT = "rwml-10-early-ui";

    private static final String GAME_GROUP = "game";
    private static final String EXTENSION_GROUP = "extensions";
    private static final String KEY_FILE = "key.txt";

    /**
     * The file, beside the cache key, holding what the build that produced the cached dex reported.
     * Its first line is {@link #PATCHED_PREFIX} and the number of classes that build changed.
     */
    private static final String BUILD_LOG = "build.log";

    /** The front of the first line of {@link #BUILD_LOG}, which carries the patched count. */
    private static final String PATCHED_PREFIX = "patched ";

    private final Context context;
    private final List<String> log = new ArrayList<>();

    /** The loader that owns the mounted game dex, or null before the preparation mounted one. */
    private static volatile ClassLoader mountedLoader;

    private Path rootDirectory;
    private Path gameDirectory;
    private Path extensionsDirectory;
    private Path dataDirectory;
    private Path dexDirectory;

    private OfflineMixinBridge bridge;
    private AndroidHost host;
    private ExtensionSelection selection;
    private List<Path> archives;
    private boolean interfacePrepared;
    private String sourceCatalog;
    private boolean reloadRequested;
    private ProgressListener preparationProgress = ProgressListener.NOOP;
    private final Set<Path> mountedDex = new LinkedHashSet<>();
    private ExtensionManager manager;
    private ClassLoader classLoader;
    private LoadReport lastReport;
    private GameBoot gameBoot;
    private UiServices ui;
    private String cacheKey = "";
    private boolean cacheHit;
    private int patchedClasses;
    private boolean prepared;
    private final java.util.concurrent.atomic.AtomicBoolean recoveryShown = new java.util.concurrent.atomic.AtomicBoolean();

    public AndroidBootstrap(Context context) {
        this.context = context;
        GameResources.attach(context);
    }

    /**
     * @return the loader that owns the mounted game dex, or null before a preparation mounted one
     *
     * <p>The app's own classes cannot reach the game by name unless the platform let the dex onto
     * their own path, so anything that looks the game up reflectively has to name this loader
     * instead of assuming one.</p>
     */
    public static ClassLoader gameLoader() {
        return mountedLoader;
    }

    /**
     * The one runtime the process owns, set the first time anyone asks for it.
     *
     * <p>Every game activity calls {@link #prepare(Context)} through {@code AndroidApp.prepare}, but
     * Android may restart the process on the last activity it had open, which never ran the splash's
     * boot. A single instance makes the preparation idempotent across entries, so the game dex is
     * mounted and the game classes become nameable from wherever the process came up.</p>
     */
    private static volatile AndroidBootstrap shared;

    /** @return the loader that owns the game dex, after preparing the runtime once. */
    public static ClassLoader prepare(Context context) {
        AndroidBootstrap bootstrap = get(context);
        if (!bootstrap.prepare()) {
            throw new IllegalStateException("The Android runtime could not be prepared: " + bootstrap.log());
        }
        return gameLoader();
    }

    /** The process owns one runtime; activities must never construct another mounted session. */
    public static AndroidBootstrap get(Context context) {
        AndroidBootstrap bootstrap = shared;
        if (bootstrap == null) {
            synchronized (AndroidBootstrap.class) {
                bootstrap = shared;
                if (bootstrap == null) {
                    bootstrap = new AndroidBootstrap(context.getApplicationContext());
                    shared = bootstrap;
                }
            }
        }
        return bootstrap;
    }

    /**
     * Prepares the runtime, once. Everything after the first call is a no-op.
     *
     * <p>A failure stops game preparation; the interface remains available to show diagnostics.</p>
     *
     * @return whether the runtime is ready
     */
    public synchronized boolean prepare() {
        if (prepared) {
            return true;
        }
        // The first launch rewrites thousands of classes and compiles them to dex, which takes long
        // enough that a reader cannot tell a slow run from a stopped one.
        Watchdog watchdog = Watchdog.start("prepare");
        try {
            prepared = start();
        } catch (Throwable t) {
            HostLog.error("game dex preparation failed", t);
            record("bootstrap failed: " + describe(t));
            showRecovery(t);
        } finally {
            watchdog.stop();
        }
        return prepared;
    }

    /** Checks for updates only when entering the launcher, never during a running game. */
    public synchronized boolean prepareLauncher() throws IOException, ExtensionException {
        if (reloadRequested) { return false; }
        if (interfacePrepared && !sourceCatalog.equals(ExtensionCatalog.fingerprint(
                sharedExtensionsDirectory(), extensionsDirectory,
                dataDirectory.resolve("extension-state.properties")))) {
            // A second launcher entry can reuse this process; changed ART classes need a fresh one.
            reloadRequested = true;
            record("extensions changed at launcher entry; reloading in a fresh process");
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                context.startActivity(new android.content.Intent(context, RestartActivity.class)
                    .putExtra("rwml.oldPid", android.os.Process.myPid())
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)));
            return false;
        }
        prepareInterface();
        return true;
    }

    /** Mounts only the UI plugin and its dependencies. No game jar is staged or compiled here. */
    public synchronized void prepareInterface() throws IOException, ExtensionException {
        if (interfacePrepared) { return; }
        HostLog.install();
        rootDirectory = new File(context.getFilesDir(), "rwml").toPath();
        gameDirectory = rootDirectory.resolve(GAME_GROUP);
        extensionsDirectory = rootDirectory.resolve(EXTENSION_GROUP);
        dataDirectory = rootDirectory.resolve("data");
        dexDirectory = rootDirectory.resolve("dex");
        Files.createDirectories(extensionsDirectory);
        Files.createDirectories(dataDirectory);
        recordAll(AssetInstaller.install(context, EXTENSION_GROUP, extensionsDirectory));
        sourceCatalog = ExtensionCatalog.fingerprint(sharedExtensionsDirectory(), extensionsDirectory,
            dataDirectory.resolve("extension-state.properties"));
        archives = new ArrayList<>(SharedExtensions.synchronize(
            sharedExtensionsDirectory(), rootDirectory.resolve("shared-extensions")));
        archives.addAll(AssetInstaller.installed(extensionsDirectory));
        List<ExtensionDescriptor> descriptors = enabledExtensions(archives);
        if (descriptors.stream().noneMatch(descriptor -> descriptor.id().equals(AndroidExtension.ID))) {
            descriptors.add(AndroidExtension.descriptor());
        }
        ExtensionStateStore states = ExtensionStateStore.open(dataDirectory);
        selection = ExtensionSelection.resolve(descriptors, states.disabledIds(), states.selectedVersions());
        AsmRegistry.clear();
        classLoader = AndroidBootstrap.class.getClassLoader();
        bridge = new OfflineMixinBridge(classLoader);
        host = new AndroidHost(context, bridge, classLoader);
        manager = new ExtensionManager(rootDirectory, host);
        UiHooks hooks = new UiHooks(
            failure -> HostLog.error("UI listener failed", failure));
        ui = new UiServices(
            new Messages(dataDirectory, hooks),
            new DefaultExtensionLibrary(manager, sharedExtensionsDirectory(),
                () -> Arrays.asList(sharedExtensionsDirectory(), extensionsDirectory),
                () -> lastReport, Dexer::validateCarriedDex, hooks), hooks);
        gameBoot = new GameBoot(new GameContextImpl(context, null, rootDirectory, HostLog::add));
        gameBoot.stopOnFailure();
        gameBoot.beforeStages("runtime", stage -> {
            preparationProgress = (percent, message) -> stage.subProgress(message);
            try {
                stage.subProgress("game dex");
                if (!prepare()) { throw new IllegalStateException("Game preparation failed: " + log()); }
                stage.subProgress("extensions");
                LoadReport loaded = load((percent, message) -> stage.subProgress(message));
                if (!loaded.isClean() || manager.recoveryRequired()) {
                    ExtensionException failure = manager.recoveryFailure("Extension preparation failed: " + loaded.failed());
                    showRecovery(failure);
                    throw failure;
                }
            } finally { preparationProgress = ProgressListener.NOOP; }
        });
        gameBoot.add(GameBoot.ENGINE, stage -> {
            requirePrepared();
            // Resolve the game-facing helper only after game dex has joined the app class path.
            Class<?> game = Class.forName("io.github.rwml.android.AndroidGame", true, classLoader);
            game.getMethod("create", Context.class, ProgressListener.class).invoke(null, context,
                (ProgressListener) (percent, message) -> stage.subProgress(message));
        });
        ui.extensions().entries();
        Rwml.install(this);
        List<Path> early = new ArrayList<>();
        List<Path> dex = new ArrayList<>();
        for (ExtensionDescriptor descriptor : selection.interfaceArchives()) {
            early.add(descriptor.source());
            dex.addAll(ensureExtensionDex(descriptor));
        }
        classLoader = mount(classLoader, dex);
        bridge.updateLoader(classLoader);
        host.updateLoader(classLoader);
        LoadReport earlyReport = manager.loadAll(early, (percent, message) -> record("ui " + message));
        if (!earlyReport.isClean() || manager.recoveryRequired()) {
            lastReport = earlyReport;
            throw new ExtensionException("Selected UI could not load: " + earlyReport.failed());
        }
        interfacePrepared = true;
        record("interface ready; game dex has not been prepared");
    }

    public static Path sharedExtensionsDirectory() {
        return new File(android.os.Environment.getExternalStorageDirectory(),
            "rustedWarfare/extensions").toPath();
    }

    private void requirePrepared() {
        if (!prepared) { throw new IllegalStateException("Game dex preparation failed"); }
    }

    /** @return the manager the extensions are loaded through, or {@code null} before {@link #prepare}. */
    @Override
    public ExtensionManager manager() {
        return manager;
    }

    /** @return the outcome of the last {@link #load} pass, or {@code null} if none has run. */
    @Override
    public LoadReport report() {
        return lastReport;
    }

    /** @return the platform this runtime runs on, which is Android. */
    @Override
    public Platform platform() {
        return Platform.ANDROID;
    }

    /**
     * @return the platform's boot, available before early extension callbacks and game preparation
     */
    @Override
    public GameBoot gameBoot() {
        return gameBoot;
    }

    @Override
    public UiServices ui() {
        if (ui == null) { throw new IllegalStateException("UI services are not prepared"); }
        return ui;
    }

    /**
     * @return the id of the extension that declared a user interface, or {@code null} for the headless
     *     case
     */
    public String uiFrameworkExtensionId() {
        ExtensionManager current = manager;
        return current == null ? null : current.uiFrameworkExtensionId();
    }

    /**
     * @return the interface to start, or {@code null} when nothing declared one
     * @throws ExtensionException when it cannot be loaded or does not implement {@code UiFramework}
     */
    public UiFramework uiFramework() throws ExtensionException {
        ExtensionManager current = manager;
        return current == null ? null : current.uiFramework();
    }

    /**
     * Loads every staged extension through the shared manager and records the outcome.
     *
     * @throws IllegalStateException when the runtime is not prepared yet
     */
    public synchronized LoadReport load(ProgressListener progress) throws IOException {
        if (manager == null) {
            throw new IllegalStateException("the runtime is not prepared; call prepare() first");
        }
        if (lastReport != null) {
            return lastReport;
        }
        LoadReport loaded = manager.loadRemaining(archives, progress);
        lastReport = loaded;
        return loaded;
    }

    /** @return every line the preparation produced, in order. */
    public List<String> log() {
        return Collections.unmodifiableList(new ArrayList<>(log));
    }

    /** @return the digest the dex cache is keyed on; empty before {@link #prepare}. */
    public String cacheKey() {
        return cacheKey;
    }

    /** @return whether the preparation reused a cache instead of compiling. */
    public boolean usedCache() {
        return cacheHit;
    }

    /** @return how many game classes the registered mixins changed on the last build. */
    public int patchedClasses() {
        return patchedClasses;
    }

    /** Invalidates the dex cache for the next process; mounted classes cannot be unloaded in place. */
    public synchronized void clear() throws IOException {
        deleteRecursively(dexDirectory);
    }

    // ------------------------------------------------------------------
    // The pipeline
    // ------------------------------------------------------------------

    private boolean start() throws IOException, ExtensionException {
        prepareInterface();
        record("preparing game assets");
        recordAll(AssetInstaller.install(context, GAME_GROUP, gameDirectory));
        List<Path> gameArchives = AssetInstaller.installed(gameDirectory);
        List<ExtensionDescriptor> enabled = new ArrayList<>();
        for (ExtensionDescriptor descriptor : selection.enabled()) {
            if (!descriptor.isBuiltIn()) { enabled.add(descriptor); }
        }
        cacheKey = computeKey(gameArchives, enabled);

        cacheHit = isCacheValid(enabled, gameArchives);
        if (cacheHit) {
            record("cache      dex reused  (key " + AssetInstaller.Digest.shortHash(cacheKey) + ")");
            replayBuildLog();
            classLoader = mount(classLoader, collectDex(enabled, gameArchives));
        } else {
            int first = log.size();
            classLoader = build(enabled, gameArchives);
            writeBuildLog(log.subList(first, log.size()));
            writeKey();
        }

        bridge.updateLoader(classLoader);
        host.updateLoader(classLoader);
        mountedLoader = classLoader;
        GameResources.mounted(classLoader);
        return true;
    }

    /** @return the dex of every extension and game archive that is already on disk, in load order. */
    private List<Path> collectDex(List<ExtensionDescriptor> enabled, List<Path> gameArchives) throws IOException {
        List<Path> dex = new ArrayList<>();
        for (ExtensionDescriptor descriptor : enabled) {
            dex.addAll(ensureExtensionDex(descriptor));
        }
        for (Path archive : gameArchives) {
            dex.addAll(Dexer.dexFiles(gameDexDirectory(archive)));
        }
        return dex;
    }

    /**
     * @param base the loader to extend: the app class loader first, then whatever the last call
     *             returned
     * @return the loader that owns the dex
     */
    private ClassLoader mount(ClassLoader base, List<Path> dex) throws IOException {
        if (dex.isEmpty()) {
            record("mounted    no dex file; the runtime keeps its own classes");
            return base;
        }
        List<Path> fresh = new ArrayList<>();
        for (Path path : dex) {
            if (!mountedDex.contains(path.toAbsolutePath().normalize())) { fresh.add(path); }
        }
        if (fresh.isEmpty()) { return base; }
        ClassLoader loader = DexClassPath.mount(fresh, rootDirectory.resolve("optimized"), base);
        for (Path path : fresh) { mountedDex.add(path.toAbsolutePath().normalize()); }
        record("mounted    " + dex.size() + " dex file(s) on "
            + (loader == base ? base.getClass().getName() : "a new " + loader.getClass().getName()));
        if (loader != AndroidBootstrap.class.getClassLoader()) {
            record("note       the app class loader refused the patched dex, so extensions live in "
                + "their own loader and code running in the app loader cannot see them");
        }
        return loader;
    }

    /**
     * Compiles the extensions, mounts them, registers their mixins and rewrites the game against them.
     *
     * <p>A mixin that cannot be registered ends the build: rewriting the game without it would produce
     * dex that behaves as though the patch were in, and the difference only shows up later.</p>
     *
     * @return the loader that owns the mounted dex
     */
    private ClassLoader build(List<ExtensionDescriptor> enabled, List<Path> gameArchives)
        throws IOException, ExtensionException {
        deleteRecursively(dexDirectory);
        patchedClasses = 0;

        ClassLoader appLoader = AndroidBootstrap.class.getClassLoader();
        List<Path> extensionDex = new ArrayList<>();
        List<ExtensionDescriptor> mounted = new ArrayList<>();
        for (ExtensionDescriptor descriptor : enabled) {
            Path output = extensionDexDirectory(descriptor);
            try {
                // An extension arrives compiled: the dexer belongs to the application, and compiling
                // every extension at installation would put that cost on the launch.
                extensionDex.addAll(ensureExtensionDex(descriptor));
                mounted.add(descriptor);
                record("dex        " + descriptor.id() + "  carried  -> " + relative(output));
            } catch (IOException e) {
                throw new ExtensionException("Extension " + descriptor.id() + " could not be mounted",
                    e);
            }
        }
        ClassLoader loader = mount(classLoader, extensionDex);
        bridge.updateLoader(loader);
        // The app carries mixins of the game's classes of its own, registered like an extension's.
        // The class comes from the app's dex, which this loader already sees, so nothing has to
        // be mounted for it.
        List<Class<?>> carried = Arrays.<Class<?>>asList(
            AndroidSoundFactoryMixin.class, GraphicsEngineAndroidMixin.class, LocaleUiMixin.class,
            NetworkEngineNotificationMixin.class, ServerListNotifyMixin.class,
            MinimapHandlerMixin.class, MinimapHandlerQueuedPointsDrawMixin.class,
            io.github.rwml.android.mixin.MasterServerListLifecycleMixin.class);
        for (Class<?> mixin : carried) {
            bridge.register(mixin);
            record("mixin      " + mixin.getName() + "  [app]");
        }

        for (ExtensionDescriptor descriptor : mounted) {
            for (String mixin : OfflineMixinBridge.mixinClassNames(descriptor.source())) {
                record("mixin      " + mixin + "  [" + descriptor.id() + "]");
            }
            try { bridge.registerArchive(descriptor.source()); }
            catch (ExtensionException failure) {
                recoverArchive(descriptor.source(), failure);
                throw failure;
            }
        }

        if (gameArchives.isEmpty()) {
            record("no game classes staged; nothing to rewrite");
            return loader;
        }

        List<Path> classpath = new ArrayList<>(gameArchives);
        for (ExtensionDescriptor descriptor : mounted) {
            classpath.add(descriptor.source());
        }
        // The rewriter computes stack map frames and needs to read the classes it reasons about.
        // This loader answers those reads from the jars and supplies a stand-in for the rest;
        // nothing has to be loadable for it to work.
        List<Path> gameDex = new ArrayList<>();
        try (JarResources resources = new JarResources(classpath, appLoader)) {
            for (Path archive : gameArchives) {
                Path patched = dexDirectory.resolve("patched").resolve(baseName(archive) + ".jar");
                record("rewriting " + archive.getFileName());
                int changed = rewrite(archive, patched, bridge, resources);
                patchedClasses += changed;
                Path output = gameDexDirectory(archive);
                record("compiling " + archive.getFileName() + " to dex");
                gameDex.addAll(Dexer.toDex(patched, output));
                record("dexed      " + archive.getFileName() + "  -> " + relative(output)
                    + "  (" + changed + " class(es) patched)");
            }
        }
        if (patchedClasses == 0) {
            // Silence here is the one outcome that reads like success: the dex is built, the game
            // starts, and every mixin quietly did nothing because it named a class or a method the
            // archive does not have under those names.
            record("warning    no game class was changed; the registered mixins name classes this "
                + "archive does not carry, or none of their injections matched");
        }
        return mount(loader, gameDex);
    }

    /**
     * Rewrites every class of {@code source} into {@code target}, dropping the bundled copy of the
     * Android API the desktop needs and a device does not. {@code android/support/*} is kept, because
     * it is a library the game bundles and no other class path supplies it.
     *
     * @throws ExtensionException when the registry cannot be applied to one of the classes
     */
    private int rewrite(Path source, Path target, MixinBridge bridge, ClassLoader resources)
        throws IOException, ExtensionException {
        Files.createDirectories(target.getParent());
        int changed = 0;
        int dropped = 0;
        try (JarFile in = new JarFile(source.toFile());
             OutputStream file = Files.newOutputStream(target);
             JarOutputStream out = new JarOutputStream(new BufferedOutputStream(file))) {
            Enumeration<JarEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.startsWith("android/") && !name.startsWith("android/support/")) {
                    dropped++;
                    continue;
                }
                byte[] bytes;
                try (InputStream stream = in.getInputStream(entry)) {
                    bytes = JarResources.readFully(stream);
                }
                if (name.endsWith(".class")) {
                    String internalName = name.substring(0, name.length() - ".class".length());
                    byte[] rewritten = bytes;
                    try {
                        byte[] result = bridge.transform(internalName, bytes, resources);
                        if (result != null && result.length > 0) {
                            rewritten = result;
                        }
                    } catch (Throwable t) {
                        // A class the registry cannot handle ends the build. Leaving it unchanged
                        // would ship dex in which one mixin quietly did not apply, and the game would
                        // then fail somewhere that says nothing about why.
                        Path failed = this.bridge.failedArchive(t);
                        if (failed != null) { recoverArchive(failed, t); }
                        throw new ExtensionException("The mixins could not be applied to "
                            + internalName, t);
                    }
                    if (!Arrays.equals(rewritten, bytes)) {
                        changed++;
                    }
                    bytes = rewritten;
                }
                out.putNextEntry(new JarEntry(name));
                out.write(bytes);
                out.closeEntry();
            }
        }
        if (dropped > 0) {
            record("dropped    " + dropped + " bundled android/* class(es); the platform supplies "
                + "the framework");
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // Cache bookkeeping
    // ------------------------------------------------------------------

    /** @return the archives whose extensions are not disabled, in file name order. */
    private List<ExtensionDescriptor> enabledExtensions(List<Path> archives) {
        Set<String> disabled;
        try {
            disabled = ExtensionStateStore.open(dataDirectory).disabledIds();
        } catch (IOException e) {
            HostLog.error("the disabled flags could not be read", e);
            record("cannot read the disabled flags (" + e + "); treating every extension as enabled");
            disabled = Collections.emptySet();
        }
        List<ExtensionDescriptor> enabled = new ArrayList<>();
        for (Path archive : archives) {
            try {
                ExtensionDescriptor descriptor = ExtensionDescriptorReader.read(archive);
                if (disabled.contains(descriptor.id())) {
                    record("disabled   " + descriptor.id());
                    continue;
                }
                enabled.add(descriptor);
            } catch (ExtensionException e) {
                HostLog.error("the descriptor of " + archive.getFileName() + " could not be read", e);
                record("unreadable " + archive.getFileName() + "  (" + e.getMessage() + ")");
            }
        }
        return enabled;
    }

    /**
     * The cache key covers the game classes, the identity and the content of every extension that
     * will load, this app's own build, and the format of the cache itself.
     *
     * <p>The app's build belongs in the key because the app carries mixins of its own, and those are
     * compiled into the dex this builds. Installing a new build over the previous one leaves the game
     * archives untouched, so a key made of those alone would be unchanged and the previous dex would
     * be reused - with the previous mixins in it, and every patch the new build added silently
     * absent. Recording the key as a hand-written constant instead is what this replaces: it worked
     * only for whoever remembered to bump it.</p>
     */
    private String computeKey(List<Path> gameArchives, List<ExtensionDescriptor> enabled) {
        StringBuilder key = new StringBuilder(CACHE_FORMAT).append('\n');
        key.append("app ").append(appIdentity()).append('\n');
        for (Path archive : gameArchives) {
            key.append("game ").append(archive.getFileName()).append(' ')
                .append(AssetInstaller.Digest.sha256(archive)).append('\n');
        }
        List<String> entries = new ArrayList<>();
        for (ExtensionDescriptor descriptor : enabled) {
            entries.add("ext " + descriptor.id() + ' ' + descriptor.version() + ' '
                + descriptor.sourceSha256() + '\n');
        }
        // Registration/load order changes the resulting patches even when the same jars are present.
        for (String entry : entries) {
            key.append(entry);
        }
        return AssetInstaller.Digest.sha256(key.toString());
    }

    /**
     * @return what identifies this build of the app, which changes on every install of it
     */
    private String appIdentity() {
        try {
            PackageInfo info = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0);
            return info.versionCode + ":" + info.lastUpdateTime;
        } catch (Throwable t) {
            // No package record means nothing that could be cached was ever built, so any constant
            // is as good as a real identity here.
            return "unidentified";
        }
    }

    /**
     * A cache is worth reusing when the key still describes what the dex was built from and every
     * directory it names still holds dex. The key is checked last because it is the cheap part.
     */
    private boolean isCacheValid(List<ExtensionDescriptor> enabled, List<Path> gameArchives) {
        for (ExtensionDescriptor descriptor : enabled) {
            // An extension that carries no dex is skipped at build time, so requiring dex of it here
            // would invalidate the cache on every launch for a reason that never changes.
            if (Dexer.carriesDex(descriptor.source())
                && Dexer.dexFiles(extensionDexDirectory(descriptor)).isEmpty()) {
                return false;
            }
        }
        for (Path archive : gameArchives) {
            if (Dexer.dexFiles(gameDexDirectory(archive)).isEmpty()) {
                return false;
            }
        }
        if (enabled.isEmpty() && gameArchives.isEmpty()) {
            return false;
        }
        Path keyFile = dexDirectory.resolve(KEY_FILE);
        if (!Files.isRegularFile(keyFile)) {
            return false;
        }
        try {
            String recorded = new String(Files.readAllBytes(keyFile), StandardCharsets.UTF_8).trim();
            return recorded.equals(cacheKey);
        } catch (IOException e) {
            return false;
        }
    }

    private void writeKey() throws IOException {
        Files.createDirectories(dexDirectory);
        Files.write(dexDirectory.resolve(KEY_FILE), cacheKey.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // Reporting
    // ------------------------------------------------------------------

    /** Records one line, in the log this object hands out and in logcat. */
    private void record(String line) {
        log.add(line);
        HostLog.add(line);
        preparationProgress.onProgress(0, line);
    }

    /** Records every line of a list the same way {@link #record} records one. */
    private void recordAll(List<String> lines) {
        for (String line : lines) {
            record(line);
        }
    }

    /**
     * Keeps what a build reported beside the dex it produced: a launch that reuses the dex performs
     * none of the work those lines describe, so they would otherwise be said once and never again.
     */
    private void writeBuildLog(List<String> build) throws IOException {
        Files.createDirectories(dexDirectory);
        StringBuilder text = new StringBuilder(PATCHED_PREFIX).append(patchedClasses).append('\n');
        for (String line : build) {
            text.append(line).append('\n');
        }
        Files.write(dexDirectory.resolve(BUILD_LOG), text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Reads back what {@link #writeBuildLog} wrote, into this run's log. */
    private void replayBuildLog() {
        Path file = dexDirectory.resolve(BUILD_LOG);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                if (line.startsWith(PATCHED_PREFIX)) {
                    try {
                        patchedClasses =
                            Integer.parseInt(line.substring(PATCHED_PREFIX.length()).trim());
                    } catch (NumberFormatException ignored) {
                        // A count that will not parse leaves the number at zero, which is what a
                        // launch that compiled nothing would have reported anyway.
                    }
                } else if (!line.trim().isEmpty()) {
                    record(line);
                }
            }
        } catch (IOException e) {
            HostLog.error("the report of the build that produced this dex cannot be read", e);
            record("the report of the build that produced this dex cannot be read (" + e + ")");
        }
    }

    // ------------------------------------------------------------------
    // Paths and small helpers
    // ------------------------------------------------------------------

    private Path extensionDexDirectory(ExtensionDescriptor descriptor) {
        return rootDirectory.resolve("extension-dex").resolve(descriptor.sourceSha256());
    }

    private List<Path> ensureExtensionDex(ExtensionDescriptor descriptor) throws IOException {
        try {
            Path output = extensionDexDirectory(descriptor);
            if (!descriptor.sourceSha256().equals(AssetInstaller.Digest.sha256(descriptor.source()))) {
                throw new IOException("Extension changed during startup: " + descriptor.id());
            }
            List<Path> existing = Dexer.dexFiles(output);
            Path complete = output.resolve("complete.txt");
            if (!existing.isEmpty() && Files.isRegularFile(complete)) { return existing; }
            List<Path> produced = Dexer.carriedDex(descriptor.source(), output);
            if (!descriptor.sourceSha256().equals(AssetInstaller.Digest.sha256(descriptor.source()))) {
                throw new IOException("Extension changed during mounting: " + descriptor.id());
            }
            Files.write(complete, descriptor.sourceSha256().getBytes(StandardCharsets.UTF_8));
            return produced;
        } catch (IOException failure) {
            recoverArchive(descriptor.source(), failure);
            throw failure;
        }
    }

    private void recoverArchive(Path archive, Throwable failure) {
        try {
            manager.quarantine(ExtensionDescriptorReader.read(archive).id(), failure);
            ui.hooks().publish(UiHooks.EXTENSIONS_CHANGED, null);
        } catch (Exception persistenceFailure) {
            failure.addSuppressed(persistenceFailure);
            HostLog.error("Cannot disable a failed extension", persistenceFailure);
        }
        showRecovery(failure);
    }

    private void showRecovery(Throwable failure) {
        if (!interfacePrepared || !recoveryShown.compareAndSet(false, true)) { return; }
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
            context.startActivity(new android.content.Intent(context,
                io.github.rwml.android.builtin.LauncherActivity.class)
                .putExtra(io.github.rwml.android.builtin.LauncherActivity.EXTRA_ERROR,
                    android.util.Log.getStackTraceString(failure))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)));
    }

    private Path gameDexDirectory(Path archive) {
        return dexDirectory.resolve("classes").resolve(baseName(archive));
    }

    private String relative(Path path) {
        return rootDirectory.relativize(path).toString().replace(File.separatorChar, '/');
    }

    private static String baseName(Path archive) {
        String name = archive.getFileName().toString();
        return name.endsWith(".jar") ? name.substring(0, name.length() - ".jar".length()) : name;
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            List<Path> paths = new ArrayList<>();
            walk.forEach(paths::add);
            Collections.reverse(paths);
            for (Path path : paths) {
                if (Files.isRegularFile(path)) {
                    // The dex here is sealed, and a sealed file is not removable on every host. This
                    // is what "clear the cache" does, so it has to be able to remove one.
                    Dexer.makeWritable(path);
                }
                Files.deleteIfExists(path);
            }
        }
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
