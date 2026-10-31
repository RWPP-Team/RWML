/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml;

import io.github.rwml.api.ExtensionException;
import io.github.rwml.core.ExtensionDescriptor;
import io.github.rwml.core.ExtensionDescriptorReader;
import io.github.rwml.core.ExtensionSelection;
import io.github.rwml.core.ResourcePackInstaller;
import io.github.rwml.host.ResourcePackEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 资源包替换游戏自己的资源文件，因此三件事必须成立：描述符能把它与其他扩展区分开；同一时间只有一个包生效；
 * 物化结果可复用且不会写到目录之外。
 */
class ResourcePackTest {

    @TempDir
    Path tempDirectory;

    @Test
    void descriptorMarksResourcePackAndAllowsMissingEntryPoint() throws Exception {
        Path jar = tempDirectory.resolve("pack.jar");
        writeJar(jar, Map.of("META-INF/extension.properties",
            "id=com.example.pack\nversion=2.0\nresourcePack=true\n".getBytes(StandardCharsets.UTF_8)));

        ExtensionDescriptor descriptor = ExtensionDescriptorReader.read(jar);

        assertTrue(descriptor.resourcePack());
        assertEquals("", descriptor.mainClass());
    }

    @Test
    void ordinaryExtensionWithoutEntryPointIsRejected() throws Exception {
        Path jar = tempDirectory.resolve("plain.jar");
        writeJar(jar, Map.of("META-INF/extension.properties",
            "id=com.example.plain\n".getBytes(StandardCharsets.UTF_8)));

        ExtensionException failure = assertThrows(ExtensionException.class,
            () -> ExtensionDescriptorReader.read(jar));
        assertTrue(failure.getMessage().contains("mainClass"));
    }

    @Test
    void onlyThePersistedPackLoadsAndTheOtherIsReportedAsDisabled() {
        ExtensionDescriptor first = pack("pack-first");
        ExtensionDescriptor second = pack("pack-second");
        ExtensionDescriptor ordinary = ordinary("ordinary");

        ExtensionSelection plan = ExtensionSelection.resolve(
            List.of(first, second, ordinary), Set.of(), Map.of(), "pack-second");

        assertNotNull(plan.resourcePack());
        assertEquals("pack-second", plan.resourcePack().id());
        assertEquals(List.of(first.id()), List.copyOf(plan.resourcePackExcluded().keySet()));
        assertTrue(plan.disabled().contains(first.id()));
        assertFalse(plan.enabled().contains(first));
        assertTrue(plan.enabled().contains(second));
    }

    @Test
    void withoutAPersistedChoiceTheFirstPackInLoadOrderWins() {
        ExtensionDescriptor first = pack("pack-a");
        ExtensionDescriptor second = pack("pack-b");

        ExtensionSelection plan = ExtensionSelection.resolve(List.of(first, second), Set.of(), Map.of(), null);

        assertEquals("pack-a", plan.resourcePack().id());
        assertEquals(Set.of("pack-b"), Set.copyOf(plan.disabled()));
    }

    @Test
    void preferredPackThatIsNotInstalledFallsBackToLoadOrder() {
        ExtensionDescriptor first = pack("pack-a");
        ExtensionDescriptor second = pack("pack-b");

        ExtensionSelection plan = ExtensionSelection.resolve(
            List.of(first, second), Set.of(), Map.of(), "pack-removed");

        assertEquals("pack-a", plan.resourcePack().id());
        assertEquals(Set.of("pack-b"), Set.copyOf(plan.disabled()));
    }

    @Test
    void installerWritesOnlyTheFilesThePackProvidesAndReusesTheResult() throws Exception {
        Path packArchive = tempDirectory.resolve("materials.jar");
        writeJar(packArchive, Map.of(
            "units/tank.png", "pack tank".getBytes(StandardCharsets.UTF_8),
            "assets/gui/panel.png", "pack panel".getBytes(StandardCharsets.UTF_8),
            "META-INF/extension.properties", "id=com.example.materials\n".getBytes(StandardCharsets.UTF_8),
            "readme.txt", "not a resource".getBytes(StandardCharsets.UTF_8)));
        TestEnvironment environment = new TestEnvironment(tempDirectory.resolve("generated"));

        ResourcePackInstaller.Result first = ResourcePackInstaller.install(environment, descriptorFor(packArchive));

        assertFalse(first.reused());
        assertEquals(2, first.entries());
        assertEquals("pack tank", read(first.directory().resolve("units/tank.png")));
        assertEquals("pack panel", read(first.directory().resolve("gui/panel.png")));
        assertFalse(Files.exists(first.directory().resolve("readme.txt")));
        assertEquals(first.directory(), environment.activated);

        // 改动物化结果后仍然复用，证明第二次物化跳过了整个流程。
        Files.write(first.directory().resolve("units/tank.png"), "edited".getBytes(StandardCharsets.UTF_8));
        ResourcePackInstaller.Result second = ResourcePackInstaller.install(environment, descriptorFor(packArchive));

        assertTrue(second.reused());
        assertEquals("edited", read(second.directory().resolve("units/tank.png")));
    }

