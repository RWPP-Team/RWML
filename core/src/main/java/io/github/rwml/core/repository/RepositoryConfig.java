/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core.repository;

import io.github.rwml.api.repository.RepositoryException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * 扩展目录地址的来源与保存。
 *
 * <p>地址按以下顺序确定：系统属性 {@value #PROPERTY}、环境变量 {@value #ENVIRONMENT}、宿主数据目录中的
 * {@code repository.properties}、内置默认地址。前两者便于发布者与测试临时覆盖，文件则由界面或部署脚本写入。</p>
 */
public final class RepositoryConfig {

    /** 未配置时使用的目录地址。部署文档站时若域名不同，应通过文件或系统属性覆盖。 */
    public static final String DEFAULT_INDEX = "https://rwpp-team.top/api/v1/index.json";

    /** 覆盖目录地址的系统属性名。 */
    public static final String PROPERTY = "rwml.repository.index";

    /** 覆盖目录地址的环境变量名。 */
    public static final String ENVIRONMENT = "RWML_REPOSITORY_INDEX";

    /** 数据目录中保存覆盖地址的文件名。 */
    public static final String FILE_NAME = "repository.properties";

    private static final String KEY = "index";

    private RepositoryConfig() {
    }

    /** @return 数据目录中的配置文件路径 */
    public static Path file(Path dataDirectory) {
        return dataDirectory.resolve(FILE_NAME);
    }

    /**
     * @param dataDirectory 宿主数据目录，用于读取配置文件
     * @return 目录地址，恒为 HTTPS
     */
    public static URI index(Path dataDirectory) throws RepositoryException {
        String override = System.getProperty(PROPERTY);
        if (override == null || override.isBlank()) {
            override = System.getenv(ENVIRONMENT);
        }
        if (override == null || override.isBlank()) {
            override = read(dataDirectory);
        }
        if (override == null || override.isBlank()) {
            override = DEFAULT_INDEX;
        }
        return validate(override);
    }

    /**
     * 写入目录地址，使下次启动使用新的目录。
     *
     * @param dataDirectory 宿主数据目录
     * @param url           新的目录地址
     * @throws IOException 无法写入文件或地址不合法
     */
    public static void index(Path dataDirectory, String url) throws IOException {
        URI uri = validate(url);
        Properties properties = new Properties();
        properties.setProperty(KEY, uri.toString());
        Files.createDirectories(dataDirectory);
        Path target = file(dataDirectory);
        Path temporary = Files.createTempFile(dataDirectory, "repository-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "RWML extension repository");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * 校验目录地址。
     *
     * @param url 待校验地址
     * @return 规范化后的地址
     * @throws RepositoryException 地址不是无凭据的 HTTPS 地址
     */
    public static URI validate(String url) throws RepositoryException {
        if (url == null || url.isBlank()) {
            throw new RepositoryException("The extension repository address is empty");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException invalid) {
            throw new RepositoryException("The extension repository address is invalid: " + url, invalid);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new RepositoryException("The extension repository must use HTTPS: " + url);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new RepositoryException("The extension repository address has no host: " + url);
        }
        if (uri.getUserInfo() != null) {
            throw new RepositoryException("The extension repository address must not contain credentials: " + url);
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new RepositoryException("The extension repository must use the default HTTPS port: " + url);
        }
        return uri;
    }

    private static String read(Path dataDirectory) {
        if (dataDirectory == null) {
            return null;
        }
        Path file = file(dataDirectory);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
            return properties.getProperty(KEY);
        } catch (IOException unreadable) {
            System.err.println("[rwml] Cannot read " + file + ": " + unreadable);
            return null;
        }
    }
}
