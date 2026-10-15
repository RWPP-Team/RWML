/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */
package io.github.rwml.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves one archive per id before dependencies, UI mounting or callbacks are considered. */
public final class ExtensionVersions {
    private ExtensionVersions() { }

    public static String identity(ExtensionDescriptor descriptor) {
        return descriptor.sourceSha256().isEmpty() ? "version:" + descriptor.version() : descriptor.sourceSha256();
    }

    public static DependencyResolution resolve(List<ExtensionDescriptor> descriptors, Map<String, String> choices) {
        Map<String, List<ExtensionDescriptor>> groups = new LinkedHashMap<>();
        for (ExtensionDescriptor descriptor : descriptors) {
            groups.computeIfAbsent(descriptor.id(), ignored -> new ArrayList<>()).add(descriptor);
        }
        List<ExtensionDescriptor> selected = new ArrayList<>();
        Map<String, String> conflicts = new LinkedHashMap<>();
        for (Map.Entry<String, List<ExtensionDescriptor>> group : groups.entrySet()) {
            Map<String, ExtensionDescriptor> candidates = new LinkedHashMap<>();
            boolean external = group.getValue().stream().anyMatch(descriptor -> !descriptor.isBuiltIn());
            for (ExtensionDescriptor descriptor : group.getValue()) {
                // An installed build may replace the descriptor the host carries on its class path.
                if (!external || !descriptor.isBuiltIn()) { candidates.putIfAbsent(identity(descriptor), descriptor); }
            }
            ExtensionDescriptor choice = candidates.get(choices.get(group.getKey()));
            if (candidates.size() == 1) { choice = candidates.values().iterator().next(); }
            if (choice == null) { conflicts.put(group.getKey(), "Choose an extension version: " + group.getKey()); }
            else { selected.add(choice); }
        }
        return new DependencyResolution(selected, conflicts);
    }
}
