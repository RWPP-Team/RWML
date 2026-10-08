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
 * @param staged  one line per archive the host supplied itself, describing what
 *                was done to it; empty on a host that supplies none
 */
public record LoadReport(List<ExtensionHandle> loaded, Map<String, String> failed, List<String> disabled,
                         List<String> staged) {

    /** Report of a pass that had no host-supplied archives to stage. */
    public LoadReport(List<ExtensionHandle> loaded, Map<String, String> failed, List<String> disabled) {
        this(loaded, failed, disabled, Collections.emptyList());
    }

    public LoadReport {
        loaded = Collections.unmodifiableList(new ArrayList<>(loaded));
        failed = Collections.unmodifiableMap(new LinkedHashMap<>(failed));
        disabled = Collections.unmodifiableList(new ArrayList<>(disabled));
        staged = Collections.unmodifiableList(new ArrayList<>(staged));
    }

    public boolean isClean() {
        return failed.isEmpty();
    }
}
