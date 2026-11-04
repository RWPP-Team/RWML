/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次目录请求的结果。
 *
 * <p>目录只为界面与安装提供数据，缓存与否由仓库实现决定；此类本身不可变，因此可以安全地在界面线程与工作线程之间传递。</p>
 *
 * @param name        目录名称
 * @param source      目录地址，用于诊断
 * @param generatedAt 目录生成时间，ISO 8601 文本；未知时为空字符串
 * @param entries     目录中的扩展
 */
public record Catalog(String name, String source, String generatedAt, List<CatalogEntry> entries) {

    public Catalog {
        name = name == null ? "" : name.trim();
        source = source == null ? "" : source.trim();
        generatedAt = generatedAt == null ? "" : generatedAt.trim();
        entries = Collections.unmodifiableList(new ArrayList<>(entries == null ? List.of() : entries));
    }

    /** @return 目录中的条目数量 */
    public int size() {
        return entries.size();
    }

    /** @return 指定 ID 的条目；不存在时返回 {@code null} */
    public CatalogEntry find(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (CatalogEntry entry : entries) {
            if (entry.id().equalsIgnoreCase(id.trim())) {
                return entry;
            }
        }
        return null;
    }
}
