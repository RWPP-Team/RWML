/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.ui;

/** Game-facing data supplied by a host after the engine is ready; no engine object crosses the API. */
public interface GameUi {

    record Snapshot(boolean available, boolean demo, boolean canResume, String version) {
        public static final Snapshot UNAVAILABLE = new Snapshot(false, false, false, "");
    }

    Snapshot snapshot();

    /** Returns null when the game has no translation for a key. */
    String text(String key);

    String mapName(String displayName);

    /** Applies a UI language preference; an empty tag means the system language. */
    void language(String tag);
}
