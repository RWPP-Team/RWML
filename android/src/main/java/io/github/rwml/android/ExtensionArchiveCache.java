/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.jar.JarFile;

/** Copies an extension archive intact so Android can load its dex and read its resources from one path. */
final class ExtensionArchiveCache {

    private static final String ARCHIVE_FILE = "extension.jar";
    private static final String COMPLETE_FILE = "complete.txt";

    private ExtensionArchiveCache() { }

    static Path install(Path directory, Path source, String expectedSha256) throws IOException {
        if (isValid(directory, source, expectedSha256)) {
            return directory.resolve(ARCHIVE_FILE);
        }

        Files.createDirectories(directory);
        Path target = directory.resolve(ARCHIVE_FILE);
        Path temporary = directory.resolve(ARCHIVE_FILE + ".tmp");
        if (Files.exists(target)) { Dexer.makeWritable(target); }
        Files.deleteIfExists(target);
        Files.deleteIfExists(directory.resolve(COMPLETE_FILE));
        Files.deleteIfExists(temporary);

        if (!expectedSha256.equalsIgnoreCase(AssetInstaller.Digest.sha256(source))) {
            throw new IOException("Extension changed before it could be cached: " + source.getFileName());
        }
        Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
        if (!expectedSha256.equalsIgnoreCase(AssetInstaller.Digest.sha256(temporary))
            || !expectedSha256.equalsIgnoreCase(AssetInstaller.Digest.sha256(source))) {
            Files.deleteIfExists(temporary);
            throw new IOException("Extension changed while it was being cached: " + source.getFileName());
        }
        if (!Dexer.carriesDex(temporary)) {
            Files.deleteIfExists(temporary);
            throw new IOException("Android extension does not carry dex: " + source.getFileName());
        }

        try (JarFile ignored = new JarFile(temporary.toFile())) {
            // Opening the copy catches truncation before it becomes a class path entry.
        } catch (IOException invalidArchive) {
            Files.deleteIfExists(temporary);
            throw new IOException("Android extension archive is invalid: " + source.getFileName(), invalidArchive);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveUnavailable) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.write(directory.resolve(COMPLETE_FILE), expectedSha256.getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return target;
    }

    static boolean isValid(Path directory, Path source, String expectedSha256) {
        Path archive = directory.resolve(ARCHIVE_FILE);
        Path complete = directory.resolve(COMPLETE_FILE);
        if (!Files.isRegularFile(archive) || !Files.isRegularFile(complete)) {
            return false;
        }
        try {
            String marker = new String(Files.readAllBytes(complete), StandardCharsets.UTF_8);
            return expectedSha256.equalsIgnoreCase(marker)
                && expectedSha256.equalsIgnoreCase(AssetInstaller.Digest.sha256(archive))
                && expectedSha256.equalsIgnoreCase(AssetInstaller.Digest.sha256(source))
                && Dexer.carriesDex(archive);
        } catch (IOException failure) {
            return false;
        }
    }
}
