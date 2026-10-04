package io.github.rwml.core;


import io.github.rwml.api.ExtensionException;
import io.github.rwml.api.ExtensionInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
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

    private static final String ANNOTATION_DESCRIPTOR =
        "L" + ExtensionInfo.class.getName().replace('.', '/') + ";";

    private ExtensionDescriptorReader() {
    }

    public static ExtensionDescriptor read(Path archive) throws ExtensionException {
        if (archive == null || !Files.isRegularFile(archive)) {
            throw new ExtensionException("Extension archive not found: " + archive);
        }
        try {
            Properties properties = loadProperties(archive);
            String mainClass = properties.getProperty("mainClass");
            if (isBlank(mainClass)) {
                mainClass = findAnnotatedClass(archive);
            }
            if (isBlank(mainClass)) {
                throw new ExtensionException("No '" + DESCRIPTOR_ENTRY + "' with a mainClass and no "
                    + "@ExtensionInfo annotated class found in " + archive.getFileName());
            }
            mainClass = mainClass.trim();

            ExtensionInfo annotation = readAnnotation(archive, mainClass);

            String id = pick(properties.getProperty("id"), annotation == null ? null : annotation.id());
            if (id == null) {
                throw new ExtensionException("Missing required 'id' in " + archive.getFileName());
            }
            String name = pick(properties.getProperty("name"),
                annotation == null ? null : annotation.name(), id);

            List<String> authors = new ArrayList<>(splitOnCommas(properties.getProperty("authors")));
            if (authors.isEmpty() && annotation != null) {
                authors.addAll(Arrays.asList(annotation.authors()));
            }
            String singleAuthor = pick(properties.getProperty("author"),
                annotation == null ? null : annotation.author());
            if (authors.isEmpty() && singleAuthor != null) {
                authors.add(singleAuthor);
            }

            int declaredJava = parseInt(pick(properties.getProperty("javaVersion"),
                annotation == null ? null : String.valueOf(annotation.javaVersion()), "8"), 8);

            long timeout = parseLong(pick(properties.getProperty("loadTimeoutMillis"),
                annotation == null ? null : String.valueOf(annotation.loadTimeoutMillis()), "30000"), 30_000L);

            return new ExtensionDescriptor(
                id,
                name,
                pick(properties.getProperty("version"), annotation == null ? null : annotation.version(), "1.0.0"),
                pick(properties.getProperty("description"), annotation == null ? null : annotation.description(), ""),
                unmodifiable(authors),
                pick(properties.getProperty("license"), annotation == null ? null : annotation.license(), ""),
                pick(properties.getProperty("licenseUrl"), annotation == null ? null : annotation.licenseUrl(), ""),
                pick(properties.getProperty("homepage"), annotation == null ? null : annotation.homepage(), ""),
                pick(properties.getProperty("icon"), annotation == null ? null : annotation.icon(), ""),
                mergeTags(properties.getProperty("tags"), annotation),
                pick(properties.getProperty("apiVersion"), annotation == null ? null : annotation.apiVersion(), "1.0"),
                declaredJava,
                mainClass,
                mergeStrings(properties.getProperty("dependsOn"),
                    annotation == null ? Collections.emptyList() : Arrays.asList(annotation.dependsOn())),
                mergeStrings(properties.getProperty("loadBefore"),
                    annotation == null ? Collections.emptyList() : Arrays.asList(annotation.loadBefore())),
                mergeStrings(properties.getProperty("loadAfter"),
                    annotation == null ? Collections.emptyList() : Arrays.asList(annotation.loadAfter())),
                timeout,
                archive.toAbsolutePath(),
                Files.size(archive),
                sha256(archive));
        } catch (ExtensionException e) {
            throw e;
        } catch (Exception e) {
            throw new ExtensionException("Unable to read descriptor from " + archive.getFileName() + ": " + e, e);
        }
    }

    private static Properties loadProperties(Path archive) throws IOException {
        Properties properties = new Properties();
        try (JarFile jar = new JarFile(archive.toFile())) {
            JarEntry entry = jar.getJarEntry(DESCRIPTOR_ENTRY);
            if (entry == null) {
                return properties;
            }
            try (InputStream stream = jar.getInputStream(entry);
                 InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        return properties;
    }

    /**
     * Scans class files for the {@code @ExtensionInfo} annotation descriptor
     * and derives the fully qualified class name from the archive path.
     */
    private static String findAnnotatedClass(Path archive) throws IOException {
        try (JarFile jar = new JarFile(archive.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream stream = jar.getInputStream(entry)) {
                    bytes = readFully(stream);
                }
                if (new String(bytes, StandardCharsets.ISO_8859_1).contains(ANNOTATION_DESCRIPTOR)) {
                    String name = entry.getName();
                    return name.substring(0, name.length() - ".class".length()).replace('/', '.');
                }
            }
        }
        return null;
    }

    private static ExtensionInfo readAnnotation(Path archive, String className) {
        URL[] urls;
        try {
            urls = new URL[]{archive.toUri().toURL()};
        } catch (Exception e) {
            return null;
        }
        try (URLClassLoader loader = new URLClassLoader(urls, ExtensionDescriptorReader.class.getClassLoader())) {
            Class<?> type = Class.forName(className, false, loader);
            return type.getAnnotation(ExtensionInfo.class);
        } catch (Throwable t) {
            return null;
        }
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

    private static List<String> mergeTags(String raw, ExtensionInfo annotation) {
        List<String> tags = split(raw);
        if (tags.isEmpty() && annotation != null) {
            tags = new ArrayList<>(Arrays.asList(annotation.tags()));
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

    /** Java 8 stand-in for {@code InputStream.readAllBytes}. */
    private static byte[] readFully(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
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