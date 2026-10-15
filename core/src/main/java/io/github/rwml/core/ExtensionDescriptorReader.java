/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import io.github.rwml.api.ExtensionException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Reads UTF-8 properties without inspecting or loading extension classes. */
public final class ExtensionDescriptorReader {
    public static final String DESCRIPTOR_ENTRY = "META-INF/extension.properties";

    private ExtensionDescriptorReader() { }

    public static ExtensionDescriptor read(Path archive) throws ExtensionException {
        if (archive == null || !Files.isRegularFile(archive)) {
            throw new ExtensionException("Extension archive not found: " + archive);
        }
        try (JarFile jar = new JarFile(archive.toFile())) {
            JarEntry entry = jar.getJarEntry(DESCRIPTOR_ENTRY);
            if (entry == null) { throw new ExtensionException("Missing " + DESCRIPTOR_ENTRY + " in " + archive.getFileName()); }
            try (InputStream stream = jar.getInputStream(entry)) {
                return descriptor(load(stream), archive.toAbsolutePath().normalize(),
                    Files.size(archive), sha256(archive));
            }
        } catch (ExtensionException e) {
            throw e;
        } catch (Exception e) {
            throw new ExtensionException("Unable to read descriptor from " + archive.getFileName() + ": " + e, e);
        }
    }

    /** Built-ins use the same format, with a resource of their own and no source archive. */
    public static ExtensionDescriptor readBuiltIn(Class<?> owner, String resource) {
        try (InputStream stream = owner.getResourceAsStream(resource)) {
            if (stream == null) { throw new IOException("Missing built-in descriptor: " + resource); }
            return descriptor(load(stream), null, 0L, "");
        } catch (IOException | ExtensionException failure) {
            throw new IllegalStateException("Cannot read built-in descriptor: " + resource, failure);
        }
    }

    private static Properties load(InputStream stream) throws IOException {
        Properties properties = new Properties();
        properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        return properties;
    }

    private static ExtensionDescriptor descriptor(Properties properties, Path source, long size, String digest)
        throws ExtensionException {
        String id = required(properties, "id");
        String mainClass = required(properties, "mainClass");
        List<String> authors = splitOnCommas(properties.getProperty("authors"));
        String author = value(properties, "author", "");
        if (authors.isEmpty() && !author.isEmpty()) { authors.add(author); }
        return new ExtensionDescriptor(id, value(properties, "name", id),
            value(properties, "version", "1.0.0"), value(properties, "description", ""),
            unmodifiable(authors), value(properties, "license", ""), value(properties, "licenseUrl", ""),
            value(properties, "homepage", ""), value(properties, "icon", ""),
            unmodifiable(split(properties.getProperty("tags"))), value(properties, "apiVersion", "1.0"),
            parseInt(value(properties, "javaVersion", "8"), 8), mainClass,
            value(properties, "ui-framework", ""), unmodifiable(split(properties.getProperty("dependsOn"))),
            unmodifiable(split(properties.getProperty("loadBefore"))),
            unmodifiable(split(properties.getProperty("loadAfter"))), source, size, digest);
    }

    private static String required(Properties properties, String key) throws ExtensionException {
        String value = value(properties, key, "");
        if (value.isEmpty()) { throw new ExtensionException("Missing required '" + key + "' in " + DESCRIPTOR_ENTRY); }
        return value;
    }

    private static String value(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key);
        return isBlank(value) ? fallback : value.trim();
    }

    private static List<String> split(String raw) {
        List<String> values = new ArrayList<>();
        if (isBlank(raw)) {
            return values;
        }
        for (String part : raw.split("[,;\\s]+")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    /**
     * Splits on commas and semicolons only. Used for values that may legitimately contain spaces,
     * such as author names ("Ada Lovelace, Alan Turing" is two authors, not four).
     */
    private static List<String> splitOnCommas(String raw) {
        List<String> values = new ArrayList<>();
        if (isBlank(raw)) {
            return values;
        }
        for (String part : raw.split("[,;]+")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    // ------------------------------------------------------------------
    // Small helpers. Deliberately Java 8 only: the very same library also runs on Android, where
    // String.isBlank, List.copyOf, List.of and InputStream.readAllBytes do not exist.
    // ------------------------------------------------------------------

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static <T> List<T> unmodifiable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (InputStream stream = Files.newInputStream(file)) {
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder builder = new StringBuilder(64);
            for (byte b : digest.digest()) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IOException("Unable to hash extension archive: " + file, e);
        }
    }
}