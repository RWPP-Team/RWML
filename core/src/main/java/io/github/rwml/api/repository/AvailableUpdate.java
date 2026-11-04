/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.repository;

/**
 * 已安装版本落后于目录时的提示信息。
 *
 * @param id               扩展 ID
 * @param installedVersion 当前安装的版本
 * @param entry            目录中的新版本
 */
public record AvailableUpdate(String id, String installedVersion, CatalogEntry entry) {
}
