/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import io.github.rwml.host.ResourcePackEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 把资源包内容写入宿主提供的目录。
 *
 * <p>资源包只携带它替换的文件。资源读取先查该目录，未命中时交回游戏自己的读取路径，因此原版资源既不需要复制，也不会
 * 因为物化而变得陈旧：游戏升级后，包未提供的资源立刻使用新版本。桌面版与 Android 各自只有一条读取路径需要接管，
 * 具体实现位于 {@code game-adapter} 与 {@code android}。</p>
 *
 * <p>物化结果带标记文件，记录包 ID、版本与归档摘要。三者均未变化时直接复用已有目录，因此重启只在包确实变化时
 * 才付出解压代价。标记最后写入，中途失败不会留下看似完整的目录。</p>
 */
public final class ResourcePackInstaller {

    /** 物化目录中的标记文件，仅由本类读写。 */
    public static final String MARKER = "rwml-resource-pack.properties";

    /** 资源包可以覆盖的根目录，与游戏自身读取资源的位置一致。 */
    private static final List<String> RESOURCE_ROOTS =
        Collections.unmodifiableList(Arrays.asList("gui", "units", "tilesets", "music", "shaders", "res"));

    /** 单包条目上限：资源包是若干目录的覆盖集，不是文件系统镜像。 */
    private static final int MAX_ENTRIES = 20000;

    /** 单条目的上限，防止解压时被一个条目耗尽磁盘。 */
    private static final long MAX_ENTRY_BYTES = 256L * 1024 * 1024;

    /** 一次物化的结果。 */
    public static final class Result {

        private final String id;
        private final String version;
        private final Path directory;
        private final boolean reused;
        private final int entries;

        private Result(String id, String version, Path directory, boolean reused, int entries) {
            this.id = id;
            this.version = version;
            this.directory = directory;
            this.reused = reused;
            this.entries = entries;
        }

        public String id() { return id; }

        public String version() { return version; }

        /** @return 游戏与扩展读取资源的根目录 */
        public Path directory() { return directory; }

        /** @return 是否复用了上次物化的目录，而没有重新复制与解压 */
        public boolean reused() { return reused; }

        /** @return 本次写入的文件数；复用时为 0 */
        public int entries() { return entries; }
    }

    private ResourcePackInstaller() { }

    /**
     * 物化一个资源包。
     *
     * @param environment 宿主提供的可写目录与激活回调
     * @param pack 已选定生效的资源包
     * @return 物化结果
     * @throws IOException 宿主不支持资源包、归档无法读取或写入失败时抛出
     */
    public static Result install(ResourcePackEnvironment environment, ExtensionDescriptor pack)
        throws IOException {
        Path root = requireDirectory(environment);
        if (isCurrent(root, pack)) {
            environment.activated(root);
            return new Result(pack.id(), pack.version(), root, true, 0);
        }
        deleteContents(root);
        Files.createDirectories(root);
        int entries = extract(pack, root);
        writeMarker(root, pack, entries);
        environment.activated(root);
        return new Result(pack.id(), pack.version(), root, false, entries);
    }

    /**
     * 删除已物化的资源包，使游戏回到原版资源。
     *
     * <p>目录本身保留为空：宿主在下次物化时会重新创建，而删除整个目录会让正在读取资源的进程看到路径消失。</p>
     */
    public static void clear(ResourcePackEnvironment environment) throws IOException {
        if (!environment.supported()) {
            environment.activated(null);
            return;
        }
        Path root = environment.directory();
        if (root == null) {
            environment.activated(null);
            return;
        }
        deleteContents(root);
        environment.activated(null);
    }

    /** @return 目录中的标记是否正好对应 {@code pack} */
    static boolean isCurrent(Path root, ExtensionDescriptor pack) {
        Path marker = root.resolve(MARKER);
        if (!Files.isRegularFile(marker)) { return false; }
        Properties properties = new Properties();
        try (InputStream stream = Files.newInputStream(marker)) {
            properties.load(stream);
        } catch (IOException | IllegalArgumentException unreadable) {
            return false;
        }
        return pack.id().equals(properties.getProperty("id", "").trim())
            && pack.version().equals(properties.getProperty("version", "").trim())
            && pack.sourceSha256().equals(properties.getProperty("archive", "").trim());
    }

    private static Path requireDirectory(ResourcePackEnvironment environment) throws IOException {
        if (!environment.supported()) {
            throw new IOException("this host cannot provide the game's resources to resource packs");
        }
        Path root = environment.directory();
        if (root == null) {
            throw new IOException("the host reports resource pack support but provides no directory");
        }
        return root;
    }

    /** 解压包内位于资源根下的条目，返回写入的文件数。 */
    private static int extract(ExtensionDescriptor pack, Path root) throws IOException {
        Path archive = pack.source();
        if (archive == null) {
            throw new IOException("resource pack '" + pack.id() + "' has no archive to extract");
        }
        int written = 0;
        int seen = 0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) { continue; }
                String relative = relativeResourcePath(entry.getName());
                if (relative == null) { continue; }
                if (++seen > MAX_ENTRIES) {
                    throw new IOException("resource pack '" + pack.id() + "' contains more than "
                        + MAX_ENTRIES + " resource files");
                }
                Path target = root.resolve(relative).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("resource pack entry escapes its directory: " + entry.getName());
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    copyBounded(in, target, MAX_ENTRY_BYTES);
                }
                written++;
            }
        }
        return written;
    }

    /**
     * @return 条目在资源根内的相对路径；条目不属于资源根时返回 null
     */
    private static String relativeResourcePath(String entryName) {
        String name = entryName.replace('\\', '/');
        while (name.startsWith("/")) { name = name.substring(1); }
        // 早期归档把资源放在 assets 下，保留这条路径以便直接迁移。
        if (name.startsWith("assets/")) { name = name.substring("assets/".length()); }
        if (name.isEmpty()) { return null; }
        int slash = name.indexOf('/');
        if (slash <= 0) { return null; }
        String head = name.substring(0, slash).toLowerCase(Locale.ROOT);
        return RESOURCE_ROOTS.contains(head) ? name : null;
    }

    private static void copyBounded(InputStream in, Path target, long limit) throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            long written = 0L;
            int read;
            while ((read = in.read(buffer)) != -1) {
                written += read;
                if (written > limit) {
                    throw new IOException("resource pack entry is larger than " + limit + " bytes: " + target);
                }
                out.write(buffer, 0, read);
            }
        }
    }

    private static void writeMarker(Path root, ExtensionDescriptor pack, int entries) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("id", pack.id());
        properties.setProperty("version", pack.version());
        properties.setProperty("archive", pack.sourceSha256());
        properties.setProperty("entries", Integer.toString(entries));
        Path marker = root.resolve(MARKER);
        Path temp = marker.resolveSibling(MARKER + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            properties.store(out, "Resource pack materialised by RWML");
        }
        try {
            Files.move(temp, marker, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(temp, marker, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 删除目录内容但保留目录本身，避免运行中的进程看到路径短暂消失。 */
    private static void deleteContents(Path root) throws IOException {
        if (!Files.isDirectory(root)) { return; }
        List<Path> deleted = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .filter(path -> !path.equals(root))
                .forEach(deleted::add);
        }
        for (Path path : deleted) { Files.deleteIfExists(path); }
    }
}
