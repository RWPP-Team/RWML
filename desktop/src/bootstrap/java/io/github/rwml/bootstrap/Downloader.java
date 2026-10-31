/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

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
 * 用于下载 JDK 的轻量 HTTPS 客户端。
 *
 * <p>有两个容易被忽略但很重要的细节：</p>
 *
 * <ul>
 *   <li><b>代理环境变量。</b>Java 读取 {@code http.proxyHost} 等系统属性，不认识 shell 和容器运行时普遍设置的
 *       {@code HTTP_PROXY}、{@code HTTPS_PROXY} 和 {@code NO_PROXY} 变量。若设备只能通过这些变量访问互联网，下载就会因超时失败，
 *       且错误信息不明确。因此此处会将这些变量转换为系统属性，但不会覆盖显式传入的 {@code -D} 参数。</li>
 *   <li><b>校验和。</b>下载内容是之后要执行的 JDK，因此会在流式传输时计算哈希值，并在解压归档前校验 Adoptium 发布的预期 SHA-256。</li>
 * </ul>
 */
public final class Downloader {

    private static final int CONNECT_TIMEOUT_MILLIS = 20_000;
    private static final int READ_TIMEOUT_MILLIS = 120_000;
    private static final int BUFFER_SIZE = 64 * 1024;

    /** 下载期间写入进度信息的间隔。 */
    private static final long PROGRESS_STEP_BYTES = 16L * 1024 * 1024;

    private Downloader() {
    }

    /** 获取一段简短文本（API 响应），并以 UTF-8 返回。 */
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
     * 将 {@code url} 的内容流式写入 {@code target}，并在写入时校验 SHA-256。
     *
     * @param expectedSha256 预期的小写十六进制摘要；传入 {@code null} 时跳过校验
     * @throws IOException 传输失败或摘要不匹配时抛出
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
            // 只有服务器未使用分块编码时该值才有意义；此时会直接忽略 -1。
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
     * 将常见代理环境变量映射为 Java 实际读取的系统属性。
     *
     * <p>只会应用对应属性尚未设置的环境变量，因此命令行中显式传入的 {@code -Dhttps.proxyHost=...} 不会被覆盖。URL 中的用户信息
     *（{@code http://user:pass@host:port}）会进行百分号解码，并转换为 {@code *.proxyUser} 和 {@code *.proxyPassword}。</p>
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
                // shell 约定使用逗号分隔，Java 则要求使用“|”分隔模式列表。
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
            // 移除路径部分。
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
        // GitHub 会将发行包下载请求重定向到 CDN，而该 CDN 会拒绝发往 API 主机的请求。
        connection.setRequestProperty("Accept", "*/*");
        return connection;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // 所有 Java 平台都必须支持 SHA-256，因此不应发生这种情况。
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
