/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api;

import io.github.rwml.api.game.GameBoot;
import io.github.rwml.api.ui.UiServices;
import io.github.rwml.core.ExtensionManager;
import io.github.rwml.core.LoadReport;

/**
 * A running RWML: the holder of the extension manager, of the last load pass, and of the sequence
 * that brings the game up.
 *
 * <p>Each host constructs one implementation when it brings the extension system up - the desktop in
 * {@code io.github.rwml.desktop.DesktopRuntime}, an Android app in its bootstrapper - and installs it
 * in {@link Rwml}.
 * Extension code reaches the host through {@code Rwml.get()}, which is what lets a mixin call into
 * the host without knowing which platform it runs on.</p>
 */
public interface RwmlRuntime {

    /** Available before game preparation; older/custom runtimes may opt out. */
    default UiServices ui() {
        throw new UnsupportedOperationException("This runtime does not provide UI services");
    }

    /** @return the manager the extensions are loaded through, or {@code null} before the host prepared one */
    ExtensionManager manager();

    /** @return the outcome of the last load pass, or {@code null} if none has run */
    LoadReport report();

    /**
     * @return the platform this runtime runs on, which is how an extension decides whether a step
     *     of its own belongs here
     */
    Platform platform();

    /**
     * The sequence that starts the game.
     *
     * <p>The host fills it with the steps its platform has to take; an interface declared through
     * {@code ui-framework} is expected to start it rather than to reach for the game by itself. The
     * boot runs once, so asking for the game twice cannot create a second engine.</p>
     *
     * @return the boot of this runtime, never null once the runtime is installed
     */
    GameBoot gameBoot();
}
