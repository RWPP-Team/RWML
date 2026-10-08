/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;


import io.github.rwml.api.ExtensionException;
import io.github.rwml.api.ExtensionInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Builds an {@link ExtensionDescriptor} from an extension archive.
 *
 * <p>Resolution order per field: {@code META-INF/extension.properties} wins,
 * then the {@code @ExtensionInfo} annotation found on the entry point class.
 * The entry point class itself is discovered from the {@code mainClass}
 * property or, as a fallback, by scanning the archive for the annotation
 * descriptor.</p>
 */
public final class ExtensionDescriptorReader {

    /** Classpath location of the textual descriptor. */
    public static final String DESCRIPTOR_ENTRY = "META-INF/extension.properties";

    private ExtensionDescriptorReader() {
    }

    public static ExtensionDescriptor read(Path archive) throws ExtensionException {
        if (archive == null || !Files.isRegularFile(archive)) {
            throw new ExtensionException("Extension archive not found: " + archive);
        }
        try (JarFile jar = new JarFile(archive.toFile())) {
            Properties properties = loadProperties(jar);
            String mainClass = properties.getProperty("mainClass");
            if (isBlank(mainClass)) {
                mainClass = findAnnotatedClass(jar);
            }
            if (isBlank(mainClass)) {
                throw new ExtensionException("No '" + DESCRIPTOR_ENTRY + "' with a mainClass and no "
                    + "@ExtensionInfo annotated class found in " + archive.getFileName());
            }
            mainClass = mainClass.trim();

            ClassFileMetadata.Annotation annotation = readAnnotation(jar, mainClass);
            mainClass = pick(properties.getProperty("mainClass"),
                annotation == null ? null : annotation.string("mainClass"), mainClass);

            String id = pick(properties.getProperty("id"), annotation == null ? null : annotation.string("id"));
            if (id == null) {
                throw new ExtensionException("Missing required 'id' in " + archive.getFileName());
            }
            String name = pick(properties.getProperty("name"),
                annotation == null ? null : annotation.string("name"), id);

            List<String> authors = new ArrayList<>(splitOnCommas(properties.getProperty("authors")));
            if (authors.isEmpty() && annotation != null) {
                authors.addAll(annotation.strings("authors"));
            }
            String singleAuthor = pick(properties.getProperty("author"),
                annotation == null ? null : annotation.string("author"));
            if (authors.isEmpty() && singleAuthor != null) {
                authors.add(singleAuthor);
            }

            int declaredJava = parseInt(pick(properties.getProperty("javaVersion"),
                annotation == null ? null : String.valueOf(annotation.integer("javaVersion", 8)), "8"), 8);

            return new ExtensionDescriptor(
                id,
                name,
                pick(properties.getProperty("version"), annotation == null ? null : annotation.string("version"), "1.0.0"),
                pick(properties.getProperty("description"), annotation == null ? null : annotation.string("description"), ""),
                unmodifiable(authors),
                pick(properties.getProperty("license"), annotation == null ? null : annotation.string("license"), ""),
                pick(properties.getProperty("licenseUrl"), annotation == null ? null : annotation.string("licenseUrl"), ""),
                pick(properties.getProperty("homepage"), annotation == null ? null : annotation.string("homepage"), ""),
                pick(properties.getProperty("icon"), annotation == null ? null : annotation.string("icon"), ""),
                mergeTags(properties.getProperty("tags"), annotation),
                pick(properties.getProperty("apiVersion"), annotation == null ? null : annotation.string("apiVersion"), "1.0"),
                declaredJava,
                mainClass,
                pick(properties.getProperty("ui-framework"),
                    annotation == null ? null : annotation.string("uiFramework"), ""),
                mergeStrings(properties.getProperty("dependsOn"),
                    annotation == null ? Collections.emptyList() : annotation.strings("dependsOn")),
                mergeStrings(properties.getProperty("loadBefore"),
                    annotation == null ? Collections.emptyList() : annotation.strings("loadBefore")),
                mergeStrings(properties.getProperty("loadAfter"),
                    annotation == null ? Collections.emptyList() : annotation.strings("loadAfter")),
                archive.toAbsolutePath(),
                Files.size(archive),
                sha256(archive));
        } catch (ExtensionException e) {
            throw e;
        } catch (Exception e) {
            throw new ExtensionException("Unable to read descriptor from " + archive.getFileName() + ": " + e, e);
        }
    }

    private static Properties loadProperties(JarFile jar) throws IOException {
        Properties properties = new Properties();
        JarEntry entry = jar.getJarEntry(DESCRIPTOR_ENTRY);
        if (entry != null) {
            try (InputStream stream = jar.getInputStream(entry);
                 InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        return properties;
    }

    private static String findAnnotatedClass(JarFile jar) throws IOException {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || !name.endsWith(".class") || name.startsWith("META-INF/")) {
                continue;
            }
            try (InputStream stream = jar.getInputStream(entry)) {
                ClassFileMetadata.Annotation annotation = ClassFileMetadata.read(stream).annotation;
                if (annotation != null) {
                    return name.substring(0, name.length() - 6).replace('/', '.');
                }
            }
        }
        return null;
    }

    private static ClassFileMetadata.Annotation readAnnotation(JarFile jar, String className)
        throws IOException {
        // @ExtensionInfo is inherited, but only classes carried by this archive are inspected.
        java.util.Set<String> visited = new java.util.HashSet<>();
        String name = className.replace('.', '/');
        while (name != null && visited.add(name)) {
            JarEntry entry = jar.getJarEntry(name + ".class");
            if (entry == null) {
                return null;
            }
            try (InputStream stream = jar.getInputStream(entry)) {
                ClassFileMetadata metadata = ClassFileMetadata.read(stream);
                if (metadata.annotation != null) {
                    return metadata.annotation;
                }
                name = metadata.superName;
            }
        }
        return null;
    }

    private static String pick(String first, String second) {
        return pick(first, second, null);
    }

    private static String pick(String first, String second, String fallback) {
        if (!isBlank(first)) {
            return first.trim();
        }
        if (!isBlank(second)) {
            return second.trim();
        }
        return fallback;
    }

    private static List<String> mergeTags(String raw, ClassFileMetadata.Annotation annotation) {
        List<String> tags = split(raw);
        if (tags.isEmpty() && annotation != null) {
            tags = new ArrayList<>(annotation.strings("tags"));
        }
        return unmodifiable(tags);
    }

    private static List<String> mergeStrings(String raw, List<String> fallback) {
        List<String> values = split(raw);
        if (values.isEmpty()) {
            values = new ArrayList<>(fallback);
        }
        return unmodifiable(values);
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

    private static String sha256(Path file) {
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
            return "";
        }
    }
}