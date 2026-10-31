/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core.transform;

import io.github.rwml.core.transform.ArchiveClassPatchesTestPatches.AppendA;
import io.github.rwml.core.transform.ArchiveClassPatchesTestPatches.AppendB;
import io.github.rwml.core.transform.ArchiveClassPatchesTestPatches.CountedPatch;
import io.github.rwml.core.transform.ArchiveClassPatchesTestPatches.ReturnsNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ArchiveClassPatchesTest {
    private static final String TARGET = "example/Target";

    @TempDir Path temporaryDirectory;

    @Test
    void appliesArchivesInRegistrationOrder() throws Exception {
        Path first = archive("first.jar", Map.of(
            ArchiveClassPatches.MANIFEST_PATH, declaration(TARGET, AppendA.class)));
        Path second = archive("second.jar", Map.of(
            ArchiveClassPatches.MANIFEST_PATH, declaration(TARGET, AppendB.class)));
        ArchiveClassPatches patches = new ArchiveClassPatches();

        assertEquals(Set.of(TARGET), patches.registerArchive(first, getClass().getClassLoader()));
        assertEquals(Set.of(TARGET), patches.registerArchive(second, getClass().getClassLoader()));

        assertEquals("inputAB", apply(patches, "input"));
        assertEquals(List.of(TARGET), List.copyOf(patches.targets()));
        assertTrue(patches.has(TARGET));
    }

    @Test
    void registeringTheSameArchiveTwiceIsIdempotent() throws Exception {
        CountedPatch.INSTANCES.set(0);
        Path archive = archive("counted.jar", Map.of(
            ArchiveClassPatches.MANIFEST_PATH, declaration(TARGET, CountedPatch.class)));
        ArchiveClassPatches patches = new ArchiveClassPatches();

        Set<String> firstTargets = patches.registerArchive(archive, getClass().getClassLoader());
        Set<String> secondTargets = patches.registerArchive(archive, getClass().getClassLoader());

        assertEquals(Set.of(TARGET), firstTargets);
        assertEquals(firstTargets, secondTargets);
        assertEquals(1, CountedPatch.INSTANCES.get());
        assertEquals("inputI", apply(patches, "input"));
    }

    @Test
    void unregisterRemovesOnlyThatArchivesChainEntriesAndResetClearsTheRest() throws Exception {
        Path first = archive("first.jar", Map.of(
            ArchiveClassPatches.MANIFEST_PATH, declaration(TARGET, AppendA.class)));
        Path second = archive("second.jar", Map.of(
            ArchiveClassPatches.MANIFEST_PATH, declaration(TARGET, AppendB.class)));
        ArchiveClassPatches patches = new ArchiveClassPatches();
        patches.registerArchive(first, getClass().getClassLoader());
        patches.registerArchive(second, getClass().getClassLoader());
        assertEquals("inputAB", apply(patches, "input"));

        patches.unregisterArchive(first);
        assertEquals("inputB", apply(patches, "input"));
        assertTrue(patches.has(TARGET));

        patches.reset();
        assertFalse(patches.has(TARGET));
        assertTrue(patches.targets().isEmpty());
        assertEquals("input", apply(patches, "input"));
    }

    @Test
    void nullResultIsAttributedToTheOwningArchiveAndPatch() throws Exception {
        Path archive = archive("null-result.jar", Map.of(
            ArchiveClassPatches.MANIFEST_PATH, declaration(TARGET, ReturnsNull.class)));
        ArchiveClassPatches patches = new ArchiveClassPatches();
        patches.registerArchive(archive, getClass().getClassLoader());

        ClassFilePatchException failure = assertThrows(ClassFilePatchException.class,
            () -> apply(patches, "input"));

        assertEquals(archive.toAbsolutePath().normalize(), failure.archive());
        assertEquals(TARGET, failure.internalName());
        assertEquals(ReturnsNull.class.getName(), failure.patchClassName());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }

    @Test
    void readsPlatformSidecarManifestsAlongsideTheBaseManifest() throws Exception {
        Path archive = archive("sidecar.jar", Map.of(
            ArchiveClassPatches.MANIFEST_PATH, declaration(TARGET, AppendA.class),
            "META-INF/rwml/class-patches-jvm.properties",
                declaration(TARGET, AppendA.class) + declaration("example/Other", AppendB.class)));

        Map<String, String> declarations = ArchiveClassPatches.readDeclarations(archive);

        assertEquals(Map.of(TARGET, AppendA.class.getName(), "example/Other", AppendB.class.getName()), declarations);
    }

    @Test
    void rejectsDuplicateTargetsWithDifferentImplementationsAcrossManifests() throws Exception {
        Path archive = archive("duplicate.jar", Map.of(
            ArchiveClassPatches.MANIFEST_PATH, declaration(TARGET, AppendA.class),
            "META-INF/rwml/class-patches-jvm.properties", declaration(TARGET, AppendB.class)));

        IOException failure = assertThrows(IOException.class,
            () -> ArchiveClassPatches.readDeclarations(archive));

        assertTrue(failure.getMessage().contains(TARGET));
    }

    @Test
    void rejectsConflictingDuplicateKeysWithinOneManifest() throws Exception {
        Path archive = archive("duplicate-key.jar", Map.of(
            ArchiveClassPatches.MANIFEST_PATH,
                declaration(TARGET, AppendA.class) + declaration(TARGET, AppendB.class)));

        IOException failure = assertThrows(IOException.class,
            () -> ArchiveClassPatches.readDeclarations(archive));

        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    @Test
    void identicalDuplicateKeysWithinOneManifestAreIdempotent() throws Exception {
        Path archive = archive("identical-duplicate-key.jar", Map.of(
            ArchiveClassPatches.MANIFEST_PATH,
                declaration(TARGET, AppendA.class) + declaration(TARGET, AppendA.class)));

        assertEquals(Map.of(TARGET, AppendA.class.getName()), ArchiveClassPatches.readDeclarations(archive));
    }

    private Path archive(String fileName, Map<String, String> entries) throws IOException {
        Path archive = temporaryDirectory.resolve(fileName);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive))) {
            for (Map.Entry<String, String> entry : new LinkedHashMap<>(entries).entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.ISO_8859_1));
                output.closeEntry();
            }
        }
        return archive;
    }

    private static String declaration(String target, Class<?> patchClass) {
        return target + "=" + patchClass.getName() + "\n";
    }

    private static String apply(ArchiveClassPatches patches, String input) {
        return new String(patches.apply(TARGET, input.getBytes(StandardCharsets.UTF_8),
            ArchiveClassPatchesTest.class.getClassLoader()), StandardCharsets.UTF_8);
    }
}
