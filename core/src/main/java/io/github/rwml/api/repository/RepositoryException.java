/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.repository;

import java.io.IOException;

/**
 * 目录无法读取或归档无法安装时抛出。
 *
 * <p>继承 {@link IOException}，因为失败原因几乎都来自网络、文件或校验。消息面向用户，例如“目录服务返回 HTTP 503”，
 * 界面可以直接显示而无需翻译。</p>
 */
public class RepositoryException extends IOException {

    public RepositoryException(String message) {
        super(message);
    }

    public RepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
