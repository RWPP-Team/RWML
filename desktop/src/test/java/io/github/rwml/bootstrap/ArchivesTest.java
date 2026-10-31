/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 归档层负责解压 RWML 下载并随后运行的 JDK。
 *
 * <p>Adoptium 在 Windows 上发布 {@code .zip}，在其他平台发布 {@code .tar.gz}；JDK 不支持 tar，因此此处手写了解析器。
 * 这些测试使用真实 JDK 归档的目录结构验证解析器，包括外层目录、嵌套路径和两种条目类型，因为解析错误会导致 Linux 上的 Java 运行时无法使用。</p>
 */
class ArchivesTest {

    private static final byte[] JAVA = "binary".getBytes(StandardCharsets.UTF_8);

    @Test
    void extractsAZipIntoTheWrapperDirectoryItContains(@TempDir Path temp) throws IOException {
        Path archive = temp.resolve("jdk.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            entry(out, "jdk-25/bin/java.exe", JAVA);
            entry(out, "jdk-25/release", "JAVA_VERSION=\"25\"\n".getBytes(StandardCharsets.UTF_8));
        }

        Path target = temp.resolve("out");
        Archives.extract(archive.toFile(), target.toFile());

        assertEquals(new String(JAVA, StandardCharsets.UTF_8),
            read(target.resolve("jdk-25/bin/java.exe")));
        assertEquals("JAVA_VERSION=\"25\"\n", read(target.resolve("jdk-25/release")));
    }

    @Test
    void extractsATarGzLaidOutLikeAPublishedJdk(@TempDir Path temp) throws IOException {
        Path archive = temp.resolve("jdk.tar.gz");
        try (OutputStream file = Files.newOutputStream(archive);
             GZIPOutputStream gz = new GZIPOutputStream(file)) {
            tarEntry(gz, "jdk-25/", new byte[0], '5');
            tarEntry(gz, "jdk-25/bin/", new byte[0], '5');
            tarEntry(gz, "jdk-25/bin/java", JAVA, '0');
            tarEntry(gz, "jdk-25/lib/modules", "modules".getBytes(StandardCharsets.UTF_8), '0');
            gz.write(new byte[1024]); // 归档结束标记
        }

        Path target = temp.resolve("out");
        Archives.extract(archive.toFile(), target.toFile());

        assertEquals(new String(JAVA, StandardCharsets.UTF_8), read(target.resolve("jdk-25/bin/java")));
        assertEquals("modules", read(target.resolve("jdk-25/lib/modules")));
    }

    @Test
    void handlesATarEntryWhosePayloadIsNotABlockMultiple(@TempDir Path temp) throws IOException {
        // 600 字节：512 字节负载加上 88 字节填充。必须完整读取这些数据，否则下一个文件头会从负载中间开始解析。
        byte[] data = new byte[600];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ('a' + (i % 26));
        }
        Path archive = temp.resolve("padded.tar.gz");
        try (OutputStream file = Files.newOutputStream(archive);
             GZIPOutputStream gz = new GZIPOutputStream(file)) {
            tarEntry(gz, "first.bin", data, '0');
            tarEntry(gz, "second.txt", "second".getBytes(StandardCharsets.UTF_8), '0');
            gz.write(new byte[1024]);
        }

        Path target = temp.resolve("out");
        Archives.extract(archive.toFile(), target.toFile());

        assertEquals(data.length, Files.readAllBytes(target.resolve("first.bin")).length);
        assertEquals("second", read(target.resolve("second.txt")));
    }

    @Test
    void refusesEntriesThatWouldEscapeTheTargetDirectory(@TempDir Path temp) throws IOException {
        Path archive = temp.resolve("hostile.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            entry(out, "bin/java", JAVA);
            entry(out, "../escaped.txt", "nope".getBytes(StandardCharsets.UTF_8));
        }

        Path target = temp.resolve("out");
        assertThrows(IOException.class, () -> Archives.extract(archive.toFile(), target.toFile()));
        assertEquals(false, Files.exists(temp.resolve("escaped.txt")), "nothing may be written outside");
    }

    @Test
    void findsTheJdkHomeInsideTheWrapperDirectory(@TempDir Path temp) throws IOException {
        Path home = temp.resolve("jdk-25.0.4.1+1");
        Files.createDirectories(home.resolve("bin"));
        Files.write(home.resolve("bin").resolve(javaExecutableName()), new byte[0]);

        assertEquals(home.toFile().getCanonicalFile(),
            Archives.findJavaHome(temp.toFile()).getCanonicalFile());
    }

    @Test
    void findsTheJdkHomeInsideAMacBundle(@TempDir Path temp) throws IOException {
        Path home = temp.resolve("jdk-25.jdk/Contents/Home");
        Files.createDirectories(home.resolve("bin"));
        Files.write(home.resolve("bin").resolve(javaExecutableName()), new byte[0]);

        assertEquals(home.toFile().getCanonicalFile(),
            Archives.findJavaHome(temp.toFile()).getCanonicalFile());
    }

    @Test
    void reportsNoJdkHomeWhenThereIsNone(@TempDir Path temp) {
        assertNull(Archives.findJavaHome(temp.toFile()));
    }

    @Test
    void deletesADirectoryTree(@TempDir Path temp) throws IOException {
        Path nested = temp.resolve("a/b/c");
        Files.createDirectories(nested);
        Files.write(nested.resolve("file"), new byte[]{1});

        Archives.deleteRecursively(temp.resolve("a").toFile());

        assertEquals(false, Files.exists(temp.resolve("a")));
    }

    // ------------------------------------------------------------------

    private static String javaExecutableName() {
        return System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void entry(ZipOutputStream out, String name, byte[] data) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(data);
        out.closeEntry();
    }

    /** 按 GNU tar 打包 JDK 镜像的方式写入一个 ustar 头及其数据。 */
    private static void tarEntry(OutputStream out, String name, byte[] data, char type) throws IOException {
        byte[] header = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, nameBytes.length);
        octal(header, 100, 8, type == '5' ? 0755 : 0644); // 权限模式
        octal(header, 108, 8, 0);                         // 用户 ID
        octal(header, 116, 8, 0);                         // 组 ID
        octal(header, 124, 12, data.length);              // 大小
        octal(header, 136, 12, 0);                        // 修改时间
        header[156] = (byte) type;
        // 读取器不校验校验和，因此该字段保持为 NUL。
        out.write(header);
        if (data.length > 0) {
            out.write(data);
        }
        int padding = (512 - (data.length % 512)) % 512;
        if (padding > 0) {
            out.write(new byte[padding]);
        }
    }

    private static void octal(byte[] header, int offset, int length, long value) {
        String text = Long.toOctalString(value);
        StringBuilder padded = new StringBuilder();
        while (padded.length() + text.length() < length - 1) {
            padded.append('0');
        }
        padded.append(text);
        byte[] bytes = padded.toString().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length - 1));
        header[offset + length - 1] = 0;
    }
}
