/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 根据扩展之间声明的关系，解析扩展加载顺序。
 *
 * <p>支持以下三种关系：</p>
 * <ul>
 *   <li>{@code dependsOn} &mdash; 硬性依赖。指定扩展必须存在且必须先加载。若缺少该扩展，或该扩展因任何原因已被排除，
 *       声明依赖的扩展也会被排除。</li>
 *   <li>{@code loadAfter} &mdash; 弱排序提示。若指定扩展存在，则先加载它；未知 ID 会被忽略。</li>
 *   <li>{@code loadBefore} &mdash; 与上项相反的弱排序提示。若指定扩展存在，则后加载它；未知 ID 会被忽略。</li>
 * </ul>
 *
 * <p>遇到常见的依赖图问题时，解析器不会抛出异常。所有无法加载的扩展都会通过
 * {@link DependencyResolution#excluded()} 报告，以便其余正常扩展仍可加载。</p>
 */
public final class ExtensionDependencyResolver {

    /** 将提供的描述信息排序为有效的加载顺序。 */
    public DependencyResolution resolve(List<ExtensionDescriptor> descriptors) {
        Map<String, ExtensionDescriptor> byId = new LinkedHashMap<>();
        Map<String, String> excluded = new LinkedHashMap<>();

        for (ExtensionDescriptor descriptor : descriptors) {
            byId.put(descriptor.id(), descriptor);
        }

        // ---- 步骤 1：递归排除缺少硬性依赖的扩展 ----
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ExtensionDescriptor descriptor : byId.values()) {
                if (excluded.containsKey(descriptor.id())) {
                    continue;
                }
                for (String required : descriptor.dependencyIds()) {
                    if (!byId.containsKey(required) || excluded.containsKey(required)) {
                        excluded.put(descriptor.id(), "Unresolved dependency '" + required + "'");
                        changed = true;
                        break;
                    }
                }
            }
        }

        // ---- 步骤 2：为剩余扩展构建排序关系图 ----
        Map<String, Set<String>> successors = new LinkedHashMap<>();
        Map<String, Integer> indegree = new LinkedHashMap<>();
        for (ExtensionDescriptor descriptor : byId.values()) {
            if (excluded.containsKey(descriptor.id())) {
                continue;
            }
            successors.put(descriptor.id(), new LinkedHashSet<>());
            indegree.put(descriptor.id(), 0);
        }

        for (ExtensionDescriptor descriptor : byId.values()) {
            if (excluded.containsKey(descriptor.id())) {
                continue;
            }
            // dependsOn 和 loadAfter 都表示“对方在我之前加载”。
            for (String predecessor : descriptor.dependencyIds()) {
                addEdge(predecessor, descriptor.id(), successors, excluded);
            }
            for (String predecessor : descriptor.loadAfter()) {
                addEdge(predecessor, descriptor.id(), successors, excluded);
            }
            // loadBefore 表示“对方在我之后加载”。
            for (String successor : descriptor.loadBefore()) {
                addEdge(descriptor.id(), successor, successors, excluded);
            }
        }

        // 自环可能导致节点被加入 `excluded`；移除该节点及仍引用已排除节点的边。
        successors.keySet().removeIf(excluded::containsKey);
        indegree.keySet().removeIf(excluded::containsKey);
        for (Set<String> targets : successors.values()) {
            targets.removeIf(target -> !successors.containsKey(target));
        }

        // 从头重新计算入度（部分边可能已被删除）。
        indegree.replaceAll((key, value) -> 0);
        for (Set<String> targets : successors.values()) {
            for (String target : targets) {
                indegree.merge(target, 1, Integer::sum);
            }
        }

        // ---- 步骤 3：执行 Kahn 算法 ----
        Deque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        List<ExtensionDescriptor> ordered = new ArrayList<>(successors.size());
        Set<String> emitted = new LinkedHashSet<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            if (!emitted.add(id)) {
                continue;
            }
            ordered.add(byId.get(id));
            for (String target : successors.getOrDefault(id, Collections.emptySet())) {
                int remaining = indegree.merge(target, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(target);
                }
            }
        }

        // ---- 步骤 4：未输出的节点位于循环中或依赖于循环 ----
        if (emitted.size() < successors.size()) {
            for (String id : successors.keySet()) {
                if (!emitted.contains(id)) {
                    excluded.put(id, "Involved in or blocked by a dependency cycle");
                }
            }
        }

        return new DependencyResolution(ordered, excluded);
    }

    private static void addEdge(String predecessor, String successor,
                                Map<String, Set<String>> successors,
                                Map<String, String> excluded) {
        if (predecessor == null || successor == null) {
            return;
        }
        if (predecessor.equals(successor)) {
            excluded.putIfAbsent(predecessor, "Declares itself as its own dependency");
            return;
        }
        // 任一方都可能是未知 ID；引用未知扩展的弱提示会直接忽略。硬性依赖已在调用此方法前的步骤 1 中处理。
        Set<String> targets = successors.get(predecessor);
        if (targets == null || !successors.containsKey(successor)) {
            return;
        }
        targets.add(successor);
    }
}