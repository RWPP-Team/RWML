/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml;

import io.github.rwml.api.Extension;
import io.github.rwml.api.ExtensionContext;
import io.github.rwml.api.MixinBridge;
import io.github.rwml.api.ProgressListener;
import io.github.rwml.api.extensions.ExtensionEntry;
import io.github.rwml.api.ui.UiHooks;
import io.github.rwml.core.DefaultExtensionLibrary;
import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.core.ExtensionManager;
import io.github.rwml.core.ExtensionStateStore;
import io.github.rwml.host.HostPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionLibraryTest {
    @TempDir Path root;

    @Test
    void importAndToggleDoNotRunCodeAndReplaceKeepsTheDisabledPreference() throws Exception {
        DefaultExtensionLibrary library = library();
        ExtensionEntry imported = library.importArchive(new ByteArrayInputStream(archive("example", "1", "")), false);
        library.setEnabled("example", false);
        assertTrue(library.restartRequired());
        assertFalse(library.entries().get(0).enabled());
        assertThrows(FileAlreadyExistsException.class, () -> library.importArchive(
            new ByteArrayInputStream(archive("example", "1", "description=replaced\n")), false));
        library.importArchive(new ByteArrayInputStream(archive("example", "1", "description=replaced\n")), true);
        ExtensionEntry replacement = library.entries().get(0);
        assertEquals(imported.source(), replacement.source());
        assertEquals("1", replacement.version());
        assertEquals("replaced", replacement.description());
        assertFalse(replacement.enabled());
        assertFalse(replacement.loaded());
        DefaultExtensionLibrary nextLaunch = library();
        assertFalse(nextLaunch.entries().get(0).enabled());
        nextLaunch.setEnabled("example", true);
        assertTrue(nextLaunch.entries().get(0).enabled());
    }

    @Test
    void invalidImportLeavesThePreviousArchiveIntactAndCannotEscapeTheDirectory() throws Exception {
        DefaultExtensionLibrary library = library();
        ExtensionEntry entry = library.importArchive(new ByteArrayInputStream(archive("example", "1", "")), false);
        byte[] before = Files.readAllBytes(entry.source());
        assertThrows(IOException.class, () -> library.importArchive(new ByteArrayInputStream(new byte[]{1, 2}), true));
        assertArrayEquals(before, Files.readAllBytes(entry.source()));
        assertThrows(IOException.class, () -> library.importArchive(new ByteArrayInputStream(archive("../outside", "1", "")), false));
        assertThrows(IOException.class, () -> library.removeArchive(root.resolve("outside.jar")));
        try (java.util.stream.Stream<Path> files = Files.list(library.directory())) {
            assertEquals(1, files.count(), "temporary import files must not remain");
        }
    }

    @Test
    void duplicateAndBrokenArchivesRemainVisibleAndCanBeRemovedWithoutDeletingData() throws Exception {
        DefaultExtensionLibrary library = library();
        ExtensionEntry entry = library.importArchive(new ByteArrayInputStream(archive("example", "1", "dependsOn=missing\n")), false);
        assertFalse(library.entries().get(0).problem().isEmpty());
        Path duplicate = library.directory().resolve("duplicate.jar");
        Files.copy(entry.source(), duplicate);
        library.setEnabled("example", false);
        assertFalse(library.entries().get(0).enabled());
        library.removeArchive(duplicate);
        Path broken = library.directory().resolve("broken.jar");
        Files.write(broken, new byte[]{1});
        assertTrue(library.entries().stream().anyMatch(item -> !item.valid()));
        library.removeArchive(broken);
        Path data = root.resolve("data/example/preserved.txt");
        Files.createDirectories(data.getParent());
        Files.write(data, new byte[]{1});
        library.remove("example");
        assertTrue(Files.exists(data));
        assertTrue(library.entries().isEmpty());
    }

    @Test
    void failedStateWriteDoesNotChangeTheInMemoryDecision() throws Exception {
        Path directory = root.resolve("data");
        ExtensionStateStore store = ExtensionStateStore.open(directory);
        Files.createDirectory(directory.resolve("extension-state.properties"));
        Files.write(directory.resolve("extension-state.properties/blocker"), new byte[]{1});
        assertThrows(IOException.class, () -> store.setDisabled("example", true));
        assertFalse(store.isDisabled("example"));
    }

    @Test
    void bundledArchivesCanBeDisabledButNotReplacedOrRemoved() throws Exception {
        Path bundled = root.resolve("bundled");
        Files.createDirectories(bundled);
        Path original = bundled.resolve("packaged.jar");
        Files.write(original, archive("packaged", "1", ""));
        DefaultExtensionLibrary library = library(bundled);
        assertTrue(library.entries().get(0).builtIn());
        library.setEnabled("packaged", false);
        assertFalse(library.entries().get(0).enabled());
        assertThrows(IOException.class, () -> library.removeArchive(original));
        assertThrows(IOException.class, () -> library.importArchive(
            new ByteArrayInputStream(archive("packaged", "1", "")), true));
        assertTrue(Files.exists(original));
    }

    @Test
    void differentVersionsCoexistAndChoiceSurvivesRestartAndRenaming() throws Exception {
        DefaultExtensionLibrary library = library();
        ExtensionEntry first = library.importArchive(new ByteArrayInputStream(archive("example", "1", "")), false);
        ExtensionEntry second = library.importArchive(new ByteArrayInputStream(archive("example", "2", "")), false);
        assertNotEquals(first.source(), second.source());
        assertEquals(2, library.entries().size());
        assertTrue(library.versionSelectionRequired());
        assertTrue(library.entries().stream().noneMatch(ExtensionEntry::versionSelected));
        library.selectVersion(second.source());
        assertEquals("2", library.entries().stream().filter(ExtensionEntry::versionSelected).findFirst().orElseThrow().version());
        assertFalse(library.versionSelectionRequired());
        library.setEnabled("example", false);
        Path renamed = second.source().resolveSibling("renamed.jar");
        Files.move(second.source(), renamed);
        DefaultExtensionLibrary next = library();
        assertEquals(renamed, next.entries().stream().filter(ExtensionEntry::versionSelected).findFirst().orElseThrow().source());
        assertTrue(next.entries().stream().noneMatch(ExtensionEntry::enabled));
        next.setEnabled("example", true);
        assertFalse(next.versionSelectionRequired());
        next.selectVersion(first.source());
        assertEquals("1", next.entries().stream().filter(ExtensionEntry::versionSelected).findFirst().orElseThrow().version());
    }

    @Test
    void replacingSelectedVersionKeepsChoiceButDoesNotRemoveOtherVersions() throws Exception {
        DefaultExtensionLibrary library = library();
        ExtensionEntry one = library.importArchive(new ByteArrayInputStream(archive("example", "1", "")), false);
        ExtensionEntry two = library.importArchive(new ByteArrayInputStream(archive("example", "2", "")), false);
        library.selectVersion(two.source());
        library.importArchive(new ByteArrayInputStream(archive("example", "2", "description=updated\n")), true);
        assertEquals(2, library.entries().size());
        ExtensionEntry selected = library.entries().stream().filter(ExtensionEntry::versionSelected).findFirst().orElseThrow();
        assertEquals(two.source(), selected.source());
        assertEquals("updated", selected.description());
        assertTrue(Files.exists(one.source()));
        assertThrows(IOException.class, () -> library.selectVersion(root.resolve("outside.jar")));
    }

    @Test
    void bundledVersionCanCoexistWithAnImportedVersionWithoutReplacingIt() throws Exception {
        Path bundled = Files.createDirectory(root.resolve("bundled"));
        Path original = bundled.resolve("packaged.jar");
        Files.write(original, archive("packaged", "1", ""));
        DefaultExtensionLibrary library = library(bundled);
        ExtensionEntry imported = library.importArchive(new ByteArrayInputStream(archive("packaged", "2", "")), false);
        assertEquals(2, library.entries().size());
        assertTrue(library.versionSelectionRequired());
        library.selectVersion(original);
        assertTrue(library.entries().stream().anyMatch(entry -> entry.builtIn() && entry.versionSelected()));
        library.selectVersion(imported.source());
        assertEquals("2", library.entries().stream().filter(ExtensionEntry::versionSelected).findFirst().orElseThrow().version());
        assertTrue(Files.exists(original));
    }

    @Test
    void failedChoiceWriteDoesNotChangeTheInMemoryDecision() throws Exception {
        ExtensionStateStore store = ExtensionStateStore.open(root.resolve("data"));
        Path file = root.resolve("data/extension-state.properties");
        Files.createDirectory(file);
        Files.write(file.resolve("blocker"), new byte[]{1});
        assertThrows(IOException.class, () -> store.setSelectedVersion("example", "digest"));
        assertTrue(store.selectedVersions().isEmpty());
    }

    private DefaultExtensionLibrary library(Path... extra) throws Exception {
        ExtensionManager manager = new ExtensionManager(root, new HostPlatform() {
            public MixinBridge mixins() { return null; }
            public List<ExtensionDescriptor> builtInExtensions() { return Collections.emptyList(); }
            public List<String> stageArchives(Path path) { return Collections.emptyList(); }
            public ClassLoader createExtensionLoader(ExtensionDescriptor descriptor) { throw new AssertionError("Management must not load code"); }
            public ExtensionDescriptor reconcileJavaRequirement(ExtensionDescriptor descriptor, ProgressListener progress) { return descriptor; }
        });
        List<Path> directories = new java.util.ArrayList<>();
        directories.add(manager.extensionDirectory());
        Collections.addAll(directories, extra);
        return new DefaultExtensionLibrary(manager, manager.extensionDirectory(),
            () -> directories, () -> null,
            archive -> { }, new UiHooks(failure -> fail(failure)));
    }

    private static byte[] archive(String id, String version, String extra) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry("META-INF/extension.properties"));
            jar.write(("id=" + id + "\nversion=" + version + "\nmainClass=" + Entry.class.getName() + "\n" + extra)
                .getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            String name = Entry.class.getName().replace('.', '/') + ".class";
            jar.putNextEntry(new JarEntry(name));
            try (InputStream input = Entry.class.getClassLoader().getResourceAsStream(name)) { input.transferTo(jar); }
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    public static final class Entry implements Extension {
        public void onLoad(ExtensionContext context) { throw new AssertionError("Management executed extension code"); }
    }
}
