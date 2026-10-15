/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import io.github.rwml.api.Extension;
import io.github.rwml.api.ExtensionException;
import io.github.rwml.api.ExtensionState;
import io.github.rwml.api.ProgressListener;
import io.github.rwml.api.ui.UiFramework;
import io.github.rwml.host.HostPlatform;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 *       own class loader, supplied by the host platform, whose parent is the
 *       host loader, so two extensions may depend on different versions of the
 *       same library without clashing.</li>
 * </ol>
 *
 * <p>Java version requirements are reconciled between the value declared in
 * the descriptor and the actual bytecode level of the archive; that check belongs to the host, so it
 * lives behind {@link HostPlatform}.</p>
 */
public final class ExtensionManager {

    private final Path rootDirectory;
    private final Path extensionDirectory;
    private final Path dataDirectory;
    private final ExtensionStateStore stateStore;
    private final HostPlatform platform;

    private final Map<String, ExtensionHandle> handles = new LinkedHashMap<>();

    /** Every extension id discovered this session, loaded or not. */
    private final Set<String> knownIds = new LinkedHashSet<>();

    /** Id of the extension that declared a UI framework, and the entry it resolved to. */
    private String uiFrameworkId;
    private UiFramework uiFrameworkEntry;
    private String uiFrameworkReservation;
    private final Set<String> loadingIds = new LinkedHashSet<>();
    private final Map<String, String> recoveredFailures = new LinkedHashMap<>();
    private final Map<String, Throwable> recoveredCauses = new LinkedHashMap<>();
    private volatile boolean recoveryRequired;

    /** A failed load may have already registered mixins; only a fresh process is safe to boot. */
    public boolean recoveryRequired() { return recoveryRequired; }

    public Map<String, String> recoveredFailures() {
        synchronized (recoveredFailures) { return Collections.unmodifiableMap(new LinkedHashMap<>(recoveredFailures)); }
    }

    /** Preserves original callback and mixin causes for the bootstrap's diagnostic log. */
    public ExtensionException recoveryFailure(String message) {
        ExtensionException result = new ExtensionException(message);
        synchronized (recoveredFailures) {
            for (Throwable failure : recoveredCauses.values()) { result.addSuppressed(failure); }
        }
        return result;
    }

    private static String failureReason(Throwable failure) {
        Throwable cause = failure;
        Set<Throwable> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        while (cause.getCause() != null && seen.add(cause)) { cause = cause.getCause(); }
        String reason = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        return cause == failure ? reason : reason + ": " + cause;
    }

    /** Records the offending extension and persists its disabled flag before returning to the host. */
    public void quarantine(String id, Throwable failure) throws ExtensionException {
        recoveryRequired = true;
        String reason = failureReason(failure);
        synchronized (recoveredFailures) {
            recoveredFailures.put(id, reason);
            recoveredCauses.put(id, failure);
        }
        synchronized (knownIds) { knownIds.add(id); }
        try { stateStore.setDisabled(id, true); }
        catch (IOException persistenceFailure) {
            throw new ExtensionException("Cannot persist the disabled flag for failed extension '" + id + "'", persistenceFailure);
        }
    }

    /**
     * Manages extensions the way {@code platform} describes.
     *
     * <p>The host is required rather than defaulted. Every decision that differs between a JVM and an
     * Android app belongs to the host, and the core module has none of its own to offer.</p>
     */
    public ExtensionManager(Path rootDirectory, HostPlatform platform) throws IOException {
        this.rootDirectory = rootDirectory.toAbsolutePath();
        this.extensionDirectory = this.rootDirectory.resolve("extensions");
        this.dataDirectory = this.rootDirectory.resolve("data");
        this.stateStore = ExtensionStateStore.open(dataDirectory);
        this.platform = platform;
    }

