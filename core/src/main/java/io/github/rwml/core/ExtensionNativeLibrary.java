/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Extracts native payloads from extension archives without changing the extension class path. */
final class ExtensionNativeLibrary {

    private static final int MAX_BYTES = 256 * 1024 * 1024;

    private ExtensionNativeLibrary() { }

    static Path extract(Path archive, String archiveEntry, Path dataDirectory) throws IOException {
        String entryName = validateEntry(archiveEntry);
        byte[] content;
        try (JarFile jar = new JarFile(archive.toFile())) {
            JarEntry entry = jar.getJarEntry(entryName);
            if (entry == null || entry.isDirectory()) {
                throw new IOException("Native library entry is missing from extension: " + entryName);
            }
            try (InputStream input = jar.getInputStream(entry)) {
                content = readBounded(input, entryName);
            }
        }

        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java platform", impossible);
        }
        StringBuilder hash = new StringBuilder(64);
        for (byte value : digest) {
            hash.append(String.format("%02x", value & 0xff));
        }

        Path targetDirectory = dataDirectory.resolve("native").resolve(hash.toString());
        Files.createDirectories(targetDirectory);
        String fileName = entryName.substring(entryName.lastIndexOf('/') + 1);
        Path target = targetDirectory.resolve(fileName);
        if (Files.isRegularFile(target) && Files.size(target) == content.length
            && matches(target, digest)) {
            return target;
        }
        Path temporary = Files.createTempFile(targetDirectory, "library-", ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveUnavailable) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return target;
    }

    private static boolean matches(Path file, byte[] expectedDigest) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is required by the Java platform", impossible);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return MessageDigest.isEqual(expectedDigest, digest.digest());
    }

    private static String validateEntry(String archiveEntry) throws IOException {
        if (archiveEntry == null || archiveEntry.trim().isEmpty() || archiveEntry.indexOf('\\') >= 0
            || archiveEntry.startsWith("/")) {
            throw new IOException("Native library entry must be a relative JAR path");
        }
        String[] segments = archiveEntry.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                || segment.indexOf(':') >= 0) {
                throw new IOException("Native library entry must not escape the extension archive");
            }
        }
        return archiveEntry;
    }

    private static byte[] readBounded(InputStream input, String entryName) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_BYTES) {
                throw new IOException("Native library is larger than 256 MiB: " + entryName);
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
