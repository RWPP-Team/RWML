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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在处理依赖、界面挂载或回调前，为每个 ID 确定一个归档。
 *
 * <p>同一 ID 可以存在多个已安装版本。选择在无人干预时自动完成：先看用户为下一个进程明确选择的版本，再看各版本能满足多少个
 * 其他扩展的版本约束，最后取较新的版本。约束来自 {@code dependsOn} 中的版本写法，例如
 * {@code lib>=2.0}。</p>
 *
 * <p>用户的选择优先于一切推断，因为它是显式指令。若记录的选择已不存在，则整个 ID 被报告而不是悄悄换成其他版本：一个
 * 指向已删除归档的选择不应被猜成别的版本。</p>
 *
 * <p>当两个扩展的版本要求互相排斥时（例如一个要求 {@code lib>=2}、另一个要求 {@code lib<2}），约束最少的那个方案仍然
 * 胜出，无法满足的一方被排除并给出原因。整个解析不抛异常：其余扩展继续加载。</p>
 */
public final class ExtensionVersions {

    /** 每次迭代至少排除一个 ID，因此迭代次数不会超过 ID 的数量。 */
    private static final int MIN_ROUNDS = 4;

    private ExtensionVersions() { }

    public static String identity(ExtensionDescriptor descriptor) {
        return descriptor.sourceSha256().isEmpty() ? "version:" + descriptor.version() : descriptor.sourceSha256();
    }

    /**
     * @param descriptors 所有已发现的扩展，可能包含同一 ID 的多个版本
     * @param choices 用户为下一个进程选择的版本，键为 ID，值为 {@link #identity(ExtensionDescriptor)}
     * @return 每个 ID 选出一个版本时的加载顺序，以及被排除的 ID 及原因
     */
    public static DependencyResolution resolve(List<ExtensionDescriptor> descriptors, Map<String, String> choices) {
        Map<String, String> selected = choices == null ? Collections.<String, String>emptyMap() : choices;
        Map<String, List<ExtensionDescriptor>> candidates = candidates(descriptors);

        Set<String> dropped = new LinkedHashSet<>();
        Map<String, String> excluded = new LinkedHashMap<>();

        // 记录的选择必须仍然存在。只有一个候选时忽略该选择，因为更换归档不应强制用户重新确认。
        for (Map.Entry<String, List<ExtensionDescriptor>> group : candidates.entrySet()) {
            String choice = selected.get(group.getKey());
            if (choice == null || find(group.getValue(), choice) != null || group.getValue().size() == 1) { continue; }
            dropped.add(group.getKey());
            excluded.put(group.getKey(), "The selected version of '" + group.getKey()
                + "' is no longer installed; choose another version");
        }

        Map<String, ExtensionDescriptor> picked = Collections.emptyMap();
        int rounds = Math.max(MIN_ROUNDS, descriptors.size() + 1);
        for (int round = 0; round < rounds; round++) {
            List<ExtensionDescriptor> active = new ArrayList<>();
            for (ExtensionDescriptor descriptor : descriptors) {
                if (!dropped.contains(descriptor.id())) { active.add(descriptor); }
            }
            picked = pick(candidates, required(active), selected, dropped);
            Map<String, String> violations = violations(active, picked);
            if (violations.isEmpty()) { break; }
            for (Map.Entry<String, String> violation : violations.entrySet()) {
                dropped.add(violation.getKey());
                if (!excluded.containsKey(violation.getKey())) { excluded.put(violation.getKey(), violation.getValue()); }
            }
        }

        List<ExtensionDescriptor> chosen = new ArrayList<>(picked.size());
        Set<String> emitted = new LinkedHashSet<>();
        for (ExtensionDescriptor descriptor : descriptors) {
            if (dropped.contains(descriptor.id())) { continue; }
            ExtensionDescriptor winner = picked.get(descriptor.id());
            if (winner != null && emitted.add(descriptor.id())) { chosen.add(winner); }
        }
        return new DependencyResolution(chosen, excluded);
    }

