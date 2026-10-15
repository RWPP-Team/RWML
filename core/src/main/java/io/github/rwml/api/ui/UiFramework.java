/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.ui;

/**
 * The one entry point an extension declares to bring a user interface up.
 *
 * <p>A user interface is always platform specific: the window, the surface, the GL context and the
 * event source belong to the platform, and the desktop and Android hosts provide different ones. An
 * extension declares a class implementing this interface through the {@code ui-framework} property,
 * and the host calls {@link #start(UiHost)} once, after the selected framework and its dependencies
 * have loaded.
 * Android defers the remaining extensions until game preparation.</p>
 *
 * <p>When no loaded extension declares one, no framework is started. A host may still provide
 * a management/recovery screen; the desktop bootstrap falls back to the original game launcher.</p>
 *
 * <p>The class must be reachable from the declaring extension's own archive; a class supplied by the
 * host itself is accepted as well, so a small extension may request the platform interface without
 * shipping platform code. Implementations are instantiated through their no-argument constructor and
 * must be thread safe with respect to the host's start-up thread.</p>
 */
public interface UiFramework {

    /**
     * Starts the user interface.
     *
     * <p>The call happens on the loader thread on Android and the launcher thread on desktop.
     * Implementations own their launcher and loading UI; Android view operations must be posted
     * to the main thread. {@link UiHost#gameBoot()} initializes the game with their progress listener.</p>
     *
     * <p>A thrown exception is reported by the host. Android shows builtin diagnostics; desktop
     * falls back to the original game launcher.</p>
     *
     * @param host the host that loaded the declaring extension
     */
    void start(UiHost host) throws Exception;
}
