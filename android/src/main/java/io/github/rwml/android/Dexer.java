/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.android;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.util.zip.Adler32;

/**
 * 在设备上使用 {@code dx} 将类文件编译为 dex。{@code dx} 是 Android 开源项目的 dex 编译器，APK 通过
 * {@code dalvik-dx} 依赖携带它。
 *
 * <p>这就是 Android 上替代 JVM agent 的流程：先改写类，再以 dex 形式交给运行时。结果会被缓存，因此启动时
 * 如果找到有效缓存，就无需重新编译。</p>
 *
 * <p>{@code --multi-dex} 是必需参数：单个 dex 文件最多可索引 65,536 个方法引用，而游戏类自身就已超过此上限。
 * 此处生成的每个 dex 在返回前都会被设为只读，因为平台拒绝打开仍可写入的 dex。</p>
 */
final class Dexer {

    /** dx 接受的最新类文件版本；详见 {@link #lowerClassVersions}。 */
    static final int MAX_CLASS_VERSION = 51;

    /** 传给 dx 的副本名称；文件位于输出目录中，并会在之后立即删除。 */
    private static final String INPUT_FILE = "input.jar";

    /** dx 用于累积输出且从不清空的静态列表；详见 {@link #clearDxState}。 */
    private static final String[] DX_ACCUMULATORS = {"dexOutputFutures", "dexOutputArrays"};

    private static final int CLASS_MAGIC = 0xCAFEBABE;

    private Dexer() {
    }

    /**
     * 将 {@code jar} 中的所有类文件编译为 dex 文件，并放入 {@code outputDirectory}。
     *
     * @return 写入的 dex 文件，按加载顺序排列
     */
    static List<Path> toDex(Path jar, Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        deleteDexFiles(outputDirectory);

        Path input = outputDirectory.resolve(INPUT_FILE);
        lowerClassVersions(jar, input);
        try {
            clearDxState();
            // dx 是较旧的命令行工具，只支持通过解析后的参数列表调用。它通过对接收的名称调用
            // File.isDirectory 来判断输出位置是否为目录，因此该目录必须预先存在（上方已创建）。
            com.android.dx.command.dexer.Main.Arguments arguments =
                new com.android.dx.command.dexer.Main.Arguments();
            arguments.parse(new String[]{
                "--multi-dex",
                "--output=" + outputDirectory.toAbsolutePath(),
                input.toAbsolutePath().toString()
            });

            int result = com.android.dx.command.dexer.Main.run(arguments);
            if (result != 0) {
                throw new IOException("dx could not compile " + jar.getFileName()
                    + " (exit code " + result + ")");
            }
        } finally {
            Files.deleteIfExists(input);
        }

        List<Path> produced = dexFiles(outputDirectory);
        if (produced.isEmpty()) {
            throw new IOException("dx produced no dex for " + jar.getFileName());
        }
        for (Path dex : produced) {
            makeReadOnly(dex);
        }
        return produced;
    }