    /**
     * @return 是否存在一条指向已删除归档的显式选择；这种状态需要用户重新选择，而不是由自动解析代替
     */
    public static boolean hasStaleSelection(List<ExtensionDescriptor> descriptors, Map<String, String> choices) {
        if (choices == null || choices.isEmpty()) { return false; }
        for (Map.Entry<String, List<ExtensionDescriptor>> group : candidates(descriptors).entrySet()) {
            String choice = choices.get(group.getKey());
            if (choice == null || group.getValue().size() == 1) { continue; }
            if (find(group.getValue(), choice) == null) { return true; }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 选择
    // ------------------------------------------------------------------

    /** 按 ID 分组，并按内容标识去重；存在外部归档时，宿主自带的同 ID 版本不再候选。 */
    private static Map<String, List<ExtensionDescriptor>> candidates(List<ExtensionDescriptor> descriptors) {
        Map<String, List<ExtensionDescriptor>> groups = new LinkedHashMap<>();
        for (ExtensionDescriptor descriptor : descriptors) {
            List<ExtensionDescriptor> group = groups.get(descriptor.id());
            if (group == null) { group = new ArrayList<>(); groups.put(descriptor.id(), group); }
            group.add(descriptor);
        }
        Map<String, List<ExtensionDescriptor>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<ExtensionDescriptor>> group : groups.entrySet()) {
            boolean external = false;
            for (ExtensionDescriptor descriptor : group.getValue()) { external |= !descriptor.isBuiltIn(); }
            List<ExtensionDescriptor> values = new ArrayList<>();
            for (ExtensionDescriptor descriptor : group.getValue()) {
                // 已安装的构建版本替换宿主类路径中的描述信息，因此外部归档优先。
                if (external && descriptor.isBuiltIn()) { continue; }
                boolean duplicate = false;
                for (ExtensionDescriptor existing : values) {
                    if (identity(existing).equals(identity(descriptor))) { duplicate = true; break; }
                }
                if (!duplicate) { values.add(descriptor); }
            }
            if (!values.isEmpty()) { result.put(group.getKey(), Collections.unmodifiableList(values)); }
        }
        return result;
    }

    /** @return 每个 ID 被多少条来自 {@code active} 的要求引用，以及这些要求本身 */
    private static Map<String, List<ExtensionRequirement>> required(List<ExtensionDescriptor> active) {
        Map<String, List<ExtensionRequirement>> requirements = new LinkedHashMap<>();
        for (ExtensionDescriptor descriptor : active) {
            for (ExtensionRequirement requirement : descriptor.requirements()) {
                List<ExtensionRequirement> group = requirements.get(requirement.id());
                if (group == null) { group = new ArrayList<>(); requirements.put(requirement.id(), group); }
                group.add(requirement);
            }
        }
        return requirements;
    }

    private static Map<String, ExtensionDescriptor> pick(Map<String, List<ExtensionDescriptor>> candidates,
                                                        Map<String, List<ExtensionRequirement>> requirements,
                                                        Map<String, String> choices,
                                                        Set<String> dropped) {
        Map<String, ExtensionDescriptor> picked = new LinkedHashMap<>();
        for (Map.Entry<String, List<ExtensionDescriptor>> group : candidates.entrySet()) {
            String id = group.getKey();
            if (dropped.contains(id)) { continue; }
            String choice = choices.get(id);
            if (choice != null) {
                ExtensionDescriptor chosen = find(group.getValue(), choice);
                if (chosen != null) { picked.put(id, chosen); continue; }
                if (group.getValue().size() > 1) { continue; }
            }
            picked.put(id, best(group.getValue(), requirements.get(id)));
        }
        return picked;
    }

    /**
     * @return 满足最多要求、其次版本最高的候选。版本相同时保留先发现的一个，使结果与文件扫描顺序一致。
     */
    private static ExtensionDescriptor best(List<ExtensionDescriptor> candidates,
                                            List<ExtensionRequirement> requirements) {
        ExtensionDescriptor best = null;
        int bestScore = -1;
        for (ExtensionDescriptor candidate : candidates) {
            int score = 0;
            if (requirements != null) {
                for (ExtensionRequirement requirement : requirements) {
                    if (requirement.satisfiedBy(candidate.version())) { score++; }
                }
            }
            if (best == null || score > bestScore
                || (score == bestScore && ExtensionVersion.compare(candidate.version(), best.version()) > 0)) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * @return 无法被任一所选版本满足的扩展，键为 ID，值为可读原因。缺少提供方的要求不在此报告，
     *         因为硬性依赖缺失由 {@link ExtensionDependencyResolver} 统一处理。
     */
    private static Map<String, String> violations(List<ExtensionDescriptor> active,
                                                  Map<String, ExtensionDescriptor> picked) {
        Map<String, String> violations = new LinkedHashMap<>();
        for (ExtensionDescriptor descriptor : active) {
            for (ExtensionRequirement requirement : descriptor.requirements()) {
                ExtensionDescriptor provider = picked.get(requirement.id());
                if (provider == null || requirement.satisfiedBy(provider.version())) { continue; }
                violations.put(descriptor.id(), "Requires " + requirement.text() + ", but " + requirement.id()
                    + " " + provider.version() + " is the version that satisfies the most extensions");
                break;
            }
        }
        return violations;
    }

    private static ExtensionDescriptor find(List<ExtensionDescriptor> candidates, String identity) {
        for (ExtensionDescriptor candidate : candidates) {
            if (identity(candidate).equals(identity)) { return candidate; }
        }
        return null;
    }
}
