/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import io.github.rwml.api.ExtensionContext;
import io.github.rwml.api.GameAssets;
import io.github.rwml.api.MixinBridge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 基于数据目录的默认 {@link ExtensionContext} 实现。 */
final class ExtensionContextImpl implements ExtensionContext {

    private final String extensionId;
    private final Path dataDirectory;
    private final Path extensionArchive;
    private final ClassLoader classLoader;
    private final MixinBridge mixins;
    private final GameAssets assets;
    private final Logger logger;
    private final java.util.List<AutoCloseable> resources = new java.util.ArrayList<>();
    private boolean closed;

    @Override
    public synchronized <T extends AutoCloseable> T own(T resource) {
        java.util.Objects.requireNonNull(resource, "resource");
        if (closed) {
            try { resource.close(); } catch (Exception e) { log("Unable to close late resource", e); }
            throw new IllegalStateException("Extension context is closed: " + extensionId);
        }
        resources.add(resource);
        return resource;
    }

    void closeResources() {
        java.util.List<AutoCloseable> closing;
        synchronized (this) {
            if (closed) { return; }
            closed = true;
            closing = new java.util.ArrayList<>(resources);
            resources.clear();
        }
        for (int i = closing.size() - 1; i >= 0; i--) {
            try { closing.get(i).close(); }
            catch (Exception e) { log("Unable to close extension resource", e); }
        }
    }

    ExtensionContextImpl(String extensionId, Path dataDirectory, Path extensionArchive, ClassLoader classLoader,
                         MixinBridge mixins, GameAssets assets) throws IOException {
        this.extensionId = extensionId;
        this.dataDirectory = dataDirectory;
        this.extensionArchive = extensionArchive;
        this.classLoader = classLoader;
        this.mixins = mixins;
        this.assets = assets;
        this.logger = Logger.getLogger("extension." + extensionId);
        Files.createDirectories(dataDirectory);
    }

    @Override
    public String extensionId() {
        return extensionId;
    }

    @Override
    public Path dataDirectory() {
        return dataDirectory;
    }

    @Override
    public ClassLoader classLoader() {
        return classLoader;
    }

    @Override
    public MixinBridge mixin() {
        return mixins;
    }

    @Override
    public GameAssets gameAssets() {
        return assets;
    }

    @Override
    public Path extractNativeLibrary(String archiveEntry) throws IOException {
        if (extensionArchive == null) {
            throw new UnsupportedOperationException("Built-in extensions do not have a source archive");
        }
        return ExtensionNativeLibrary.extract(extensionArchive, archiveEntry, dataDirectory);
    }

    @Override
    public void log(String message) {
        logger.info(() -> "[" + extensionId + "] " + message);
    }

    @Override
    public void log(String message, Throwable error) {
        logger.log(Level.SEVERE, "[" + extensionId + "] " + message, error);
    }
}
