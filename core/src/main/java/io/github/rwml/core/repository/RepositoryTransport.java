/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core.repository;

import io.github.rwml.api.repository.RepositoryException;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * 仓库客户端使用的 HTTPS GET 传输。
 *
 * <p>桌面与 Android 共用此实现。Android 不提供 {@code java.net.http.HttpClient}，因此传输只使用
 * {@link HttpURLConnection}；重定向由此处逐跳处理，使每次跳转后的地址都经过同一套 HTTPS 校验。</p>
 */
interface RepositoryTransport {

    /** 目录请求的用户代理。 */
    String USER_AGENT = "RWML-Extension-Browser/1.0";

    /** 跟随重定向的最大次数。 */
    int MAX_REDIRECTS = 5;

    /**
     * 发起一次 GET 请求。
     *
     * @param uri            请求地址，必须为无凭据的 HTTPS 地址
     * @param accept         Accept 请求头
     * @param connectMillis  连接超时，毫秒
     * @param readMillis     读取超时，毫秒
     * @return 响应；调用方负责关闭
     * @throws IOException 网络失败、地址不安全或重定向次数超限
     */
    Response get(URI uri, String accept, int connectMillis, int readMillis) throws IOException;

    /** @return 使用平台标准连接实现的传输 */
    static RepositoryTransport system() {
        return new UrlConnectionTransport();
    }

    /**
     * @param status        响应状态码
     * @param uri           跟随重定向后的最终地址
     * @param contentLength Content-Length，未知时为 -1
     * @param body          响应体，不为 {@code null}
     */
    record Response(int status, URI uri, long contentLength, InputStream body) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            body.close();
        }
    }

    /** 基于 {@link HttpURLConnection} 的实现；可运行在 Android API 26 及以上。 */
    final class UrlConnectionTransport implements RepositoryTransport {

        @Override
        public Response get(URI target, String accept, int connectMillis, int readMillis) throws IOException {
            URI uri = target;
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                requireHttps(uri);
                HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(connectMillis);
                connection.setReadTimeout(readMillis);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept", accept);
                connection.setRequestMethod("GET");
                int status;
                try {
                    status = connection.getResponseCode();
                } catch (IOException failure) {
                    connection.disconnect();
                    throw failure;
                }
                if (isRedirect(status)) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    if (location == null || location.isBlank()) {
                        throw new RepositoryException("The extension repository returned HTTP "
                            + status + " without a redirect address");
                    }
                    URI next;
                    try {
                        next = uri.resolve(location.trim());
                    } catch (IllegalArgumentException invalid) {
                        throw new RepositoryException("The extension repository returned an invalid redirect: "
                            + location, invalid);
                    }
                    requireHttps(next);
                    uri = next;
                    continue;
                }
                try {
                    InputStream stream = status == 200
                        ? connection.getInputStream()
                        : errorStream(connection);
                    return new Response(status, uri, connection.getHeaderFieldLong("Content-Length", -1L),
                        new FilterInputStream(stream) {
                            @Override
                            public void close() throws IOException {
                                try {
                                    super.close();
                                } finally {
                                    connection.disconnect();
                                }
                            }
                        });
                } catch (IOException failure) {
                    connection.disconnect();
                    throw failure;
                }
            }
            throw new RepositoryException("The extension repository redirected more than "
                + MAX_REDIRECTS + " times");
        }

        private static InputStream errorStream(HttpURLConnection connection) {
            InputStream error = connection.getErrorStream();
            return error == null ? new ByteArrayInputStream(new byte[0]) : error;
        }

        private static boolean isRedirect(int status) {
            return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
        }

        /** 每一跳都必须是无凭据的 HTTPS 地址；协议降级与非默认端口同样拒绝。 */
        private static void requireHttps(URI uri) throws RepositoryException {
            if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new RepositoryException("The repository redirected to an insecure address: " + uri);
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new RepositoryException("The repository address has no host: " + uri);
            }
            if (uri.getUserInfo() != null) {
                throw new RepositoryException("The repository address must not contain credentials: " + uri);
            }
            if (uri.getPort() != -1 && uri.getPort() != 443) {
                throw new RepositoryException("The repository must use the default HTTPS port: " + uri);
            }
        }
    }
}
