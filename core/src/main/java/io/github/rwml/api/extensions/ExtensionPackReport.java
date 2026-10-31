/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.extensions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次整合包导入的结果。
 *
 * <p>导入不做代码执行，也不决定启用状态之外的任何加载行为：归档被放入扩展目录后，下一次启动由依赖与版本解析决定
 * 实际加载什么。</p>
 *
 * @param name 整合包名称；没有清单文件时为空字符串
 * @param version 整合包版本；没有清单文件时为空字符串
 * @param imported 本次新写入或替换的扩展
 * @param skipped 未写入的成员及原因，例如已安装同一版本
 * @param failed 无法写入的成员及原因，键为压缩包内路径
 */
public record ExtensionPackReport(String name, String version, List<ExtensionEntry> imported,
                                  List<String> skipped, Map<String, String> failed) {

    public ExtensionPackReport {
        name = name == null ? "" : name;
        version = version == null ? "" : version;
        imported = Collections.unmodifiableList(new ArrayList<>(imported));
        skipped = Collections.unmodifiableList(new ArrayList<>(skipped));
        failed = Collections.unmodifiableMap(new LinkedHashMap<>(failed));
    }

    /** @return 写入的扩展数量 */
    public int installed() { return imported.size(); }

    /** @return 是否有归档发生变化，因而需要重启宿主 */
    public boolean requiresRestart() { return !imported.isEmpty(); }
}
