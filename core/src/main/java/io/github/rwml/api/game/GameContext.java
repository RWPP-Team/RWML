/*
 * Copyright (c) 2026 RWPP-Team. All rights reserved.
 *
 * Licensed under the RWPP-Team Shield License 1.0.0.
 * Full license text: https://github.com/RWPP-Team/RWML/blob/master/LICENSE.md
 */

package io.github.rwml.api.game;

import java.nio.file.Path;
import java.util.List;

/**
 * What a {@link GameStage} is handed while it runs.
 *
 * <p>The interface is deliberately small and carries no game type. The core of RWML is compiled
 * without the game on its class path and every extension has to stay portable across the two hosts,
 * so a stage reaches its platform through {@link #platform()} and casts after asking which platform
 * it is talking to. The same object is handed to every stage of one boot.</p>
 */
public interface GameContext {

    /**
     * The host's platform object.
     *
     * <p>On Android this is the {@code android.content.Context} of the application, which is what
     * the game's own entry points ask for. On the desktop it is the running
     * {@link io.github.rwml.api.RwmlRuntime}. It is never null, and its type is only known to the
     * platform that supplied it.</p>
     *
     * @return the platform handle, never null
     */
    Object platform();

    /**
     * The arguments the process was started with, in the order they were given.
     *
     * @return an immutable argument list, never null
     */
    List<String> arguments();

    /**
     * The directory the game reads its data from and writes its settings to.
     *
     * <p>On the desktop this is the directory holding the game jar. On Android it is the runtime's
     * private directory, because the packaged assets are read-only and the game's own storage lives
     * outside the application.</p>
     *
     * @return the game directory, never null
     */
    Path directory();

    /** Writes a message describing what the stage is doing. */
    void log(String message);

    /**
     * Reports one line of sub-progress for the stage that is running.
     *
     * <p>The engine's own loading messages are the example: a platform that performs several boot
     * operations inside one stage forwards each message the engine prints while it works, and a
     * loading screen then shows the real sequence rather than a single line for the whole stage.
     * The boot that owns this context routes the line to the listener it was started with, prefixed
     * with the stage's name.</p>
     *
     * <p>The default does nothing. A stage that has nothing finer to report should not have to say
     * so, and a context built outside a running boot has nowhere to send the line anyway.</p>
     *
     * @param message the line to report
     */
    default void subProgress(String message) {
    }
}
