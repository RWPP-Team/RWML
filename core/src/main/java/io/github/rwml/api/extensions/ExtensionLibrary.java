/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.extensions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/** Archive management never executes extension code. Changes apply in a fresh host process. */
public interface ExtensionLibrary {
    Path directory();
    List<ExtensionEntry> entries() throws IOException;
    /** The enabled flag applies to an id; at most one of its versions can load. */
    void setEnabled(String id, boolean enabled) throws IOException;
    /** Selects an archive for the next process, retaining the id's enabled/disabled preference. */
    default void selectVersion(Path archive) throws IOException { throw new UnsupportedOperationException(); }
    /** Cached by catalog refresh; hosts use this to keep unresolved choices in the launcher. */
    default boolean versionSelectionRequired() { return false; }
    ExtensionEntry importArchive(InputStream input, boolean replace) throws IOException;
    void remove(String id) throws IOException;
    /** Also permits removing a malformed or duplicate archive selected by its catalog path. */
    void removeArchive(Path archive) throws IOException;
    boolean restartRequired();
}
