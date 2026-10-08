/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.ui;

import io.github.rwml.api.Rwml;
import io.github.rwml.api.RwmlRuntime;
import io.github.rwml.api.game.GameBoot;

import java.util.List;

/**
 * What a {@link UiFramework} is handed when the host starts it.
 *
 * <p>The interface is deliberately small. It carries the platform handle an implementation needs to
 * reach its own platform, the arguments the host was started with, and the one action every platform
 * can perform: putting a screen up.</p>
 */
public interface UiHost {

    /**
     * The platform handle.
     *
     * <p>On Android this is the {@code android.content.Context} of the launcher activity, which is
     * also the only way to start another activity. On the desktop it is the host runtime. The value
     * is never null, but its type is only known to the platform, so an implementation casts it after
     * checking the platform it was written for.</p>
     *
     * @return the host's platform object, never null
     */
    Object platform();

    /**
     * The arguments the host was started with, in the order they were given.
     *
     * @return an immutable argument list, never null
     */
    List<String> arguments();

    /**
     * The shared game initialization sequence. A framework may show its own launcher, then run
     * this boot on a worker with its own progress listener before opening a game screen.
     *
     * @return the host's boot, available after extension loading on both platforms
     */
    default GameBoot gameBoot() {
        RwmlRuntime runtime = Rwml.get();
        if (runtime == null || runtime.gameBoot() == null) {
            throw new IllegalStateException("The host has not prepared a game boot");
        }
        return runtime.gameBoot();
    }

    /**
     * Asks the platform to put a screen up.
     *
     * <p>The name is a hint, not a contract: a platform maps the names it knows onto its own screens
     * and reports an unknown one instead of failing. The call may return before the screen is
     * visible, because starting a screen is asynchronous on Android.</p>
     *
     * @param screen the requested screen, e.g. {@code game}
     */
    void openScreen(String screen);
}
