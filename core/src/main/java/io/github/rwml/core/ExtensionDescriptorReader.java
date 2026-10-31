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

/** 读取 UTF-8 属性文件，不检查或加载扩展类。 */
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

    /** 内置扩展采用相同格式，但使用自身资源文件且没有源归档。 */
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
        // 资源包只提供资源，通常没有需要执行的代码，因此入口点可省略；其他扩展必须声明入口点。
        boolean resourcePack = flag(properties, "resourcePack", "resource");
        String mainClass = value(properties, "mainClass", "");
        if (mainClass.isEmpty() && !resourcePack) {
            throw new ExtensionException("Missing required 'mainClass' in " + DESCRIPTOR_ENTRY);
        }
        List<String> authors = splitOnCommas(properties.getProperty("authors"));
        String author = value(properties, "author", "");
        if (authors.isEmpty() && !author.isEmpty()) { authors.add(author); }
        return new ExtensionDescriptor(id, value(properties, "name", id),
            value(properties, "version", "1.0.0"), value(properties, "description", ""),
            unmodifiable(authors), value(properties, "license", ""), value(properties, "licenseUrl", ""),
            value(properties, "homepage", ""), value(properties, "icon", ""),
            unmodifiable(split(properties.getProperty("tags"))), value(properties, "apiVersion", "1.0"),
            parseInt(value(properties, "javaVersion", "8"), 8), mainClass,
            value(properties, "ui-framework", ""), resourcePack,
            unmodifiable(requirements(properties.getProperty("dependsOn"))),
            unmodifiable(split(properties.getProperty("loadBefore"))),
            unmodifiable(split(properties.getProperty("loadAfter"))), source, size, digest);
    }

    /**
     * 读取布尔标记。接受 {@code true}/{@code 1}/{@code yes}/{@code on}，其余取值视为未设置。
     *
     * @param aliases 依次检查的键名，用于兼容同一标记的历史写法
     */
    private static boolean flag(Properties properties, String... aliases) {
        for (String key : aliases) {
            String raw = value(properties, key, "");
            if (raw.isEmpty()) { continue; }
            String normalized = raw.toLowerCase(java.util.Locale.ROOT);
            return "true".equals(normalized) || "1".equals(normalized)
                || "yes".equals(normalized) || "on".equals(normalized);
        }
        return false;
    }

    /**
     * 拆分 {@code dependsOn}，并在读取阶段拒绝无法解析的版本约束。
     *
     * <p>约束语法错误属于作者笔误，放在此处报告比在加载顺序解析阶段悄悄排除扩展更容易定位。</p>
     */
    private static List<String> requirements(String raw) throws ExtensionException {
        List<String> tokens = new ArrayList<>(ExtensionRequirement.tokenize(raw));
        for (String token : tokens) {
            ExtensionRequirement requirement = ExtensionRequirement.parse(token);
            if (requirement != null && requirement.constraint().invalid()) {
                throw new ExtensionException("Invalid version constraint in dependsOn: " + token);
            }
        }
        return tokens;
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
     * 只按逗号和分号拆分。用于可能合法包含空格的值，例如作者姓名（“Ada Lovelace, Alan Turing”代表两位作者，
     * 而不是四个名字）。
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
    // 简单辅助方法。刻意只使用 Java 8 功能：同一库也会在 Android 上运行，而 Android 尚不支持
    // String.isBlank、List.copyOf、List.of 和 InputStream.readAllBytes。
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