    /**
     * 将 dx 保存输出结果的列表恢复为新进程中的初始状态。
     * <p>dx 将结果保存在静态字段中，两个多 dex 累加器也从未清空。同一进程中再次运行时会一并写出第一次
     * 运行生成的分片；这些分片又排在加载顺序前面，导致重建要修改的类仍由上一次构建决定。</p>
     */
    private static void clearDxState() {
        for (String name : DX_ACCUMULATORS) {
            try {
                Field field = com.android.dx.command.dexer.Main.class.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof Collection) {
                    ((Collection<?>) value).clear();
                }
            } catch (Throwable t) {
                System.err.println("[rwml-dex] Could not reset dx." + name + ": " + t);
            }
        }
    }

    /**
     * 将 {@code source} 复制到 {@code target}，并为每个类文件标记 dx 可接受的版本：dx 早于 Java 8，
     * 会拒绝版本标记为 52 或更高的文件。编译后不会保留该版本标记，因此降低标记不会改变代码行为。
     */
    private static void lowerClassVersions(Path source, Path target) throws IOException {
        try (JarFile in = new JarFile(source.toFile());
             OutputStream file = Files.newOutputStream(target);
             JarOutputStream out = new JarOutputStream(new BufferedOutputStream(file))) {
            Enumeration<JarEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] bytes;
                try (InputStream stream = in.getInputStream(entry)) {
                    bytes = JarResources.readFully(stream);
                }
                out.putNextEntry(new JarEntry(entry.getName()));
                out.write(accepted(bytes));
                out.closeEntry();
            }
        }
    }

    /** @return dx 接受当前版本时返回未修改的 {@code classBytes}，否则返回降低版本标记后的内容 */
    private static byte[] accepted(byte[] classBytes) {
        if (classBytes.length < 8 || magic(classBytes) != CLASS_MAGIC) {
            return classBytes;
        }
        if (major(classBytes) <= MAX_CLASS_VERSION) {
            return classBytes;
        }
        byte[] lowered = classBytes.clone();
        // 版本 51 及以下只允许次版本号为 0。
        lowered[4] = 0;
        lowered[5] = 0;
        lowered[6] = (byte) (MAX_CLASS_VERSION >>> 8);
        lowered[7] = (byte) MAX_CLASS_VERSION;
        return lowered;
    }

    private static int magic(byte[] classBytes) {
        return ((classBytes[0] & 0xFF) << 24) | ((classBytes[1] & 0xFF) << 16)
            | ((classBytes[2] & 0xFF) << 8) | (classBytes[3] & 0xFF);
    }

    private static int major(byte[] classBytes) {
        return ((classBytes[6] & 0xFF) << 8) | (classBytes[7] & 0xFF);
    }

    /** @return 目录中的 dex 文件，按 {@code classes.dex}、{@code classes2.dex}……的顺序排列 */
    static List<Path> dexFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            return Collections.emptyList();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                .filter(Dexer::isDex)
                .forEach(files::add);
        } catch (IOException e) {
            return Collections.emptyList();
        }
        Collections.sort(files, Dexer::byDexIndex);
        return files;
    }

    /**
     * 将扩展归档中已有的 dex 复制到 {@code outputDirectory}。设备上只编译游戏：dexer 由应用持有，若在安装时
     * 编译每个扩展，就会把相应开销转移到启动阶段。
     *
     * @return 写入的 dex 文件，按加载顺序排列
     * @throws IOException 归档中完全不含 dex 时抛出
     */
    static List<Path> carriedDex(Path archive, Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        deleteDexFiles(outputDirectory);

        List<Path> produced = new ArrayList<>();
        try (JarFile in = new JarFile(archive.toFile())) {
            Enumeration<JarEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !isDexName(entry.getName())) {
                    continue;
                }
                // 归档使用与平台相同的 dex 命名方式，因此多 dex 扩展无需单独的清单文件。
                Path target = outputDirectory.resolve(entry.getName());
                try (InputStream stream = in.getInputStream(entry);
                     OutputStream file = Files.newOutputStream(target)) {
                    byte[] bytes = JarResources.readFully(stream);
                    file.write(bytes);
                }
                makeReadOnly(target);
                produced.add(target);
            }
        }
        if (produced.isEmpty()) {
            throw new IOException(archive.getFileName() + " carries no dex");
        }
        Collections.sort(produced, Dexer::byDexIndex);
        return produced;
    }

    /** @return 归档是否自带 dex，因此可以挂载 */
    static boolean carriesDex(Path archive) {
        try (JarFile in = new JarFile(archive.toFile())) {
            Enumeration<JarEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.isDirectory() && isDexName(entry.getName())) {
                    return true;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    /** 导入文件替换已安装归档前检查其中携带的 dex。此操作不会挂载任何类。 */
    static void validateCarriedDex(Path archive) throws IOException {
        try (JarFile jar = new JarFile(archive.toFile())) {
            if (jar.getJarEntry("classes.dex") == null) {
                throw new IOException("Android extensions must contain classes.dex and class metadata");
            }
            Enumeration<JarEntry> entries = jar.entries();
            long total = 0;
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!isDexName(entry.getName())) { continue; }
                if (!entry.getName().matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) {
                    throw new IOException("Invalid dex name: " + entry.getName());
                }
                total += entry.getSize();
                if (entry.getSize() < 112 || total > 128L * 1024 * 1024) {
                    throw new IOException("Invalid or oversized dex: " + entry.getName());
                }
                try (DataInputStream input = new DataInputStream(jar.getInputStream(entry))) {
                    byte[] header = new byte[112];
                    input.readFully(header);
                    if (header[0] != 'd' || header[1] != 'e' || header[2] != 'x' || header[3] != '\n'
                        || header[7] != 0 || header[4] < '0' || header[4] > '9'
                        || header[5] < '0' || header[5] > '9' || header[6] < '0' || header[6] > '9') {
                        throw new IOException("Invalid dex header: " + entry.getName());
                    }
                    ByteBuffer fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                    if (fields.getInt(32) != entry.getSize() || fields.getInt(36) < 112
                        || fields.getInt(36) > entry.getSize()) {
                        throw new IOException("Invalid dex size: " + entry.getName());
                    }
                    Adler32 checksum = new Adler32();
                    MessageDigest signature;
                    try { signature = MessageDigest.getInstance("SHA-1"); }
                    catch (NoSuchAlgorithmException missing) { throw new IOException(missing); }
                    checksum.update(header, 12, 100);
                    signature.update(header, 32, 80);
                    byte[] buffer = new byte[16384];
                    long length = header.length;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checksum.update(buffer, 0, count);
                        signature.update(buffer, 0, count);
                        length += count;
                    }
                    if (length != entry.getSize() || (int) checksum.getValue() != fields.getInt(8)
                        || !Arrays.equals(signature.digest(), Arrays.copyOfRange(header, 12, 32))) {
                        throw new IOException("Corrupt dex: " + entry.getName());
                    }
                }
            }
        }
    }

    /** @return JAR 条目名称是否为平台会加载的 dex 文件 */
    private static boolean isDexName(String name) {
        if (name.indexOf('/') >= 0) {
            return false;
        }
        return name.equals("classes.dex")
            || (name.startsWith("classes") && name.endsWith(".dex"));
    }

    private static boolean isDex(Path path) {
        return isDexName(path.getFileName().toString());
    }

    /** 确保 {@code classes.dex} 排在 {@code classes2.dex} 之前，而 {@code classes2.dex} 又排在 {@code classes10.dex} 之前。 */
    private static int byDexIndex(Path left, Path right) {
        return index(left) - index(right);
    }

    private static int index(Path path) {
        String name = path.getFileName().toString();
        String digits = name.substring("classes".length(), name.length() - ".dex".length());
        if (digits.isEmpty()) {
            return 1;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static void deleteDexFiles(Path directory) throws IOException {
        for (Path dex : dexFiles(directory)) {
            makeWritable(dex);
            Files.deleteIfExists(dex);
        }
    }

    /**
     * 清除 dex 文件的所有写入权限位：从 Android 14 起，平台会拒绝打开仍可写入的文件，因为应用可修改的文件
     * 可能替换已校验的字节码。目录本身会保留写入权限，供运行时存放优化后的副本。
     *
     * @throws IOException 操作后文件仍可写入时抛出
     */
    static void makeReadOnly(Path dex) throws IOException {
        if (dex.toFile().setReadOnly() || !Files.isWritable(dex)) {
            return;
        }
        throw new IOException("cannot make " + dex.getFileName()
            + " read-only, and the platform refuses to load a writable dex");
    }

    /**
     * 恢复 {@link #makeReadOnly} 清除的写入权限位。
     *
     * <p>设为只读是为了满足平台要求，但会影响常规文件操作：不同系统对只读文件能否删除的处理不尽相同，
     * 因此删除只读文件前会恢复写入权限。无法删除的文件也无法重新构建，会导致缓存无法更新。</p>
     */
    static void makeWritable(Path dex) {
        dex.toFile().setWritable(true, true);
    }
}
