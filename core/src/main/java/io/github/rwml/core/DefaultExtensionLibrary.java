/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import io.github.rwml.api.ExtensionException;
import io.github.rwml.api.extensions.ExtensionEntry;
import io.github.rwml.api.extensions.ExtensionLibrary;
import io.github.rwml.api.ui.UiHooks;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** One archive catalog for both hosts, independent of which extensions were loaded this session. */
public final class DefaultExtensionLibrary implements ExtensionLibrary {

    @FunctionalInterface
    public interface Validator {
        void validate(Path archive) throws IOException;
    }

    private static final long MAX_ARCHIVE_BYTES = 128L * 1024 * 1024;
    private final ExtensionManager manager;
    private final Path directory;
    private final Supplier<List<Path>> directories;
    private final Supplier<LoadReport> report;
    private final Validator validator;
    private final UiHooks hooks;
    private volatile boolean changed;
    private volatile boolean versionSelectionRequired;

    public DefaultExtensionLibrary(ExtensionManager manager, Path directory,
                                   Supplier<List<Path>> directories, Supplier<LoadReport> report,
                                   Validator validator, UiHooks hooks) {
        this.manager = manager;
        this.directory = directory.toAbsolutePath().normalize();
        this.directories = directories;
        this.report = report;
        this.validator = validator;
        this.hooks = hooks;
    }

    @Override public Path directory() { return directory; }
    @Override public boolean restartRequired() { return changed || manager.recoveryRequired(); }
    @Override public boolean versionSelectionRequired() { return versionSelectionRequired; }

    @Override
    public synchronized List<ExtensionEntry> entries() throws IOException {
        List<ExtensionDescriptor> descriptors = new ArrayList<>(manager.platform().builtInExtensions());
        List<ExtensionEntry> broken = new ArrayList<>();
        for (Path parent : directories.get()) {
            if (!Files.isDirectory(parent)) { continue; }
            try (Stream<Path> files = Files.list(parent)) {
                for (Path archive : files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .sorted().collect(Collectors.toList())) {
                    try {
                        descriptors.add(ExtensionDescriptorReader.read(archive));
                    } catch (ExtensionException failure) {
                        broken.add(new ExtensionEntry(null, archive.getFileName().toString(), "", "",
                            Collections.emptyList(), Collections.emptyList(), archive,
                            !directory.equals(archive.toAbsolutePath().normalize().getParent()), false,
                            false, failure.getMessage()));
                    }
                }
            }
        }
        DependencyResolution versions = ExtensionVersions.resolve(descriptors,
            manager.extensionStates().selectedVersions());
        ExtensionSelection selection = ExtensionSelection.resolve(descriptors,
            manager.extensionStates().disabledIds(), manager.extensionStates().selectedVersions());
        versionSelectionRequired = versions.excluded().keySet().stream()
            .anyMatch(id -> !manager.extensionStates().isDisabled(id));
        LoadReport loaded = report.get();
        List<ExtensionEntry> result = new ArrayList<>();
        for (ExtensionDescriptor descriptor : descriptors) {
            String problem = selection.excluded().getOrDefault(descriptor.id(), "");
            if (!changed && loaded != null && loaded.failed().containsKey(descriptor.id())) {
                problem = loaded.failed().get(descriptor.id());
            }
            if (manager.recoveredFailures().containsKey(descriptor.id())) {
                problem = manager.recoveredFailures().get(descriptor.id());
            }
            ExtensionHandle handle = manager.handle(descriptor.id());
            boolean selected = versions.loadOrder().contains(descriptor);
            boolean active = handle != null && handle.isEnabled()
                && ExtensionVersions.identity(handle.descriptor()).equals(ExtensionVersions.identity(descriptor));
            result.add(entry(descriptor, active, selected ? problem : "", selected,
                versions.excluded().containsKey(descriptor.id())));
        }
        result.addAll(broken);
        return Collections.unmodifiableList(result);
    }

    @Override
    public synchronized void setEnabled(String id, boolean enabled) throws IOException {
        if (entries().stream().noneMatch(entry -> id.equals(entry.id()))) {
            throw new IOException("Unknown extension id: " + id);
        }
        if (manager.extensionStates().isDisabled(id) != !enabled) {
            manager.extensionStates().setDisabled(id, !enabled);
            changed();
        }
    }

    @Override
    public synchronized void selectVersion(Path archive) throws IOException {
        Path source = archive.toAbsolutePath().normalize();
        ExtensionEntry selected = null;
        for (ExtensionEntry entry : entries()) {
            if (entry.valid() && source.equals(entry.source())) { selected = entry; break; }
        }
        if (selected == null) { throw new IOException("Unknown extension archive: " + archive); }
        try {
            ExtensionDescriptor descriptor = ExtensionDescriptorReader.read(source);
            String identity = ExtensionVersions.identity(descriptor);
            if (!identity.equals(manager.extensionStates().selectedVersions().get(descriptor.id()))) {
                manager.extensionStates().setSelectedVersion(descriptor.id(), identity);
                changed();
            }
        } catch (ExtensionException failure) { throw new IOException(failure); }
    }

