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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
    private final Map<String, String> disabledArchives = new LinkedHashMap<>();
    private final Map<String, String> selectedVersions = new LinkedHashMap<>();
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
            try {
                write();
            } catch (IOException failure) {
                if (disable) { disabled.remove(id); }
                else { disabled.add(id); }
                throw failure;
            }
        }
    }

    /** Content identities survive Android staging and do not depend on an archive's filename. */
    public synchronized Map<String, String> selectedVersions() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(selectedVersions));
    }

    public synchronized void setSelectedVersion(String id, String identity) throws IOException {
        String previous = selectedVersions.get(id);
        if (Objects.equals(previous, identity)) { return; }
        if (identity == null) { selectedVersions.remove(id); }
        else { selectedVersions.put(id, identity); }
        try {
            write();
        } catch (IOException failure) {
            if (previous == null) { selectedVersions.remove(id); }
            else { selectedVersions.put(id, previous); }
            throw failure;
        }
    }

    /** An unreadable descriptor has no id; quarantine its bytes without renaming or deleting the file. */
    public synchronized boolean isArchiveDisabled(Path archive) throws IOException {
        return !disabledArchives.isEmpty() && disabledArchives.containsKey(archiveIdentity(archive));
    }

    public synchronized void quarantineArchive(Path archive, String reason) throws IOException {
        String identity = archiveIdentity(archive);
        String previous = disabledArchives.put(identity, reason);
        try { write(); }
        catch (IOException failure) {
            if (previous == null) { disabledArchives.remove(identity); }
            else { disabledArchives.put(identity, previous); }
            throw failure;
        }
    }

    private static String archiveIdentity(Path archive) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(archive)) {
                byte[] bytes = new byte[8192];
                int count;
                while ((count = input.read(bytes)) != -1) { digest.update(bytes, 0, count); }
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(Character.forDigit((value >> 4) & 15, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private void read() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream stream = Files.newInputStream(file)) {
            properties.load(stream);
        } catch (IOException | IllegalArgumentException e) {
            // A corrupt store must not stop the host from starting; begin clean.
            return;
        }
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("disabledArchive.")) {
                disabledArchives.put(key.substring("disabledArchive.".length()), properties.getProperty(key));
            }
            if (key.startsWith("selected.")) {
                selectedVersions.put(key.substring("selected.".length()), properties.getProperty(key));
            }
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
        for (Map.Entry<String, String> entry : selectedVersions.entrySet()) {
            properties.setProperty("selected." + entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, String> entry : disabledArchives.entrySet()) {
            properties.setProperty("disabledArchive." + entry.getKey(), entry.getValue());
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream stream = Files.newOutputStream(temp)) {
            properties.store(stream, "Persisted extension state and selected archive identities");
        }
        // Safe same-filesystem replace; fall back to a plain move if needed.
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
