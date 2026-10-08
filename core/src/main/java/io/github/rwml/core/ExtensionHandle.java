/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.core;

import io.github.rwml.api.Extension;
import io.github.rwml.api.ExtensionState;

import java.io.Closeable;
import java.io.IOException;

/** Runtime wrapper pairing an instance with its descriptor and class loader. */
public final class ExtensionHandle implements AutoCloseable {

    private final ExtensionDescriptor descriptor;
    private final Extension instance;
    private final ClassLoader classLoader;

    private ExtensionContextImpl context;
    private volatile ExtensionState state = ExtensionState.DISCOVERED;

    ExtensionHandle(ExtensionDescriptor descriptor, Extension instance, ClassLoader classLoader) {
        this.descriptor = descriptor;
        this.instance = instance;
        this.classLoader = classLoader;
    }

    public ExtensionDescriptor descriptor() {
        return descriptor;
    }

    public Extension instance() {
        return instance;
    }

    public ExtensionState state() {
        return state;
    }

    public void setState(ExtensionState state) {
        this.state = state;
    }

    public boolean isEnabled() {
        return state == ExtensionState.ENABLED;
    }

    public boolean isUsable() {
        return state != ExtensionState.FAILED && state != ExtensionState.INCOMPATIBLE;
    }

    ExtensionContextImpl context() {
        return context;
    }

    void attachContext(ExtensionContextImpl context) {
        this.context = context;
    }

    /** @return the loader that owns this extension's classes. */
    public ClassLoader classLoader() {
        return classLoader;
    }

    @Override
    public void close() {
        if (classLoader instanceof Closeable) {
            try {
                ((Closeable) classLoader).close();
            } catch (IOException ignored) {
                // Nothing sensible to do while releasing the loader.
            }
        }
    }
}