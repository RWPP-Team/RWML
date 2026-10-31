/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.bootstrap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * JDK 安装器使用的归档解压逻辑。
 *
 * <p>Adoptium 在 Windows 上发布 {@code .zip} 文件，在其他平台发布 {@code .tar.gz} 文件，因此此处同时处理这两种格式。
 * JDK 不支持 tar，所以此处手动实现了解析器，只覆盖 GNU tar 用于 JDK 镜像的功能子集：ustar 前缀名称、GNU 长名称（{@code L} 条目）、
 * 目录、普通文件和符号链接。</p>
 *
 * <p>每个条目路径都会通过 {@link #safeChild} 解析；如果路径会逃出目标目录，则拒绝该条目，避免恶意或损坏的归档写入缓存目录以外的位置。</p>
 */
public final class Archives {

    private static final int TAR_BLOCK = 512;
    private static final int BUFFER_SIZE = 64 * 1024;

    private Archives() {
    }

    /** 将 {@code archive} 解压到 {@code destination}，并根据文件扩展名选择格式。 */
    public static void extract(File archive, File destination) throws IOException {
        String name = archive.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) {
            extractZip(archive, destination);
        } else if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            extractTarGz(archive, destination);
        } else {
            throw new IOException("Unsupported archive format: " + archive.getName());
        }
    }

    // ------------------------------------------------------------------
    // ZIP 归档
    // ------------------------------------------------------------------

    private static void extractZip(File archive, File destination) throws IOException {
        mkdirs(destination);
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                File out = safeChild(destination, entry.getName());
                if (entry.isDirectory()) {
                    mkdirs(out);
                    continue;
                }
                mkdirs(out.getParentFile());
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream os = new BufferedOutputStream(new FileOutputStream(out), BUFFER_SIZE)) {
                    copy(in, os);
                }
                if (entry.getTime() > 0) {
                    out.setLastModified(entry.getTime());
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // TAR.GZ 归档
    // ------------------------------------------------------------------

    private static void extractTarGz(File archive, File destination) throws IOException {
        mkdirs(destination);
        try (InputStream raw = new BufferedInputStream(new FileInputStream(archive), BUFFER_SIZE);
             GZIPInputStream gz = new GZIPInputStream(raw, BUFFER_SIZE)) {

            byte[] header = new byte[TAR_BLOCK];
            String longName = null;

            while (true) {
                if (!readBlock(gz, header)) {
                    break; // 流的正常结束标记。
                }
                if (isZeroBlock(header)) {
                    break; // 归档结束标记。
                }

                String name = stringField(header, 0, 100);
                long size = octalField(header, 124, 12);
                int type = header[156] & 0xFF;
                String prefix = stringField(header, 345, 155);
                String linkName = stringField(header, 157, 100);
                long padded = paddedSize(size);

                // GNU 长名称：负载是*下一个*条目的真实名称。
                if (type == 'L') {
                    if (size < 0 || size > 64 * 1024) {
                        throw new IOException("Unreasonable tar long-name length: " + size);
                    }
                    byte[] data = new byte[(int) size];
                    readExact(gz, data, (int) size);
                    skipExact(gz, padded - size);
                    longName = nulTerminated(data, 0, data.length);
                    continue;
                }
                // pax 全局/扩展头及 GNU 长链接名称不包含我们需要的文件。
                if (type == 'x' || type == 'g' || type == 'K') {
                    skipExact(gz, padded);
                    continue;
                }

                String effective = longName != null
                    ? longName
                    : (prefix.isEmpty() ? name : prefix + "/" + name);
                longName = null;

                File out = safeChild(destination, effective);
                switch (type) {
                    case 0:
                    case '0':
                        mkdirs(out.getParentFile());
                        try (InputStream in = new BoundedInputStream(gz, size);
                             OutputStream os = new BufferedOutputStream(new FileOutputStream(out), BUFFER_SIZE)) {
                            copy(in, os);
                        }
                        break;
                    case '2': // 符号链接
                        createSymlink(out, linkName);
                        break;
                    case '5': // 目录
                        mkdirs(out);
                        break;
                    default:
                        // JDK 镜像不会使用硬链接（'1'）或设备节点。
                        break;
                }
                if (padded > size) {
                    skipExact(gz, padded - size);
                }
            }
        }
    }

    private static boolean readBlock(InputStream in, byte[] block) throws IOException {
        int total = 0;
        while (total < TAR_BLOCK) {
            int read = in.read(block, total, TAR_BLOCK - total);
            if (read == -1) {
                if (total == 0) {
                    return false;
                }
                throw new IOException("Truncated tar header");
            }
            total += read;
        }
        return true;
    }

    /** 尝试创建符号链接；平台不支持时改为复制目标内容。 */
    private static void createSymlink(File link, String target) {
        if (target.isEmpty()) {
            return;
        }
        try {
            mkdirs(link.getParentFile());
            Files.createSymbolicLink(link.toPath(), new File(target).toPath());
        } catch (Throwable t) {
            // 可能是 Windows 权限不足，也可能是目标尚不存在。之后会通过运行 JDK 来验证安装结果，因此缺少链接时会明确报错，
            // 而不是造成无法察觉的损坏。
            BootstrapLog.debug("Could not create symlink " + link + " -> " + target + " ("
                + BootstrapLog.describe(t) + ")");
        }
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte value : block) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static String stringField(byte[] block, int offset, int length) {
        return nulTerminated(block, offset, length);
    }

    private static String nulTerminated(byte[] bytes, int offset, int length) {
        int end = offset;
        int limit = Math.min(offset + length, bytes.length);
        while (end < limit && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    /** 读取以 NUL 或空格结尾的八进制数，即 tar 大小字段使用的格式。 */
    private static long octalField(byte[] block, int offset, int length) {
        String text = nulTerminated(block, offset, length);
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(text, 8);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static long paddedSize(long size) {
        return size <= 0 ? 0 : ((size + TAR_BLOCK - 1) / TAR_BLOCK) * TAR_BLOCK;
    }

    private static void readExact(InputStream in, byte[] buffer, int length) throws IOException {
        int total = 0;
        while (total < length) {
            int read = in.read(buffer, total, length - total);
            if (read == -1) {
                throw new IOException("Unexpected end of tar stream");
            }
            total += read;
        }
    }

    private static void skipExact(InputStream in, long count) throws IOException {
        long remaining = count;
        byte[] scratch = new byte[8192];
        while (remaining > 0) {
            int read = in.read(scratch, 0, (int) Math.min(scratch.length, remaining));
            if (read == -1) {
                throw new IOException("Unexpected end of tar stream while skipping padding");
            }
            remaining -= read;
        }
    }

    // ------------------------------------------------------------------
    // 目录结构辅助方法
    // ------------------------------------------------------------------

    /**
     * 在解压后的归档中查找 JDK 主目录。
     *
     * <p>Temurin 归档会将文件放在带版本号的目录（{@code jdk-25+1/}）下，而 macOS 构建还会将其嵌套在
     * {@code Contents/Home} 下。因此，搜索会向下遍历数层目录，查找用于识别 JDK 主目录的 {@code bin/java}。</p>
     */
    public static File findJavaHome(File root) {
        return findJavaHome(root, 0);
    }

    private static File findJavaHome(File root, int depth) {
        if (root == null || !root.isDirectory()) {
            return null;
        }
        if (javaExecutable(root) != null) {
            return root;
        }
        if (depth >= 3) {
            return null;
        }
        File macBundle = new File(root, "Contents" + File.separator + "Home");
        if (javaExecutable(macBundle) != null) {
            return macBundle;
        }
        File[] children = root.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                File found = findJavaHome(child, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return findJavaHome(macBundle, depth + 1);
    }

    /** @return JDK 主目录中的 {@code bin/java} 启动程序；不存在时返回 {@code null} */
    public static File javaExecutable(File home) {
        if (home == null) {
            return null;
        }
        File binary = new File(home, "bin" + File.separator + (isWindows() ? "java.exe" : "java"));
        return binary.isFile() ? binary : null;
    }

    /**
     * 为 JDK 启动程序恢复可执行权限位。
     *
     * <p>ZIP 归档不包含 POSIX 权限；通过 JDK 自带流解压 tar 时也不会应用权限。因此在 Unix 上必须手动设置 {@code bin/} 目录中的
     * 启动程序为可执行，否则刚下载的 JDK 无法启动。</p>
     */
    public static void makeExecutable(File home) {
        if (isWindows()) {
            return;
        }
        File bin = new File(home, "bin");
        if (bin.isDirectory()) {
            markExecutable(bin);
        }
        File spawnHelper = new File(home, "lib" + File.separator + "jspawnhelper");
        if (spawnHelper.isFile()) {
            spawnHelper.setExecutable(true, false);
        }
    }

    private static void markExecutable(File directory) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                markExecutable(child);
            } else if (!child.setExecutable(true, false)) {
                BootstrapLog.debug("Could not mark " + child + " executable.");
            }
        }
    }

    /** 移动目录；目标位于其他设备时改为递归复制。 */
    public static void moveOrCopy(File source, File target) throws IOException {
        mkdirs(target.getParentFile());
        try {
            Files.move(source.toPath(), target.toPath());
            return;
        } catch (IOException | UnsupportedOperationException e) {
            BootstrapLog.debug("Directory move failed (" + BootstrapLog.describe(e) + "); copying instead.");
        }
        copyRecursively(source, target);
        deleteRecursively(source);
    }

    public static void copyRecursively(File source, File target) throws IOException {
        if (source.isDirectory()) {
            mkdirs(target);
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyRecursively(child, new File(target, child.getName()));
                }
            }
            return;
        }
        mkdirs(target.getParentFile());
        try (InputStream in = new BufferedInputStream(new FileInputStream(source), BUFFER_SIZE);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(target), BUFFER_SIZE)) {
            copy(in, out);
        }
        target.setExecutable(source.canExecute(), false);
    }

    public static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            BootstrapLog.debug("Could not delete " + file);
        }
    }

    public static void mkdirs(File directory) throws IOException {
        if (directory == null || directory.isDirectory()) {
            return;
        }
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Could not create directory " + directory);
        }
    }

    /** 将归档条目解析到目标目录，并拒绝任何越出目标目录的路径。 */
    private static File safeChild(File destination, String entryName) throws IOException {
        String normalized = entryName.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        File candidate = new File(destination, normalized);
        String rootPath = destination.getCanonicalPath();
        String candidatePath = candidate.getCanonicalPath();
        if (!candidatePath.equals(rootPath) && !candidatePath.startsWith(rootPath + File.separator)) {
            throw new IOException("Refusing to extract outside the target directory: " + entryName);
        }
        return candidate;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** 读取固定字节数后停止的输入流，用于读取 tar 条目数据。 */
    private static final class BoundedInputStream extends InputStream {

        private final InputStream delegate;
        private long remaining;

        private BoundedInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = delegate.read();
            if (value != -1) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int capped = (int) Math.min(length, remaining);
            int read = delegate.read(buffer, offset, capped);
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }
}
