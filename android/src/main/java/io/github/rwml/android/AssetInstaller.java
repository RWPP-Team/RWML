/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * 将 APK 中某一组归档复制到可写目录，并且只复制有变化的文件。
 *
 * <p>打包资源是只读的，因此运行时需要修补、转换为 dex 或删除的文件都放在应用存储中。是否需要复制
 * 由两个摘要共同判断：构建时记录的资源摘要，以及上次安装文件的摘要。若三者一致，就跳过开销较大的
 * 解码操作，避免处理数 MB 的数据。</p>
 */
final class AssetInstaller {

    /** 构建流程暂存到 {@code assets/} 中的所有资源的根目录。 */
    static final String ASSET_ROOT = "rwml";

    private static final String MANIFEST = ASSET_ROOT + "/manifest.properties";
    private static final String RECORD = "installed.properties";

    private AssetInstaller() {
    }

    /**
     * 将 {@code rwml/<group>/} 下的所有资源安装到 {@code targetDirectory}。
     *
     * @param group 构建时暂存的目录之一，例如 {@code extensions}
     * @return 每个归档对应一行，说明对其执行的操作
     */
    static List<String> install(Context context, String group, Path targetDirectory) {
        List<String> log = new ArrayList<>();
        AssetManager assets = context.getAssets();

        Properties manifest = new Properties();
        try (InputStream in = assets.open(MANIFEST)) {
            manifest.load(in);
        } catch (IOException nothingStaged) {
            return log;
        }

        try {
            Files.createDirectories(targetDirectory);
        } catch (IOException e) {
            log.add("cannot create " + targetDirectory + ": " + e);
            return log;
        }

        Properties recorded = readRecord(targetDirectory);
        try {
            String[] entries = assets.list(ASSET_ROOT + "/" + group);
            if (entries == null) {
                return log;
            }
            List<String> names = new ArrayList<>();
            Collections.addAll(names, entries);
            Collections.sort(names);

            boolean changed = false;
            for (String name : names) {
                String expected = manifest.getProperty(group + "/" + name);
                Path target = targetDirectory.resolve(name);
                String current = Files.isRegularFile(target) ? Digest.sha256(target) : null;

                if (expected != null && expected.equalsIgnoreCase(current)
                    && expected.equalsIgnoreCase(recorded.getProperty(group + "/" + name))) {
                    log.add("cached     " + group + "/" + name + "  (unchanged, "
                        + Digest.shortHash(expected) + ")");
                    continue;
                }

                try (InputStream in = assets.open(ASSET_ROOT + "/" + group + "/" + name)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                recorded.setProperty(group + "/" + name, expected == null ? "" : expected);
                changed = true;
                log.add("installed  " + group + "/" + name + "  ("
                    + (current == null ? "new" : "updated") + ")");
            }
            if (changed) {
                writeRecord(targetDirectory, recorded);
            }
        } catch (IOException e) {
            log.add("install of " + group + " failed: " + e);
        }
        return log;
    }

    /** @return 当前已安装的指定组归档。 */
    static List<Path> installed(Path targetDirectory) throws IOException {
        if (!Files.isDirectory(targetDirectory)) {
            return Collections.emptyList();
        }
        List<Path> archives = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(targetDirectory)) {
            stream.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".jar"))
                .sorted()
                .forEach(archives::add);
        }
        return archives;
    }

    private static Properties readRecord(Path targetDirectory) {
        Properties record = new Properties();
        Path file = targetDirectory.resolve(RECORD);
        if (!Files.isRegularFile(file)) {
            return record;
        }
        try (InputStream in = Files.newInputStream(file)) {
            record.load(in);
        } catch (IOException ignored) {
            // 重新计算所有内容，不要因此阻止应用启动。
        }
        return record;
    }

    /** 按固定键顺序写入，确保其他内容不变时文件本身也不会变化。 */
    private static void writeRecord(Path targetDirectory, Properties record) throws IOException {
        List<String> keys = new ArrayList<>(record.stringPropertyNames());
        Collections.sort(keys);
        StringBuilder text = new StringBuilder("# Digest of every asset seen at install time.\n");
        for (String key : keys) {
            text.append(key).append('=').append(record.getProperty(key, "")).append('\n');
        }
        Files.write(targetDirectory.resolve(RECORD),
            text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 内容摘要，用于判断哪些文件需要重新处理。 */
    static final class Digest {

        private Digest() {
        }

        static String sha256(Path file) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[8192];
                try (InputStream in = Files.newInputStream(file)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                    }
                }
                return hex(digest.digest());
            } catch (Exception e) {
                return "";
            }
        }

        static String sha256(String text) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                return hex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                return "";
            }
        }

        static String shortHash(String hash) {
            return hash.length() > 12 ? hash.substring(0, 12) : hash;
        }

        private static String hex(byte[] bytes) {
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        }
    }
}
