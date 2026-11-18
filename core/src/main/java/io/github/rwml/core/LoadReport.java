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
 * 完整加载过程的执行结果。
 *
 * @param loaded  成功实例化的扩展句柄，按加载顺序排列
 * @param failed  扩展 ID（不可读归档则使用归档路径）及对应的简短失败原因
 * @param disabled 已持久化为禁用状态、因此主动跳过的扩展；包含因已有资源包生效而被跳过的其他资源包
 * @param staged  每个宿主自带归档对应一行，描述对其执行的操作；宿主未提供归档时为空
 * @param resourcePack 本次生效的资源包 ID；没有资源包生效时为 null
 * @param incompatible 因要求更新的宿主而未加载的扩展及升级提示
 */
public record LoadReport(List<ExtensionHandle> loaded, Map<String, String> failed, List<String> disabled,
                         List<String> staged, String resourcePack, Map<String, String> incompatible) {

    public LoadReport(List<ExtensionHandle> loaded, Map<String, String> failed, List<String> disabled,
                      List<String> staged, String resourcePack) {
        this(loaded, failed, disabled, staged, resourcePack, Collections.emptyMap());
    }

    /** 此构造函数用于创建没有宿主自带归档需要暂存的报告。 */
    public LoadReport(List<ExtensionHandle> loaded, Map<String, String> failed, List<String> disabled) {
        this(loaded, failed, disabled, Collections.emptyList(), null);
    }

    /** 此构造函数用于创建没有资源包生效的报告。 */
    public LoadReport(List<ExtensionHandle> loaded, Map<String, String> failed, List<String> disabled,
                      List<String> staged) {
        this(loaded, failed, disabled, staged, null);
    }

    public LoadReport {
        loaded = Collections.unmodifiableList(new ArrayList<>(loaded));
        failed = Collections.unmodifiableMap(new LinkedHashMap<>(failed));
        disabled = Collections.unmodifiableList(new ArrayList<>(disabled));
        staged = Collections.unmodifiableList(new ArrayList<>(staged));
        incompatible = Collections.unmodifiableMap(new LinkedHashMap<>(incompatible));
    }

    /** @return 本次是否物化了资源包 */
    public boolean hasResourcePack() {
        return resourcePack != null && !resourcePack.trim().isEmpty();
    }

    public boolean isClean() {
        return failed.isEmpty();
    }
}
