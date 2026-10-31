/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionArchiveCacheTest {

    @TempDir Path temporary;

    @Test
    void cachedClassPathArchiveRetainsComposeResourcesAndNativeLibraries() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("classes.dex", new byte[]{'d', 'e', 'x', '\n', '0', '3', '5', 0});
        entries.put("composeResources/io.github.rwpp.generated/font/valorax.ttf",
            new byte[]{0, 1, 2, 3, (byte) 255});
        entries.put("composeResources/io.github.rwpp.generated/values/strings.xml",
            "<resources><string name=\"app\">RWPP</string></resources>".getBytes(StandardCharsets.UTF_8));
        entries.put("composeResources/io.github.rwpp.generated/files/config.toml",
            "[client]\nlauncher = true\n".getBytes(StandardCharsets.UTF_8));
        entries.put("jni/arm64-v8a/liblua54.so", new byte[]{7, 8, 9});
        Path source = archive("rwpp.jar", entries);
        String sha256 = AssetInstaller.Digest.sha256(source);
        Path directory = temporary.resolve("cache").resolve(sha256);

        Path cached = ExtensionArchiveCache.install(directory, source, sha256);

        assertTrue(ExtensionArchiveCache.isValid(directory, source, sha256));
        try (JarFile jar = new JarFile(cached.toFile())) {
            for (Map.Entry<String, byte[]> expected : entries.entrySet()) {
                try (InputStream in = jar.getInputStream(jar.getJarEntry(expected.getKey()))) {
                    assertArrayEquals(expected.getValue(), in.readAllBytes(), expected.getKey());
                }
            }
        }

        try (URLClassLoader classLoader = new URLClassLoader(new java.net.URL[]{cached.toUri().toURL()}, null);
             InputStream resource = classLoader.getResourceAsStream(
                 "composeResources/io.github.rwpp.generated/font/valorax.ttf")) {
            assertArrayEquals(entries.get("composeResources/io.github.rwpp.generated/font/valorax.ttf"),
                resource.readAllBytes(), "an archive class path must expose Compose resources");
        }

        Dexer.makeReadOnly(cached);
        Files.write(directory.resolve("complete.txt"), "stale".getBytes(StandardCharsets.UTF_8));
        assertFalse(ExtensionArchiveCache.isValid(directory, source, sha256));
        Path restored = ExtensionArchiveCache.install(directory, source, sha256);
        assertTrue(ExtensionArchiveCache.isValid(directory, source, sha256));
        try (JarFile jar = new JarFile(restored.toFile())) {
            assertArrayEquals(entries.get("composeResources/io.github.rwpp.generated/files/config.toml"),
                jar.getInputStream(jar.getJarEntry("composeResources/io.github.rwpp.generated/files/config.toml"))
                    .readAllBytes());
        }
    }

    @Test
    void refusesSourceThatDoesNotMatchItsRecordedDigest() throws Exception {
        Path source = archive("rwpp.jar", Map.of("classes.dex", new byte[]{1}));
        assertFalse(ExtensionArchiveCache.isValid(temporary.resolve("cache"), source, "wrong"));
        assertThrowsIOException(() -> ExtensionArchiveCache.install(temporary.resolve("cache"), source, "wrong"));
    }

    private Path archive(String name, Map<String, byte[]> entries) throws IOException {
        Path target = temporary.resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return target;
    }

    private static void assertThrowsIOException(ThrowingAction action) {
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, action::run);
    }

    @FunctionalInterface
    private interface ThrowingAction { void run() throws Exception; }
}
