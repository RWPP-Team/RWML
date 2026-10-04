package io.github.rwml.core;

import io.github.rwml.api.Extension;
import io.github.rwml.api.ExtensionException;
import io.github.rwml.api.ExtensionState;
import io.github.rwml.api.ProgressListener;
import io.github.rwml.asm.MixinSupport;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Central registry driving discovery, loading, enabling and disabling of
 * extensions.
 *
 * <p>Loading happens in three phases:</p>
 * <ol>
 *   <li>every archive is scanned and its descriptor is read;</li>
 *   <li>{@link ExtensionDependencyResolver} turns the declared inter-extension
 *       relationships into a valid load order and drops everything that cannot
 *       be satisfied;</li>
 *   <li>the extensions are instantiated in that order. Each one receives its
 *       own {@link URLClassLoader} whose parent is the host loader, so two
 *       extensions may depend on different versions of the same library
 *       without clashing.</li>
 * </ol>
 *
 * <p>Java version requirements are reconciled between the value declared in
 * the descriptor and the actual bytecode level of the archive; when the
 * running JVM is too old, an alternative JDK is looked up and reported to the
 * caller.</p>
 */
public final class ExtensionManager {

    private final Path rootDirectory;
    private final Path extensionDirectory;
    private final Path dataDirectory;
    private final ExtensionDependencyResolver resolver = new ExtensionDependencyResolver();
    private final ExtensionStateStore stateStore;

    private final Map<String, ExtensionHandle> handles = new LinkedHashMap<>();
    /** Every extension id discovered this session, loaded or not. */
    private final Set<String> knownIds = new LinkedHashSet<>();

    public ExtensionManager(Path rootDirectory) throws IOException {
        this.rootDirectory = rootDirectory.toAbsolutePath();
        this.extensionDirectory = this.rootDirectory.resolve("extensions");
        this.dataDirectory = this.rootDirectory.resolve("data");
        this.stateStore = ExtensionStateStore.open(dataDirectory);
    }

    public Path rootDirectory() {
        return rootDirectory;
    }

