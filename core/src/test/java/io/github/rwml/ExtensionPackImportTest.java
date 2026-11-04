/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml;

import io.github.rwml.api.Extension;
import io.github.rwml.api.ExtensionContext;
import io.github.rwml.api.MixinBridge;
import io.github.rwml.api.ProgressListener;
import io.github.rwml.api.extensions.ExtensionEntry;
import io.github.rwml.api.extensions.ExtensionPackReport;
import io.github.rwml.api.ui.UiHooks;
import io.github.rwml.core.DefaultExtensionLibrary;
import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.core.ExtensionManager;
import io.github.rwml.host.HostPlatform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** zip 整合包导入：多扩展、重复安装与清单意图。 */
class ExtensionPackImportTest {
    @TempDir Path root;

    @Test
    void importsEveryExtensionAndReportsMembersThatAreNotExtensions() throws Exception {
        DefaultExtensionLibrary library = library();
        byte[] pack = pack(
            "one.jar", archive("lib.one", "1.0", ""),
            "nested/two.jar", archive("lib.two", "1.5", "dependsOn=lib.one>=1"),
            "docs/readme.txt", "documentation, not an extension".getBytes(StandardCharsets.UTF_8),
            "broken.jar", new byte[]{1, 2, 3},
            "__MACOSX/._two.jar", new byte[]{1});

        ExtensionPackReport report = library.importPack(new ByteArrayInputStream(pack), false);

        assertEquals(2, report.installed(), report.failed().toString());
        assertTrue(report.failed().containsKey("broken.jar"));
        assertTrue(report.skipped().isEmpty());
        assertTrue(report.requiresRestart());
        assertTrue(library.restartRequired());
        List<ExtensionEntry> entries = library.entries();
        assertEquals(2, entries.size());
        assertTrue(entries.stream().allMatch(ExtensionEntry::valid));
        assertTrue(entries.stream().allMatch(ExtensionEntry::enabled));

        ExtensionPackReport again = library.importPack(new ByteArrayInputStream(pack), false);
        assertEquals(0, again.installed());
        assertEquals(2, again.skipped().size());
        assertEquals(2, library.entries().size());
    }

    @Test
    void packResolvesDuplicateIdsBeforeApplyingItsManifest() throws Exception {
        DefaultExtensionLibrary library = library();
        byte[] pack = pack(
            "core-1.jar", archive("lib.core", "1.0", ""),
            "core-2.jar", archive("lib.core", "2.0", ""),
            "rwml-pack.properties", ("name=Demo pack\nversion=2.1\nauthors=RWPP-Team\npin.lib.core=1.0\n"
                + "disabled=lib.core\n").getBytes(StandardCharsets.UTF_8));

        ExtensionPackReport report = library.importPack(new ByteArrayInputStream(pack), false);

        assertEquals(2, report.installed(), report.failed().toString());
        assertEquals("Demo pack", report.name());
        assertEquals("2.1", report.version());
        // The older archive was replaced; an unavailable pin cannot select it.
        assertFalse(library.versionSelectionRequired());
        ExtensionEntry selected = library.entries().stream().filter(ExtensionEntry::versionSelected)
            .findFirst().orElseThrow();
        assertEquals("2.0", selected.version());
        assertFalse(selected.enabled());
        assertEquals(1, library.entries().size());
    }

    @Test
    void packWithoutAnyJarIsRejectedAndNothingIsWritten() throws Exception {
        DefaultExtensionLibrary library = library();
        byte[] pack = pack("docs/readme.txt", "nothing".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> library.importPack(new ByteArrayInputStream(pack), false));
        assertTrue(library.entries().isEmpty());
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(library.directory())) {
            assertEquals(0, files.count(), "temporary pack files must not remain");
        }
    }

    @Test
    void replacingAnInstalledVersionRetiresOtherVersionsOfTheSameExtension() throws Exception {
        DefaultExtensionLibrary library = library();
        library.importArchive(new ByteArrayInputStream(archive("lib.core", "1.0", "")), false);
        library.importArchive(new ByteArrayInputStream(archive("lib.core", "2.0", "")), false);
        byte[] pack = pack("core-updated.jar", archive("lib.core", "2.0", "description=updated"));

        ExtensionPackReport report = library.importPack(new ByteArrayInputStream(pack), true);

        assertEquals(1, report.installed(), report.failed().toString());
        assertEquals(1, library.entries().size());
        assertEquals("updated", library.entries().stream()
            .filter(entry -> entry.version().equals("2.0")).findFirst().orElseThrow().description());
    }

    // ------------------------------------------------------------------

    private DefaultExtensionLibrary library() throws Exception {
        ExtensionManager manager = new ExtensionManager(root, new HostPlatform() {
            public MixinBridge mixins() { return null; }
            public List<ExtensionDescriptor> builtInExtensions() { return Collections.emptyList(); }
            public List<String> stageArchives(Path path) { return Collections.emptyList(); }
            public ClassLoader createExtensionLoader(ExtensionDescriptor descriptor) { throw new AssertionError("Management must not load code"); }
            public ExtensionDescriptor reconcileJavaRequirement(ExtensionDescriptor descriptor, ProgressListener progress) { return descriptor; }
        });
        List<Path> directories = new java.util.ArrayList<>();
        directories.add(manager.extensionDirectory());
        return new DefaultExtensionLibrary(manager, manager.extensionDirectory(),
            () -> directories, () -> null, archive -> { }, new UiHooks(failure -> {
                throw new AssertionError(failure);
            }));
    }

    private static byte[] pack(Object... nameAndBytes) throws IOException {
        Map<String, byte[]> members = new LinkedHashMap<>();
        for (int index = 0; index < nameAndBytes.length; index += 2) {
            members.put((String) nameAndBytes[index], (byte[]) nameAndBytes[index + 1]);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> member : members.entrySet()) {
                zip.putNextEntry(new ZipEntry(member.getKey()));
                zip.write(member.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
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
