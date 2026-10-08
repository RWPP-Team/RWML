/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */
package io.github.rwml.android;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** SHA-checked private copies of the shared extension directory, including replacement and removal. */
public final class SharedExtensions {
    private SharedExtensions() { }

    public static List<Path> synchronize(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        List<Path> originals;
        if (!Files.exists(source)) {
            originals = Collections.emptyList();
        } else {
            try (Stream<Path> files = Files.list(source)) {
                originals = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted().collect(Collectors.toList());
            }
        }
        List<Path> copies = new ArrayList<>();
        for (Path original : originals) {
            Path copy = target.resolve(original.getFileName());
            String digest = AssetInstaller.Digest.sha256(original);
            if (digest.isEmpty()) { throw new IOException("Cannot hash " + original); }
            if (!Files.isRegularFile(copy) || !digest.equals(AssetInstaller.Digest.sha256(copy))) {
                Path temporary = Files.createTempFile(target, "extension-", ".tmp");
                try {
                    Files.copy(original, temporary, StandardCopyOption.REPLACE_EXISTING);
                    if (!digest.equals(AssetInstaller.Digest.sha256(temporary))) {
                        throw new IOException("Extension changed while copying: " + original);
                    }
                    Files.move(temporary, copy, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(temporary); }
            }
            copies.add(copy);
        }
        try (Stream<Path> files = Files.list(target)) {
            for (Path old : files.filter(Files::isRegularFile).collect(Collectors.toList())) {
                if (old.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                    && !copies.contains(old)) { Files.delete(old); }
            }
        }
        return copies;
    }
}
