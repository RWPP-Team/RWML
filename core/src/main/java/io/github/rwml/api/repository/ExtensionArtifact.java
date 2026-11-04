/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.repository;

/**
 * 目录中一个可下载的扩展归档。
 *
 * <p>一个条目可以携带多个归档：平台不同时各用一份，或是同一平台的 API 与实现分成两个 jar。归档的 {@code sha256}
 * 是安装前的强制校验值，缺失校验值的条目不会被下载。</p>
 *
 * @param id         归档内 {@code META-INF/extension.properties} 声明的扩展 ID
 * @param platform   目标平台，取 {@code jvm}、{@code android} 或空字符串（表示全部平台）
 * @param url        下载地址；相对地址按目录地址解析
 * @param sha256     归档的 SHA-256 摘要，十六进制，大小写不敏感
 * @param size       归档字节数；未知时为 0
 */
public record ExtensionArtifact(String id, String platform, String url, String sha256, long size) {

    public ExtensionArtifact {
        id = id == null ? "" : id.trim();
        platform = platform == null ? "" : platform.trim();
        url = url == null ? "" : url.trim();
        sha256 = sha256 == null ? "" : sha256.trim().toLowerCase(java.util.Locale.ROOT);
        if (size < 0) {
            throw new IllegalArgumentException("Artifact size cannot be negative");
        }
    }

    /** @return 此归档是否适用于给定平台 */
    public boolean matches(String target) {
        return platform.isEmpty() || platform.equalsIgnoreCase(target);
    }

    /** @return 是否带有安装所需的摘要与地址 */
    public boolean usable() {
        return !url.isEmpty() && sha256.length() == 64 && size >= 0;
    }
}
