/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import io.github.rwml.api.ExtensionException;
import io.github.rwml.api.extensions.ExtensionEntry;
import io.github.rwml.api.extensions.ExtensionLibrary;
import io.github.rwml.api.extensions.ExtensionPackReport;
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
import java.util.Map;
import java.util.function.Supplier;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 两种宿主共用一个归档目录，且不受本会话已加载扩展的影响。 */
public final class DefaultExtensionLibrary implements ExtensionLibrary {

    @FunctionalInterface
    public interface Validator {
        void validate(Path archive) throws IOException;
    }

    private static final long MAX_ARCHIVE_BYTES = 128L * 1024 * 1024;
    /** 整合包包含多个归档，因此上限高于单个扩展，但仍设有界限。 */
    private static final long MAX_PACK_BYTES = 512L * 1024 * 1024;
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
    public void selectResourcePack(String id) throws IOException {
        try {
            manager.selectResourcePack(id);
        } catch (ExtensionException e) {
            throw new IOException(e.getMessage(), e);
        }
        changed = true;
        hooks.publish(UiHooks.EXTENSIONS_CHANGED, null);
    }

    @Override
    public String selectedResourcePack() {
        return manager.extensionStates().selectedResourcePack();
    }

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
            manager.extensionStates().disabledIds(), manager.extensionStates().selectedVersions(),
            manager.extensionStates().selectedResourcePack());
        // 版本冲突不再需要人工选择：只有指向已删除归档的显式选择才必须由用户重新确认。
        versionSelectionRequired = ExtensionVersions.hasStaleSelection(descriptors,
            manager.extensionStates().selectedVersions());
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
            String incompatibility = RwmlVersion.incompatibility(descriptor);
            String visibleProblem = incompatibility != null ? incompatibility : selected ? problem : "";
            result.add(entry(descriptor, active, visibleProblem, selected,
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
            copy(input, temporary, MAX_ARCHIVE_BYTES);
            ExtensionEntry entry = install(temporary, replace, false);
            if (entry == null) { throw new IOException("The archive could not be installed"); }
            return entry;
        } finally { Files.deleteIfExists(temporary); }
    }

    /**
     * 导入 zip 整合包。
     *
     * <p>包内每个扩展独立安装，一个成员的失败不影响其他成员。包里已有的同一版本不再重复写入，因此重复导入同一个包是安全的。
     * 清单中的固定版本与启用状态在安装完成后写入，使下一次启动按作者意图解析。</p>
     */
    @Override
    public synchronized ExtensionPackReport importPack(InputStream input, boolean replace) throws IOException {
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "pack-", ".zip");
        try {
            copy(input, temporary, MAX_PACK_BYTES);
            ExtensionPackImporter.Result result = ExtensionPackImporter.importPack(temporary, directory,
                (jar, member) -> install(jar, replace, true));
            apply(result.manifest());
            return result.report();
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void copy(InputStream input, Path target, long limit) throws IOException {
        try (OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[16384];
            long total = 0;
            int size;
            while ((size = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) { throw new InterruptedIOException(); }
                total += size;
                if (total > limit) { throw new IOException("Archive exceeds " + (limit / (1024 * 1024)) + " MiB"); }
                output.write(buffer, 0, size);
            }
        }
    }

    /**
     * 校验并写入一个扩展归档。单文件导入与整合包导入共用此流程。
     *
     * @param tolerateExisting 已安装同一版本时返回 {@code null} 而不是报告冲突
     * @return 目录中对应的条目；{@code tolerateExisting} 且版本已存在时返回 {@code null}
     */
    private ExtensionEntry install(Path temporary, boolean replace, boolean tolerateExisting) throws IOException {
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
        // 整合包成员的临时文件本身就是可读的归档，因此它也在目录列表中；比对时排除自己。
        Path staging = temporary.toAbsolutePath().normalize();
        List<ExtensionEntry> matches = new ArrayList<>();
        for (ExtensionEntry existing : entries()) {
            if (staging.equals(existing.source())) { continue; }
            if (descriptor.id().equals(existing.id())) { matches.add(existing); }
        }
        if (matches.stream().anyMatch(entry -> entry.source() == null)) {
            throw new IOException("Cannot replace a built-in extension: " + descriptor.id());
        }
        ExtensionEntry identical = null;
        for (ExtensionEntry existing : matches) {
            int order = ExtensionVersion.compare(descriptor.version(), existing.version());
            if (order < 0 && !replace) {
                throw new IOException("A newer version of " + descriptor.id() + " is already installed: " + existing.version());
            }
            if (order == 0) {
                if (existing.builtIn() && replace) throw new IOException("Bundled archives cannot be replaced");
                ExtensionDescriptor old;
                try { old = ExtensionDescriptorReader.read(existing.source()); }
                catch (ExtensionException failure) { throw new IOException(failure); }
                if (descriptor.sourceSha256().equals(old.sourceSha256())) identical = existing;
                else if (!replace) throw new FileAlreadyExistsException(descriptor.id() + " " + descriptor.version());
                else if (existing.builtIn()) throw new IOException("Bundled archives cannot be replaced");
            }
        }
        boolean created = identical == null;
        if (identical != null) {
            target = identical.source().toAbsolutePath().normalize();
        } else {
            if (Files.exists(target)) throw new IOException("The destination is occupied by another archive: " + target);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target);
            }
        }
        // Publish the new identity before retiring old files, so a failed state write never
        // strands the user without the old version. Loaded archives must remain untouched.
        boolean selectionChanged = false;
        try {
            String identity = ExtensionVersions.identity(descriptor);
            if (!matches.isEmpty() && !identity.equals(manager.extensionStates().selectedVersions().get(descriptor.id()))) {
                manager.extensionStates().setSelectedVersion(descriptor.id(), identity);
                selectionChanged = true;
            }
        } catch (IOException failure) {
            if (created) Files.deleteIfExists(target);
            throw failure;
        }
        ExtensionHandle loaded = manager.handle(descriptor.id());
        Path loadedPath = loaded == null || loaded.descriptor().source() == null ? null
            : loaded.descriptor().source().toAbsolutePath().normalize();
        boolean retired = false;
        for (ExtensionEntry old : matches) {
            if (old.builtIn() || old.source() == null) continue;
            Path source = managed(old.source());
            if (source.equals(target) || source.equals(loadedPath)) continue;
            Path backup = directory.resolveSibling(directory.getFileName() + "-backup");
            try {
                Files.createDirectories(backup);
                Path destination = backup.resolve(source.getFileName() + "." + java.util.UUID.randomUUID());
                Files.move(source, destination);
                retired = true;
            } catch (IOException failure) {
                // The selected identity still prevents duplicate loading; preserve the archive
                // and give a bounded diagnostic if a file is locked or cannot be moved.
                System.err.println("[rwml] Could not retire old extension archive " + source + ": " + failure.getMessage());
            }
        }
        if (created || selectionChanged || retired) changed();
        if (identical != null && tolerateExisting) return null;
        for (ExtensionEntry entry : entries()) {
            if (target.equals(entry.source())) { return entry; }
        }
        throw new IOException("Imported archive is missing from the catalog: " + target);
    }

    /**
     * 应用整合包清单表达的意图。
     *
     * <p>清单只补充用户与作者的选择，不改变依赖图：固定版本写入版本选择，启用或禁用写入持久状态。包内没有的扩展被忽略，
     * 因为一个包可能只更新已安装扩展的一部分。</p>
     */
    private void apply(ExtensionPackManifest manifest) throws IOException {
        if (manifest.pins().isEmpty() && manifest.enabled().isEmpty() && manifest.disabled().isEmpty()) { return; }
        boolean touched = false;
        List<ExtensionEntry> current = entries();
        for (Map.Entry<String, String> pin : manifest.pins().entrySet()) {
            ExtensionEntry match = null;
            for (ExtensionEntry entry : current) {
                if (pin.getKey().equals(entry.id()) && pin.getValue().equals(entry.version())) { match = entry; break; }
            }
            if (match == null || match.source() == null) {
                System.err.println("[rwml] The pack pins " + pin.getKey() + " " + pin.getValue()
                    + ", which is not installed");
                continue;
            }
            try {
                ExtensionDescriptor descriptor = ExtensionDescriptorReader.read(match.source());
                manager.extensionStates().setSelectedVersion(match.id(), ExtensionVersions.identity(descriptor));
                touched = true;
            } catch (ExtensionException failure) { throw new IOException(failure.getMessage(), failure); }
        }
        touched |= toggle(current, manifest.enabled(), false);
        touched |= toggle(current, manifest.disabled(), true);
        if (touched) { changed(); }
    }

    private boolean toggle(List<ExtensionEntry> current, List<String> ids, boolean disable) throws IOException {
        boolean touched = false;
        for (String id : ids) {
            boolean known = false;
            for (ExtensionEntry entry : current) { known |= id.equals(entry.id()); }
            if (!known) {
                System.err.println("[rwml] The pack lists " + id + ", which is not installed");
                continue;
            }
            if (manager.extensionStates().isDisabled(id) != disable) {
                manager.extensionStates().setDisabled(id, disable);
                touched = true;
            }
        }
        return touched;
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
