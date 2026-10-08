/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import io.github.rwml.api.ExtensionContext;
import io.github.rwml.api.MixinBridge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Default {@link ExtensionContext} implementation backed by a data directory. */
final class ExtensionContextImpl implements ExtensionContext {

    private final String extensionId;
    private final Path dataDirectory;
    private final ClassLoader classLoader;
    private final MixinBridge mixins;
    private final Logger logger;

    ExtensionContextImpl(String extensionId, Path dataDirectory, ClassLoader classLoader,
                         MixinBridge mixins) throws IOException {
        this.extensionId = extensionId;
        this.dataDirectory = dataDirectory;
        this.classLoader = classLoader;
        this.mixins = mixins;
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
    public void log(String message) {
        logger.info(() -> "[" + extensionId + "] " + message);
    }

    @Override
    public void log(String message, Throwable error) {
        logger.log(Level.SEVERE, "[" + extensionId + "] " + message, error);
    }
}