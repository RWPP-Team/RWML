/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * 确保磁盘上存在符合版本要求的 JVM；不存在时会下载一个。
 *
 * <p>此类解决的问题是：游戏以 Java 8 字节码发布，启动器可能会使用 Java 8 运行时启动游戏，但 RWML 核心需要较新的 JVM。
 * 启动器无需要求玩家自行安装，而是下载一次官方 Temurin 构建，缓存起来供之后启动复用。</p>
 *
 * <p>按以下顺序查找，使用第一个符合条件的结果：</p>
 * <ol>
 *   <li>{@code JAVA_HOME} 指向符合或高于所需版本的 JVM 时，直接使用玩家已有的 JDK，不执行下载；</li>
 *   <li>{@code <cache>/jdk/temurin-<version>} 中之前下载的 JDK，使用前会先验证；</li>
 *   <li>从 Adoptium API 下载新版本，校验和验证通过后解压到缓存目录。</li>
 * </ol>
 *
 * <p>查找规则就是全部配置：RWML 不接收参数，因此无需向命令行传递设置，也无需在启动器和游戏之间保持配置同步。</p>
 *
 * <p>所有候选版本都使用相同方式验证：实际运行其 {@code java -version}，并检查报告的主版本。解压不完整或架构错误的 JDK 会在此处被拒绝并给出明确消息，
 * 而不是留到之后以难以理解的错误失败。</p>
 */
public final class JavaRuntime {

    private static final String ASSETS_API =
        "https://api.adoptium.net/v3/assets/latest/%d/hotspot"
            + "?os=%s&arch=%s&image_type=jdk&vendor=eclipse&heap_size=normal";

    private static final String BINARY_API =
        "https://api.adoptium.net/v3/binary/latest/%d/ga/%s/%s/jdk/hotspot/normal/eclipse";

    private JavaRuntime() {
    }

    /**
     * @param required 所需的最低 JVM 主版本
     * @return 符合 {@code required} 要求的 JVM 主目录
     * @throws IOException 没有可用 JVM 且无法下载时抛出
     */
    public static File provision(int required) throws IOException {
        File system = systemJavaHome();
        if (system != null) {
            int version = versionOf(system);
            if (version >= required) {
                BootstrapLog.info("Using the JVM at JAVA_HOME: " + system + " (Java " + version + ")");
                return system;
            }
            BootstrapLog.debug("JAVA_HOME is " + system + ", which is "
                + (version < 0 ? "not a usable JVM" : "Java " + version) + "; Java " + required
                + " or newer is required, so another JVM is looked for.");
        }

        File cached = cachedHome(required);
        int cachedVersion = versionOf(cached);
        if (cachedVersion >= required) {
            BootstrapLog.info("Using the cached JVM at " + cached + " (Java " + cachedVersion + ")");
            return cached;
        }
        if (cached.exists()) {
            BootstrapLog.warn("The cached JVM at " + cached + " is unusable"
                + (cachedVersion < 0 ? "" : " (reports Java " + cachedVersion + ")")
                + "; downloading a fresh copy.");
            Archives.deleteRecursively(cached);
        }
        return download(required, cached);
    }

    /**
     * @return {@code JAVA_HOME} 环境变量指定的 JDK；变量未设置或目录不存在时返回 {@code null}
     */
    private static File systemJavaHome() {
        String value = System.getenv("JAVA_HOME");
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        File home = new File(value.trim());
        return home.isDirectory() ? home : null;
    }

    /** @return 缓存所需版本 {@code required} 的已下载 JDK 的目录 */
    public static File cachedHome(int required) {
        return new File(new File(cacheRoot(), "jdk"), "temurin-" + required);
    }

    private static File cacheRoot() {
        File root = new File(System.getProperty("user.home", "."), ".rwml");
        try {
            Archives.mkdirs(root);
            return root;
        } catch (IOException e) {
            File fallback = new File(System.getProperty("java.io.tmpdir", "."), "rwml-cache");
            BootstrapLog.warn("Cannot write to " + root + " (" + BootstrapLog.describe(e)
                + "); falling back to " + fallback);
            return fallback;
        }
    }

    // ------------------------------------------------------------------
    // 下载
    // ------------------------------------------------------------------

