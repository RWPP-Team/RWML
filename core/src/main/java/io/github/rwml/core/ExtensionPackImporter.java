/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import io.github.rwml.api.extensions.ExtensionEntry;
import io.github.rwml.api.extensions.ExtensionPackReport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 从 zip 整合包中取出各个扩展归档。
 *
 * <p>本类只负责读取压缩包、取出成员并交给安装回调；命名、去重、替换策略与启用状态由
 * {@link DefaultExtensionLibrary} 决定，两者共用单文件导入的同一套校验。</p>
 *
 * <p>解压不使用压缩包内的路径，因此不存在目录穿越风险。每个成员先写入同一文件系统中的临时文件，安装失败时只丢弃该成员，
 * 其他成员继续。</p>
 */
public final class ExtensionPackImporter {

    /** 单个成员的上限与单文件导入一致，避免压缩包绕过大小的限制。 */
    private static final long MAX_MEMBER_BYTES = 128L * 1024 * 1024;
    /** 成员数量上限：整合包不是文件系统镜像，超出此数量的目录多半是错误用法。 */
    private static final int MAX_MEMBERS = 256;

    /** 安装一个已解出的成员。 */
    @FunctionalInterface
    public interface Installer {
        /**
         * @return 写入的扩展；归档内容已存在时返回 {@code null}
         */
        ExtensionEntry install(Path jar, String member) throws IOException;
    }

    /** 导入结果以及包清单。 */
    public static final class Result {
        private final ExtensionPackManifest manifest;
        private final ExtensionPackReport report;

        private Result(ExtensionPackManifest manifest, ExtensionPackReport report) {
            this.manifest = manifest;
            this.report = report;
        }

        public ExtensionPackManifest manifest() { return manifest; }

        public ExtensionPackReport report() { return report; }
    }

    private ExtensionPackImporter() { }

    /**
     * 读取压缩包中的每个扩展成员并交给 {@code installer}。
     *
     * @param pack 已写入磁盘的 zip 文件
     * @param staging 解出成员的临时目录，须与扩展目录位于同一文件系统
     * @throws IOException 压缩包无法读取，或不包含任何 jar 成员
     */
    public static Result importPack(Path pack, Path staging, Installer installer) throws IOException {
        List<String> skipped = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();
        List<ExtensionEntry> imported = new ArrayList<>();
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            ExtensionPackManifest manifest = ExtensionPackManifest.read(zip);
            List<ZipEntry> members = members(zip);
            if (members.isEmpty()) {
                throw new IOException("The pack contains no extension jars");
            }
            for (ZipEntry member : members) {
                Path extracted = Files.createTempFile(staging, "member-", ".jar");
                try {
                    extract(zip, member, extracted);
                    ExtensionEntry entry = installer.install(extracted, member.getName());
                    if (entry == null) {
                        skipped.add(member.getName() + ": the same version is already installed");
                    } else {
                        imported.add(entry);
                    }
                } catch (IOException failure) {
                    failed.put(member.getName(), failure.getMessage() == null
                        ? failure.toString() : failure.getMessage());
                } finally {
                    Files.deleteIfExists(extracted);
                }
            }
            return new Result(manifest, new ExtensionPackReport(manifest.name(), manifest.version(),
                imported, skipped, failed));
        }
    }

    /** @return 压缩包中的 jar 成员，按名称排序，结果与文件系统顺序无关 */
    private static List<ZipEntry> members(ZipFile zip) throws IOException {
        List<ZipEntry> members = new ArrayList<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) { continue; }
            String name = entry.getName();
            if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) { continue; }
            // 归档工具会写入资源分支与元数据文件，它们不是扩展。
            if (name.startsWith("__MACOSX/") || name.contains("/._")) { continue; }
            members.add(entry);
            if (members.size() > MAX_MEMBERS) {
                throw new IOException("The pack contains more than " + MAX_MEMBERS + " jars");
            }
        }
        Collections.sort(members, (left, right) -> left.getName().compareTo(right.getName()));
        return members;
    }

    private static void extract(ZipFile zip, ZipEntry entry, Path target) throws IOException {
        try (InputStream input = zip.getInputStream(entry);
             OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[16384];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_MEMBER_BYTES) {
                    throw new IOException(entry.getName() + " exceeds 128 MiB");
                }
                output.write(buffer, 0, read);
            }
        }
    }
}
