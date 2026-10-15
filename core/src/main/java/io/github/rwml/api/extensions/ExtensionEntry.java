/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.extensions;

import io.github.rwml.api.i18n.Messages;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable management data, including archives which could not be loaded. */
public record ExtensionEntry(String id, String name, String version, String description,
                             List<String> authors, List<String> dependencies, Path source,
                             boolean builtIn, boolean enabled, boolean loaded, String problem,
                             boolean versionSelected, boolean versionChoiceRequired) {
    public ExtensionEntry {
        authors = Collections.unmodifiableList(new ArrayList<>(authors));
        dependencies = Collections.unmodifiableList(new ArrayList<>(dependencies));
    }

    /** Compatibility constructor for catalogs without multiple versions. */
    public ExtensionEntry(String id, String name, String version, String description,
                          List<String> authors, List<String> dependencies, Path source,
                          boolean builtIn, boolean enabled, boolean loaded, String problem) {
        this(id, name, version, description, authors, dependencies, source,
            builtIn, enabled, loaded, problem, id != null, false);
    }

    public boolean valid() { return id != null; }

    /** A source may translate metadata through extensions.<id>.name/description keys. */
    public String displayName(Messages messages) {
        return translated(messages, "name", name);
    }

    public String displayDescription(Messages messages) {
        return translated(messages, "description", description);
    }

    private String translated(Messages messages, String property, String fallback) {
        if (id == null) { return fallback; }
        String key = "extensions." + id + "." + property;
        String value = messages.text(key);
        return value.equals(key) ? fallback : value;
    }
}
