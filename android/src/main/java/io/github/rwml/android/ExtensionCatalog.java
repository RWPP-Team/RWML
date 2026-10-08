/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Content identity, including additions/removals; size and modification time are never trusted. */
final class ExtensionCatalog {
    static String fingerprint(Path... directories) throws IOException {
        List<String> entries = new ArrayList<>();
        for (Path directory : directories) {
            if (!Files.exists(directory)) { continue; }
            if (Files.isRegularFile(directory)) { add(entries, directory); continue; }
            try (java.util.stream.Stream<Path> paths = Files.list(directory)) {
                for (Path file : (Iterable<Path>) paths::iterator) {
                    if (Files.isRegularFile(file) && file.getFileName().toString()
                        .toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) { add(entries, file); }
                }
            }
        }
        Collections.sort(entries);
        return AssetInstaller.Digest.sha256(String.join("\n", entries));
    }
    private static void add(List<String> entries, Path file) throws IOException {
        String digest = AssetInstaller.Digest.sha256(file);
        if (digest.isEmpty()) { throw new IOException("Cannot hash " + file); }
        entries.add(file.toAbsolutePath().normalize() + " " + digest);
    }
}
