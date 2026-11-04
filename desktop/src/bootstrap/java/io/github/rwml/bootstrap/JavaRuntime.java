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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 确保磁盘上存在符合版本要求的 JVM；不存在时会下载一个。
 *
 * <p>此类解决的问题是：游戏以 Java 8 字节码发布，启动器可能会使用 Java 8 运行时启动游戏，但 RWML 核心需要较新的 JVM。
 * 启动器无需要求玩家自行安装，而是下载一次官方 Temurin 构建，缓存起来供之后启动复用。</p>
 *
 * <p>按以下顺序查找：</p>
 * <ol>
 *   <li>安装程序写入游戏目录的 {@code rwml-runtime}，无需下载；</li>
 *   <li>本机已安装的 JVM：{@code JAVA_HOME}、当前进程的 {@code java.home}、{@code PATH} 中的
 *       {@code java}、Windows 注册表中的 {@code JavaHome} 与常见安装目录。安装包通常只更新
 *       {@code PATH} 或注册表，不设置 {@code JAVA_HOME}，因此不能只检查该变量；</li>
 *   <li>{@code <cache>/jdk/temurin-<version>} 中之前下载的 JDK，使用前会先验证；</li>
 *   <li>从国内镜像下载新版本，镜像不可用时回退 Adoptium，校验和验证通过后解压到缓存目录。</li>
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
            + "?os=%s&architecture=%s&image_type=jdk&vendor=eclipse&heap_size=normal";

    private static final String BINARY_API =
        "https://api.adoptium.net/v3/binary/latest/%d/ga/%s/%s/jdk/hotspot/normal/eclipse";

    /**
     * Temurin 的国内镜像。镜像目录结构与 Adoptium 发布文件名一致，因此可以用 API 返回的包名直接拼接。
     * 镜像不可用时仍回退到 Adoptium 给出的地址。
     */
    private static final String[] MIRROR_ROOTS = {
        "https://mirrors.tuna.tsinghua.edu.cn/Adoptium",
        // Java 8 的证书库可能不包含镜像证书；HTTP 只作为回退，且必须先取得 HTTPS API 的摘要。
        "http://mirrors.tuna.tsinghua.edu.cn/Adoptium"
    };

    private JavaRuntime() {
    }

    /**
     * @param required 所需的最低 JVM 主版本
     * @return 符合 {@code required} 要求的 JVM 主目录
     * @throws IOException 没有可用 JVM 且无法下载时抛出
     */
    public static File provision(int required) throws IOException {
        File bundled = bundledHome(required);
        if (bundled != null) {
            BootstrapLog.info("Using the Java runtime installed with RWML at " + bundled);
            return bundled;
        }

        File system = bestSystemHome(required);
        if (system != null) {
            BootstrapLog.info("Using the installed JVM at " + system + " (Java "
                + versionOf(system) + ")");
            return system;
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
     * @return 安装程序随附的 JRE；不存在或版本不足时返回 {@code null}
     */
    private static File bundledHome(int required) {
        for (File directory : gameDirectories()) {
            File runtime = new File(directory, "rwml-runtime");
            for (File candidate : new File[]{new File(runtime, "jdk-" + required), runtime}) {
                if (versionOf(candidate) >= required) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** @return 归档或当前工作目录所在的游戏目录；用于定位随附运行时 */
    private static List<File> gameDirectories() {
        List<File> result = new ArrayList<File>();
        File ownJar = Relauncher.ownJar();
        if (ownJar != null && ownJar.getParentFile() != null) {
            result.add(ownJar.getParentFile());
        }
        String working = System.getProperty("user.dir");
        if (working != null) {
            File directory = new File(working);
            if (!result.contains(directory)) {
                result.add(directory);
            }
        }
        return result;
    }

    /**
     * @return 本机满足 {@code required} 的最高版本 JVM；没有可用 JVM 时返回 {@code null}
     */
    private static File bestSystemHome(int required) {
        File best = null;
        int bestVersion = -1;
        for (File home : systemJavaHomes()) {
            int version = versionOf(home);
            if (version < required) {
                BootstrapLog.debug("Ignoring " + home + ": "
                    + (version < 0 ? "not a usable JVM" : "Java " + version));
                continue;
            }
            if (version > bestVersion) {
                best = home;
                bestVersion = version;
            }
        }
        return best;
    }

    /**
     * 收集本机可能存在的 JVM。
     *
     * <p>安装包通常只写 {@code PATH} 与注册表，{@code JAVA_HOME} 往往为空；只检查该变量会让已经安装
     * Java 25 的玩家仍然触发下载。</p>
     */
    private static List<File> systemJavaHomes() {
        Map<String, File> homes = new LinkedHashMap<String, File>();
        addHome(homes, System.getenv("JAVA_HOME"));
        addHome(homes, System.getProperty("java.home"));
        addHomes(homes, pathJavaHomes());
        addHomes(homes, registryJavaHomes());
        addHomes(homes, commonJavaHomes());
        return new ArrayList<File>(homes.values());
    }

    private static void addHomes(Map<String, File> homes, List<File> candidates) {
        for (File candidate : candidates) {
            if (candidate != null) {
                addHome(homes, candidate.getPath());
            }
        }
    }

    /** 接受 JDK 主目录，也接受误指向 {@code bin} 的配置。 */
    private static void addHome(Map<String, File> homes, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        File home = new File(value.trim());
        if (!home.isDirectory()) {
            return;
        }
        if ("bin".equalsIgnoreCase(home.getName())) {
            File parent = home.getParentFile();
            if (parent != null) {
                home = parent;
            }
        }
        if (Archives.javaExecutable(home) == null) {
            return;
        }
        try {
            File canonical = home.getCanonicalFile();
            homes.put(dedupeKey(canonical), canonical);
        } catch (IOException unreadable) {
            homes.put(dedupeKey(home), home);
        }
    }

    /** @return {@code PATH} 中每个 {@code java} 所在的 JDK 主目录 */
    private static List<File> pathJavaHomes() {
        List<File> result = new ArrayList<File>();
        String path = System.getenv("PATH");
        if (path == null || path.isEmpty()) {
            return result;
        }
        String name = isWindows() ? "java.exe" : "java";
        for (String entry : path.split(File.pathSeparator)) {
            String directory = entry.trim();
            if (directory.length() >= 2 && directory.charAt(0) == '"'
                && directory.charAt(directory.length() - 1) == '"') {
                directory = directory.substring(1, directory.length() - 1);
            }
            if (directory.isEmpty()) {
                continue;
            }
            File executable = new File(directory, name);
            if (!executable.isFile()) {
                continue;
            }
            File home = homeOf(executable);
            if (home != null) {
                result.add(home);
            }
        }
        return result;
    }

    /** @return Windows 注册表中登记过的 JDK 主目录；其他平台返回空列表 */
    private static List<File> registryJavaHomes() {
        List<File> result = new ArrayList<File>();
        if (!isWindows()) {
            return result;
        }
        String[] keys = {
            "HKLM\\SOFTWARE\\JavaSoft\\JDK",
            "HKLM\\SOFTWARE\\JavaSoft\\JRE",
            "HKLM\\SOFTWARE\\Eclipse Adoptium\\JDK",
            "HKLM\\SOFTWARE\\Microsoft\\JDK",
            "HKLM\\SOFTWARE\\Azul Systems\\Zulu",
            "HKLM\\SOFTWARE\\BellSoft\\LibericaJDK",
            "HKCU\\SOFTWARE\\JavaSoft\\JDK",
            "HKLM\\SOFTWARE\\WOW6432Node\\JavaSoft\\JDK"
        };
        for (String key : keys) {
            Process process = null;
            try {
                process = new ProcessBuilder("reg", "query", key, "/s", "/v", "JavaHome")
                    .redirectErrorStream(true)
                    .start();
                String output = readProcessText(process);
                for (String line : output.split("\\r?\\n")) {
                    int marker = line.indexOf("REG_SZ");
                    if (marker < 0) {
                        continue;
                    }
                    String value = line.substring(marker + "REG_SZ".length()).trim();
                    if (!value.isEmpty()) {
                        result.add(new File(value));
                    }
                }
            } catch (Exception unreadable) {
                BootstrapLog.debug("Could not read " + key + " (" + BootstrapLog.describe(unreadable) + ").");
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
        }
        return result;
    }

    /** @return 常见安装根目录下的 JDK；不存在的目录会被跳过 */
    private static List<File> commonJavaHomes() {
        List<File> roots = new ArrayList<File>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (isWindows()) {
            addRoot(roots, System.getenv("ProgramFiles"), "Java");
            addRoot(roots, System.getenv("ProgramFiles"), "Eclipse Adoptium");
            addRoot(roots, System.getenv("ProgramFiles"), "Microsoft");
            addRoot(roots, System.getenv("ProgramFiles"), "BellSoft");
            addRoot(roots, System.getenv("ProgramFiles"), "Zulu");
            addRoot(roots, System.getenv("ProgramFiles"), "Amazon Corretto");
            addRoot(roots, System.getenv("LOCALAPPDATA"), "Programs/Eclipse Adoptium");
            addRoot(roots, System.getenv("USERPROFILE"), ".jdks");
        } else if (os.contains("mac") || os.contains("darwin")) {
            roots.add(new File("/Library/Java/JavaVirtualMachines"));
            roots.add(new File("/System/Volumes/Data/Library/Java/JavaVirtualMachines"));
        } else {
            roots.add(new File("/usr/lib/jvm"));
            roots.add(new File("/usr/java"));
            roots.add(new File("/opt/java"));
            roots.add(new File("/opt"));
        }

        List<File> result = new ArrayList<File>();
        for (File root : roots) {
            File[] children = root.listFiles();
            if (children == null) {
                continue;
            }
            for (File child : children) {
                if (!child.isDirectory()) {
                    continue;
                }
                result.add(child);
                File bundle = new File(child, "Contents" + File.separator + "Home");
                if (bundle.isDirectory()) {
                    result.add(bundle);
                }
            }
        }
        return result;
    }

    private static void addRoot(List<File> roots, String base, String relative) {
        if (base == null || base.trim().isEmpty()) {
            return;
        }
        roots.add(new File(base.trim(), relative));
    }

    /** @return 可执行文件所在 JDK 的主目录 */
    private static File homeOf(File executable) {
        try {
            File canonical = executable.getCanonicalFile();
            File bin = canonical.getParentFile();
            return bin == null ? null : bin.getParentFile();
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static String dedupeKey(File home) {
        String path = home.getAbsolutePath();
        return isWindows() ? path.toLowerCase(Locale.ROOT) : path;
    }

    /** 读取 {@code reg} 等短命令的输出；兼容 UTF-16 与控制台代码页。 */
    private static String readProcessText(Process process) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        InputStream input = process.getInputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        byte[] data = buffer.toByteArray();
        Charset charset = data.length >= 2 && data[1] == 0
            ? StandardCharsets.UTF_16LE : Charset.defaultCharset();
        return new String(data, charset);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
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
        String packageName = null;
        String checksum = null;
        long size = -1;
        try {
            String json = Downloader.getText(String.format(ASSETS_API, required, os, arch));
            // 以“package”对象为起点，确保下方读取的字段属于 JDK 归档，而不是前面的“release_link”或同级安装程序条目。
            int anchor = json.indexOf("\"package\"");
            String scope = anchor >= 0 ? json.substring(anchor) : json;
            packageName = firstQuoted(scope, "name");
            checksum = firstQuoted(scope, "checksum");
            link = firstQuoted(scope, "link");
            size = firstNumber(scope, "size");
        } catch (IOException e) {
            BootstrapLog.warn("Could not query the Adoptium API (" + BootstrapLog.describe(e) + ").");
        }

        if (checksum == null) {
            BootstrapLog.warn("No published checksum was available; the archive cannot be "
                + "checksum-verified.");
        }
        if (size > 0) {
            BootstrapLog.info(String.format("Java %d for %s/%s is %.1f MiB.", required, os, arch,
                size / 1048576.0));
        }

        File downloads = new File(cacheRoot(), "downloads");
        Archives.mkdirs(downloads);
        String fileName = fileName(packageName, link, required, os, arch);
        File archive = new File(downloads, fileName);

        File staging = new File(cacheRoot(), "staging-" + Long.toHexString(System.nanoTime()));
        try {
            List<String> sources = archiveSources(required, os, arch, packageName, link);
            if (checksum == null) {
                // 未取得摘要时不允许走明文回退，避免执行无法校验的内容。
                for (java.util.Iterator<String> iterator = sources.iterator(); iterator.hasNext(); ) {
                    if (iterator.next().startsWith("http://")) {
                        iterator.remove();
                    }
                }
            }
            IOException failure = null;
            for (int index = 0; index < sources.size(); index++) {
                String source = sources.get(index);
                if (source.startsWith("http://")) {
                    BootstrapLog.warn("This JVM cannot verify the mirror certificate; using plain HTTP "
                        + "and the published SHA-256 instead: " + source);
                }
                try {
                    Downloader.downloadTo(source, archive, checksum);
                    failure = null;
                    break;
                } catch (IOException attempt) {
                    failure = attempt;
                    BootstrapLog.warn("Could not download from " + source + ": "
                        + BootstrapLog.describe(attempt));
                    if (index + 1 < sources.size()) {
                        BootstrapLog.info("Trying another download source ...");
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }

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

    /**
     * @return 下载地址，国内镜像在前，Adoptium 给出的官方地址在后
     */
    static List<String> archiveSources(int required, String os, String arch,
                                       String packageName, String officialLink) {
        List<String> sources = new ArrayList<String>();
        if (packageName != null && !packageName.isEmpty()) {
            for (String root : MIRROR_ROOTS) {
                sources.add(root + "/" + required + "/jdk/" + arch + "/" + os + "/" + packageName);
            }
        }
        if (officialLink != null && !officialLink.isEmpty()) {
            sources.add(officialLink);
        } else {
            sources.add(String.format(BINARY_API, required, os, arch));
        }
        return sources;
    }

    /** @return 归档在磁盘上的文件名；缺少包名时按平台补上格式后缀 */
    static String fileName(String packageName, String link, int required, String os, String arch) {
        if (packageName != null && !packageName.isEmpty()) {
            return packageName;
        }
        if (link != null && !link.isEmpty()) {
            String name = link.substring(link.lastIndexOf('/') + 1);
            int query = name.indexOf('?');
            if (query >= 0) {
                name = name.substring(0, query);
            }
            if (name.indexOf('.') >= 0) {
                return name;
            }
        }
        String extension = "windows".equals(os) ? ".zip" : ".tar.gz";
        return "jdk-" + required + "-" + os + "-" + arch + extension;
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
            // 32 位 JVM 运行在 64 位 Windows 上时，系统仍能运行 64 位 Java；使用扩展变量避免下载 32 位 JDK。
            String extended = System.getenv("PROCESSOR_ARCHITEW6432");
            if (extended != null) {
                String value = extended.toLowerCase(Locale.ROOT);
                if (value.contains("arm64")) {
                    return "aarch64";
                }
                if (value.contains("amd64") || value.contains("x86_64")) {
                    return "x64";
                }
            }
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
