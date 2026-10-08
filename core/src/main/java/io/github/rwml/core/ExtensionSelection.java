/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
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

/** One load plan shared by early UI mounting, mixin registration, and extension callbacks. */
public final class ExtensionSelection {
    private final List<ExtensionDescriptor> enabled;
    private final List<String> disabled;
    private final Map<String, String> excluded;
    private final ExtensionDescriptor framework;

    private ExtensionSelection(DependencyResolution resolution, Set<String> disabled,
                               ExtensionDescriptor framework) {
        this.enabled = resolution.loadOrder();
        this.excluded = resolution.excluded();
        this.disabled = Collections.unmodifiableList(new ArrayList<>(disabled));
        this.framework = framework;
    }

    public List<ExtensionDescriptor> enabled() { return enabled; }
    public List<String> disabled() { return disabled; }
    public Map<String, String> excluded() { return excluded; }
    public ExtensionDescriptor framework() { return framework; }

    public static ExtensionSelection resolve(List<ExtensionDescriptor> descriptors, Set<String> disabledIds) {
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
        candidates.add(null); // no viable UI: load ordinary extensions only
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
                return new ExtensionSelection(resolution, disabled, candidate);
            }
        }
        throw new AssertionError("The headless selection is always valid");
    }

    public static boolean isFramework(ExtensionDescriptor descriptor) {
        return descriptor.uiFramework() != null && !descriptor.uiFramework().trim().isEmpty();
    }

    /** Only the selected framework and its hard dependencies are needed before game preparation. */
    public List<ExtensionDescriptor> interfaceArchives() {
        Set<String> required = new LinkedHashSet<>();
        if (framework != null) { required.add(framework.id()); }
        boolean changed;
        do {
            changed = false;
            for (ExtensionDescriptor descriptor : enabled) {
                if (required.contains(descriptor.id())) {
                    changed |= required.addAll(descriptor.dependsOn());
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
