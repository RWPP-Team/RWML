/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.repository;

import io.github.rwml.api.extensions.ExtensionEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次安装的结果。
 *
 * <p>安装只写入归档，不执行扩展代码。结果保留每个归档的导入情况，使界面能够区分“已更新”“已是同一版本”和“写入失败”。
 * 归档发生变化时，宿主需要重启才能加载新代码。</p>
 *
 * @param id        目录条目的扩展 ID
 * @param version   已安装的版本
 * @param imported  本次写入的扩展
 * @param skipped   未写入的归档及原因
 * @param failed    写入失败的归档及原因
 */
public record InstallReport(String id, String version, List<ExtensionEntry> imported,
                            List<String> skipped, Map<String, String> failed) {

    public InstallReport {
        id = id == null ? "" : id.trim();
        version = version == null ? "" : version.trim();
        imported = Collections.unmodifiableList(new ArrayList<>(imported == null ? List.of() : imported));
        skipped = Collections.unmodifiableList(new ArrayList<>(skipped == null ? List.of() : skipped));
        failed = Collections.unmodifiableMap(new LinkedHashMap<>(failed == null ? Map.of() : failed));
    }

    /** @return 是否有归档发生变化，因而需要重启宿主 */
    public boolean requiresRestart() {
        return !imported.isEmpty();
    }

    /** @return 两个平台之外的归档是否全部成功 */
    public boolean complete() {
        return failed.isEmpty();
    }
}
