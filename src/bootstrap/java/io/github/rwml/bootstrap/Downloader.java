package io.github.rwml.bootstrap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A tiny HTTPS client used to fetch a JDK.
 *
 * <p>Two details matter more than they look:</p>
 *
 * <ul>
 *   <li><b>Proxy environment variables.</b> Java reads {@code http.proxyHost} and friends, and knows
 *       nothing about the {@code HTTP_PROXY} / {@code HTTPS_PROXY} / {@code NO_PROXY} variables that
 *       every shell and container runtime sets. A machine that reaches the internet only through
 *       those variables would fail to download with a confusing timeout, so they are translated into
 *       system properties here - unless an explicit {@code -D} was already given, which always
 *       wins.</li>
 *   <li><b>Checksums.</b> The download is a JDK that will be executed, so the bytes are hashed while
 *       streaming and the expected SHA-256 published by Adoptium is verified before the archive is
 *       ever unpacked.</li>
 * </ul>
 */
public final class Downloader {

    private static final int CONNECT_TIMEOUT_MILLIS = 20_000;
    private static final int READ_TIMEOUT_MILLIS = 120_000;
    private static final int BUFFER_SIZE = 64 * 1024;

    /** How often a progress line is written while downloading. */
    private static final long PROGRESS_STEP_BYTES = 16L * 1024 * 1024;

    private Downloader() {
    }

    /** Fetches a small text document (an API response) and returns it as UTF-8. */
    public static String getText(String url) throws IOException {
        configureProxyFromEnvironment();
        HttpURLConnection connection = open(url);
        try {
            int status = connection.getResponseCode();
            if (status / 100 != 2) {
                throw new IOException("GET " + url + " returned HTTP " + status);
            }
            try (InputStream in = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE)) {
                return new String(readFully(in), "UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Streams {@code url} into {@code target}, verifying the SHA-256 while it is written.
     *
     * @param expectedSha256 the expected lowercase hex digest, or {@code null} to skip verification
     * @throws IOException if the transfer fails or the digest does not match
     */
    public static void downloadTo(String url, File target, String expectedSha256) throws IOException {
        configureProxyFromEnvironment();
        BootstrapLog.info("Downloading " + url);
        HttpURLConnection connection = open(url);
        MessageDigest digest = newSha256();
        long total = 0;
        long expectedSize = -1;
        long nextReport = PROGRESS_STEP_BYTES;
        try {
            int status = connection.getResponseCode();
            if (status / 100 != 2) {
                throw new IOException("GET " + url + " returned HTTP " + status);
            }
            // Only meaningful when the server did not use chunked encoding; -1 is then simply ignored.
            expectedSize = connection.getContentLengthLong();
            if (expectedSize > 0) {
                BootstrapLog.info(String.format("Size: %.1f MiB", expectedSize / 1048576.0));
            }
            try (InputStream in = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(target), BUFFER_SIZE)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    total += read;
                    if (total >= nextReport) {
                        nextReport += PROGRESS_STEP_BYTES;
                        BootstrapLog.info(String.format("  ... %.1f MiB", total / 1048576.0));
                    }
                }
            }
        } catch (IOException e) {
            deleteQuietly(target);
            throw e;
        } finally {
            connection.disconnect();
        }

        String actual = toHex(digest.digest());
        if (expectedSha256 != null && !expectedSha256.equalsIgnoreCase(actual)) {
            deleteQuietly(target);
            throw new IOException("Checksum mismatch for " + url
                + ": expected sha256 " + expectedSha256 + " but got " + actual);
        }
        if (expectedSha256 == null) {
            BootstrapLog.warn("No published checksum was available; sha256 of the download is " + actual);
        } else {
            BootstrapLog.info("Checksum verified (sha256 " + actual + ").");
        }
    }

    /**
     * Mirrors the conventional proxy environment variables into the properties Java actually reads.
     *
     * <p>Only variables whose property is still unset are applied, so an explicit
     * {@code -Dhttps.proxyHost=...} on the command line is never overridden. Userinfo in the URL
     * ({@code http://user:pass@host:port}) is percent-decoded into {@code *.proxyUser} and
     * {@code *.proxyPassword}.</p>
     */
    static synchronized void configureProxyFromEnvironment() {
        String https = firstNonEmpty(System.getenv("https_proxy"), System.getenv("HTTPS_PROXY"));
        String http = firstNonEmpty(System.getenv("http_proxy"), System.getenv("HTTP_PROXY"));
        String chosen = firstNonEmpty(https, http);
        if (chosen != null) {
            for (String scheme : new String[]{"https", "http"}) {
                if (System.getProperty(scheme + ".proxyHost") == null) {
                    applyProxy(chosen, scheme);
                }
            }
            BootstrapLog.debug("Using the proxy at " + chosen + " for HTTP(S) downloads.");
        }
        if (System.getProperty("http.nonProxyHosts") == null) {
            String noProxy = firstNonEmpty(System.getenv("no_proxy"), System.getenv("NO_PROXY"));
            if (noProxy != null && !noProxy.trim().isEmpty()) {
                // The shell convention uses commas; Java expects a '|' separated pattern list.
                System.setProperty("http.nonProxyHosts",
                    noProxy.replace(",", "|").replace("*", "*"));
            }
        }
    }

    private static void applyProxy(String raw, String scheme) {
        try {
            String value = raw.trim();
            int schemeEnd = value.indexOf("://");
            if (schemeEnd >= 0) {
                value = value.substring(schemeEnd + 3);
            }
            // Strip any path component.
            int slash = value.indexOf('/');
            if (slash >= 0) {
                value = value.substring(0, slash);
            }
            String host = value;
            String port = null;
            String userInfo = null;
            int at = value.lastIndexOf('@');
            if (at >= 0) {
                userInfo = value.substring(0, at);
                host = value.substring(at + 1);
            }
            int colon = host.lastIndexOf(':');
            if (colon >= 0) {
                port = host.substring(colon + 1);
                host = host.substring(0, colon);
            }
            if (host.isEmpty()) {
                return;
            }
            System.setProperty(scheme + ".proxyHost", host);
            if (port != null && !port.isEmpty()) {
                System.setProperty(scheme + ".proxyPort", port);
            }
            if (userInfo != null) {
                int userColon = userInfo.indexOf(':');
                String user = userColon >= 0 ? userInfo.substring(0, userColon) : userInfo;
                String password = userColon >= 0 ? userInfo.substring(userColon + 1) : "";
                System.setProperty(scheme + ".proxyUser", URLDecoder.decode(user, "UTF-8"));
                System.setProperty(scheme + ".proxyPassword", URLDecoder.decode(password, "UTF-8"));
            }
        } catch (Throwable t) {
            BootstrapLog.warn("Could not interpret " + scheme + " proxy setting '" + raw + "': "
                + BootstrapLog.describe(t));
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "rwml-bootstrap");
        // GitHub redirects the release download to a CDN that rejects requests for the API host.
        connection.setRequestProperty("Accept", "*/*");
        return connection;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every Java platform; this cannot happen.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }

    private static String firstNonEmpty(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first;
        }
        return second != null && !second.trim().isEmpty() ? second : null;
    }

    private static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }
}