    private static File download(int required, File target) throws IOException {
        String os = osName();
        String arch = archName();

        String link = null;
        String checksum = null;
        long size = -1;
        try {
            String json = Downloader.getText(String.format(ASSETS_API, required, os, arch));
            // 以“package”对象为起点，确保下方读取的字段属于 JDK 归档，而不是前面的“release_link”或同级安装程序条目。
            int anchor = json.indexOf("\"package\"");
            String scope = anchor >= 0 ? json.substring(anchor) : json;
            checksum = firstQuoted(scope, "checksum");
            link = firstQuoted(scope, "link");
            size = firstNumber(scope, "size");
        } catch (IOException e) {
            BootstrapLog.warn("Could not query the Adoptium API (" + BootstrapLog.describe(e) + ").");
        }

        if (link == null) {
            link = String.format(BINARY_API, required, os, arch);
            BootstrapLog.warn("Falling back to the direct download endpoint; the archive cannot be "
                + "checksum-verified.");
        }
        if (size > 0) {
            BootstrapLog.info(String.format("Java %d for %s/%s is %.1f MiB.", required, os, arch,
                size / 1048576.0));
        }

        File downloads = new File(cacheRoot(), "downloads");
        Archives.mkdirs(downloads);
        String fileName = link.substring(link.lastIndexOf('/') + 1);
        int query = fileName.indexOf('?');
        if (query >= 0) {
            fileName = fileName.substring(0, query);
        }
        File archive = new File(downloads, fileName);

        File staging = new File(cacheRoot(), "staging-" + Long.toHexString(System.nanoTime()));
        try {
            Downloader.downloadTo(link, archive, checksum);

            BootstrapLog.info("Extracting " + archive.getName() + " ...");
            Archives.mkdirs(staging);
            Archives.extract(archive, staging);

            File extracted = Archives.findJavaHome(staging);
            if (extracted == null) {
                throw new IOException("The downloaded archive did not contain a JDK home "
                    + "(no bin/java found in it).");
            }
            Archives.makeExecutable(extracted);
            Archives.deleteRecursively(target);
            Archives.moveOrCopy(extracted, target);

            int version = versionOf(target);
            if (version < required) {
                throw new IOException("The downloaded JDK reports "
                    + (version < 0 ? "no usable version" : "Java " + version)
                    + " but Java " + required + " was expected.");
            }
            BootstrapLog.info("Java " + version + " is ready at " + target);
            return target;
        } catch (IOException e) {
            Archives.deleteRecursively(target);
            throw e;
        } finally {
            Archives.deleteRecursively(staging);
            if (archive.exists() && !archive.delete()) {
                BootstrapLog.debug("Could not delete the downloaded archive " + archive);
            }
        }
    }

    // ------------------------------------------------------------------
    // 验证
    // ------------------------------------------------------------------

    /**
     * 通过运行 JVM 主目录下的程序来确定其主版本。
     *
     * <p>实际启动 JVM 是唯一能验证下载完整、架构匹配且解压成功的方法；更简单的文件存在性检查会错误接受只写入一半的目录。</p>
     *
     * @return JVM 主版本；主目录中没有可运行的 JVM 时返回 {@code -1}
     */
    static int versionOf(File home) {
        File java = Archives.javaExecutable(home);
        if (java == null) {
            return -1;
        }
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(java.getAbsolutePath(), "-version");
            builder.redirectErrorStream(true);
            process = builder.start();
            String output = readText(process.getInputStream());
            process.waitFor();
            return parseJavaVersionOutput(output);
        } catch (Exception e) {
            BootstrapLog.debug("Could not run " + java + " (" + BootstrapLog.describe(e) + ")");
            return -1;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    /** 从 {@code java -version} 输出中的第一个 {@code version "..."} 标记提取功能版本。 */
    static int parseJavaVersionOutput(String output) {
        if (output == null) {
            return -1;
        }
        int marker = output.indexOf("version \"");
        int start = marker >= 0 ? marker + "version \"".length() : output.indexOf('"') + 1;
        if (start <= 0) {
            return -1;
        }
        int end = output.indexOf('"', start);
        if (end < 0) {
            return -1;
        }
        return JvmVersion.parseFeature(output.substring(start, end));
    }

    // ------------------------------------------------------------------
    // 平台检测
    // ------------------------------------------------------------------

    static String osName() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "mac";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        throw new IOException("Unsupported operating system for an automatic JDK download: "
            + System.getProperty("os.name"));
    }

    static String archName() throws IOException {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "aarch64";
        }
        if (arch.contains("64")) {
            // 对于此处支持的 JVM，x86_64、amd64 和 ppc64le 都会进入此分支。
            return arch.contains("ppc") || arch.contains("s390") || arch.contains("riscv")
                ? arch
                : "x64";
        }
        if (arch.contains("x86") || arch.contains("i386") || arch.contains("i686")) {
            return "x32";
        }
        throw new IOException("Unsupported CPU architecture for an automatic JDK download: "
            + System.getProperty("os.arch"));
    }

    // ------------------------------------------------------------------
    // 简易 JSON 字段读取器
    // ------------------------------------------------------------------

    /** @return {@code json} 中第一个 {@code "key": "value"} 对应的值；不存在时返回 {@code null} */
    static String firstQuoted(String json, String key) {
        String needle = "\"" + key + "\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int open = json.indexOf('"', at + needle.length());
        if (open < 0) {
            return null;
        }
        int close = json.indexOf('"', open + 1);
        return close < 0 ? null : json.substring(open + 1, close);
    }

    /** @return {@code json} 中第一个 {@code "key": <number>} 对应的值；不存在时返回 {@code -1} */
    static long firstNumber(String json, String key) {
        String needle = "\"" + key + "\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            return -1;
        }
        int cursor = at + needle.length();
        while (cursor < json.length() && (json.charAt(cursor) == ':' || Character.isWhitespace(json.charAt(cursor)))) {
            cursor++;
        }
        int start = cursor;
        while (cursor < json.length() && Character.isDigit(json.charAt(cursor))) {
            cursor++;
        }
        if (cursor == start) {
            return -1;
        }
        try {
            return Long.parseLong(json.substring(start, cursor));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String readText(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), "UTF-8");
    }
}
