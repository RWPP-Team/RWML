/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * 检测归档所需的 Java 字节码版本，并查找设备上安装的其他 JVM。
 */
public final class JavaVersionUtil {

    private JavaVersionUtil() {
    }

    /** 当前运行 JVM 的 Java 版本，例如 {@code 17}。 */
    public static int currentJavaVersion() {
        String specification = System.getProperty("java.specification.version", "8");
        try {
            if (specification.startsWith("1.")) {
                return Integer.parseInt(specification.substring(2, 3));
            }
            return Integer.parseInt(specification.split("\\.")[0]);
        } catch (RuntimeException e) {
            return 8;
        }
    }

    /** 读取类文件头中的主版本号字段。 */
    public static int classFileMajorVersion(byte[] header) {
        if (header == null || header.length < 8) {
            return -1;
        }
        if ((header[0] & 0xFF) != 0xCA || (header[1] & 0xFF) != 0xFE) {
            return -1;
        }
        return ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
    }

    /** 将类文件主版本号转换为 Java 功能版本号。 */
    public static int majorToJavaVersion(int major) {
        if (major <= 44) {
            return 1;
        }
        return major - 44;
    }

    /**
     * 扫描归档中的所有类文件，并返回链接这些类所需的最高 Java 主版本。
     */
    public static int requiredJavaVersion(Path jarFile) throws IOException {
        int highest = 0;
        try (JarFile jar = new JarFile(jarFile.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream stream = jar.getInputStream(entry)) {
                    byte[] header = new byte[8];
                    int read = 0;
                    while (read < header.length) {
                        int count = stream.read(header, read, header.length - read);
                        if (count < 0) {
                            break;
                        }
                        read += count;
                    }
                    int major = classFileMajorVersion(Arrays.copyOf(header, read));
                    if (major > highest) {
                        highest = major;
                    }
                }
            }
        }
        return majorToJavaVersion(highest);
    }

    /**
     * 尝试查找主版本不低于 {@code minimumVersion} 的 {@code java} 可执行文件。
     *
     * @return 可执行文件路径；没有合适版本时返回 {@code null}
     */
    public static Path findJvmAtLeast(int minimumVersion) {
        List<Path> candidates = new ArrayList<>();
        addCandidate(candidates, System.getProperty("java.home"));
        addCandidate(candidates, System.getenv("JAVA_HOME"));

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> roots;
        if (os.contains("win")) {
            roots = Arrays.asList("C:\\Program Files\\Java", "C:\\Program Files\\Eclipse Adoptium");
        } else if (os.contains("mac")) {
            roots = Arrays.asList("/Library/Java/JavaVirtualMachines",
                "/System/Volumes/Data/Library/Java/JavaVirtualMachines");
        } else {
            roots = Arrays.asList("/usr/lib/jvm", "/usr/java", "/opt/java");
        }
        for (String root : roots) {
            Path directory = Paths.get(root);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> children = Files.list(directory)) {
                children.filter(Files::isDirectory).forEach(candidates::add);
            } catch (IOException ignored) {
                // 根目录无法读取，继续尝试其他目录。
            }
        }

        String executableName = os.contains("win") ? "java.exe" : "java";
        for (Path home : candidates) {
            Path executable = home.resolve("bin").resolve(executableName);
            if (!Files.isExecutable(executable)) {
                continue;
            }
            if (probeVersion(home) >= minimumVersion) {
                return executable;
            }
        }
        return null;
    }

    private static void addCandidate(List<Path> candidates, String path) {
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        Path candidate = Paths.get(path);
        if (Files.isDirectory(candidate) && !candidates.contains(candidate)) {
            candidates.add(candidate);
        }
    }

    /** 读取 JDK 附带的 {@code release} 文件；无法确定时返回 -1。 */
    private static int probeVersion(Path javaHome) {
        Path release = javaHome.resolve("release");
        if (!Files.isRegularFile(release)) {
            return -1;
        }
        try {
            for (String line : Files.readAllLines(release)) {
                if (!line.startsWith("JAVA_VERSION=")) {
                    continue;
                }
                String value = line.substring("JAVA_VERSION=".length()).replace("\"", "").trim();
                if (value.startsWith("1.")) {
                    return Integer.parseInt(value.substring(2, 3));
                }
                return Integer.parseInt(value.split("[.\\-_+]")[0]);
            }
        } catch (Exception ignored) {
            // 继续执行，并报告版本未知。
        }
        return -1;
    }
}