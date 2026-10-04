package io.github.rwml.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outcome of a full load pass.
 *
 * @param loaded  handles that were instantiated successfully, in load order
 * @param failed  extension ids (or archive paths for unreadable archives)
 *                mapped to a short failure reason
 * @param disabled ids that were intentionally skipped because they are
 *                persisted as disabled
 */
public record LoadReport(List<ExtensionHandle> loaded, Map<String, String> failed, List<String> disabled) {

    public LoadReport {
        loaded = Collections.unmodifiableList(new ArrayList<>(loaded));
        failed = Collections.unmodifiableMap(new LinkedHashMap<>(failed));
        disabled = Collections.unmodifiableList(new ArrayList<>(disabled));
    }

    public boolean isClean() {
        return failed.isEmpty();
    }
}
