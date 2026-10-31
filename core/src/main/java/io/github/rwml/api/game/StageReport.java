/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 {@link GameBoot#start()} 的执行结果。
 *
 * <p>此报告按顺序列出所有已运行阶段，并列出每个失败阶段及其原因。两类结果有意分开：字体加载失败时引擎阶段仍可能成功，
 * 调用方需要区分“尚未运行”和“运行失败”。</p>
 */
public final class StageReport {

    private final List<String> ran;
    private final Map<String, String> failed;

    StageReport(List<String> ran, Map<String, String> failed) {
        this.ran = Collections.unmodifiableList(new ArrayList<>(ran));
        this.failed = Collections.unmodifiableMap(new LinkedHashMap<>(failed));
    }

    /** @return 已成功完成的阶段名称，顺序与运行顺序一致 */
    public List<String> ran() {
        return ran;
    }

    /** @return 失败阶段及其原因，以阶段名称为键 */
    public Map<String, String> failed() {
        return failed;
    }

    /** @return 所有阶段是否都已成功完成 */
    public boolean isClean() {
        return failed.isEmpty();
    }

    /**
     * @return 指定阶段是否已运行并完成；调用方可据此判断引擎是否启动
     */
    public boolean contains(String name) {
        return ran.contains(name);
    }

    @Override
    public String toString() {
        return "StageReport{ran=" + ran + ", failed=" + failed + "}";
    }
}
