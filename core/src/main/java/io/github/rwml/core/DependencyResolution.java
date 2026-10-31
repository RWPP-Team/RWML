/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对一组扩展进行依赖排序的结果。
 *
 * @param loadOrder 按加载顺序排列的扩展列表；只包含通过依赖验证的扩展
 * @param excluded  从计划中移除的扩展，以扩展 ID 为键，并映射到简短易读的原因
 */
public record DependencyResolution(List<ExtensionDescriptor> loadOrder, Map<String, String> excluded) {

    public DependencyResolution {
        // 使用 Collections.unmodifiable*，而不使用 List/Map.copyOf：此记录也会在 Android 上构建，
        // API 30 以下没有 copyOf 工厂方法。
        loadOrder = Collections.unmodifiableList(new ArrayList<>(loadOrder));
        excluded = Collections.unmodifiableMap(new LinkedHashMap<>(excluded));
    }
}