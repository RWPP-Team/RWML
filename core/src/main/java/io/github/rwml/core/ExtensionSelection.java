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

/** 供提前挂载界面、注册 Mixin 和调用扩展回调共用的加载计划。 */
public final class ExtensionSelection {
    private final List<ExtensionDescriptor> enabled;
    private final List<String> disabled;
    private final Map<String, String> excluded;
    private final ExtensionDescriptor framework;
    private final ExtensionDescriptor resourcePack;
    private final Map<String, String> resourcePackExcluded;

    private ExtensionSelection(DependencyResolution resolution, Set<String> disabled,
                               ExtensionDescriptor framework, ExtensionDescriptor resourcePack,
                               Map<String, String> resourcePackExcluded) {
        this.enabled = resolution.loadOrder();
        this.excluded = resolution.excluded();
        this.disabled = Collections.unmodifiableList(new ArrayList<>(disabled));
        this.framework = framework;
        this.resourcePack = resourcePack;
        this.resourcePackExcluded = Collections.unmodifiableMap(new LinkedHashMap<>(resourcePackExcluded));
    }

    public List<ExtensionDescriptor> enabled() { return enabled; }
    public List<String> disabled() { return disabled; }
    public Map<String, String> excluded() { return excluded; }
    public ExtensionDescriptor framework() { return framework; }

    /** @return 本次加载生效的资源包；没有资源包被启用时返回 null */
    public ExtensionDescriptor resourcePack() { return resourcePack; }

    /** @return 因已有资源包生效而跳过的资源包 ID 及原因 */
    public Map<String, String> resourcePackExcluded() { return resourcePackExcluded; }

    public static ExtensionSelection resolve(List<ExtensionDescriptor> descriptors, Set<String> disabledIds) {
        return resolve(descriptors, disabledIds, Collections.emptyMap(), null);
    }

    public static ExtensionSelection resolve(List<ExtensionDescriptor> descriptors, Set<String> disabledIds,
                                             Map<String, String> selectedVersions) {
        return resolve(descriptors, disabledIds, selectedVersions, null);
    }

    /**
     * 生成加载计划。
     *
     * @param preferredResourcePack 上次选择并持久化的资源包 ID；它仍可用时优先于加载顺序中的第一个资源包。
     *     为 null 时按加载顺序选择
     */
    public static ExtensionSelection resolve(List<ExtensionDescriptor> descriptors, Set<String> disabledIds,
                                             Map<String, String> selectedVersions,
                                             String preferredResourcePack) {
        List<ExtensionDescriptor> eligible = new ArrayList<>();
        for (ExtensionDescriptor descriptor : descriptors) {
            if (!disabledIds.contains(descriptor.id())) { eligible.add(descriptor); }
        }
        DependencyResolution versions = ExtensionVersions.resolve(eligible, selectedVersions);
        descriptors = versions.loadOrder();
        ExtensionDependencyResolver resolver = new ExtensionDependencyResolver();
        List<ExtensionDescriptor> ordered = new ArrayList<>(resolver.resolve(descriptors).loadOrder());
        Set<String> seen = new LinkedHashSet<>();
        for (ExtensionDescriptor descriptor : ordered) { seen.add(descriptor.id()); }
        for (ExtensionDescriptor descriptor : descriptors) {
            if (seen.add(descriptor.id())) { ordered.add(descriptor); }
        }
        List<ExtensionDescriptor> candidates = new ArrayList<>();
        for (boolean builtin : new boolean[]{false, true}) {
            for (ExtensionDescriptor descriptor : ordered) {
                if (isFramework(descriptor) && descriptor.isBuiltIn() == builtin
                    && !disabledIds.contains(descriptor.id())) {
                    candidates.add(descriptor);
                }
            }
        }
        candidates.add(null); // 没有可用的界面扩展：仅加载普通扩展
        for (ExtensionDescriptor candidate : candidates) {
            Set<String> disabled = new LinkedHashSet<>();
            Map<String, ExtensionDescriptor> remaining = new LinkedHashMap<>();
            for (ExtensionDescriptor descriptor : descriptors) {
                if (disabledIds.contains(descriptor.id())
                    || (isFramework(descriptor) && descriptor != candidate)) {
                    disabled.add(descriptor.id());
                } else {
                    remaining.put(descriptor.id(), descriptor);
                }
            }
            DependencyResolution resolution = resolver.resolve(new ArrayList<>(remaining.values()));
            if (candidate == null || resolution.loadOrder().contains(candidate)) {
                disabled.addAll(disabledIds);
                Map<String, String> excluded = new LinkedHashMap<>(versions.excluded());
                excluded.putAll(resolution.excluded());

                // 资源包彼此替换同一批文件，同时物化两个只会让后写入的一方获胜。此处只保留一个，其余按禁用处理；
                // 依赖它们的扩展会在重新排列依赖时一并退出。
                ExtensionDescriptor pack = chooseResourcePack(resolution.loadOrder(), preferredResourcePack);
                Map<String, String> packsSkipped = new LinkedHashMap<>();
                if (pack != null) {
                    List<ExtensionDescriptor> kept = new ArrayList<>();
                    for (ExtensionDescriptor descriptor : resolution.loadOrder()) {
                        if (descriptor.resourcePack() && !descriptor.id().equals(pack.id())) {
                            packsSkipped.put(descriptor.id(),
                                "resource pack '" + pack.id() + "' is active");
                            disabled.add(descriptor.id());
                        } else {
                            kept.add(descriptor);
                        }
                    }
                    if (kept.size() != resolution.loadOrder().size()) {
                        resolution = resolver.resolve(kept);
                        excluded.putAll(resolution.excluded());
                    }
                }
                return new ExtensionSelection(new DependencyResolution(resolution.loadOrder(), excluded), disabled,
                    candidate, pack, packsSkipped);
            }
        }
        throw new AssertionError("The headless selection is always valid");
    }

    /**
     * @return 本次生效的资源包，即 {@code preferred} 仍在加载顺序中时取它，否则取第一个资源包
     */
    private static ExtensionDescriptor chooseResourcePack(List<ExtensionDescriptor> order, String preferred) {
        ExtensionDescriptor first = null;
        for (ExtensionDescriptor descriptor : order) {
            if (!descriptor.resourcePack()) { continue; }
            if (preferred != null && preferred.equals(descriptor.id())) { return descriptor; }
            if (first == null) { first = descriptor; }
        }
        return first;
    }

    public static boolean isFramework(ExtensionDescriptor descriptor) {
        return descriptor.uiFramework() != null && !descriptor.uiFramework().trim().isEmpty();
    }

    /** 准备游戏前只需加载选定的框架及其强制依赖。 */
    public List<ExtensionDescriptor> interfaceArchives() {
        Set<String> required = new LinkedHashSet<>();
        if (framework != null) { required.add(framework.id()); }
        boolean changed;
        do {
            changed = false;
            for (ExtensionDescriptor descriptor : enabled) {
                if (required.contains(descriptor.id())) {
                    changed |= required.addAll(descriptor.dependencyIds());
                }
            }
        } while (changed);
        List<ExtensionDescriptor> result = new ArrayList<>();
        for (ExtensionDescriptor descriptor : enabled) {
            if (!descriptor.isBuiltIn() && required.contains(descriptor.id())) { result.add(descriptor); }
        }
        return result;
    }
}
