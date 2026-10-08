/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Persists the enable/disable decision for every extension so the choice
 * survives a restart of the host.
 *
 * <p>Every extension defaults to <em>enabled</em>; only the ids recorded here
 * are skipped when the host starts up again. The store is the single source of
 * truth for the flag and rewrites its backing file atomically on every change,
 * so a crash in the middle of a write can never leave a half-written file
 * behind.</p>
 *
 * <p>A host that has to prepare something before the extensions are loaded reads the same store, so
 * the decision it makes matches the decision {@link ExtensionManager} makes.</p>
 */
public final class ExtensionStateStore {

    private static final String FILE_NAME = "extension-state.properties";
    private static final String DISABLED_KEY = "disabled";

    private final Path file;
    private final Set<String> disabled = new LinkedHashSet<>();

    private ExtensionStateStore(Path file) {
        this.file = file;
    }

    /** Opens (creating if necessary) the store rooted at {@code dataDirectory}. */
    public static ExtensionStateStore open(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        ExtensionStateStore store = new ExtensionStateStore(dataDirectory.resolve(FILE_NAME));
        store.read();
        return store;
    }

    /** @return whether {@code id} is persisted as disabled. */
    public synchronized boolean isDisabled(String id) {
        return disabled.contains(id);
    }

    /** @return an immutable snapshot of every id persisted as disabled. */
    public synchronized Set<String> disabledIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(disabled));
    }

    /** Persists the disabled flag for {@code id}; no other behaviour is implied. */
    public synchronized void setDisabled(String id, boolean disable) throws IOException {
        boolean changed = disable ? disabled.add(id) : disabled.remove(id);
        if (changed) {
            write();
        }
    }

    private void read() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream stream = Files.newInputStream(file)) {
            properties.load(stream);
        } catch (IOException e) {
            // A corrupt store must not stop the host from starting; begin clean.
            return;
        }
        String raw = properties.getProperty(DISABLED_KEY, "");
        for (String part : raw.split("[,;\\s]+")) {
            String id = part.trim();
            if (!id.isEmpty()) {
                disabled.add(id);
            }
        }
    }

    private void write() throws IOException {
        Properties properties = new Properties();
        properties.setProperty(DISABLED_KEY, String.join(",", disabled));
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream stream = Files.newOutputStream(temp)) {
            properties.store(stream, "Persisted extension enable/disable state");
        }
        // Safe same-filesystem replace; fall back to a plain move if needed.
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
