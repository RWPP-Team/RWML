/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionNativeLibraryTest {

    @TempDir Path temporary;

    @Test
    void extractsAnEmbeddedNativeLibraryIntoTheExtensionDataDirectory() throws Exception {
        byte[] library = new byte[]{0, 1, 2, 3, 4};
        Path archive = temporary.resolve("extension.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new JarEntry("jni/arm64-v8a/liblua54.so"));
            output.write(library);
            output.closeEntry();
        }

        Path extracted = ExtensionNativeLibrary.extract(archive,
            "jni/arm64-v8a/liblua54.so", temporary.resolve("data"));

        assertTrue(extracted.startsWith(temporary.resolve("data/native")));
        assertEquals("liblua54.so", extracted.getFileName().toString());
        assertArrayEquals(library, Files.readAllBytes(extracted));
        assertEquals(extracted, ExtensionNativeLibrary.extract(archive,
            "jni/arm64-v8a/liblua54.so", temporary.resolve("data")));
    }

    @Test
    void rejectsTraversalAndMissingEntries() throws Exception {
        Path archive = temporary.resolve("extension.jar");
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(archive))) { }
        Path data = temporary.resolve("data");

        assertThrows(IOException.class, () -> ExtensionNativeLibrary.extract(archive, "../outside.so", data));
        assertThrows(IOException.class, () -> ExtensionNativeLibrary.extract(archive, "missing.so", data));
    }
}
