/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import static org.junit.jupiter.api.Assertions.*;

class ExtensionStorageTest {
    @TempDir Path root;
    private static void write(Path path, String text) throws Exception {
        Files.write(path, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void sameSizeAndTimeReplacementUpdatesPrivateCopyAndFingerprint() throws Exception {
        Path shared = Files.createDirectory(root.resolve("shared"));
        Path target = root.resolve("private");
        Path jar = shared.resolve("example.JAR");
        write(jar, "first");
        FileTime time = Files.getLastModifiedTime(jar);
        SharedExtensions.synchronize(shared, target);
        String before = ExtensionCatalog.fingerprint(shared);
        write(jar, "other"); Files.setLastModifiedTime(jar, time);
        assertNotEquals(before, ExtensionCatalog.fingerprint(shared));
        assertEquals(1, SharedExtensions.synchronize(shared, target).size());
        assertEquals("other", new String(Files.readAllBytes(target.resolve("example.JAR")), java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void unchangedCopyIsReusedAndRemovalRemovesOnlyImportedArchives() throws Exception {
        Path shared = Files.createDirectory(root.resolve("shared"));
        Path target = root.resolve("private");
        Path jar = shared.resolve("ui.jar"); write(jar, "ui");
        SharedExtensions.synchronize(shared, target);
        Path copy = target.resolve("ui.jar");
        FileTime marker = FileTime.fromMillis(1000000);
        Files.setLastModifiedTime(copy, marker);
        write(target.resolve("keep.txt"), "data");
        SharedExtensions.synchronize(shared, target);
        assertEquals(marker, Files.getLastModifiedTime(copy));
        String before = ExtensionCatalog.fingerprint(shared);
        Files.delete(jar);
        assertNotEquals(before, ExtensionCatalog.fingerprint(shared));
        assertTrue(SharedExtensions.synchronize(shared, target).isEmpty());
        assertFalse(Files.exists(copy)); assertTrue(Files.exists(target.resolve("keep.txt")));
    }
    @Test void enablingStateParticipatesInIdentity() throws Exception {
        Path state = root.resolve("state.properties");
        String before = ExtensionCatalog.fingerprint(state);
        write(state, "disabled=ui");
        assertNotEquals(before, ExtensionCatalog.fingerprint(state));
    }
}