    /** @return the host platform this manager was built for. */
    public HostPlatform platform() {
        return platform;
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

    /** The same state used by startup and management, including not-yet-loaded archives. */
    ExtensionStateStore extensionStates() {
        return stateStore;
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
    // User interface
    // ------------------------------------------------------------------

    /**
     * Finds the extension that declares a user interface.
     *
     * <p>The load order decides: the first usable extension whose descriptor names a
     * {@code ui-framework} wins and the rest are ignored, because a process has one interface. A
     * disabled or failed extension never wins.</p>
     *
     * @return the declaring extension's id, or null when nothing declared one, which means the
     *     runtime is headless
     */
    public String uiFrameworkExtensionId() {
        // A user extension wins over a built-in one regardless of load order: an install that
        // brought its own interface means it, and the shipped one is the fallback.
        String fallback = null;
        for (ExtensionHandle handle : handles()) {
            if (!handle.isUsable()) {
                continue;
            }
            String entry = handle.descriptor().uiFramework();
            if (entry == null || entry.trim().isEmpty()) {
                continue;
            }
            if (!handle.descriptor().isBuiltIn()) {
                return handle.descriptor().id();
            }
            if (fallback == null) {
                fallback = handle.descriptor().id();
            }
        }
        return fallback;
    }

    /**
     * Instantiates the user interface the loaded extensions declare, once.
     *
     * <p>The entry is resolved against the declaring extension's own loader first, so an extension
     * may ship its own implementation, and against the host's loader second, so a small extension
     * may name one the host supplies. The instance is cached, because the host starts an interface
     * once and a second call must not build a second one.</p>
     *
     * @return the entry point, or null when no extension declared one
     * @throws ExtensionException when the declared entry cannot be loaded, does not implement
     *     {@link UiFramework}, or cannot be instantiated
     */
    public UiFramework uiFramework() throws ExtensionException {
        String id = uiFrameworkExtensionId();
        if (id == null) {
            return null;
        }
        synchronized (this) {
            if (id.equals(uiFrameworkId) && uiFrameworkEntry != null) {
                return uiFrameworkEntry;
            }
            ExtensionHandle handle = handle(id);
            String entry = handle.descriptor().uiFramework().trim();
            try {
                Class<?> type = loadUiFrameworkEntry(entry, handle);
                if (!UiFramework.class.isAssignableFrom(type)) {
                    throw new ExtensionException("Extension '" + id + "' declares ui-framework '" + entry
                        + "', which does not implement " + UiFramework.class.getName());
                }
                try {
                    uiFrameworkEntry = (UiFramework) type.getDeclaredConstructor().newInstance();
                } catch (Exception e) {
                    throw new ExtensionException("Extension '" + id + "' declares ui-framework '" + entry
                        + "', which could not be instantiated: " + e, e);
                }
                uiFrameworkId = id;
                return uiFrameworkEntry;
            } catch (Throwable failure) {
                ExtensionException error = failure instanceof ExtensionException ? (ExtensionException) failure
                    : new ExtensionException("Interface of '" + id + "' failed", failure);
                try { quarantine(id, error); }
                catch (ExtensionException persistenceFailure) { error.addSuppressed(persistenceFailure); }
                throw error;
            }
        }
    }

    private Class<?> loadUiFrameworkEntry(String entry, ExtensionHandle handle)
        throws ExtensionException {
        try {
            return Class.forName(entry, true, handle.classLoader());
        } catch (ClassNotFoundException | LinkageError notInExtension) {
            try {
                return Class.forName(entry, true, ExtensionManager.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError notInHost) {
                throw new ExtensionException("Extension '" + handle.descriptor().id()
                    + "' declares ui-framework '" + entry
                    + "', which is in neither the extension nor the host", notInHost);
            }
        }
    }

    private void forgetUiFramework() {
        synchronized (this) {
            uiFrameworkId = null;
            uiFrameworkEntry = null;
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
    public synchronized LoadReport loadAll(List<Path> archives, ProgressListener listener) {
        return loadAll(archives, listener, false);
    }

    /** Completes an early UI load without running its callbacks or registering its mixins twice. */
    public synchronized LoadReport loadRemaining(List<Path> archives, ProgressListener listener) {
        return loadAll(archives, listener, true);
    }

    private LoadReport loadAll(List<Path> archives, ProgressListener listener, boolean keepLoaded) {
        ProgressListener progress = listener == null ? ProgressListener.NOOP : listener;
        platform.mixins().install();

        if (!keepLoaded) {
            synchronized (handles) {
                for (ExtensionHandle handle : handles.values()) { closeQuietly(handle.classLoader()); }
                handles.clear();
            }
            forgetUiFramework();
            uiFrameworkReservation = null;
        }

        // ---------- Phase 0: archives the host carries inside itself ----------
        progress.onProgress(0, "Staging extensions supplied by the host");
        List<String> staged = stage(progress);

        // ---------- Phase 1: descriptors ----------
        progress.onProgress(1, "Scanning " + archives.size() + " archive(s)");
        List<ExtensionDescriptor> descriptors = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        List<String> quarantinedArchives = new ArrayList<>();

        int totalArchives = Math.max(1, archives.size());
        for (int i = 0; i < archives.size(); i++) {
            Path archive = archives.get(i);
            int percent = 1 + (int) (27.0 * i / totalArchives);
            progress.onProgress(percent, "Reading " + archive.getFileName());
            try {
                ExtensionDescriptor descriptor = ExtensionDescriptorReader.read(archive);
                descriptors.add(descriptor);
            } catch (ExtensionException e) {
                try {
                    if (stateStore.isArchiveDisabled(archive)) {
                        quarantinedArchives.add(archive.getFileName().toString());
                        continue;
                    }
                    recoveryRequired = true;
                    stateStore.quarantineArchive(archive, e.getMessage());
                    synchronized (recoveredFailures) {
                        recoveredCauses.put(archive.getFileName().toString(), e);
                    }
                } catch (IOException persistenceFailure) { e.addSuppressed(persistenceFailure); }
                failures.put(archive.getFileName().toString(), e.getMessage());
            }
        }

        // ---------- Phase 1b: extensions the host carries inside itself ----------
        for (ExtensionDescriptor builtIn : platform.builtInExtensions()) {
            descriptors.add(builtIn);
            progress.onProgress(29, "Staged the built-in " + builtIn.name());
        }

        // ---------- Phase 2: dependency ordering ----------
        progress.onProgress(30, "Resolving extension load order");
        ExtensionSelection selection = ExtensionSelection.resolve(
            descriptors, stateStore.disabledIds(), stateStore.selectedVersions());

        if (keepLoaded) {
            String active = uiFrameworkExtensionId();
            String selected = selection.framework() == null ? null : selection.framework().id();
            if (!Objects.equals(active, selected)) {
                throw new IllegalStateException("UI selection changed; restart the host before loading remaining extensions");
            }
        }

        uiFrameworkReservation = selection.framework() == null ? null : selection.framework().id();
        for (Map.Entry<String, String> entry : selection.excluded().entrySet()) {
            failures.put(entry.getKey(), entry.getValue());
        }

        // ---------- Phase 3: instantiate in order ----------
        List<ExtensionDescriptor> order = selection.enabled();
        List<ExtensionHandle> loaded = new ArrayList<>(order.size());
        List<String> disabled = new ArrayList<>(selection.disabled());
        disabled.addAll(quarantinedArchives);
        Set<String> disabledIds = stateStore.disabledIds();
        int totalToLoad = Math.max(1, order.size());

        for (ExtensionDescriptor descriptor : descriptors) {
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
                ExtensionHandle existing = handle(descriptor.id());
                if (existing != null) {
                    if (!existing.descriptor().sourceSha256().equals(descriptor.sourceSha256())) {
                        throw new ExtensionException("Archive changed after loading '" + descriptor.id()
                            + "'; restart the host before mounting its replacement");
                    }
                    loaded.add(existing);
                    continue;
                }
                ExtensionHandle handle = instantiate(descriptor, (pct, message) ->
                    progress.onProgress(percent + Math.min(span - 1, pct * span / 100), message));
                loaded.add(handle);
                synchronized (handles) {
                    handles.put(descriptor.id(), handle);
                }
            } catch (ExtensionException e) {
                failures.put(descriptor.id(), failureReason(e));
            }
        }

        progress.onProgress(100, loaded.size() + " loaded, " + disabled.size()
            + " disabled, " + failures.size() + " skipped");
        return new LoadReport(loaded, failures, disabled, staged);
    }

    /**
     * Hands the host its chance to place the archives it ships into the extension directory.
     *
     * <p>A failure here is reported and otherwise ignored: an unreadable asset must not stop the
     * extensions that are already installed from loading.</p>
     */
    private List<String> stage(ProgressListener progress) {
        try {
            List<String> staged = platform.stageArchives(extensionDirectory);
            for (String line : staged) {
                progress.onProgress(0, line);
            }
            return staged;
        } catch (IOException e) {
            progress.onProgress(0, "Staging failed: " + e);
            return Collections.singletonList("staging failed: " + e);
        }
    }

    // ------------------------------------------------------------------
    // Single extension
    // ------------------------------------------------------------------

    /** Loads one archive that has already been discovered on disk. */
    public synchronized ExtensionHandle load(Path archive, ProgressListener listener) throws ExtensionException {
        ProgressListener progress = listener == null ? ProgressListener.NOOP : listener;
        platform.mixins().install();
        progress.onProgress(3, "Reading descriptor of " + archive.getFileName());
        ExtensionDescriptor descriptor = ExtensionDescriptorReader.read(archive);

        synchronized (knownIds) {
            knownIds.add(descriptor.id());
        }

        if (stateStore.isDisabled(descriptor.id())) {
            throw new ExtensionException("Extension '" + descriptor.id()
                + "' is disabled and will not be loaded");
        }

        String selectedVersion = stateStore.selectedVersions().get(descriptor.id());
        if (selectedVersion != null && !selectedVersion.equals(ExtensionVersions.identity(descriptor))) {
            throw new ExtensionException("A different version is selected for '" + descriptor.id()
                + "'; select this archive and restart before loading it");
        }
        synchronized (handles) {
            ExtensionHandle existing = handles.get(descriptor.id());
            if (existing != null) {
                throw new ExtensionException("Duplicate extension id '" + descriptor.id()
                    + "' (already provided by " + existing.descriptor().source()
                    + ", new source " + archive + ")");
            }
        }
        if (loadingIds.contains(descriptor.id())) {
            throw new ExtensionException("Extension is already being loaded: " + descriptor.id());
        }
        if (ExtensionSelection.isFramework(descriptor)) {
            String active = uiFrameworkReservation == null ? uiFrameworkExtensionId() : uiFrameworkReservation;
            if (active != null && !active.equals(descriptor.id())) {
                throw new ExtensionException("UI framework already owned by '" + active
                    + "'; restart to select a different framework");
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

        if (ExtensionSelection.isFramework(descriptor)) {
            String active = uiFrameworkReservation == null ? uiFrameworkExtensionId() : uiFrameworkReservation;
            if (active != null && !active.equals(descriptor.id())) {
                throw new ExtensionException("UI framework already owned by '" + active + "'");
            }
            uiFrameworkReservation = descriptor.id();
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
        if (!loadingIds.add(descriptor.id())) {
            throw new ExtensionException("Extension is already being loaded: " + descriptor.id());
        }
        try { return instantiateNew(descriptor, progress); }
        catch (Throwable failure) {
            ExtensionException error = failure instanceof ExtensionException ? (ExtensionException) failure
                : new ExtensionException("Loading extension '" + descriptor.id() + "' failed", failure);
            try { quarantine(descriptor.id(), error); }
            catch (ExtensionException persistenceFailure) { error.addSuppressed(persistenceFailure); }
            throw error;
        } finally { loadingIds.remove(descriptor.id()); }
    }

    private ExtensionHandle instantiateNew(ExtensionDescriptor descriptor, ProgressListener progress)
        throws ExtensionException {
        // A built-in extension has no archive to inspect: its bytecode is already on the host's own
        // class path, so there is no Java requirement to reconcile and no loader to open.
        descriptor = descriptor.isBuiltIn()
            ? descriptor
            : platform.reconcileJavaRequirement(descriptor, progress);

        progress.onProgress(60, "Creating the class loader of " + descriptor.id());
        ClassLoader classLoader;
        if (descriptor.isBuiltIn()) {
            classLoader = ExtensionManager.class.getClassLoader();
        } else {
            try {
                classLoader = platform.createExtensionLoader(descriptor);
            } catch (IOException e) {
                throw new ExtensionException("Unable to open " + descriptor.source().getFileName(), e);
            }
        }

        try {
            // Register before the entry class is initialized: static initialization and onLoad may
            // both use the classes the extension patches.
            // A built-in extension has no archive to scan; its mixins are already on the host's class
            // path and are registered by the host.
            if (!descriptor.isBuiltIn()) {
                platform.mixins().registerArchive(descriptor.source());
            }

            progress.onProgress(72, "Instantiating " + descriptor.mainClass());
            Class<?> type;
            try {
                type = Class.forName(descriptor.mainClass(), true, classLoader);
            } catch (ClassNotFoundException | LinkageError e) {
                throw new ExtensionException("Entry point " + descriptor.mainClass()
                    + " could not be loaded: " + e, e);
            }
            if (!Extension.class.isAssignableFrom(type)) {
                throw new ExtensionException("Entry point " + descriptor.mainClass()
                    + " does not implement " + Extension.class.getName());
            }

            Extension instance;
            try {
                instance = (Extension) type.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new ExtensionException("Constructor of " + descriptor.mainClass() + " failed", e);
            }

            ExtensionHandle handle = new ExtensionHandle(descriptor, instance, classLoader);
            ExtensionContextImpl context;
            try {
                context = new ExtensionContextImpl(
                    descriptor.id(), dataDirectory.resolve(descriptor.id()), classLoader, platform.mixins());
            } catch (IOException e) {
                throw new ExtensionException("Unable to prepare the data directory for " + descriptor.id(), e);
            }
            handle.attachContext(context);

            progress.onProgress(85, "Running onLoad for " + descriptor.id());
            try {
                instance.onLoad(context);
            } catch (Exception e) {
                throw new ExtensionException("onLoad of '" + descriptor.id() + "' failed", e);
            }

            handle.setState(ExtensionState.ENABLED);
            return handle;
        } catch (Throwable failure) {
            if (!descriptor.isBuiltIn()) { closeQuietly(classLoader); }
            if (failure instanceof ExtensionException) { throw (ExtensionException) failure; }
            throw new ExtensionException("Loading extension '" + descriptor.id() + "' failed", failure);
        }
    }

    /** @return the first hard dependency of {@code descriptor} that failed, or {@code null}. */
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
    public synchronized void unload(String id) throws ExtensionException {
        ExtensionHandle handle = require(id);
        handle.close();
        synchronized (handles) {
            handles.remove(id);
        }
        forgetUiFramework();
        if (id.equals(uiFrameworkReservation)) { uiFrameworkReservation = null; }
    }

    /** Closes every loaded extension, releasing class loaders in reverse load order. */
    public synchronized void shutdown() {
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

    /**
     * Releases a class loader if it can be released. A {@code URLClassLoader} keeps its jar files
     * open and has to be closed; the loaders a host builds out of dex have nothing to release.
     */
    private static void closeQuietly(ClassLoader loader) {
        if (!(loader instanceof Closeable)) {
            return;
        }
        try {
            ((Closeable) loader).close();
        } catch (IOException ignored) {
            // Ignore.
        }
    }
}