    @Test
    void aChangedPackReplacesThePreviousContent() throws Exception {
        Path first = tempDirectory.resolve("first.jar");
        writeJar(first, Map.of("units/tank.png", "first".getBytes(StandardCharsets.UTF_8)));
        TestEnvironment environment = new TestEnvironment(tempDirectory.resolve("generated"));
        ResourcePackInstaller.Result installed = ResourcePackInstaller.install(environment, descriptorFor(first));

        Path second = tempDirectory.resolve("second.jar");
        writeJar(second, Map.of("units/tank.png", "second".getBytes(StandardCharsets.UTF_8)));
        ResourcePackInstaller.Result replaced = ResourcePackInstaller.install(environment,
            descriptorFor(second, "another-digest"));

        assertFalse(replaced.reused());
        assertEquals("second", read(replaced.directory().resolve("units/tank.png")));
        assertEquals(installed.directory(), replaced.directory());
    }

    @Test
    void installerRejectsEntriesThatEscapeTheResourceDirectory() throws Exception {
        Path packArchive = tempDirectory.resolve("escaping.jar");
        writeJar(packArchive, Map.of(
            "units/../../outside.png", "escape".getBytes(StandardCharsets.UTF_8)));
        TestEnvironment environment = new TestEnvironment(tempDirectory.resolve("generated"));

        assertThrows(IOException.class,
            () -> ResourcePackInstaller.install(environment, descriptorFor(packArchive)));
    }

    @Test
    void clearingRemovesTheFilesAndDeactivatesTheDirectory() throws Exception {
        Path packArchive = tempDirectory.resolve("pack.jar");
        writeJar(packArchive, Map.of("units/tank.png", "pack tank".getBytes(StandardCharsets.UTF_8)));
        TestEnvironment environment = new TestEnvironment(tempDirectory.resolve("generated"));
        ResourcePackInstaller.Result installed = ResourcePackInstaller.install(environment, descriptorFor(packArchive));

        ResourcePackInstaller.clear(environment);

        assertFalse(Files.exists(installed.directory().resolve("units/tank.png")));
        assertNull(environment.activated);
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private static ExtensionDescriptor pack(String id) {
        return new ExtensionDescriptor(id, id, "1", "", List.of(), "", "", "", "", List.of(), "1", 8,
            "", "", true, List.of(), List.of(), List.of(), Path.of(id + ".jar"), 0, id);
    }

    private static ExtensionDescriptor ordinary(String id) {
        return new ExtensionDescriptor(id, id, "1", "", List.of(), "", "", "", "", List.of(), "1", 8,
            "Main", "", false, List.of(), List.of(), List.of(), Path.of(id + ".jar"), 0, id);
    }

    private static ExtensionDescriptor descriptorFor(Path archive) {
        return descriptorFor(archive, "digest");
    }

    private static ExtensionDescriptor descriptorFor(Path archive, String digest) {
        return new ExtensionDescriptor("com.example.materials", "Materials", "1.0", "", List.of(), "", "", "",
            "", List.of(), "1.0", 8, "", "", true, List.of(), List.of(), List.of(), archive, 0L, digest);
    }

    private static void writeJar(Path jar, Map<String, byte[]> entries) throws IOException {
        Map<String, byte[]> ordered = new LinkedHashMap<>(entries);
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream zip = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : ordered.entrySet()) {
                zip.putNextEntry(new JarEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** 物化目录初始为空，资源包写入的一切都来自归档。 */
    private static final class TestEnvironment implements ResourcePackEnvironment {

        private final Path root;
        private Path activated;

        private TestEnvironment(Path root) {
            this.root = root;
        }

        @Override
        public boolean supported() {
            return true;
        }

        @Override
        public Path directory() {
            return root;
        }

        @Override
        public void activated(Path directory) {
            activated = directory;
        }
    }
}
