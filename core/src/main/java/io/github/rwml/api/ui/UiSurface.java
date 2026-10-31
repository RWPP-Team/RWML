/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.ui;

import java.util.concurrent.Executor;

/**
 * A host-owned surface for extension-provided native UI content.
 *
 * <p>The content type is deliberately opaque so a framework can attach a platform window, view, or another
 * host-native object without making the shared API depend on a UI toolkit. Create and attach content by
 * submitting work to {@link #uiExecutor()}. Closing the surface releases the host window or finishes its
 * owning activity; close listeners run on the UI thread.</p>
 */
public interface UiSurface extends AutoCloseable {

    /** @return executor for the platform UI thread */
    Executor uiExecutor();

    /**
     * Attaches and displays platform-native UI content.
     *
     * @param nativeContent a platform window/view accepted by this host
     * @throws IllegalArgumentException if this host cannot attach the supplied object
     */
    void attach(Object nativeContent);

    /**
     * Defers creation of native content until this surface has a target platform owner.
     *
     * <p>Most surfaces accept already-created content through {@link #attach(Object)}. Hosts that create their
     * target Activity or window later can override this method to invoke the factory with that owner.</p>
     *
     * @param factory creates content for the target platform owner
     * @throws UnsupportedOperationException if deferred content is not supported
     */
    default void attachFactory(UiContentFactory factory) {
        throw new UnsupportedOperationException("This UI surface does not support deferred content");
    }

    /**
     * Registers a callback for when the user or host closes this surface.
     *
     * @param listener callback to run on the UI thread
     * @return a handle that unregisters the callback when closed
     */
    AutoCloseable onClose(Runnable listener);

    /** @return whether the surface has closed or its owning activity has been destroyed */
    boolean isClosed();

    /** Closes the surface and notifies registered listeners. */
    @Override
    void close();
}
