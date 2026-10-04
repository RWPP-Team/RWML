package io.github.rwml.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Makes sure a JVM of the required version is available on disk, downloading one if it is not.
 *
 * <p>The problem this solves: the game ships as Java 8 bytecode and the launcher may well start it on
 * a Java 8 runtime, but the RWML core needs a modern JVM. Rather than asking the player to install
 * one, the bootstrap fetches an official Temurin build once, caches it, and reuses it on every later
 * launch.</p>
 *
 * <p>Resolution order, first hit wins:</p>
 * <ol>
 *   <li>{@code JAVA_HOME}, when it names a JVM of the required version or newer - a JDK the player
 *       already has is used as it is, and nothing is downloaded;</li>
 *   <li>{@code <cache>/jdk/temurin-<version>} - a previously downloaded JDK, validated before use;</li>
 *   <li>a fresh download from the Adoptium API, checksum verified, extracted into the cache.</li>
 * </ol>
 *
 * <p>The lookup is the whole configuration: RWML takes no parameters, so there is nothing to pass
 * on the command line and nothing to keep in sync between the launcher and the game.</p>
 *
 * <p>Every candidate is validated the same way - by actually running its {@code java -version} and
 * checking the reported feature version. A partially extracted or wrong-architecture JDK is
 * therefore rejected here, with a clear message, instead of failing later with an obscure error.</p>
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
     * @param required the minimum JVM feature version
     * @return the home directory of a JVM satisfying {@code required}
     * @throws IOException when no JVM is available and none could be downloaded
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
     * @return the JDK named by the {@code JAVA_HOME} environment variable, or {@code null} when it
     *         is unset or does not name an existing directory.
     */
    private static File systemJavaHome() {
        String value = System.getenv("JAVA_HOME");
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        File home = new File(value.trim());
        return home.isDirectory() ? home : null;
    }

    /** @return the directory a downloaded JDK of {@code required} is cached in. */
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
    // Download
    // ------------------------------------------------------------------

    private static File download(int required, File target) throws IOException {
        String os = osName();
        String arch = archName();

        String link = null;
        String checksum = null;
        long size = -1;
        try {
            String json = Downloader.getText(String.format(ASSETS_API, required, os, arch));
            // Anchor on the "package" object so the fields read below belong to the JDK archive
            // rather than to an earlier "release_link" or a sibling installer entry.
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
    // Validation
    // ------------------------------------------------------------------

    /**
     * Determines the feature version of a JVM home by running it.
     *
     * <p>Actually starting the JVM is the only check that proves the download is complete, matches
     * the architecture and survives the extraction - a cheaper file-existence test would happily
     * accept a half-written directory.</p>
     *
     * @return the feature version, or {@code -1} when the home holds no runnable JVM
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

    /** Extracts the feature version from the first {@code version "..."} token of {@code java -version}. */
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
    // Platform detection
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
            // x86_64, amd64 and ppc64le all reach this branch on the JVMs worth supporting here.
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
    // Minimal JSON field readers
    // ------------------------------------------------------------------

    /** @return the value of the first {@code "key": "value"} in {@code json}, or {@code null}. */
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

    /** @return the value of the first {@code "key": <number>} in {@code json}, or {@code -1}. */
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
