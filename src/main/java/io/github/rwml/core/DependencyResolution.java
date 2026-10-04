package io.github.rwml.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of dependency ordering across a set of extensions.
 *
 * @param loadOrder extensions in the order they must be loaded, only contains
 *                  extensions that passed dependency validation
 * @param excluded  extensions dropped from the plan, keyed by extension id,
 *                  mapped to a short readable reason
 */
public record DependencyResolution(List<ExtensionDescriptor> loadOrder, Map<String, String> excluded) {

    public DependencyResolution {
        // Collections.unmodifiable* rather than List/Map.copyOf: this record is also built on Android,
        // where the copyOf factories do not exist below API 30.
        loadOrder = Collections.unmodifiableList(new ArrayList<>(loadOrder));
        excluded = Collections.unmodifiableMap(new LinkedHashMap<>(excluded));
    }
}