    @Override
    public synchronized ExtensionEntry importArchive(InputStream input, boolean replace) throws IOException {
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "import-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[16384];
                long total = 0;
                int size;
                while ((size = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) { throw new InterruptedIOException(); }
                    total += size;
                    if (total > MAX_ARCHIVE_BYTES) { throw new IOException("Extension exceeds 128 MiB"); }
                    output.write(buffer, 0, size);
                }
            }
            ExtensionDescriptor descriptor;
            try {
                descriptor = ExtensionDescriptorReader.read(temporary);
            } catch (ExtensionException failure) {
                throw new IOException(failure.getMessage(), failure);
            }
            if (!descriptor.id().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")) {
                throw new IOException("Invalid extension id: " + descriptor.id());
            }
            try (JarFile jar = new JarFile(temporary.toFile())) {
                if (jar.getJarEntry(descriptor.mainClass().replace('.', '/') + ".class") == null) {
                    throw new IOException("The entry point class is missing: " + descriptor.mainClass());
                }
            }
            validator.validate(temporary);
            Path target = directory.resolve("extension-" + descriptor.id() + "-"
                + descriptor.sourceSha256().substring(0, 16) + ".jar");
            List<ExtensionEntry> matches = new ArrayList<>();
            for (ExtensionEntry existing : entries()) {
                if (descriptor.id().equals(existing.id())) { matches.add(existing); }
            }
            if (matches.stream().anyMatch(entry -> entry.source() == null)) {
                throw new IOException("Cannot replace a built-in extension: " + descriptor.id());
            }
            List<ExtensionEntry> sameVersion = new ArrayList<>();
            for (ExtensionEntry existing : matches) {
                if (descriptor.version().equals(existing.version())) { sameVersion.add(existing); }
            }
            boolean replaceSelected = false;
            if (!sameVersion.isEmpty()) {
                if (sameVersion.size() > 1) { throw new IOException("Remove duplicate archives of version " + descriptor.version() + " before replacing it"); }
                ExtensionEntry existing = sameVersion.get(0);
                if (existing.builtIn()) { throw new IOException("Bundled archives cannot be replaced"); }
                if (!replace) { throw new FileAlreadyExistsException(descriptor.id() + " " + descriptor.version()); }
                target = managed(existing.source());
                replaceSelected = existing.versionSelected();
            } else if (Files.exists(target)) {
                throw new IOException("The destination is occupied by another archive: " + target);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            // A replacement of the chosen version keeps that decision, even though its bytes changed.
            changed();
            if (replaceSelected) {
                manager.extensionStates().setSelectedVersion(descriptor.id(), ExtensionVersions.identity(descriptor));
            }
            for (ExtensionEntry entry : entries()) {
                if (target.equals(entry.source())) { return entry; }
            }
            throw new IOException("Imported archive is missing from the catalog: " + target);
        } finally { Files.deleteIfExists(temporary); }
    }

    @Override
    public synchronized void remove(String id) throws IOException {
        ExtensionEntry entry = require(id);
        if (entry.builtIn()) { throw new IOException("Built-in extensions cannot be removed"); }
        removeArchive(entry.source());
    }

    @Override
    public synchronized void removeArchive(Path archive) throws IOException {
        Path file = managed(archive);
        if (!Files.isRegularFile(file) || !file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IOException("Not an extension archive: " + file);
        }
        Files.delete(file);
        changed();
    }

    private Path managed(Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        if (directory.equals(normalized.getParent()) && !Files.isSymbolicLink(normalized)) { return normalized; }
        throw new IOException("Archive is outside the managed directories: " + file);
    }

    private ExtensionEntry require(String id) throws IOException {
        List<ExtensionEntry> matches = new ArrayList<>();
        for (ExtensionEntry entry : entries()) { if (id.equals(entry.id())) { matches.add(entry); } }
        if (matches.size() != 1) { throw new IOException("Unknown or duplicate extension id: " + id); }
        return matches.get(0);
    }

    private ExtensionEntry entry(ExtensionDescriptor descriptor, boolean loaded, String problem,
                                 boolean selected, boolean choiceRequired) {
        return new ExtensionEntry(descriptor.id(), descriptor.name(), descriptor.version(),
            descriptor.description(), descriptor.authors(), descriptor.dependsOn(), descriptor.source(),
            descriptor.isBuiltIn() || !directory.equals(descriptor.source().toAbsolutePath().normalize().getParent()),
            !manager.extensionStates().isDisabled(descriptor.id()), loaded, problem, selected, choiceRequired);
    }

    private void changed() {
        changed = true;
        hooks.publish(UiHooks.EXTENSIONS_CHANGED, null);
    }
}