    public Path extensionDirectory() {
        return extensionDirectory;
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    /** @return every {@code .jar} found in the extension directory, sorted by name. */
    public List<Path> discover() throws IOException {
        Files.createDirectories(extensionDirectory);
        try (Stream<Path> stream = Files.list(extensionDirectory)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                .sorted()
                .collect(Collectors.toList());
        }
    }

    /** @return an immutable snapshot of every currently loaded handle. */
    public List<ExtensionHandle> handles() {
        synchronized (handles) {
            return Collections.unmodifiableList(new ArrayList<>(handles.values()));
        }
    }

    public ExtensionHandle handle(String id) {
        synchronized (handles) {
            return handles.get(id);
        }
    }

    public Set<String> extensionIds() {
        synchronized (handles) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(handles.keySet()));
        }
    }

    // ------------------------------------------------------------------
    // Bulk load
    // ------------------------------------------------------------------

    /**
     * Reads, orders and instantiates every supplied archive.
     *
     * <p>Failures never abort the pass: an archive that cannot be read, an
     * extension with unresolved dependencies, a cycle participant and a
     * runtime failure during instantiation are all collected into the returned
     * report, and the remaining extensions are loaded normally.</p>
     */
    public LoadReport loadAll(List<Path> archives, ProgressListener listener) {
        ProgressListener progress = listener == null ? ProgressListener.NOOP : listener;
        MixinSupport.instance().installTransformer();

        synchronized (handles) {
            handles.clear();
        }

        // ---------- Phase 1: descriptors ----------
        progress.onProgress(1, "Scanning " + archives.size() + " archive(s)");
        Map<String, ExtensionDescriptor> descriptors = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();

        int totalArchives = Math.max(1, archives.size());
        for (int i = 0; i < archives.size(); i++) {
            Path archive = archives.get(i);
            int percent = 1 + (int) (27.0 * i / totalArchives);
            progress.onProgress(percent, "Reading " + archive.getFileName());
            try {
                ExtensionDescriptor descriptor = ExtensionDescriptorReader.read(archive);
                if (descriptors.containsKey(descriptor.id())) {
                    failures.put(archive.getFileName().toString(),
                        "Duplicate extension id '" + descriptor.id() + "'");
                } else {
                    descriptors.put(descriptor.id(), descriptor);
                }
            } catch (ExtensionException e) {
                failures.put(archive.getFileName().toString(), e.getMessage());
            }
        }

        // ---------- Phase 2: dependency ordering ----------
        progress.onProgress(30, "Resolving extension load order");
        DependencyResolution resolution = resolver.resolve(new ArrayList<>(descriptors.values()));

        for (Map.Entry<String, String> entry : resolution.excluded().entrySet()) {
            failures.put(entry.getKey(), entry.getValue());
        }

        // ---------- Phase 3: instantiate in order ----------
        List<ExtensionDescriptor> order = resolution.loadOrder();
        List<ExtensionHandle> loaded = new ArrayList<>(order.size());
        List<String> disabled = new ArrayList<>();
        Set<String> disabledIds = stateStore.disabledIds();
        int totalToLoad = Math.max(1, order.size());

        for (ExtensionDescriptor descriptor : order) {
            synchronized (knownIds) {
                knownIds.add(descriptor.id());
            }
        }

        for (int i = 0; i < order.size(); i++) {
            ExtensionDescriptor descriptor = order.get(i);
            int percent = 34 + (int) (64.0 * i / totalToLoad);
            int span = Math.max(1, 64 / totalToLoad);
            progress.onProgress(percent, "Loading " + descriptor.name());

            String blockingDependency = firstFailedDependency(descriptor, failures);
            if (blockingDependency != null) {
                failures.put(descriptor.id(),
                    "Required extension '" + blockingDependency + "' did not load");
                continue;
            }

            String missingDependency = firstDisabledDependency(descriptor, disabledIds);
            if (missingDependency != null) {
                failures.put(descriptor.id(),
                    "Required extension '" + missingDependency + "' is disabled");
                continue;
            }

            if (disabledIds.contains(descriptor.id())) {
                disabled.add(descriptor.id());
                continue;
            }

            try {
                ExtensionHandle handle = instantiate(descriptor, (pct, message) ->
                    progress.onProgress(percent + Math.min(span - 1, pct * span / 100), message));
                loaded.add(handle);
                synchronized (handles) {
                    handles.put(descriptor.id(), handle);
                }
            } catch (ExtensionException e) {
                failures.put(descriptor.id(), e.getMessage());
            }
        }

        progress.onProgress(100, loaded.size() + " loaded, " + disabled.size()
            + " disabled, " + failures.size() + " skipped");
        return new LoadReport(loaded, failures, disabled);
    }

    // ------------------------------------------------------------------
    // Single extension
    // ------------------------------------------------------------------

    /** Loads one archive that has already been discovered on disk. */
    public ExtensionHandle load(Path archive, ProgressListener listener) throws ExtensionException {
        ProgressListener progress = listener == null ? ProgressListener.NOOP : listener;
        MixinSupport.instance().installTransformer();
        progress.onProgress(3, "Reading descriptor of " + archive.getFileName());
        ExtensionDescriptor descriptor = ExtensionDescriptorReader.read(archive);

        synchronized (knownIds) {
            knownIds.add(descriptor.id());
        }

        if (stateStore.isDisabled(descriptor.id())) {
            throw new ExtensionException("Extension '" + descriptor.id()
                + "' is disabled and will not be loaded");
        }

        synchronized (handles) {
            ExtensionHandle existing = handles.get(descriptor.id());
            if (existing != null) {
                throw new ExtensionException("Duplicate extension id '" + descriptor.id()
                    + "' (already provided by " + existing.descriptor().source()
                    + ", new source " + archive + ")");
            }
        }
        progress.onProgress(12, "Ordering " + descriptor.id());

        // A single-extension load still validates hard dependencies against
        // whatever is already loaded, so we never end up with a broken graph.
        synchronized (handles) {
            for (String required : descriptor.dependsOn()) {
                if (!handles.containsKey(required)) {
                    throw new ExtensionException("Extension '" + descriptor.id()
                        + "' requires '" + required + "', which is not loaded");
                }
            }
        }

        ExtensionHandle handle = instantiate(descriptor, progress);
        synchronized (handles) {
            handles.put(descriptor.id(), handle);
        }
        progress.onProgress(100, "Loaded " + descriptor.name() + " " + descriptor.version());
        return handle;
    }

    /** Shared instantiation routine used by both bulk and single loads. */
    private ExtensionHandle instantiate(ExtensionDescriptor descriptor, ProgressListener progress)
        throws ExtensionException {
        descriptor = reconcileJavaVersion(descriptor, progress);

        progress.onProgress(60, "Creating isolated class loader for " + descriptor.id());
        URLClassLoader classLoader;
        try {
            URL url = descriptor.source().toUri().toURL();
            classLoader = new URLClassLoader(new URL[]{url}, ExtensionManager.class.getClassLoader());
        } catch (Exception e) {
            throw new ExtensionException("Unable to open " + descriptor.source().getFileName(), e);
        }

        progress.onProgress(72, "Instantiating " + descriptor.mainClass());
        Class<?> type;
        try {
            type = Class.forName(descriptor.mainClass(), true, classLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            closeQuietly(classLoader);
            throw new ExtensionException("Entry point " + descriptor.mainClass()
                + " could not be loaded: " + e, e);
        }
        if (!Extension.class.isAssignableFrom(type)) {
            closeQuietly(classLoader);
            throw new ExtensionException("Entry point " + descriptor.mainClass()
                + " does not implement " + Extension.class.getName());
        }

        Extension instance;
        try {
            instance = (Extension) type.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            closeQuietly(classLoader);
            throw new ExtensionException("Constructor of " + descriptor.mainClass() + " failed", e);
        }

        ExtensionHandle handle = new ExtensionHandle(descriptor, instance, classLoader);
        ExtensionContextImpl context;
        try {
            context = new ExtensionContextImpl(
                descriptor.id(), dataDirectory.resolve(descriptor.id()), classLoader);
        } catch (IOException e) {
            closeQuietly(classLoader);
            throw new ExtensionException("Unable to prepare the data directory for " + descriptor.id(), e);
        }
        handle.attachContext(context);

        // Register the extension's mixins *before* onLoad runs: an extension may well load (and use)
        // the very classes its mixins patch during startup, so the registry has to be populated first.
        MixinSupport.instance().registerArchive(descriptor.source());

        progress.onProgress(85, "Running onLoad for " + descriptor.id());
        try {
            runWithTimeout(() -> instance.onLoad(context),
                descriptor.loadTimeoutMillis(), "onLoad of " + descriptor.id());
        } catch (Exception e) {
            closeQuietly(classLoader);
            throw e instanceof ExtensionException ee ? ee
                : new ExtensionException("onLoad of '" + descriptor.id() + "' failed", e);
        }

        handle.setState(ExtensionState.ENABLED);
        return handle;
    }

    /**
     * Reconciles the Java requirement declared in the descriptor with the
     * actual bytecode level of the archive and with the running JVM.
     */
    private ExtensionDescriptor reconcileJavaVersion(ExtensionDescriptor descriptor,
                                                     ProgressListener progress) throws ExtensionException {
        int bytecodeLevel;
        try {
            bytecodeLevel = JavaVersionUtil.requiredJavaVersion(descriptor.source());
        } catch (IOException e) {
            throw new ExtensionException("Unable to inspect bytecode of " + descriptor.source().getFileName(), e);
        }

        int declared = descriptor.requiredJavaVersion();
        int required = Math.max(declared, bytecodeLevel);
        int runtime = JavaVersionUtil.currentJavaVersion();

        if (required > runtime) {
            Path alternative = JavaVersionUtil.findJvmAtLeast(required);
            String hint = alternative != null
                ? " A compatible JVM was found at " + alternative
                + "; restart the host with that JVM to load this extension."
                : " No compatible JVM was found on this machine.";
            throw new ExtensionException(String.format(
                "Extension '%s' requires Java %d (declared %d, bytecode %d) but the running JVM is Java %d.%s",
                descriptor.id(), required, declared, bytecodeLevel, runtime, hint));
        }
        if (bytecodeLevel > declared) {
            progress.onProgress(45, "Raised Java requirement of " + descriptor.id()
                + " from " + declared + " to " + bytecodeLevel);
        }
        return descriptor.withRequiredJavaVersion(required);
    }

    private static String firstFailedDependency(ExtensionDescriptor descriptor, Map<String, String> failures) {
        for (String dependency : descriptor.dependsOn()) {
            if (failures.containsKey(dependency)) {
                return dependency;
            }
        }
        return null;
    }

    private static String firstDisabledDependency(ExtensionDescriptor descriptor, Set<String> disabled) {
        for (String dependency : descriptor.dependsOn()) {
            if (disabled.contains(dependency)) {
                return dependency;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Enable / disable (persisted flag; takes effect on the next startup)
    // ------------------------------------------------------------------

    /**
     * Marks an extension as disabled. Disabling has no effect on the currently
     * running instance: the extension keeps running until the host restarts, at
     * which point it is simply not loaded.
     */
    public void disable(String id) throws ExtensionException {
        requireKnown(id);
        try {
            stateStore.setDisabled(id, true);
        } catch (IOException e) {
            throw new ExtensionException("Unable to persist disabled state for '" + id + "'", e);
        }
    }

    /**
     * Clears the disabled mark so the extension is loaded again on the next
     * startup.
     */
    public void enable(String id) throws ExtensionException {
        requireKnown(id);
        try {
            stateStore.setDisabled(id, false);
        } catch (IOException e) {
            throw new ExtensionException("Unable to persist enabled state for '" + id + "'", e);
        }
    }

    /** @return whether the extension is persisted as disabled. */
    public boolean isDisabled(String id) {
        synchronized (knownIds) {
            return knownIds.contains(id) && stateStore.isDisabled(id);
        }
    }

    private void requireKnown(String id) throws ExtensionException {
        synchronized (knownIds) {
            if (!knownIds.contains(id)) {
                throw new ExtensionException("Unknown extension id '" + id + "'");
            }
        }
    }

    // ------------------------------------------------------------------
    // Unload
    // ------------------------------------------------------------------

    /** Unloads and forgets a currently loaded extension, releasing its class loader. */
    public void unload(String id) throws ExtensionException {
        ExtensionHandle handle = require(id);
        handle.close();
        synchronized (handles) {
            handles.remove(id);
        }
    }

    /** Closes every loaded extension, releasing class loaders in reverse load order. */
    public void shutdown() {
        List<String> ids;
        synchronized (handles) {
            ids = new ArrayList<>(handles.keySet());
        }
        // Unload in reverse load order so that dependents disappear first.
        for (int i = ids.size() - 1; i >= 0; i--) {
            try {
                unload(ids.get(i));
            } catch (ExtensionException ignored) {
                // Shutdown must not abort half way through.
            }
        }
    }

    private ExtensionHandle require(String id) throws ExtensionException {
        ExtensionHandle handle = handle(id);
        if (handle == null) {
            throw new ExtensionException("Unknown extension id '" + id + "'");
        }
        return handle;
    }

    private static void closeQuietly(URLClassLoader loader) {
        try {
            loader.close();
        } catch (IOException ignored) {
            // Ignore.
        }
    }

    @FunctionalInterface
    private interface ExtensionTask {
        void run() throws Exception;
    }

    /** Runs a lifecycle callback on a daemon thread, enforcing a timeout. */
    private static void runWithTimeout(ExtensionTask task, long timeoutMillis, String what) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "extension-lifecycle");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<?> future = executor.submit(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ExtensionException(what + " timed out after " + timeoutMillis + " ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime && runtime.getCause() instanceof Exception inner) {
                throw inner;
            }
            throw new ExtensionException(what + " failed", cause);
        } finally {
            executor.shutdownNow();
        }
    }
}