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
 * Resolves the loading order of extensions based on the relationships they
 * declare with each other.
 *
 * <p>Three kinds of relationships are recognised:</p>
 * <ul>
 *   <li>{@code dependsOn} &mdash; hard requirement. The named extension must
 *       exist and must be loaded first. If it is missing, or if it has already
 *       been excluded for any reason, the declaring extension is excluded
 *       too.</li>
 *   <li>{@code loadAfter} &mdash; soft ordering hint. If the named extension
 *       exists it is loaded first; unknown ids are ignored.</li>
 *   <li>{@code loadBefore} &mdash; soft ordering hint, inverse of the above.
 *       If the named extension exists it is loaded afterwards; unknown ids
 *       are ignored.</li>
 * </ul>
 *
 * <p>The resolver never throws for ordinary graph problems. Everything that
 * cannot be loaded is reported through {@link DependencyResolution#excluded()},
 * so that the remaining, healthy extensions can still be loaded.</p>
 */
public final class ExtensionDependencyResolver {

    /** Sorts the supplied descriptors into a valid load order. */
    public DependencyResolution resolve(List<ExtensionDescriptor> descriptors) {
        Map<String, ExtensionDescriptor> byId = new LinkedHashMap<>();
        Map<String, String> excluded = new LinkedHashMap<>();

        for (ExtensionDescriptor descriptor : descriptors) {
            byId.put(descriptor.id(), descriptor);
        }

        // ---- Step 1: cascade exclusion of extensions with missing hard deps ----
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ExtensionDescriptor descriptor : byId.values()) {
                if (excluded.containsKey(descriptor.id())) {
                    continue;
                }
                for (String required : descriptor.dependsOn()) {
                    if (!byId.containsKey(required) || excluded.containsKey(required)) {
                        excluded.put(descriptor.id(), "Unresolved dependency '" + required + "'");
                        changed = true;
                        break;
                    }
                }
            }
        }

        // ---- Step 2: build the ordering graph among the survivors ----
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
            // dependsOn and loadAfter both mean "they come before me".
            for (String predecessor : descriptor.dependsOn()) {
                addEdge(predecessor, descriptor.id(), successors, excluded);
            }
            for (String predecessor : descriptor.loadAfter()) {
                addEdge(predecessor, descriptor.id(), successors, excluded);
            }
            // loadBefore means "they come after me".
            for (String successor : descriptor.loadBefore()) {
                addEdge(descriptor.id(), successor, successors, excluded);
            }
        }

        // Self loops may have added an entry to `excluded`; remove the node
        // and any edge that still references an excluded node.
        successors.keySet().removeIf(excluded::containsKey);
        indegree.keySet().removeIf(excluded::containsKey);
        for (Set<String> targets : successors.values()) {
            targets.removeIf(target -> !successors.containsKey(target));
        }

        // Recompute the indegrees from scratch (edges may have been pruned).
        indegree.replaceAll((key, value) -> 0);
        for (Set<String> targets : successors.values()) {
            for (String target : targets) {
                indegree.merge(target, 1, Integer::sum);
            }
        }

        // ---- Step 3: Kahn's algorithm ----
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

        // ---- Step 4: anything not emitted is inside or behind a cycle ----
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
        // Either side may be an unknown id; soft hints referencing unknown
        // extensions are simply ignored. Hard requirements are handled in
        // step 1 before this method ever runs.
        Set<String> targets = successors.get(predecessor);
        if (targets == null || !successors.containsKey(successor)) {
            return;
        }
        targets.add(successor);
    }
}